#!/usr/bin/env python3
"""Independent fictional annual lease schedule; Decimal only, no DSL or engine imports."""
import argparse
import hashlib
from datetime import date
from decimal import Decimal, ROUND_HALF_UP, localcontext
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
D = Decimal


def decimal(value):
    return D(str(value))


def compute(fixture, scale=2):
    quantum = D(1).scaleb(-scale)
    rounded = lambda value: value.quantize(quantum, ROUND_HALF_UP)
    periods = fixture['periods']
    for previous, current in zip(periods, periods[1:]):
        if previous['end'] != current['start']:
            raise ValueError('Periods must be contiguous')
    if not periods:
        raise ValueError('At least one annual period is required')
    for period in periods:
        start, end = date.fromisoformat(period['start']), date.fromisoformat(period['end'])
        if start.day != 1 or end != date(start.year + 1, start.month, start.day):
            raise ValueError('This preparation model supports full annual periods only')
    amounts = {}
    summaries = []
    findings = []
    for lease in fixture['leases']:
        lease_id = lease['id']
        rate = decimal(lease['annual_rate'])
        if rate <= -1:
            raise ValueError('Discounting is mathematically undefined at rate <= -1')
        # A demo-only supported-input rule; negative interest is still calculated faithfully.
        if rate < 0:
            findings.append({'code': 'MANTRA-CHECK-FAILED', 'node': 'lease-rate-supported',
                             'coord': [lease_id], 'severity': 'error'})
        term = lease['term_years']
        if term <= 0 or term > len(periods) or len(lease['payments']) != term:
            raise ValueError('Positive term must match the supplied annual payment schedule')
        payments = [decimal(p) for p in lease['payments']]
        initial = rounded(sum((payment / (1 + rate) ** (index + 1)
                               for index, payment in enumerate(payments)), D(0)))
        prepaid = decimal(lease['commencement_payment'])
        direct = decimal(lease['initial_direct_costs'])
        incentive = decimal(lease['commencement_incentive'])
        initial_rou = initial + prepaid + direct - incentive
        if initial_rou < 0:
            raise ValueError('Preparation fixtures require a nonnegative right-of-use cost')
        ordinary_depreciation = rounded(initial_rou / D(term))
        opening = initial
        rou_opening = initial_rou
        amounts[f'initial-liability@{lease_id}'] = initial
        amounts[f'initial-rou-cost@{lease_id}'] = initial_rou
        total_interest = D(0)
        total_payment = D(0)
        total_depreciation = D(0)
        lease_periods = []
        for index, period in enumerate(periods):
            period_id = period['id']
            in_term = index < term
            payment = payments[index] if in_term else D(0)
            interest = rounded(opening * rate) if in_term else D(0)
            closing = opening + interest - payment
            # Terminal service-period currency residue is disclosed, not hidden in lease payments.
            depreciation = (rou_opening if index + 1 == term else min(rou_opening, ordinary_depreciation)) if in_term else D(0)
            rou_closing = rou_opening - depreciation
            principal = payment - interest
            values = {'liability_opening': opening, 'interest': interest, 'payment': payment,
                      'principal_reduction': principal, 'liability_closing': closing,
                      'rou_opening': rou_opening, 'rou_depreciation': depreciation, 'rou_closing': rou_closing}
            for key, value in values.items():
                amounts[f'{key.replace("_", "-")}@{lease_id}/{period_id}'] = value
            if payment < 0:
                findings.append({'code': 'MANTRA-CHECK-FAILED', 'node': 'payment-nonnegative',
                                 'coord': [lease_id, period_id], 'severity': 'error'})
            reported = decimal(lease['reported_liability_closing'][period_id])
            difference = closing - reported
            amounts[f'reported-liability-closing@{lease_id}/{period_id}'] = reported
            amounts[f'liability-reconciliation@{lease_id}/{period_id}'] = difference
            if abs(difference) > decimal(fixture['reconciliation_tolerance']):
                findings.append({'code': 'MANTRA-RECONCILE-FAILED', 'node': 'liability-reconciliation',
                                 'coord': [lease_id, period_id], 'severity': 'error'})
            total_interest += interest
            total_payment += payment
            total_depreciation += depreciation
            lease_periods.append({'period': period_id, **{key: str(value) for key,value in values.items()},
                                  'reported_liability_closing': str(reported), 'difference': str(difference)})
            opening = closing
            rou_opening = rou_closing
        amounts.update({f'liability-opening@{lease_id}/*': initial,
                        f'liability-closing@{lease_id}/*': opening,
                        f'rou-opening@{lease_id}/*': initial_rou,
                        f'rou-closing@{lease_id}/*': rou_opening,
                        f'interest@{lease_id}/*': total_interest,
                        f'payment@{lease_id}/*': total_payment,
                        f'rou-depreciation@{lease_id}/*': total_depreciation,
                        f'principal-reduction@{lease_id}/*': total_payment - total_interest,
                        f'reported-liability-closing@{lease_id}/*': decimal(lease['reported_liability_closing'][periods[-1]['id']])})
        assert initial + total_interest - total_payment == opening
        assert initial_rou - total_depreciation == rou_opening
        summaries.append({'lease': lease_id, 'initial_liability': str(initial), 'initial_rou': str(initial_rou),
                          'commencement_cash': str(prepaid), 'final_liability': str(opening),
                          'final_rou': str(rou_opening), 'total_interest': str(total_interest),
                          'total_payments': str(total_payment), 'total_rou_depreciation': str(total_depreciation),
                          'periods': lease_periods})
    for index, period in enumerate(periods):
        for node in ['liability-opening','interest','payment','principal-reduction','liability-closing',
                     'rou-opening','rou-depreciation','rou-closing']:
            amounts[f'period-{node}@{period["id"]}'] = sum(
                (amounts[f'{node}@{lease["id"]}/{period["id"]}'] for lease in fixture['leases']), D(0))
    stocks = ['liability-opening', 'liability-closing', 'rou-opening', 'rou-closing', 'reported-liability-closing']
    flows = ['interest', 'payment', 'rou-depreciation', 'principal-reduction']
    for node in stocks + flows:
        amounts[f'{node}@*'] = sum((amounts[f'{node}@{lease["id"]}/*'] for lease in fixture['leases']), D(0))
        for period in periods:
            amounts[f'{node}@*/{period["id"]}'] = sum(
                (amounts[f'{node}@{lease["id"]}/{period["id"]}'] for lease in fixture['leases']), D(0))
    for node in ['initial-liability', 'initial-rou-cost']:
        amounts[f'{node}@*'] = sum((amounts[f'{node}@{lease["id"]}'] for lease in fixture['leases']), D(0))
    for node in ['liability-opening', 'interest', 'payment', 'principal-reduction', 'liability-closing',
                 'rou-opening', 'rou-depreciation', 'rou-closing']:
        series = [amounts[f'period-{node}@{period["id"]}'] for period in periods]
        amounts[f'period-{node}@*'] = series[0] if node.endswith('-opening') else (
            series[-1] if node.endswith('-closing') else sum(series, D(0)))
    amounts['currency-scale'] = D(scale)
    return amounts, {'case': fixture['id'], 'scale': scale, 'leases': summaries,
                     'combined_initial_liability': str(amounts['liability-opening@*']),
                     'combined_final_liability': str(amounts['liability-closing@*']),
                     'combined_final_rou': str(amounts['rou-closing@*']),
                     'interest_flow': str(amounts['interest@*']), 'payment_flow': str(amounts['payment@*']),
                     'depreciation_flow': str(amounts['rou-depreciation@*']),
                     'validation_passed': not findings, 'expected_business': findings}


def expected(name='demo', scale=2):
    with localcontext() as context:
        context.prec = 80
        return compute(json.loads((HERE / 'fixtures' / f'{name}.json').read_text()), scale)


def self_test():
    values, summary = expected()
    assert values['initial-liability@Office'] == D('27232.48')
    assert [values[f'liability-closing@Office/P{year-2025}'] for year in range(2026,2029)] == list(map(D, ['18594.10','9523.81','0']))
    assert [values[f'rou-depreciation@Office/P{year-2025}'] for year in range(2026,2029)] == list(map(D, ['9077.49','9077.49','9077.50']))
    assert summary['validation_passed']
    assert values['liability-opening@*'] == D('45232.48')
    assert values['liability-closing@*'] == 0 and values['rou-closing@*'] == 0
    assert values['interest@*'] == D('2767.52') and values['payment@*'] == D(48000)
    assert values['interest@Storage/*'] == 0
    assert values['liability-opening@Office/*'] == D('27232.48')
    assert values['liability-closing@Office/*'] == 0
    assert values['liability-closing@*/P2'] == D('15523.81')
    assert values['principal-reduction@Office/*'] == D('27232.48')
    assert values['reported-liability-closing@Office/*'] == 0
    assert values['period-liability-opening@*'] == D('45232.48')
    assert values['period-liability-closing@*'] == 0
    assert values['period-principal-reduction@*'] == D('45232.48')
    single, _ = expected('single-period')
    assert single['initial-liability@Dock'] == D(8000)
    assert single['interest@Dock/P1'] == D(400)
    advance, _ = expected('commencement-payment')
    assert advance['initial-liability@Office'] == D('18594.10')
    assert advance['initial-rou-cost@Office'] == D('28594.10')
    assert advance['rou-depreciation@Office/P3'] == D('9531.36')
    failed, failure = expected('unreconciled')
    assert failed['liability-reconciliation@Office/P2'] == D('-.02')
    assert len(failure['expected_business']) == 1 and not failure['validation_passed']
    negative, negative_summary = expected('negative-payment')
    assert negative['payment@Office/P2'] == D(-1000)
    assert negative['liability-reconciliation@Office/P2'] == D('.01')
    assert negative['liability-closing@Office/*'] == D('.01')
    precise_negative, _ = expected('negative-payment', 4)
    assert precise_negative['liability-closing@Office/*'] == 0
    assert precise_negative['liability-reconciliation@Office/P2'] == D('-.0005')
    assert any(d['node'] == 'payment-nonnegative' for d in negative_summary['expected_business'])
    rate, rate_summary = expected('negative-rate-outside-demo')
    assert rate['interest@Office/P1'] < 0
    assert any(d['node'] == 'lease-rate-supported' for d in rate_summary['expected_business'])
    print('Lease preparation self-test passed: PV, zero rate, recurrence, terminal residue, advance payment and findings')


def write_references():
    reference_dir = HERE / 'references'
    reference_dir.mkdir(exist_ok=True)
    for fixture in sorted((HERE / 'fixtures').glob('*.json')):
        for scale in [2, 4]:
            values, summary = expected(fixture.stem, scale)
            for kind, data in [('values', {key: str(value) for key, value in values.items()}), ('summary', summary)]:
                (reference_dir / f'{fixture.stem}-scale{scale}-{kind}.json').write_text(json.dumps(data, indent=2) + '\n')
    paths = [path for path in HERE.rglob('*') if path.is_file() and
             ('build' not in path.relative_to(HERE).parts) and ('src' not in path.relative_to(HERE).parts) and
             path.name != 'source-manifest.json' and path.suffix in {'.json', '.py', '.md', '.csv', '.mantra'}]
    manifest = {'notice': 'Project-authored fictional facts; independent Decimal sources, never generated from engine values.',
                'original_preparation': '../../docs/milestones/m2-source-preparation/reference-manifest.json',
                'files_sha256': {str(path.relative_to(HERE)): hashlib.sha256(path.read_bytes()).hexdigest()
                                 for path in sorted(paths)}}
    (HERE / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', choices=[p.stem for p in sorted((HERE / 'fixtures').glob('*.json'))], default='demo')
    parser.add_argument('--scale', type=int, choices=[2,4], default=2)
    parser.add_argument('--verify', type=Path, help='Compare a flat numeric JSON document; keys are public node coordinates and reduction mappings')
    parser.add_argument('--summary', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--write-references', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.write_references:
        write_references()
        return
    values, summary = expected(args.case, args.scale)
    if args.verify:
        actual = json.loads(args.verify.read_text())
        assert not {key for key in actual if '*' in key} - values.keys(), 'Uncovered numeric reduction source'
        for key, value in values.items():
            assert decimal(actual[key]) == value, f'{key}: {actual.get(key)} != {value}'
        print(f'Verified {len(values)} independent lease amounts ({args.case}, scale {args.scale})')
    else:
        print(json.dumps(summary if args.summary else {key: str(value) for key,value in values.items()}, indent=2))


if __name__ == '__main__':
    main()
