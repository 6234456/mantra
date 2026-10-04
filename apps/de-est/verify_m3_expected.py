#!/usr/bin/env python3
"""Draft-independent application address checker; standard library only.

_reference.py is the byte-identical frozen source algorithm. This adapter first
recomputes each frozen reference, then checks the independent address adaptation.
No engine, renderer, workbook or JVM is imported, and no engine value is an oracle.
UNCOMPILED APP DRAFT: this source check cannot establish engine acceptance.
"""
import argparse, hashlib, importlib.util, json
from decimal import Decimal
from pathlib import Path
HERE = Path(__file__).resolve().parent
DATA = HERE / 'independent'
spec = importlib.util.spec_from_file_location('m3_original_reference', DATA / '_reference.py')
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)
CASES = json.loads((DATA / 'case-map.json').read_text())['cases']

def equal_numbers(expected, actual, context):
    for key, value in expected.items():
        assert key in actual, (context, 'missing', key)
        assert Decimal(source.text(actual[key])) == Decimal(source.text(value)), (context, key, value, actual[key])

def verify_sources():
    seen = set()
    for case in CASES:
        fact_path = DATA / case['source_fact']
        fact = json.loads(fact_path.read_text())
        domain = case['domain']
        token = (domain, fact['case'])
        if token in seen: continue
        seen.add(token)
        values, summary = source.reference(domain, fact)
        frozen = json.loads((DATA / case['source_reference']).read_text())
        assert set(values) == set(frozen), token
        equal_numbers(frozen, values, token)
        assert summary == json.loads((DATA / case['source_summary']).read_text()), token
    manifest = json.loads((DATA / 'manifest.json').read_text())
    for name, digest in manifest['sha256'].items():
        assert hashlib.sha256((DATA / name).read_bytes()).hexdigest() == digest, name
    return len(seen)

def verify_addresses():
    for case in CASES:
        expected = case['expected_values']
        frozen_app = json.loads((DATA / 'app-values' / (Path(case['case_path']).stem + '-values.json')).read_text())
        assert expected == frozen_app, case['case_path']
        assert (HERE / case['case_path']).is_file(), case['case_path']
        # Every adaptation is a rename/alias or a separately declared input/weight.
        fact = json.loads((DATA / case['source_fact']).read_text())
        original, _ = source.reference(case['domain'], fact)
        if case['domain'] == 'loss':
            for alias, key in {'ledger-opening':'opening-loss','ledger-new':'eligible-new-loss',
                               'ledger-used':'loss-used','ledger-carryback':'selected-carryback'}.items():
                assert Decimal(expected[alias]) == original[key], (case['case_path'], alias)
        if case.get('supplemental_source'):
            independent = source.income_selected_chain(source.Z, Decimal(fact['positive_income']), original['loss-used'])
            adapted = {}
            for key, value in independent.items():
                address = key.replace('gewst-payable@A', 'gewst-due@A')
                if address in {'positive-trade-income', 'fourfold-messbetrag'}:
                    address += '@A'
                adapted[address] = value
            equal_numbers(adapted, expected, case['case_path'])
        if case['domain'] == 'trade' and case['schema'] == 'de.gewst/2025@2025.1':
            for index, fee in enumerate(fact['fees'], 1):
                key = '@F' + str(index)
                assert Decimal(expected['fee-amount' + key]) == Decimal(fee['amount'])
                weight = Decimal(source.P['trade']['weighted_fractions'].get(fee['category'], '0'))
                assert Decimal(expected['weighting-fraction' + key]) == weight
                assert Decimal(expected['weighted-fee' + key]) == Decimal(fee['amount']) * weight
        if case['domain'] == 'converge':
            assert Decimal(expected['maximum-calls']) == Decimal(fact['maximum_calls'])
            if case['acceptance_role'] == 'targeted-technical-failure':
                assert 'converged-amount' not in expected
    return sum(len(c['expected_values']) for c in CASES)

def compare_outputs(folder):
    compared = 0
    for case in CASES:
        if case['acceptance_role'] in {'targeted-technical-failure', 'focused-amount-test'}:
            continue  # These need explicit targeted diagnostic/amount tests, not fabricated successful outputs.
        relative = Path(case['case_path']).with_suffix('')
        actual_path = folder / relative.parent / (relative.name + '-values.json')
        actual = json.loads(actual_path.read_text())
        equal_numbers(case['expected_values'], actual, case['case_path'])
        compared += len(case['expected_values'])
    return compared

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--outputs', type=Path)
    args = parser.parse_args()
    facts = verify_sources()
    assertions = verify_addresses()
    if args.outputs:
        count = compare_outputs(args.outputs)
        print(f'{count} independently sourced numeric output assertions')
    else:
        print(f'{facts} frozen facts; {assertions} independent draft address assertions')
