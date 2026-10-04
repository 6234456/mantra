#!/usr/bin/env python3
"""Independent fictional asset roll-forward; standard-library Decimal, no Mantra imports."""
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


def month_index(value):
    parsed = date.fromisoformat(value)
    if parsed.day != 1:
        raise ValueError('Preparation fixtures use month-boundary service dates')
    return parsed.year * 12 + parsed.month - 1


def compute(fixture, scale=2):
    """Track cost and accumulated depreciation separately; dispose after period depreciation."""
    quantum = D(1).scaleb(-scale)
    rounded = lambda value: value.quantize(quantum, ROUND_HALF_UP)
    periods = fixture['periods']
    for previous, current in zip(periods, periods[1:]):
        if previous['end'] != current['start']:
            raise ValueError('Periods must be contiguous')
    if not periods or any(month_index(p['end']) <= month_index(p['start']) for p in periods):
        raise ValueError('Periods must have a positive duration')
    state = {a['id']: [decimal(a['opening_cost']), decimal(a['opening_accumulated_depreciation'])]
             for a in fixture['assets']}
    amounts = {}
    summaries = []
    findings = []
    disposed_assets = set()
    total = {key: D(0) for key in ['additions', 'depreciation', 'cost_disposals',
                                  'accumulated_disposals', 'carrying_disposals']}
    for period in periods:
        period_id = period['id']
        rollup = {key: D(0) for key in ['gross_opening', 'gross_additions', 'gross_disposals',
                                      'gross_closing', 'accumulated_opening', 'depreciation',
                                      'accumulated_disposals', 'accumulated_closing',
                                      'carrying_opening', 'carrying_disposals', 'carrying_closing']}
        for asset in fixture['assets']:
            asset_id = asset['id']
            opening_cost, opening_accumulated = state[asset_id]
            additions = decimal(asset['acquisition_cost']) if asset.get('acquisition_period') == period_id else D(0)
            cost_before_disposal = opening_cost + additions
            residual = decimal(asset['residual_value'])
            depreciation = D(0)
            months = 0
            if asset['depreciable'] and asset_id not in disposed_assets:
                lifetime = asset['remaining_months']
                if lifetime <= 0:
                    raise ValueError('Depreciable assets require a positive remaining life')
                service_start = month_index(asset['available_from'])
                service_end = service_start + lifetime
                months = max(0, min(month_index(period['end']), service_end)
                             - max(month_index(period['start']), service_start))
                original_basis = (decimal(asset['opening_cost']) - decimal(asset['opening_accumulated_depreciation'])
                                  + decimal(asset['acquisition_cost']) - residual)
                remaining = max(D(0), cost_before_disposal - opening_accumulated - residual)
                regular = rounded(max(D(0), original_basis) * D(months) / D(lifetime))
                terminal = months > 0 and month_index(period['end']) >= service_end
                # Explicit presentation-currency policy: final service period absorbs rounding residue.
                depreciation = remaining if terminal else min(remaining, regular)
            accumulated_before_disposal = opening_accumulated + depreciation
            disposed = asset.get('disposal_at_period_end') == period_id
            if disposed:
                disposed_assets.add(asset_id)
            cost_disposal = cost_before_disposal if disposed else D(0)
            accumulated_disposal = accumulated_before_disposal if disposed else D(0)
            carrying_disposal = cost_disposal - accumulated_disposal
            closing_cost = cost_before_disposal - cost_disposal
            closing_accumulated = accumulated_before_disposal - accumulated_disposal
            closing_carrying = closing_cost - closing_accumulated
            values = {'gross_opening': opening_cost, 'gross_additions': additions,
                      'gross_disposals': cost_disposal, 'gross_closing': closing_cost,
                      'accumulated_opening': opening_accumulated, 'depreciation': depreciation,
                      'accumulated_disposals': accumulated_disposal, 'accumulated_closing': closing_accumulated,
                      'carrying_opening': opening_cost - opening_accumulated,
                      'carrying_disposals': carrying_disposal, 'carrying_closing': closing_carrying}
            for key, value in values.items():
                amounts[f'{key.replace("_", "-")}@{asset_id}/{period_id}'] = value
                rollup[key] += value
            amounts[f'carrying-additions@{asset_id}/{period_id}'] = additions
            amounts[f'carrying-depreciation@{asset_id}/{period_id}'] = depreciation
            amounts[f'asset-cost-crossfoot@{asset_id}/{period_id}'] = (
                closing_carrying - (closing_cost - closing_accumulated))
            amounts[f'service-months@{asset_id}/{period_id}'] = D(months)
            state[asset_id] = [closing_cost, closing_accumulated]
            assert values['carrying_opening'] + additions - depreciation - carrying_disposal == closing_carrying
        reported = decimal(fixture['reported_closing'][period_id])
        difference = rollup['carrying_closing'] - reported
        amounts[f'period-carrying-reconciliation@{period_id}'] = difference
        amounts[f'reported-closing@{period_id}'] = reported
        for key, value in rollup.items():
            amounts[f'period-{key.replace("_", "-")}@{period_id}'] = value
        amounts[f'period-additions@{period_id}'] = rollup['gross_additions']
        if abs(difference) > decimal(fixture['reconciliation_tolerance']):
            findings.append({'code': 'MANTRA-RECONCILE-FAILED', 'node': 'period-carrying-reconciliation',
                             'coord': [period_id], 'severity': 'error'})
        total['additions'] += rollup['gross_additions']
        total['depreciation'] += rollup['depreciation']
        total['cost_disposals'] += rollup['gross_disposals']
        total['accumulated_disposals'] += rollup['accumulated_disposals']
        total['carrying_disposals'] += rollup['carrying_disposals']
        summaries.append({'period': period_id, **{key: str(value) for key, value in rollup.items()},
                          'reported_closing': str(reported), 'difference': str(difference)})
    first = summaries[0]
    last = summaries[-1]
    amounts.update({'gross-opening@*': decimal(first['gross_opening']),
                    'accumulated-opening@*': decimal(first['accumulated_opening']),
                    'carrying-opening@*': decimal(first['carrying_opening']),
                    'gross-closing@*': decimal(last['gross_closing']),
                    'accumulated-closing@*': decimal(last['accumulated_closing']),
                    'carrying-closing@*': decimal(last['carrying_closing']),
                    'gross-additions@*': total['additions'], 'depreciation@*': total['depreciation'],
                    'gross-disposals@*': total['cost_disposals'],
                    'accumulated-disposals@*': total['accumulated_disposals'],
                    'carrying-disposals@*': total['carrying_disposals']})
    assert amounts['gross-opening@*'] + total['additions'] - total['cost_disposals'] == amounts['gross-closing@*']
    assert amounts['accumulated-opening@*'] + total['depreciation'] - total['accumulated_disposals'] == amounts['accumulated-closing@*']
    assert amounts['carrying-opening@*'] + total['additions'] - total['depreciation'] - total['carrying_disposals'] == amounts['carrying-closing@*']
    amounts['currency-scale'] = D(scale)
    stocks = {f'{kind}-{edge}': edge for kind in ['gross', 'accumulated', 'carrying']
              for edge in ['opening', 'closing']}
    flows = ['gross-additions', 'gross-disposals', 'depreciation', 'accumulated-disposals',
             'carrying-additions', 'carrying-depreciation', 'carrying-disposals']
    for node in list(stocks) + flows:
        for asset in fixture['assets']:
            series = [amounts[f'{node}@{asset["id"]}/{period["id"]}'] for period in periods]
            reduced = (series[0] if stocks[node] == 'opening' else series[-1]) if node in stocks else sum(series, D(0))
            amounts[f'{node}@{asset["id"]}/*'] = reduced
        amounts[f'{node}@*'] = sum((amounts[f'{node}@{asset["id"]}/*'] for asset in fixture['assets']), D(0))
        for period in periods:
            amounts[f'{node}@*/{period["id"]}'] = sum(
                (amounts[f'{node}@{asset["id"]}/{period["id"]}'] for asset in fixture['assets']), D(0))
    for node in ['period-gross-opening', 'period-gross-closing', 'period-accumulated-opening',
                 'period-accumulated-closing', 'period-carrying-opening', 'period-carrying-closing',
                 'reported-closing', 'period-gross-additions', 'period-gross-disposals',
                 'period-accumulated-disposals', 'period-additions', 'period-depreciation',
                 'period-carrying-disposals']:
        series = [amounts[f'{node}@{period["id"]}'] for period in periods]
        amounts[f'{node}@*'] = series[0] if node.endswith('-opening') else (
            series[-1] if node.endswith('-closing') else sum(series, D(0)))
    return amounts, {'case': fixture['id'], 'scale': scale, 'periods': summaries,
                     'first_opening': str(amounts['carrying-opening@*']),
                     'final_gross': str(amounts['gross-closing@*']),
                     'final_accumulated': str(amounts['accumulated-closing@*']),
                     'final_carrying': str(amounts['carrying-closing@*']),
                     'flows': {key: str(value) for key, value in total.items()},
                     'validation_passed': not findings, 'expected_business': findings}


def expected(name='demo', scale=2):
    with localcontext() as context:
        context.prec = 80
        return compute(json.loads((HERE / 'fixtures' / f'{name}.json').read_text()), scale)


def self_test():
    values, summary = expected()
    assert [D(p['carrying_closing']) for p in summary['periods']] == list(map(D, [126000,144000,82000,60000,60000]))
    assert values['gross-closing@*'] == D(170000)
    assert values['accumulated-closing@*'] == D(110000)
    assert values['depreciation@*'] == D(128000)
    assert values['gross-opening@Equipment/*'] == 0
    assert values['gross-closing@Equipment/*'] == 0
    assert values['carrying-opening@Machine/*'] == D(98000)
    assert values['carrying-closing@Machine/*'] == D(10000)
    assert values['carrying-closing@Land/*'] == D(50000)
    assert values['carrying-closing@*/P3'] == D(82000)
    assert values['depreciation@Machine/*'] == D(88000)
    assert values['carrying-depreciation@*'] == values['period-depreciation@*'] == D(128000)
    assert values['period-carrying-closing@*'] == D(60000)
    assert values['period-gross-additions@*'] == values['period-additions@*'] == D(60000)
    assert values['carrying-disposals@Equipment/P3'] == D(20000)
    assert values['carrying-closing@Equipment/P4'] == D(0)
    assert values['service-months@Equipment/P4'] == D(0)
    assert values['depreciation@Machine/P5'] == D(0)
    assert sum(D(p['carrying_closing']) for p in summary['periods']) != values['carrying-closing@*']
    partial, _ = expected('partial-year')
    assert [partial[f'depreciation@Equipment/P{year-2025}'] for year in range(2026,2031)] == list(map(D, [10000,20000,20000,10000,0]))
    rounding, _ = expected('rounding-residue')
    assert [rounding[f'depreciation@Tool/P{year-2025}'] for year in range(2026,2029)] == list(map(D, ['33.33','33.33','33.34']))
    precise, _ = expected('rounding-residue', 4)
    assert [precise[f'depreciation@Tool/P{year-2025}'] for year in range(2026,2029)] == list(map(D, ['33.3333','33.3333','33.3334']))
    full, _ = expected('fully-depreciated')
    assert full['depreciation@*'] == 0 and full['carrying-closing@*'] == 0
    failed, failure = expected('unreconciled')
    assert failed['period-carrying-reconciliation@P2'] == D('-.02')
    assert len(failure['expected_business']) == 1 and not failure['validation_passed']
    print('Asset preparation self-test passed: continuity, disposal, residual floor, partial year, rounding and reconciliation')


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
        print(f'Verified {len(values)} independent asset amounts ({args.case}, scale {args.scale})')
    else:
        print(json.dumps(summary if args.summary else {key: str(value) for key,value in values.items()}, indent=2))


if __name__ == '__main__':
    main()
