#!/usr/bin/env python3
"""Independent monthly net-energy model: Decimal stocks/flows and Fraction coverage ratios."""
import argparse
from copy import deepcopy
from decimal import Decimal as D, localcontext, ROUND_HALF_UP
from fractions import Fraction
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
PERIODS = [{'id': 'P1', 'start': '2026-01-01', 'end': '2026-02-01'},
           {'id': 'P2', 'start': '2026-02-01', 'end': '2026-03-01'},
           {'id': 'P3', 'start': '2026-03-01', 'end': '2026-04-01'}]
BASE = {'id': 'demo', 'periods': PERIODS, 'parameters': {'purchase_rate': '.28', 'export_rate': '.10', 'currency_scale': 2, 'ratio_scale': 6},
        'sites': [{'id': 'A', 'title': 'Fictional workshop', 'capacity': '20', 'opening': '12'},
                  {'id': 'B', 'title': 'Fictional studio without storage', 'capacity': '0', 'opening': '0'}],
        'readings': [{'site': site, 'period': period['id'], 'generation': str(generation), 'demand': str(demand)}
                     for site, generation_values, demand_values in [('A', [30, 100, 20], [90, 70, 100]), ('B', [80, 40, 10], [60, 60, 0])]
                     for period, generation, demand in zip(PERIODS, generation_values, demand_values)]}
STOCK_FIRST = {'opening-storage'}
STOCK_LAST = {'closing-storage', 'reported-closing'}


def rounded(value, scale):
    return value.quantize(D(1).scaleb(-scale), ROUND_HALF_UP)


def ratio(numerator, denominator, scale):
    if denominator == 0:
        return None
    exact = Fraction(numerator) / Fraction(denominator)
    return rounded(D(exact.numerator) / D(exact.denominator), scale)


def compute(fact):
    with localcontext() as context:
        context.prec = 80
        rows, values, undefined = {}, {}, []
        params = fact['parameters']
        values.update({'purchase-rate':D(params['purchase_rate']),'export-rate':D(params['export_rate']),'currency-scale':D(params['currency_scale'])})
        for site in fact['sites']:
            opening = D(site['opening'])
            capacity = D(site['capacity'])
            assert 0 <= opening <= capacity
            for period in fact['periods']:
                source = next(row for row in fact['readings'] if row['site'] == site['id'] and row['period'] == period['id'])
                generation, demand = D(source['generation']), D(source['demand'])
                assert generation >= 0 and demand >= 0
                available = opening + generation
                imported = max(demand - available, D(0))
                locally_served = min(demand, available)
                exported = max(available - demand - capacity, D(0))
                closing = opening + generation + imported - demand - exported
                assert 0 <= closing <= capacity
                purchase = rounded(imported * D(params['purchase_rate']), params['currency_scale'])
                credit = rounded(exported * D(params['export_rate']), params['currency_scale'])
                reported = D(source.get('reported_closing', str(closing)))
                rows[site['id'], period['id']] = {'opening-storage': opening, 'energy-generation': generation, 'grid-import': imported,
                    'demand': demand, 'served-locally': locally_served, 'energy-export': exported, 'closing-storage': closing,
                    'import-cost': purchase, 'export-credit': credit, 'net-cost': purchase - credit,
                    'reported-closing': reported, 'energy-difference': closing - reported}
                opening = closing
            for name, field in [('storage-capacity', 'capacity'), ('initial-storage', 'opening')]:
                values[f'{name}@{site["id"]}'] = D(site[field])
        for name, field in [('storage-capacity', 'capacity'), ('initial-storage', 'opening')]:
            values[f'{name}@*'] = sum((D(site[field]) for site in fact['sites']), D(0))
        for coord, row in rows.items():
            for name, value in row.items():
                values[f'{name}@{"/".join(coord)}'] = value
            # The diagnostic control exposes its own per-reading difference, but it
            # deliberately has no numeric reducer. The ordinary difference above does.
            values[f'energy-reconciliation@{"/".join(coord)}'] = row['energy-difference']
            share = ratio(row['served-locally'], row['demand'], params['ratio_scale'])
            if share is None:
                undefined.append(f'local-coverage@{"/".join(coord)}')
            else:
                values[f'local-coverage@{"/".join(coord)}'] = share
        def reduce(name, site=None, period=None):
            matching = [(coord, row) for coord, row in rows.items() if (site is None or coord[0] == site) and (period is None or coord[1] == period)]
            if period is None and name in STOCK_FIRST | STOCK_LAST:
                chosen = fact['periods'][0 if name in STOCK_FIRST else -1]['id']
                matching = [(coord, row) for coord, row in matching if coord[1] == chosen]
            return sum((row[name] for _, row in matching), D(0))
        names = next(iter(rows.values())).keys()
        scopes = [('*', None, None)] + [(f'{site["id"]}/*', site['id'], None) for site in fact['sites']] + [(f'*/{period["id"]}', None, period['id']) for period in fact['periods']]
        for suffix, site, period in scopes:
            for name in names:
                values[f'{name}@{suffix}'] = reduce(name, site, period)
            share = ratio(reduce('served-locally', site, period), reduce('demand', site, period), params['ratio_scale'])
            if share is None:
                undefined.append(f'local-coverage@{suffix}')
            else:
                values[f'local-coverage@{suffix}'] = share
        diagnostics = [{'code': 'MANTRA-RECONCILE-FAILED', 'node': 'energy-reconciliation', 'coord': list(coord), 'severity': 'error'}
                       for coord, row in rows.items() if row['energy-difference'] != 0]
        if values['demand@*'] == 0:
            diagnostics.append({'code': 'MANTRA-AGGREGATE-ZERO-DENOMINATOR', 'node': 'local-coverage', 'severity': 'warning'})
        return values, {'id': fact['id'], 'succeeded': True, 'validationPassed': not any(row['severity'] == 'error' for row in diagnostics),
                        'expectedBusiness': diagnostics, 'undefined': sorted(undefined), 'closing': str(values['closing-storage@*']),
                        'netCost': str(values['net-cost@*'])}


def fixtures():
    variants = {}
    for name in ['demo', 'zero-capacity', 'single-site', 'all-zero-demand', 'fractional-measurements', 'unreconciled']:
        fact = deepcopy(BASE)
        fact['id'] = name
        if name == 'zero-capacity':
            fact['sites'][0].update(capacity='0', opening='0')
        elif name == 'single-site':
            fact['sites'] = fact['sites'][:1]
            fact['readings'] = [row for row in fact['readings'] if row['site'] == 'A']
        elif name == 'all-zero-demand':
            for row in fact['readings']:
                row['demand'] = '0'
        elif name == 'fractional-measurements':
            fact['sites'] = [{'id': 'A', 'title': 'Fictional fractional-energy meter', 'capacity': '.300', 'opening': '.100'}]
            fact['readings'] = [{'site': 'A', 'period': period['id'], 'generation': generation, 'demand': demand}
                                for period, generation, demand in zip(PERIODS, ['.200', '.010', '.330'], ['.130', '.190', '.210'])]
        values, _ = compute(fact)
        # These reported amounts are fictional source facts independently computed here, not engine observations.
        for row in fact['readings']:
            row['reported_closing'] = str(values[f'closing-storage@{row["site"]}/{row["period"]}'])
        if name == 'unreconciled':
            next(row for row in fact['readings'] if row['site'] == 'A' and row['period'] == 'P2')['reported_closing'] = '21'
        variants[name] = fact
    return variants


def self_test():
    facts = fixtures()
    values, summary = compute(facts['demo'])
    assert values['net-cost@*'] == D('31.84')
    assert values['local-coverage@*'] == D('.663158')
    assert values['opening-storage@*'] == 12 and values['closing-storage@*'] == 0
    assert values['grid-import@*'] == 128 and values['energy-export@*'] == 40
    for fact in facts.values():
        values, _ = compute(fact)
        for site in fact['sites']:
            scope = site['id'] + '/*'
            assert values[f'opening-storage@{scope}'] + values[f'energy-generation@{scope}'] + values[f'grid-import@{scope}'] - values[f'demand@{scope}'] - values[f'energy-export@{scope}'] == values[f'closing-storage@{scope}']
            assert values[f'closing-storage@{scope}'] == values[f'closing-storage@{site["id"]}/P3']
        assert values['opening-storage@*'] + values['energy-generation@*'] + values['grid-import@*'] - values['demand@*'] - values['energy-export@*'] == values['closing-storage@*']
        assert values['energy-difference@*'] == sum((values[f'energy-difference@{site["id"]}/{period["id"]}'] for site in fact['sites'] for period in fact['periods']), D(0))
        assert not any(key.startswith('energy-reconciliation@') and '*' in key for key in values)
    _, zero = compute(facts['all-zero-demand'])
    assert 'local-coverage@*' in zero['undefined'] and zero['validationPassed']
    assert len(zero['expectedBusiness']) == 1
    assert not compute(facts['unreconciled'])[1]['validationPassed']
    assert compute(facts['unreconciled'])[0]['energy-difference@*'] == D('-1')
    assert compute(facts['unreconciled'])[0]['energy-difference@*/P2'] == D('-1')


def write():
    entries = []
    for name, fact in fixtures().items():
        values, summary = compute(fact)
        for path, data in [(HERE/'fixtures'/f'{name}.json', fact), (HERE/'references'/f'{name}-values.json', {key:str(value) for key,value in sorted(values.items())}),
                           (HERE/'references'/f'{name}-summary.json', summary)]:
            path.write_text(json.dumps(data, indent=2)+'\n')
            entries.append({'path':path.relative_to(HERE).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    entries.append({'path':'reference.py','sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()})
    (HERE/'manifest.json').write_text(json.dumps({'status':'INDEPENDENT_REFERENCE_ENGINE_UNEXECUTED','fixtureCount':6,'files':entries},indent=2)+'\n')
    print(json.dumps([compute(fact)[1] for fact in fixtures().values()],indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--write-references', action='store_true')
    arguments = parser.parse_args()
    self_test()
    if arguments.write_references:
        write()
