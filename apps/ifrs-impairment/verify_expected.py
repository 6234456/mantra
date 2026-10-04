#!/usr/bin/env python3
"""Independent arithmetic for the demonstration (Fraction/Decimal; no Mantra dependencies).
Inputs are independently authored fictional facts, documented in README.md.
"""
import argparse
import hashlib
from decimal import Decimal, ROUND_HALF_UP, localcontext
from fractions import Fraction
import json
from itertools import product
from pathlib import Path

HERE = Path(__file__).resolve().parent


def allocate(total, weights, scale=0):
    factor = 10 ** scale
    target = Fraction(total) * factor
    assert target.denominator == 1
    target = int(target)
    weights = [Fraction(weight) for weight in weights]
    exact = [target * weight / sum(weights) for weight in weights]
    base = [value.numerator // value.denominator for value in exact]
    # Equal remainders use input member order, matching the documented deterministic rule.
    priority = sorted(range(len(weights)), key=lambda i: (-(exact[i] - base[i]), i))
    for index in priority[:target - sum(base)]:
        base[index] += 1
    assert sum(base) == target
    return [Fraction(value, factor) for value in base]


def capped_expected(case, scale=0):
    # Rational water filling, independently expressed from the fictional facts.
    facts = json.loads((HERE / 'fixtures' / f'{case}.json').read_text())
    ids = [asset['id'] for asset in facts['assets']]
    weights = [Fraction(asset['carrying']) for asset in facts['assets']]
    floors = [max(Fraction(0), Fraction(asset['fvlcd']), Fraction(asset['viu'])) for asset in facts['assets']]
    caps = [w - floor for w, floor in zip(weights, floors)]
    impairment = max(Fraction(0), Fraction(facts['cgu_carrying']) - Fraction(facts['recoverable']))
    goodwill_loss = min(impairment, Fraction(facts['goodwill']))
    own_loss = impairment - goodwill_loss
    assert own_loss == Fraction(facts['own_loss'])
    open_keys = list(range(len(ids)))
    assigned = [Fraction(0)] * len(ids)
    remaining = own_loss
    while open_keys:
        basis = sum(weights[i] for i in open_keys)
        proposals = {i: remaining * weights[i] / basis for i in open_keys}
        capped = [i for i in open_keys if proposals[i] > caps[i]]
        if not capped:
            for i, share in zip(open_keys, allocate(remaining, [weights[i] for i in open_keys], scale)):
                assigned[i] = share
            break
        for i in capped:
            assigned[i] = caps[i]
            remaining -= caps[i]
            open_keys.remove(i)
    values = {'corporate-share@Workshop': 0, 'carrying-amount-allocated@Workshop': Fraction(facts['cgu_carrying']),
              'recoverable-amount@Workshop': Fraction(facts['recoverable']), 'impairment-loss@Workshop': impairment,
              'loss-to-goodwill@Workshop': goodwill_loss, 'loss-to-corporate@Workshop': 0,
              'loss-to-own-assets@Workshop': own_loss, 'group-before': Fraction(facts['cgu_carrying']),
              'group-carrying-amount': Fraction(facts['cgu_carrying']) - impairment,
              'group-loss': 0, 'total-impairment': impairment,
              'own-loss-to-allocate@Workshop': own_loss, 'own-capped-capacity@Workshop': sum(caps),
              'own-capped-loss@Workshop': sum(assigned),
              'own-unallocated-loss@Workshop': own_loss - sum(assigned),
              'own-capped-closing@Workshop': sum(weights) - sum(assigned),
              'capped-loss-crossfoot@Workshop': own_loss - sum(assigned)}
    for name, weight, floor, cap, loss in zip(ids, weights, floors, caps, assigned):
        coord = f'@Workshop/{name}'
        values.update({f'individual-carrying{coord}': weight, f'individual-floor{coord}': floor,
                       f'asset-capacity{coord}': cap, f'asset-base{coord}': weight,
                       f'asset-allocated-loss{coord}': loss, f'individual-closing{coord}': weight - loss})
    assert all(weight-loss >= floor for weight, loss, floor in zip(weights, assigned, floors))
    if case == 'capped':
        assert sum(assigned) == 60
        assert assigned == list(map(Fraction, [10, 38, 12] if scale == 0 else [10, '37.5', '12.5']))
    else:
        assert sum(assigned) == 15 and values['own-unallocated-loss@Workshop'] == 45
    return extend_new_case_sources(values, case, scale)


def extend_new_case_sources(values, case, scale):
    """Independent facts, identities and sums for every numeric domain reduction.

    IAS 36's axes contain flows and allocation components, so both axes sum. Inactive forecast
    and cap branches contribute zero to a numeric reduction; their exact nil coordinates remain
    absent from the numeric source. No engine or rendered amounts are read here.
    """
    forecast = case == 'discounted-viu'
    facts = json.loads((HERE / 'fixtures' / f'{case}.json').read_text())
    if forecast:
        members = [cgu['id'] for cgu in facts['cgus']]
        carrying = [Fraction(cgu['carrying']) for cgu in facts['cgus']]
        lives = [int(cgu['remaining_life']) for cgu in facts['cgus']]
        weights = [own * Fraction(life, min(lives)) for own, life in zip(carrying, lives)]
        goodwill = [0, 0, 0]
        asset_ids = []
    else:
        members, carrying, weights, lives = ['Workshop'], [Fraction(facts['cgu_carrying'])], [Fraction(facts['cgu_carrying'])], [int(facts['remaining_life'])]
        goodwill = [Fraction(facts['goodwill'])]
        asset_ids = [asset['id'] for asset in facts['assets']]
    with localcontext() as context:
        context.prec = 80
        for member, own, weighted, life, original_goodwill in zip(members, carrying, weights, lives, goodwill):
            coord = f'@{member}'
            values[f'carrying-amount{coord}'] = own
            values[f'remaining-life{coord}'] = life
            values[f'weighting{coord}'] = Decimal(life) / Decimal(min(lives))
            values[f'weighted-amount{coord}'] = weighted
            key = Fraction(weighted) / sum(map(Fraction, weights))
            values[f'allocation-key{coord}'] = (Decimal(key.numerator) / Decimal(key.denominator)).quantize(
                Decimal('0.000001'), rounding=ROUND_HALF_UP)
            values[f'tested-amount{coord}'] = values[f'carrying-amount-allocated{coord}']
            if not forecast:
                values[f'measured-value-in-use{coord}'] = Fraction(facts['recoverable'])
            values[f'loss-to-goodwill{coord}'] = min(values[f'impairment-loss{coord}'], original_goodwill)
            values[f'loss-allocated{coord}'] = (values[f'loss-to-goodwill{coord}'] +
                values[f'loss-to-corporate{coord}'] + values[f'loss-to-own-assets{coord}'])
            values[f'cgu-loss-crossfoot{coord}'] = values[f'impairment-loss{coord}'] - values[f'loss-allocated{coord}']
    cgu_nodes = ['carrying-amount', 'weighted-amount', 'allocation-key', 'corporate-share',
                 'carrying-amount-allocated', 'tested-amount', 'measured-value-in-use', 'recoverable-amount',
                 'impairment-loss', 'loss-to-goodwill', 'loss-to-corporate', 'loss-to-own-assets', 'loss-allocated']
    control_nodes = ['own-loss-to-allocate', 'own-capped-capacity', 'own-capped-loss',
                     'own-unallocated-loss', 'own-capped-closing']
    asset_nodes = ['asset-base', 'asset-capacity', 'individual-carrying', 'individual-floor',
                   'asset-allocated-loss', 'individual-closing']
    specs = [(node, [members]) for node in cgu_nodes + control_nodes]
    specs += [(node, [members, ['P1', 'P2', 'P3']]) for node in ['forecast-cash-flow', 'present-value']]
    specs += [(node, [members, asset_ids]) for node in asset_nodes]
    for node, domains in specs:
        coordinates = list(product(*domains))
        values[f'{node}@*'] = sum((values.get(f'{node}@' + '/'.join(coord), 0) for coord in coordinates), 0)
        if len(domains) > 1 and coordinates:
            for axis, domain in enumerate(domains):
                for key in domain:
                    partial = ['*'] * len(domains)
                    partial[axis] = key
                    values[f'{node}@' + '/'.join(partial)] = sum(
                        (values.get(f'{node}@' + '/'.join(coord), 0) for coord in coordinates if coord[axis] == key), 0)
    values.update({'rounding': scale, 'discount-factor-scale': 18,
                   'group-cgu-assets': sum(carrying), 'group-building': Fraction(facts['allocable_corporate']) if forecast else 0,
                   'group-research': Fraction(facts['unallocable_corporate']) if forecast else 0,
                   'group-step1-losses': values['loss-allocated@*'],
                   'group-ra': Fraction(facts['group_recoverable']) if forecast else Fraction(facts['recoverable']),
                   'summary-goodwill': values['loss-to-goodwill@*'],
                   'summary-own': values['loss-to-own-assets@*'],
                   'summary-corporate': values['loss-to-corporate@*'], 'summary-group': values['group-loss']})
    return values


def expected(case, scale=0):
    if case in ('capped', 'insufficient-capacity'):
        return capped_expected(case, scale)
    if case == 'discounted-viu':
        facts = json.loads((HERE / 'fixtures' / f'{case}.json').read_text())
        carrying = [Fraction(cgu['carrying']) for cgu in facts['cgus']]
        lives = [int(cgu['remaining_life']) for cgu in facts['cgus']]
        weights = [own * Fraction(life, min(lives)) for own, life in zip(carrying, lives)]
        rate = Fraction(facts['annual_pre_tax_rate'])
        recoverable = [sum(Fraction(flow) / (1 + rate) ** (index + 1)
                          for index, flow in enumerate(facts['cashflows'][cgu['id']])) for cgu in facts['cgus']]
        corporate = Fraction(facts['allocable_corporate'])
        before = sum(carrying) + corporate + Fraction(facts['unallocable_corporate'])
    else:
        carrying = [120, 180, 260]
        weights = [4320, 25920, 84240] if case == 'custom-weight' else [120, 360, 780]
        recoverable = [1000, 1000, 1000] if case == 'no-impairment' else [180, 210, 300]
        corporate, before = 211, 844
    shares = allocate(corporate, weights, scale)
    losses = [max(0, own + share - ra) for own, share, ra in zip(carrying, shares, recoverable)]
    values = {}
    for member, own, share, ra, loss in zip('ABC', carrying, shares, recoverable, losses):
        for name, value in [('corporate-share', share), ('carrying-amount-allocated', own + share),
                            ('recoverable-amount', ra), ('impairment-loss', loss)]:
            values[f'{name}@{member}'] = value
        split = allocate(loss, [share, own], scale)
        values[f'loss-to-corporate@{member}'], values[f'loss-to-own-assets@{member}'] = split
    values['group-before'] = before
    values['group-carrying-amount'] = before - sum(losses)
    values['group-loss'] = max(0, values['group-carrying-amount'] - Fraction(facts['group_recoverable'])) if case == 'discounted-viu' else 0
    values['total-impairment'] = sum(losses) + values['group-loss']
    if case == 'discounted-viu':
        facts = json.loads((HERE / 'fixtures' / f'{case}.json').read_text())
        forecasts = facts['cashflows']
        rate = Fraction(facts['annual_pre_tax_rate'])
        with localcontext() as context:
            context.prec = 80
            for member, flows in forecasts.items():
                pv = Fraction(0)
                for index, flow in enumerate(flows):
                    amount = Fraction(flow)
                    term = amount / (1 + rate) ** (index + 1)
                    pv += term
                    coord = f'@{member}/P{index+1}'
                    values[f'forecast-cash-flow{coord}'] = amount
                    values[f'present-value{coord}'] = term
                    factor = Decimal(1) + Decimal(rate.numerator) / Decimal(rate.denominator)
                    values[f'discount-factor{coord}'] = (Decimal(1) / factor ** (index+1)).quantize(
                        Decimal('0.000000000000000001'), rounding=ROUND_HALF_UP)
                assert pv == {'A': 180, 'B': 210, 'C': 300}[member]
                values[f'measured-value-in-use@{member}'] = pv
    return extend_new_case_sources(values, case, scale) if case == 'discounted-viu' else values


def self_test():
    for scale in [0, 2]:
        forecast = expected('discounted-viu', scale)
        assert forecast['present-value@A/*'] == 180
        assert forecast['present-value@B/*'] == 210
        assert forecast['present-value@C/*'] == 300
        assert forecast['present-value@*/P1'] == forecast['present-value@*/P3'] == 230
        assert forecast['present-value@*'] == forecast['measured-value-in-use@*'] == 690
        assert forecast['forecast-cash-flow@*'] == Fraction('837.43')
        assert forecast['individual-closing@*'] == 0
        capped = expected('capped', scale)
        assert capped['asset-allocated-loss@Workshop/*'] == capped['asset-allocated-loss@*'] == 60
        assert capped['asset-allocated-loss@*/Machine'] == 10
        assert capped['individual-closing@*/Machine'] == 110
        assert capped['individual-closing@*'] == 300
        assert capped['forecast-cash-flow@Workshop/*'] == capped['present-value@*/P2'] == 0
        limited = expected('insufficient-capacity', scale)
        assert limited['asset-allocated-loss@*'] == limited['asset-allocated-loss@Workshop/*'] == 15
        assert limited['own-unallocated-loss@*'] == 45
        assert limited['individual-closing@*'] == 345
        assert limited['capped-loss-crossfoot@Workshop'] == 45
    print('IAS36 independent self-test passed: forecast axes, capped partial sums, capacity and conservation')


def decimal_text(value):
    with localcontext() as context:
        context.prec = 80
        return str(Decimal(value.numerator) / Decimal(value.denominator)) if isinstance(value, Fraction) else str(value)


def write_references():
    reference_dir = HERE / 'references'
    reference_dir.mkdir(exist_ok=True)
    for case in ['discounted-viu', 'capped', 'insufficient-capacity']:
        for scale in [0, 2]:
            values = expected(case, scale)
            (reference_dir / f'{case}-scale{scale}-values.json').write_text(
                json.dumps({key: decimal_text(value) for key, value in values.items()}, indent=2) + '\n')
    paths = [path for path in HERE.rglob('*') if path.is_file() and
             ('build' not in path.relative_to(HERE).parts) and ('src' not in path.relative_to(HERE).parts) and
             path.name != 'source-manifest.json' and path.suffix in {'.json', '.py', '.md', '.csv', '.mantra'}]
    manifest = {'notice': 'Project-authored fictional facts; independent Fraction/Decimal sources, never generated from engine values.',
                'files_sha256': {str(path.relative_to(HERE)): hashlib.sha256(path.read_bytes()).hexdigest()
                                 for path in sorted(paths)}}
    (HERE / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', choices=['demo', 'custom-weight', 'no-impairment', 'discounted-viu', 'capped', 'insufficient-capacity'], default='demo')
    parser.add_argument('--verify', type=Path)
    parser.add_argument('--scale', type=int, choices=[0, 2], default=0)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--write-references', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        raise SystemExit(0)
    if args.write_references:
        write_references()
        raise SystemExit(0)
    values = expected(args.case, args.scale)
    if args.verify:
        values = {key: Decimal(value.numerator) / Decimal(value.denominator) if isinstance(value, Fraction)
                  else Decimal(value) for key, value in values.items()}
        actual = json.loads(args.verify.read_text())
        if args.case in ('discounted-viu', 'capped', 'insufficient-capacity'):
            assert not {key for key in actual if '*' in key} - values.keys(), 'Uncovered numeric reduction source'
        for key, value in values.items():
            assert Decimal(actual[key]) == Decimal(value), f'{key}: {actual[key]} != {value}'
        print(f'Verified {len(values)} independently recomputed amounts for {args.case}')
    else:
        print(json.dumps({key: str(Decimal(value.numerator) / Decimal(value.denominator))
                          if isinstance(value, Fraction) else str(value) for key, value in values.items()}, indent=2))
