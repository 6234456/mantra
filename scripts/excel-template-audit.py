#!/usr/bin/env python3
"""Full OOXML inventory and frozen Template Engine import/recalculation evidence; no third-party Python packages."""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
from decimal import Decimal
from xml.etree import ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
TE_COMMIT = '6443c68875a8592e79ea2099637545fe96a18ec0'
NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
      'p': 'http://schemas.openxmlformats.org/package/2006/relationships'}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text())


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n')


def formula_functions(formula):
    """Lex calls outside Excel string literals and quoted worksheet names, retaining extension prefixes."""
    visible = []
    quote = None
    index = 0
    while index < len(formula):
        character = formula[index]
        if quote:
            if character == quote:
                if index + 1 < len(formula) and formula[index + 1] == quote:
                    index += 2
                    continue
                quote = None
            visible.append(' ')
        elif character in {'"', "'"}:
            quote = character
            visible.append(' ')
        else:
            visible.append(character)
        index += 1
    return sorted(set(re.findall(r'(?<![\w.])([A-Za-z_][\w.]*)\s*\(', ''.join(visible))))


def xml_data(element):
    return {'tag': element.tag.split('}')[-1], 'attributes': dict(element.attrib),
            **({'text': element.text} if element.text else {}),
            **({'children': [xml_data(child) for child in element]} if len(element) else {})}


def relation_target(parent, target):
    parts = []
    for part in str(PurePosixPath(parent).parent / target).split('/') if not target.startswith('/') else target.split('/'):
        if part == '..':
            if not parts:
                raise ValueError('OOXML relationship escapes its package')
            parts.pop()
        elif part not in {'', '.'}:
            parts.append(part)
    return '/'.join(parts)


def relationships(package, parent):
    path = str(PurePosixPath(parent).parent / '_rels' / (PurePosixPath(parent).name + '.rels'))
    if path not in package.namelist():
        return []
    return [dict(element.attrib) for element in ET.fromstring(package.read(path))]


def plain_string(element):
    return ''.join(node.text or '' for node in [*element.findall('m:t', NS), *element.findall('m:r/m:t', NS)])


def inventory(path, provenance=None):
    contents = Path(path).read_bytes()
    if provenance and provenance['xlsxSHA256'] != sha256(contents):
        raise ValueError('Source sidecar is bound to a different workbook SHA-256')
    for source in (provenance or {}).get('sourceDocuments', {}).values():
        if sha256(source['text'].encode()) != source['sha256']:
            raise ValueError('Source sidecar text does not match its source SHA-256')
    owner_index = {}
    for mapping in (provenance or {}).get('mappings', []):
        match = re.fullmatch(r"(?:'((?:[^']|'')+)'|([^!]+))!(\$?[A-Z]+\$?\d+)", mapping.get('address') or '')
        if match:
            sheet = (match[1] or match[2]).replace("''", "'")
            owner_index.setdefault((sheet, match[3].replace('$', '')), []).append(mapping)
    with ZipFile(path) as package:
        files = package.infolist()
        if len(set(file.filename for file in files)) != len(files):
            raise ValueError('Duplicate ZIP part names cannot be audited unambiguously')
        if sum(file.file_size for file in files) > 100 * 1024 * 1024:
            raise ValueError('OOXML package exceeds the 100 MiB audit budget')
        parts = [{'path': file.filename, 'bytes': file.file_size, 'sha256': sha256(package.read(file))} for file in files]
        workbook = ET.fromstring(package.read('xl/workbook.xml'))
        workbook_relations = relationships(package, 'xl/workbook.xml')
        targets = {relation['Id']: relation_target('xl/workbook.xml', relation['Target'])
                   for relation in workbook_relations if relation.get('TargetMode') != 'External'}
        shared = []
        if 'xl/sharedStrings.xml' in package.namelist():
            shared = [plain_string(element) for element in ET.fromstring(package.read('xl/sharedStrings.xml'))]
        style_root = ET.fromstring(package.read('xl/styles.xml')) if 'xl/styles.xml' in package.namelist() else None
        styles = list(style_root.find('m:cellXfs', NS)) if style_root is not None else []
        style_bases = list(style_root.find('m:cellStyleXfs', NS)) if style_root is not None else []
        custom_formats = {item.attrib['numFmtId']: item.attrib['formatCode']
                          for item in style_root.findall('m:numFmts/m:numFmt', NS)} if style_root is not None else {}
        sheets = []
        functions = {}
        cell_count = 0
        formula_count = 0
        for sheet in workbook.findall('m:sheets/m:sheet', NS):
            attributes = dict(sheet.attrib)
            worksheet_path = targets[attributes[f"{{{NS['r']}}}id"]]
            xml = ET.fromstring(package.read(worksheet_path))
            protection = xml.find('m:sheetProtection', NS)
            cells = []
            for cell in xml.findall('m:sheetData/m:row/m:c', NS):
                style_index = int(cell.get('s', '0'))
                style = styles[style_index] if style_index < len(styles) else None
                cell_protection = style.find('m:protection', NS) if style is not None else None
                base_index = int(style.get('xfId', '0')) if style is not None else 0
                if cell_protection is None and base_index < len(style_bases):
                    cell_protection = style_bases[base_index].find('m:protection', NS)
                effective = {'locked': True, 'hidden': False}
                if cell_protection is not None:
                    effective.update({name: value not in {'0', 'false'} for name, value in cell_protection.attrib.items()})
                formula = cell.find('m:f', NS)
                raw = cell.findtext('m:v', default=None, namespaces=NS)
                kind = cell.get('t', 'n')
                value = raw
                if kind == 's' and raw is not None:
                    value = shared[int(raw)]
                elif kind == 'inlineStr':
                    inline = cell.find('m:is', NS)
                    value = plain_string(inline) if inline is not None else ''
                item = {'address': cell.attrib['r'], 'ooxmlType': kind, 'rawCachedValue': raw, 'value': value,
                        'styleIndex': style_index, 'numberFormatId': style.get('numFmtId', '0') if style is not None else '0',
                        'protection': effective, 'sourceMappings': owner_index.get((attributes['name'], cell.attrib['r']), [])}
                if formula is not None:
                    source = formula.text or ''
                    names = formula_functions(source)
                    item['formula'] = {'text': source, 'attributes': dict(formula.attrib), 'functionCalls': names}
                    formula_count += 1
                    for name in names:
                        functions.setdefault(name.upper(), []).append({'sheet': attributes['name'], 'cell': cell.attrib['r']})
                cells.append(item)
            cell_count += len(cells)
            sheets.append({'name': attributes['name'], 'state': attributes.get('state', 'visible'), 'part': worksheet_path,
                           'dimension': xml.find('m:dimension', NS).attrib if xml.find('m:dimension', NS) is not None else None,
                           'protection': dict(protection.attrib) if protection is not None else None,
                           'cells': cells, 'relationships': relationships(package, worksheet_path),
                           'mergeRanges': [element.attrib for element in xml.findall('m:mergeCells/m:mergeCell', NS)],
                           'dataValidations': [xml_data(element) for element in xml.findall('m:dataValidations', NS)],
                           'conditionalFormatting': [xml_data(element) for element in xml.findall('m:conditionalFormatting', NS)]})
        names = [{'attributes': dict(element.attrib), 'formula': element.text or '',
                  'functionCalls': formula_functions(element.text or '')}
                 for element in workbook.findall('m:definedNames/m:definedName', NS)]
        for name in names:
            for function in name['functionCalls']:
                functions.setdefault(function.upper(), []).append({'definedName': name['attributes']['name']})
        return {'format': 'mantra.excel-template-artifact-inventory/1', 'xlsxSHA256': sha256(contents),
                'bytes': len(contents), 'parts': parts, 'sheetCount': len(sheets), 'cellCount': cell_count,
                'formulaCount': formula_count, 'definedNameCount': len(names), 'functionCalls': functions,
                'calcProperties': workbook.find('m:calcPr', NS).attrib if workbook.find('m:calcPr', NS) is not None else None,
                'workbookRelationships': workbook_relations, 'definedNames': names, 'sheets': sheets,
                'styles': xml_data(style_root) if style_root is not None else None, 'customNumberFormats': custom_formats,
                'tables': [{'part': file.filename, 'xml': xml_data(ET.fromstring(package.read(file)))}
                           for file in files if re.fullmatch(r'xl/tables/[^/]+\.xml', file.filename)],
                'mappingCount': len((provenance or {}).get('mappings', [])),
                'unmappedSources': [mapping for mapping in (provenance or {}).get('mappings', []) if not mapping.get('address')]}


def compare_decimal_rows(stage):
    compared = []
    for entry in stage.get('sourceValues', []):
        actual = entry['actual']
        matches = decimal_matches(actual, entry['expectedExactDecimal'])
        compared.append({**entry, 'matchesExactDecimalText': matches})
    return compared


def decimal_matches(actual, expected):
    value = actual.get('value')
    return actual.get('kind') == 'number' and isinstance(value, (int, float)) and not isinstance(value, bool) \
        and Decimal(str(value)).is_finite() and Decimal(str(value)) == Decimal(expected)


def compare_initial_formula_caches(artifact, static):
    runtime = {(entry['sheet'], entry['cell']): entry for entry in artifact.get('initial', {}).get('formulas', [])}
    compared = []
    for sheet in static['sheets']:
        for cell in sheet['cells']:
            if 'formula' not in cell:
                continue
            actual = runtime.get((sheet['name'], cell['address']), {}).get('result')
            raw = cell['rawCachedValue']
            kind = cell['ooxmlType']
            expected = {'ooxmlType': kind, 'rawCachedValue': raw, 'value': cell['value']}
            matches = False
            if actual and raw is not None:
                if kind == 'n' and actual.get('kind') == 'number':
                    matches = decimal_matches(actual, raw)
                elif kind == 'b' and actual.get('kind') == 'boolean':
                    matches = (raw == '1') == actual['value']
                elif kind in {'s', 'str', 'inlineStr'} and actual.get('kind') == 'string':
                    matches = cell['value'] == actual['value']
                elif kind == 'e' and actual.get('kind') == 'error':
                    matches = raw == actual.get('code')
            compared.append({'sheet': sheet['name'], 'cell': cell['address'], 'formula': cell['formula']['text'],
                             'cachedGenerationExpected': expected, 'actual': actual,
                             'matchesGenerationCache': matches, 'cacheAvailable': raw is not None})
    return compared


def compatibility(artifact, static, provenance, edited_provenance, capabilities):
    supported = {item['name'] for item in capabilities}
    unsupported = [{'function': name, 'locations': locations} for name, locations in static['functionCalls'].items()
                   if name not in supported]
    initial = compare_decimal_rows(artifact.get('initial', {}))
    edited = compare_decimal_rows(artifact.get('edited', {}))
    calculated = [entry for entry in edited if not entry['editableInput']]
    first = {(entry['kind'], entry['node'], tuple(entry['coord'])): entry for entry in initial if not entry['editableInput']}
    changed = [entry for entry in calculated if first.get((entry['kind'], entry['node'], tuple(entry['coord'])), {}).get('actual')
               != entry['actual']]
    initial_errors = artifact.get('initial', {}).get('formulaErrors', [])
    edited_errors = artifact.get('edited', {}).get('formulaErrors', [])
    initial_report = provenance['exportReport']
    formula_caches = compare_initial_formula_caches(artifact, static)
    protected_sheets = [sheet['name'] for sheet in static['sheets']
                        if (sheet['protection'] or {}).get('sheet') in {'1', 'true'}]
    checks = [{**entry, 'matchesBoolean': entry['actual'].get('kind') == 'boolean'
               and entry['actual'].get('value') == entry['expectedBoolean']}
              for entry in artifact.get('edited', {}).get('sourceChecks', [])]
    initial_checks = [{**entry, 'matchesBoolean': entry['actual'].get('kind') == 'boolean'
                       and entry['actual'].get('value') == entry['expectedBoolean']}
                      for entry in artifact.get('initial', {}).get('sourceChecks', [])]
    subset_passed = bool(artifact.get('importSucceeded') and not artifact.get('failure') and artifact.get('inputChanges')
                         and calculated and changed and all(entry['matchesExactDecimalText'] for entry in initial + edited)
                         and all(entry['matchesBoolean'] for entry in initial_checks + checks))
    all_passed = bool(subset_passed and not unsupported and not initial_errors and not edited_errors
                      and not initial_report['fallbacks'] and not initial_report['evaluationErrors']
                      and not artifact.get('importPlan', {}).get('diagnostics')
                      and all(entry['matchesGenerationCache'] for entry in formula_caches))
    return {'wholeWorkbookCompatible': all_passed,
            'scopedNumericalInputRecalculationProofPassed': subset_passed,
            'scope': 'All source-bound numeric cells and aggregates in these two explicit seed/changed cases only.',
            'unsupportedFunctions': unsupported, 'initialFormulaErrorCount': len(initial_errors),
            'editedFormulaErrorCount': len(edited_errors), 'importDiagnostics': artifact.get('importIR', {}).get('diagnostics', []),
            'conversionDiagnostics': artifact.get('importPlan', {}).get('diagnostics', []),
            'initialExactDecimalComparisons': initial, 'editedExactDecimalComparisons': edited,
            'initialFullFormulaCacheComparisons': formula_caches,
            'initialFormulaCacheMismatchCount': sum(not entry['matchesGenerationCache'] for entry in formula_caches),
            'initialSourceCheckComparisons': initial_checks, 'editedSourceCheckComparisons': checks,
            'changedCaseValidationPassed': edited_provenance['validationPassed'],
            'changedCaseDiagnostics': edited_provenance['diagnostics'],
            'protectionAssessment': {'protectedSheets': protected_sheets,
                                     'formulaCellsOnUnprotectedSheets': sum('formula' in cell for sheet in static['sheets']
                                         if sheet['name'] not in protected_sheets for cell in sheet['cells']),
                                     'inputEditabilityComesFromSourceSidecar': True,
                                     'derivedFormulaEditPolicy': 'Most generated formulas are not protected by the workbook; edits constitute forks.'},
            'styleAssessment': {'completeOOXMLStylesRetained': True, 'actualImportStylesRetainedInRuntimeEvidence': True,
                                'visualParityVerified': False},
            'changedCalculatedSourceCells': changed, 'generationReport': initial_report,
            'changedCaseGenerationReport': edited_provenance['exportReport'],
            'sourceIdentitySHA256': provenance['sourceIdentitySHA256'],
            'xlsxSHA256': static['xlsxSHA256'], 'failure': artifact.get('failure')}


def command(arguments, cwd=None):
    result = subprocess.run([str(argument) for argument in arguments], cwd=cwd, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {arguments[0]}\n{result.stderr}\n{result.stdout}")
    return {'arguments': [str(argument) for argument in arguments], 'stdout': result.stdout, 'stderr': result.stderr}


def normalize_provenance(path):
    provenance = read_json(path)
    sources = {}
    for name, source in provenance['sourceDocuments'].items():
        try:
            relative = str(Path(name).relative_to(ROOT))
        except ValueError:
            relative = str(Path(name).name)
        if relative in sources:
            raise ValueError('Source closure cannot collapse distinct paths')
        sources[relative] = source
    provenance['sourceDocuments'] = sources
    provenance['sourceIdentitySHA256'] = sha256(json.dumps(
        sorted((name, source['sha256']) for name, source in sources.items()), separators=(',', ':')).encode())
    write_json(path, provenance)
    return provenance


def proof(arguments):
    output = arguments.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    revision = command(['git', '-C', arguments.template_engine, 'rev-parse', f'{TE_COMMIT}^{{commit}}'])['stdout'].strip()
    if revision != TE_COMMIT:
        raise ValueError('The reviewed Template Engine source commit is required')
    tree = command(['git', '-C', arguments.template_engine, 'rev-parse', f'{TE_COMMIT}^{{tree}}'])['stdout'].strip()
    with tempfile.TemporaryDirectory(prefix='mantra-excel-audit-') as temporary:
        temporary = Path(temporary)
        archive = temporary / 'template-engine.tar'
        with archive.open('wb') as stream:
            subprocess.run(['git', '-C', str(arguments.template_engine), 'archive', '--format=tar', TE_COMMIT],
                           stdout=stream, check=True)
        frozen = temporary / 'template-engine'
        frozen.mkdir()
        with tarfile.open(archive) as source:
            source.extractall(frozen, filter='data')
        library = temporary / 'lib'
        library.mkdir()
        jars = []
        for file in sorted((arguments.cli_home / 'lib').glob('*.jar')):
            shutil.copyfile(file, library / file.name)
            jars.append({'name': file.name, 'sha256': sha256((library / file.name).read_bytes())})
        if not jars:
            raise ValueError('Build mantra-cli:installDist first, or supply --cli-home')
        fixture = ROOT / 'scripts/fixtures/excel-template-audit/simple'
        pattern = ROOT / 'docs/patterns/actuals-vs-baseline'
        edited_case = temporary / 'actuals-edited.mantra'
        original_case = (pattern / 'case-demo.mantra').read_text()
        if original_case.count('[:A "Member A" 10 12 10 150]') != 1:
            raise ValueError('The proof input edit must match the exact recorded source once')
        edited_case.write_text(original_case.replace('[:A "Member A" 10 12 10 150]', '[:A "Member A" 13 12 10 150]'))
        cases = [('simple', fixture / 'schema.mantra', fixture / 'case-base.mantra', fixture / 'case-edited.mantra', 'default'),
                 ('actuals-vs-baseline', pattern / 'schema.mantra', pattern / 'case-demo.mantra', edited_case,
                  pattern / 'layout.mantra')]
        requests = []
        artifacts = {}
        runs = []
        for identity, schema, initial_case, changed_case, layout in cases:
            base_directory = output / identity / 'base'
            edited_directory = output / identity / 'edited'
            for facts, destination in [(initial_case, base_directory), (changed_case, edited_directory)]:
                runs.append(command([arguments.java, '-cp', str(library / '*'), ROOT / 'scripts/excel-template-audit-export.java',
                                     schema, facts, layout, destination], ROOT))
            base = normalize_provenance(base_directory / 'provenance.json')
            edited = normalize_provenance(edited_directory / 'provenance.json')
            static = inventory(base_directory / 'artifact.xlsx', base)
            write_json(base_directory / 'inventory.json', static)
            write_json(edited_directory / 'inventory.json', inventory(edited_directory / 'artifact.xlsx', edited))
            artifacts[identity] = (static, base, edited)
            requests.append({'id': identity, 'xlsx': str(base_directory / 'artifact.xlsx'),
                             'provenance': str(base_directory / 'provenance.json'),
                             'editedProvenance': str(edited_directory / 'provenance.json')})
        request = temporary / 'request.json'
        write_json(request, {'templateEngineCommit': TE_COMMIT, 'artifacts': requests})
        runtime_path = output / 'template-engine-runtime.json'
        runs.append(command(['node', ROOT / 'scripts/excel-template-audit-runtime.mjs', frozen,
                             arguments.dependencies.resolve(), request, runtime_path], ROOT))
        runtime = read_json(runtime_path)
        dependency_lock = arguments.dependencies / 'package-lock.json'
        shutil.copyfile(dependency_lock, output / 'audit-dependencies-lock.json')
        expected = {'EXACT("A","A")': True, 'ISBLANK(A1)': True, 'ISLOGICAL(TRUE)': True, 'ISTEXT("A")': True,
                    'ROUND(-1.5,0)': '-2', 'ROUND(1.005,2)': '1.01', '0.1+0.2': '0.3',
                    '9007199254740992+1': '9007199254740993'}
        probes = []
        for probe in runtime['probes']:
            expectation = expected[probe['formula']]
            actual = probe['actual']
            matches = (actual.get('kind') == 'boolean' and actual.get('value') == expectation) if isinstance(expectation, bool) \
                else decimal_matches(actual, expectation)
            probes.append({**probe, 'independentExpected': expectation, 'matchesIndependentExpected': matches})
        report = {'format': 'mantra.excel-template-compatibility/1',
                  'templateEngine': {'repository': 'https://github.com/6234456/paramita-v2', 'commit': TE_COMMIT,
                                     'tree': tree, 'sourceArchiveSHA256': sha256(archive.read_bytes()),
                                     'sourceLockSHA256': sha256((frozen / 'pnpm-lock.yaml').read_bytes()),
                                     'actualModuleCount': len(runtime['sourceModules']),
                                     'sourceModuleIdentitySHA256': sha256(json.dumps(runtime['sourceModules'],
                                         separators=(',', ':')).encode())},
                  'engineLibraries': jars,
                  'engineLibraryIdentitySHA256': sha256(json.dumps(jars, separators=(',', ':')).encode()),
                  'installedDependencyLockSHA256': sha256(dependency_lock.read_bytes()),
                  'actualDependencies': runtime['actualDependencies'],
                  'numericContract': {'target': 'JavaScript IEEE-754 binary64; source decimals retained as exact strings',
                                      'verifiedScope': 'The recorded small inputs and exact results only; no general decimal parity.',
                                      'negativeHalfRounding': 'ROUND differs from Mantra HALF_UP and Excel expected semantics.',
                                      'maximumSafeInteger': '9007199254740991',
                                      'microsoftExcelRuntimeVerified': False},
                  'independentProbes': probes,
                  'artifacts': {item['id']: compatibility(item, *artifacts[item['id']], runtime['capabilities'])
                                for item in runtime['artifacts']},
                  'execution': {'auditCompleted': True, 'runtimeEvidence': 'template-engine-runtime.json',
                                'dependencyLock': 'audit-dependencies-lock.json',
                                'nodeVersion': command(['node', '--version'])['stdout'].strip(),
                                'javaVersion': command([arguments.java, '--version'])['stdout'].strip(),
                                'pythonVersion': platform.python_version(),
                                'commands': [{**run, 'arguments': [value.replace(str(temporary), '<temporary>')
                                                               for value in run['arguments']]} for run in runs]},
                  'publicationReady': False}
        write_json(output / 'compatibility.json', report)
        for identity, item in report['artifacts'].items():
            print(f"{identity}: import={runtime['artifacts'][list(report['artifacts']).index(identity)].get('importSucceeded', False)} "
                  f"numericSubset={item['scopedNumericalInputRecalculationProofPassed']} "
                  f"wholeWorkbook={item['wholeWorkbookCompatible']} "
                  f"runtimeErrors={item['editedFormulaErrorCount']}")
        if arguments.require_compatible and not all(item['wholeWorkbookCompatible'] for item in report['artifacts'].values()):
            return 2
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest='action', required=True)
    scan = subcommands.add_parser('inventory', help='Inspect every OOXML cell, formula, name, style and protection setting')
    scan.add_argument('xlsx', type=Path)
    scan.add_argument('--provenance', type=Path)
    scan.add_argument('--output', type=Path, required=True)
    verify = subcommands.add_parser('proof', help='Generate XLSX and execute the frozen real Template Engine import/runtime')
    verify.add_argument('--template-engine', type=Path, required=True)
    verify.add_argument('--dependencies', type=Path, required=True, help='Isolated npm prefix with the documented locked dependencies')
    verify.add_argument('--cli-home', type=Path, default=ROOT / 'mantra-cli/build/install/mantra')
    verify.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    verify.add_argument('--output', type=Path, default=ROOT / 'build/excel-template-audit')
    verify.add_argument('--require-compatible', action='store_true', help='Exit 2 after a completed incompatible audit')
    arguments = parser.parse_args()
    if arguments.action == 'inventory':
        write_json(arguments.output, inventory(arguments.xlsx, read_json(arguments.provenance) if arguments.provenance else None))
        return 0
    return proof(arguments)


if __name__ == '__main__':
    raise SystemExit(main())
