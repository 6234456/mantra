#!/usr/bin/env python3
"""Frozen fictional 10k lease source and Decimal references; no engine, DSL or workbook reads."""
import argparse
from decimal import Decimal as D, ROUND_HALF_UP, localcontext
import hashlib
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('independent_lease_reference', HERE.parent / 'verify_expected.py')
REFERENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REFERENCE)
PERIODS = [{'id': f'P{index+1}', 'start': f'{2026+index}-01-01', 'end': f'{2027+index}-01-01'} for index in range(3)]


def facts(index):
    """Contract amounts are fictional and differ for every case, not repeated values with new IDs."""
    term = index % 3 + 1
    rate = [D('0'), D('0.025'), D('0.05')][index % 3]
    payments = [D('1000') + D(index + 1) * D('7.13') + D(period + 1) * D('23.41') for period in range(term)]
    rounded = lambda value: value.quantize(D('0.01'), ROUND_HALF_UP)
    opening = rounded(sum((payment / (1 + rate) ** (period + 1) for period, payment in enumerate(payments)), D(0)))
    reported = {}
    for period in range(3):
        interest = rounded(opening * rate) if period < term else D(0)
        payment = payments[period] if period < term else D(0)
        opening = opening + interest - payment  # Never clamp terminal currency residue.
        reported[f'P{period+1}'] = str(opening)
    return {'id': f'lease-batch-{index+1:05d}', 'periods': PERIODS,
            'reconciliation_tolerance': '0.01',
            'leases': [{'id': f'L{index+1:05d}', 'title': f'Fictional contract {index+1}',
                        'annual_rate': str(rate), 'term_years': term, 'payments': [str(value) for value in payments],
                        'commencement_payment': str(D(index + 1) * D('0.03')),
                        'initial_direct_costs': str(D(index % 7) * D('1.11')),
                        'commencement_incentive': '0', 'reported_liability_closing': reported}]}


def record(index):
    with localcontext() as context:
        context.prec = 80
        source = facts(index)
        values, summary = REFERENCE.compute(source)
        assert summary['validation_passed']
        return {'index': index, 'caseId': source['id'], 'facts': source,
                'values': {key: str(value) for key, value in sorted(values.items())},
                'validationPassed': True, 'expectedBusiness': []}


def self_test():
    first = record(0)
    lease = 'L00001'
    assert first['values'][f'initial-liability@{lease}'] == '1030.54'
    assert D(first['values'][f'liability-closing@{lease}/P1']) == 0
    assert D(first['values']['rou-closing@*']) == 0
    assert D(first['values']['rou-opening@*']) == D('1030.57')
    assert D(first['values']['interest@*']) == 0
    for index in [0, 1, 2, 7, 749, 9999]:
        row = record(index)
        values = {key: D(value) for key, value in row['values'].items()}
        assert values['liability-opening@*'] + values['interest@*'] - values['payment@*'] == values['liability-closing@*']
        assert values['rou-opening@*'] - values['rou-depreciation@*'] == values['rou-closing@*']
        assert values[f'liability-opening@L{index+1:05d}/*'] == values['liability-opening@*']
        assert values['liability-closing@*/P3'] == values['liability-closing@*']
    assert record(9999)['facts']['leases'][0]['payments'] != first['facts']['leases'][0]['payments']


def write(count, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('w', encoding='utf-8', newline='\n') as target:
        for index in range(count):
            target.write(json.dumps(record(index), ensure_ascii=False, separators=(',', ':')) + '\n')
    manifest = {'status': 'INDEPENDENT_REFERENCE_GENERATED_ENGINE_UNEXECUTED', 'cases': count,
                'binding': {'schemaId': 'ifrs.ifrs16/lessee-schedule', 'schemaVersion': '0.1', 'versionMode': 'legacy-exact', 'parameterIds': [], 'currencyScale': 2},
                'policy': 'Frozen fictional rates/payments; source reported balances independently Decimal-derived; no liability clamp; cent half-up rounding.',
                'records': {'path': output.name, 'byteLength': output.stat().st_size, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest()},
                'sources': [{'path': path.name if path.parent == HERE else '../verify_expected.py',
                             'sha256': hashlib.sha256(path.read_bytes()).hexdigest()} for path in [Path(__file__).resolve(), HERE.parent / 'verify_expected.py']]}
    output.with_suffix('.manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'cases': count, 'valuesPerCase': len(record(0)['values']), 'bytes': output.stat().st_size,
                      'referenceSHA256': manifest['records']['sha256'], 'engineExecuted': False}, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', type=int, default=10000)
    parser.add_argument('--out', type=Path, default=HERE / 'reference.jsonl')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    self_test()
    if not args.self_test:
        if args.cases <= 0:
            raise ValueError('Positive case count required')
        write(args.cases, args.out)


if __name__ == '__main__':
    main()
