#!/usr/bin/env python3
"""Independent Decimal oracle for fictional recognized deferred-tax roll-forwards.

No Mantra imports or generated engine/workbook values. Sources and formulas are
locked in independent/roll-forward/source-manifest.json before DSL implementation.
Signed differences/tax are positive liabilities and negative assets. PL and OCI
provenance buckets are measured separately; rounded differences reconcile exactly.
"""
import argparse
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'independent/roll-forward'
PERIODS = ('P1', 'P2', 'P3')


def calculate(facts, scale=2):
    result = {'currency-scale': str(scale)}
    quantum = Decimal(1).scaleb(-scale)
    rounded = lambda number: number.quantize(quantum, rounding=ROUND_HALF_UP)
    amounts = {}
    def put(node, coord, value):
        result[node + ('@' + '/'.join(coord) if coord else '')] = format(value, 'f')
        amounts.setdefault(node, {})[tuple(coord)] = value
    states = {}
    for item in facts['items']:
        pl, oci, rate = map(Decimal, (item['opening-pl-difference'], item['opening-oci-difference'], item['opening-rate']))
        states[item['id']] = (pl, oci, rate, rounded(pl * rate), rounded(oci * rate))
    for period, rate in zip(PERIODS, facts['rates']):
        rate = Decimal(rate)
        put('closing-tax-rate', [period], rate)
        put('rate-source-count', [period], Decimal(1))
        put('reported-source-count', [period], Decimal(1))
        for item in facts['items']:
            key = item['id']
            opening_pl, opening_oci, previous_rate, opening_pl_tax, opening_oci_tax = states[key]
            movement = facts['movements'].get(key, {}).get(period, ['0', '0'])
            pl_movement, oci_movement = map(Decimal, movement)
            closing_pl, closing_oci = opening_pl + pl_movement, opening_oci + oci_movement
            pl_tax, oci_tax = rounded(closing_pl * rate), rounded(closing_oci * rate)
            values = {
                'opening-pl-difference': opening_pl, 'opening-oci-difference': opening_oci,
                'pl-difference-movement': pl_movement, 'oci-difference-movement': oci_movement,
                'closing-pl-difference': closing_pl, 'closing-oci-difference': closing_oci,
                'closing-difference': closing_pl + closing_oci,
                'opening-tax-rate': previous_rate,
                'opening-pl-tax': opening_pl_tax, 'opening-oci-tax': opening_oci_tax,
                'measured-pl-tax': pl_tax, 'measured-oci-tax': oci_tax,
                'opening-deferred-tax': opening_pl_tax + opening_oci_tax,
                'pl-tax-movement': pl_tax - rounded(opening_pl * rate),
                'oci-tax-movement': oci_tax - rounded(opening_oci * rate),
                'pl-rate-effect': rounded(opening_pl * rate) - opening_pl_tax,
                'oci-rate-effect': rounded(opening_oci * rate) - opening_oci_tax,
                'closing-deferred-tax': pl_tax + oci_tax,
                'measurement-crossfoot': Decimal(0),
                'closing-tax-asset': max(Decimal(0), -(pl_tax + oci_tax)),
                'closing-tax-liability': max(Decimal(0), pl_tax + oci_tax),
            }
            assert (values['opening-deferred-tax'] + values['pl-tax-movement'] + values['oci-tax-movement']
                    + values['pl-rate-effect'] + values['oci-rate-effect']) == values['closing-deferred-tax']
            for node, amount in values.items():
                put(node, [key, period], amount)
            states[key] = closing_pl, closing_oci, rate, pl_tax, oci_tax
        for node in ('opening-deferred-tax', 'pl-tax-movement', 'oci-tax-movement', 'pl-rate-effect',
                     'oci-rate-effect', 'closing-deferred-tax', 'closing-tax-asset', 'closing-tax-liability'):
            put('period-' + node, [period], sum(amounts[node][(item['id'], period)] for item in facts['items']))
        reported = Decimal(facts['reported'][period])
        put('reported-deferred-tax', [period], reported)
        put('deferred-tax-reconciliation', [period], amounts['period-closing-deferred-tax'][(period,)] - reported)
    first = {'opening-pl-difference', 'opening-oci-difference', 'opening-pl-tax', 'opening-oci-tax',
             'opening-deferred-tax', 'period-opening-deferred-tax'}
    last = {'closing-pl-difference', 'closing-oci-difference', 'closing-difference', 'measured-pl-tax',
            'measured-oci-tax', 'closing-deferred-tax', 'closing-tax-asset', 'closing-tax-liability',
            'period-closing-deferred-tax', 'period-closing-tax-asset', 'period-closing-tax-liability',
            'reported-deferred-tax'}
    excluded = {'closing-tax-rate', 'opening-tax-rate', 'rate-source-count', 'reported-source-count',
                'measurement-crossfoot', 'deferred-tax-reconciliation'}
    for node, entries in list(amounts.items()):
        if node in excluded:
            continue
        chosen_period = PERIODS[0] if node in first else PERIODS[-1] if node in last else None
        chosen = {coord: value for coord, value in entries.items() if chosen_period is None or coord[-1] == chosen_period}
        result[node + '@*'] = format(sum(chosen.values()), 'f')
        if len(next(iter(entries))) == 2:
            for item in facts['items']:
                result[node + '@' + item['id'] + '/*'] = format(
                    sum(value for coord, value in chosen.items() if coord[0] == item['id']), 'f')
            for period in PERIODS:
                result[node + '@*/' + period] = format(sum(value for coord, value in entries.items() if coord[-1] == period), 'f')
    failed = [period for period in PERIODS if abs(amounts['deferred-tax-reconciliation'][(period,)]) > Decimal('0.01')]
    return dict(sorted(result.items())), {'validationPassed': not failed, 'failedPeriods': failed}


def references(check=False):
    for file in sorted((SOURCE / 'fixtures').glob('*.json')):
        facts = json.loads(file.read_text())
        for scale in (2, 4):
            values, summary = calculate(facts, scale)
            for suffix, data in (('values', values), ('summary', summary)):
                target = SOURCE / 'references' / f'{file.stem}-scale{scale}-{suffix}.json'
                content = json.dumps(data, indent=2) + '\n'
                if check:
                    if target.read_text() != content:
                        raise SystemExit(f'Independent reference changed: {target}')
                else:
                    target.write_text(content)


def check_lock():
    manifest = json.loads((SOURCE / 'source-manifest.json').read_text())
    for name, expected in manifest['sha256'].items():
        actual = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
        if expected != actual:
            raise SystemExit(f'Independent source changed: {name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-references', action='store_true')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--verify', type=Path, help='Engine-produced values JSON (comparison only, never an oracle source)')
    parser.add_argument('--case', default='demo')
    parser.add_argument('--scale', type=int, choices=(2, 4), default=2)
    args = parser.parse_args()
    if args.write_references:
        references()
    if args.check:
        check_lock()
        references(check=True)
        print('All independent deferred-tax sources and Decimal references verified')
    if args.verify:
        expected, _ = calculate(json.loads((SOURCE / 'fixtures' / f'{args.case}.json').read_text()), args.scale)
        actual = json.loads(args.verify.read_text())
        for address, value in expected.items():
            if address not in actual or Decimal(str(actual[address])) != Decimal(value):
                raise SystemExit(f'{address}: expected {value}, actual {actual.get(address)}')
        print(f'Verified {len(expected)} deferred-tax values against the independent Decimal oracle')


if __name__ == '__main__':
    main()
