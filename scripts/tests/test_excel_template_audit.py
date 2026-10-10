"""Audit correctness checks use independent OOXML fixtures and semantic mismatch examples."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location('excel_template_audit', Path(__file__).parents[1] / 'excel-template-audit.py')
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class ExcelTemplateAuditTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / 'fixture.xlsx'
        workbook = '''<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets><sheet name="Full sheet" sheetId="1" r:id="s1"/></sheets>
          <definedNames><definedName name="late_value">'Full sheet'!$AF$61</definedName>
          <definedName name="named_formula" localSheetId="0">MAX(1,2)</definedName></definedNames>
          <calcPr fullCalcOnLoad="1"/></workbook>'''
        relationships = '''<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="s1" Type="worksheet" Target="worksheets/sheet1.xml"/></Relationships>'''
        worksheet = '''<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <dimension ref="A1:AF61"/><sheetData><row r="1">
          <c r="A1" t="s"><v>0</v></c><c r="B1" t="b"><v>1</v></c>
          <c r="C1" t="e"><v>#VALUE!</v></c><c r="D1" t="inlineStr"><is><t>literal</t></is></c></row>
          <row r="61"><c r="AF61" s="1"><f>SUM(1,2)</f><v>3</v></c></row></sheetData>
          <sheetProtection sheet="1" selectLockedCells="0"/>
          <mergeCells><mergeCell ref="A2:B2"/></mergeCells></worksheet>'''
        styles = '''<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <numFmts><numFmt numFmtId="164" formatCode="0.00"/></numFmts>
          <cellStyleXfs><xf numFmtId="0"/></cellStyleXfs>
          <cellXfs><xf numFmtId="0"/><xf numFmtId="164" applyProtection="1">
          <protection locked="0" hidden="1"/></xf></cellXfs></styleSheet>'''
        shared = '''<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <si><r><t>rich </t></r><r><t>text</t></r></si></sst>'''
        with ZipFile(self.path, 'w') as package:
            for name, text in [('xl/workbook.xml', workbook), ('xl/_rels/workbook.xml.rels', relationships),
                               ('xl/worksheets/sheet1.xml', worksheet), ('xl/styles.xml', styles), ('xl/sharedStrings.xml', shared)]:
                package.writestr(name, text)

    def test_reads_cells_beyond_the_bounded_preview_and_formula_names(self):
        result = AUDIT.inventory(self.path)
        self.assertEqual(5, result['cellCount'])
        self.assertEqual(1, result['formulaCount'])
        self.assertEqual('AF61', result['sheets'][0]['cells'][-1]['address'])
        self.assertEqual(['MAX'], result['definedNames'][1]['functionCalls'])
        self.assertEqual({'sheet': 'Full sheet', 'cell': 'AF61'}, result['functionCalls']['SUM'][0])
        self.assertEqual({'definedName': 'named_formula'}, result['functionCalls']['MAX'][0])

    def test_preserves_types_rich_text_number_formats_and_effective_protection(self):
        result = AUDIT.inventory(self.path)
        cells = result['sheets'][0]['cells']
        self.assertEqual(['s', 'b', 'e', 'inlineStr', 'n'], [cell['ooxmlType'] for cell in cells])
        self.assertEqual('rich text', cells[0]['value'])
        self.assertEqual('literal', cells[3]['value'])
        self.assertEqual({'locked': False, 'hidden': True}, cells[-1]['protection'])
        self.assertEqual({'locked': True, 'hidden': False}, cells[0]['protection'])
        self.assertEqual('0.00', result['customNumberFormats']['164'])
        self.assertEqual('1', result['sheets'][0]['protection']['sheet'])

    def test_sidecar_binding_preserves_source_coordinates_and_rejects_wrong_workbook(self):
        source = '; exact source\r\n'
        mapping = {'node': 'value', 'coord': ['member'], 'address': "'Full sheet'!$AF$61", 'editableInput': False}
        provenance = {'xlsxSHA256': AUDIT.sha256(self.path.read_bytes()), 'mappings': [mapping],
                      'sourceDocuments': {'source.mantra': {'text': source, 'sha256': AUDIT.sha256(source.encode())}}}
        self.assertEqual([mapping], AUDIT.inventory(self.path, provenance)['sheets'][0]['cells'][-1]['sourceMappings'])
        provenance['xlsxSHA256'] = 'different'
        with self.assertRaisesRegex(ValueError, 'different workbook'):
            AUDIT.inventory(self.path, provenance)

    def test_rejects_tampered_source_text_even_when_workbook_hash_matches(self):
        provenance = {'xlsxSHA256': AUDIT.sha256(self.path.read_bytes()), 'mappings': [],
                      'sourceDocuments': {'source.mantra': {'text': 'edited', 'sha256': AUDIT.sha256(b'original')}}}
        with self.assertRaisesRegex(ValueError, 'source SHA-256'):
            AUDIT.inventory(self.path, provenance)

    def test_lexes_function_calls_without_confusing_strings_quoted_sheets_or_escaped_quotes(self):
        formula = '''IF(A1="SUM(1)","EXACT(""a"",""a"")",'MAX(pretend)'!A1+_xlfn.ROUND(2,0))'''
        self.assertEqual(['IF', '_xlfn.ROUND'], AUDIT.formula_functions(formula))

    def test_compares_exact_decimal_text_without_hiding_unsafe_integer_or_fraction_errors(self):
        rows = [{'expectedExactDecimal': '0.3', 'actual': {'kind': 'number', 'value': 0.30000000000000004}},
                {'expectedExactDecimal': '9007199254740993', 'actual': {'kind': 'number', 'value': 9007199254740992}},
                {'expectedExactDecimal': '26.00', 'actual': {'kind': 'number', 'value': 26}}]
        compared = AUDIT.compare_decimal_rows({'sourceValues': rows})
        self.assertEqual([False, False, True], [row['matchesExactDecimalText'] for row in compared])

    def test_nonfinite_runtime_values_are_mismatches_instead_of_crashing_or_becoming_null_matches(self):
        row = {'expectedExactDecimal': '3', 'actual': {'kind': 'number', 'value': {'nonFinite': 'Infinity'}}}
        self.assertFalse(AUDIT.compare_decimal_rows({'sourceValues': [row]})[0]['matchesExactDecimalText'])

    def test_detects_iferror_masked_semantic_failure_against_the_original_formula_cache(self):
        static = {'sheets': [{'name': 'Audit', 'cells': [
            {'address': 'F14', 'formula': {'text': 'IF(IFERROR(ISTEXT(A1),FALSE),0,1)'},
             'ooxmlType': 'n', 'rawCachedValue': '0', 'value': '0'}]}]}
        runtime = {'initial': {'formulas': [{'sheet': 'Audit', 'cell': 'F14',
                                           'result': {'kind': 'number', 'value': 1}}]}}
        compared = AUDIT.compare_initial_formula_caches(runtime, static)
        self.assertFalse(compared[0]['matchesGenerationCache'])

    def test_never_treats_a_missing_generation_cache_as_a_verified_match(self):
        static = {'sheets': [{'name': 'Sheet', 'cells': [
            {'address': 'A1', 'formula': {'text': '1+2'}, 'ooxmlType': 'n', 'rawCachedValue': None, 'value': None}]}]}
        runtime = {'initial': {'formulas': [{'sheet': 'Sheet', 'cell': 'A1', 'result': {'kind': 'number', 'value': 3}}]}}
        compared = AUDIT.compare_initial_formula_caches(runtime, static)
        self.assertFalse(compared[0]['cacheAvailable'])
        self.assertFalse(compared[0]['matchesGenerationCache'])

    def test_masked_semantic_failure_blocks_complete_compatibility_even_when_scoped_values_and_error_counts_pass(self):
        def source_row(value):
            return {'kind': 'node', 'node': 'doubled', 'coord': [], 'editableInput': False,
                    'expectedExactDecimal': str(value), 'actual': {'kind': 'number', 'value': value}}
        runtime = {'importSucceeded': True, 'inputChanges': [{'before': 10, 'after': 13}],
                   'initial': {'sourceValues': [source_row(20)], 'formulaErrors': [], 'formulas': [
                       {'sheet': 'Audit', 'cell': 'F14', 'result': {'kind': 'number', 'value': 1}}]},
                   'edited': {'sourceValues': [source_row(26)], 'formulaErrors': []},
                   'importIR': {'diagnostics': []}, 'importPlan': {'diagnostics': []}}
        static = {'functionCalls': {}, 'xlsxSHA256': 'bound-workbook', 'sheets': [
            {'name': 'Audit', 'protection': {'sheet': '1'}, 'cells': [
                {'address': 'F14', 'formula': {'text': 'IFERROR(unsupported(),1)'},
                 'ooxmlType': 'n', 'rawCachedValue': '0', 'value': '0'}]}]}
        provenance = {'exportReport': {'fallbacks': [], 'evaluationErrors': []}, 'sourceIdentitySHA256': 'source',
                      'validationPassed': True, 'diagnostics': []}
        result = AUDIT.compatibility(runtime, static, provenance, provenance, [])
        self.assertTrue(result['scopedNumericalInputRecalculationProofPassed'])
        self.assertEqual(0, result['initialFormulaErrorCount'])
        self.assertEqual(1, result['initialFormulaCacheMismatchCount'])
        self.assertFalse(result['wholeWorkbookCompatible'])

    def test_relationships_stay_inside_package_and_absolute_paths_are_supported(self):
        self.assertEqual('xl/worksheets/sheet1.xml', AUDIT.relation_target('xl/workbook.xml', '/xl/worksheets/sheet1.xml'))
        self.assertEqual('xl/sharedStrings.xml', AUDIT.relation_target('xl/worksheets/sheet1.xml', '../sharedStrings.xml'))
        with self.assertRaisesRegex(ValueError, 'escapes'):
            AUDIT.relation_target('xl/workbook.xml', '../../outside.xml')


if __name__ == '__main__':
    unittest.main()
