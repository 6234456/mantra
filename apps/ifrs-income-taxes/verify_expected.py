#!/usr/bin/env python3
"""Independently recompute fictional single-period tax reconciliation using Decimal only."""
import argparse
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path


D = Decimal


def expected(case='demo'):
    # These fixture facts are intentionally independent of DSL parsing and engine formulas.
    if case in ('demo', 'unreconciled'):
        entities = [('North', D(240000), D('.25'), D('65400.02' if case == 'unreconciled' else '65400')),
                    ('South', D(80000), D('.20'), D(14400))]
        effects = [('N1', 'North', D(6500)), ('N2', 'North', D(-2400)), ('N3', 'North', D(1300)),
                   ('S1', 'South', D(800)), ('S2', 'South', D(-1600)), ('S3', 'South', D(-800))]
    elif case == 'loss':
        entities = [('North', D(-100000), D('.25'), D(-23000))]
        effects = [('N1', 'North', D(2000))]
    elif case == 'zero-profit':
        entities = [('North', D(0), D('.25'), D(0))]
        effects = [('N1', 'North', D(0))]
    else:
        raise ValueError(f'unknown fixture: {case}')
    values = {f'tax-effect@{item}': effect for item, _, effect in effects}
    for member, profit, rate, booked in entities:
        adjustments = sum((amount for _, owner, amount in effects if owner == member), D(0))
        expected_tax = profit * rate
        explained = expected_tax + adjustments
        for node, value in [('accounting-profit', profit), ('applicable-tax-rate', rate),
                            ('expected-tax', expected_tax), ('tax-effect-total', adjustments),
                            ('explained-tax', explained), ('actual-tax', booked),
                            ('tax-reconciliation', explained - booked)]:
            values[f'{node}@{member}'] = value
        if profit != 0:
            values[f'effective-tax-rate@{member}'] = (booked / profit).quantize(D('.000001'), ROUND_HALF_UP)
    profit = sum((row[1] for row in entities), D(0))
    tax = sum((row[3] for row in entities), D(0))
    values['group-profit'] = profit
    values['group-tax'] = tax
    values['group-explained-tax'] = sum((row[1] * row[2] for row in entities), D(0)) + sum(
        (row[2] for row in effects), D(0))
    values['group-tax-reconciliation'] = values['group-explained-tax'] - tax
    if profit != 0:
        values['group-effective-rate'] = (tax / profit).quantize(D('.000001'), ROUND_HALF_UP)
        values['effective-tax-rate@*'] = values['group-effective-rate']
    return values


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', choices=['demo', 'loss', 'zero-profit', 'unreconciled'], default='demo')
    parser.add_argument('--verify', type=Path)
    args = parser.parse_args()
    values = expected(args.case)
    if args.verify:
        actual = json.loads(args.verify.read_text())
        for key, value in values.items():
            assert D(actual[key]) == value, f'{key}: {actual.get(key)} != {value}'
        if args.case == 'zero-profit':
            assert 'effective-tax-rate@North' not in actual, 'zero-profit entity rate must be undefined'
            assert 'group-effective-rate' not in actual, 'zero-profit group rate must be undefined'
            assert 'effective-tax-rate@*' not in actual, 'zero-profit weighted rate must be undefined'
        print(f'Verified {len(values)} independently recomputed tax-reconciliation amounts ({args.case})')
    else:
        print(json.dumps({key: str(value) for key, value in values.items()}, indent=2))


if __name__ == '__main__':
    main()
