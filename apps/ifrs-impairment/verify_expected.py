#!/usr/bin/env python3
"""Independent arithmetic for the demonstration (Fraction/Decimal; no Mantra dependencies).
Inputs are independently authored fictional facts, documented in README.md.
"""
import argparse
from decimal import Decimal
from fractions import Fraction
import json
from pathlib import Path


def allocate(total, weights):
    exact = [Fraction(total * weight, sum(weights)) for weight in weights]
    base = [value.numerator // value.denominator for value in exact]
    # Equal remainders use input member order, matching the documented deterministic rule.
    priority = sorted(range(len(weights)), key=lambda i: (-(exact[i] - base[i]), i))
    for index in priority[:total - sum(base)]:
        base[index] += 1
    assert sum(base) == total
    return base


def expected(case):
    carrying = [120, 180, 260]
    weights = [4320, 25920, 84240] if case == 'custom-weight' else [120, 360, 780]
    recoverable = [1000, 1000, 1000] if case == 'no-impairment' else [180, 210, 300]
    shares = allocate(211, weights)
    losses = [max(0, own + share - ra) for own, share, ra in zip(carrying, shares, recoverable)]
    values = {}
    for member, own, share, ra, loss in zip('ABC', carrying, shares, recoverable, losses):
        for name, value in [('corporate-share', share), ('carrying-amount-allocated', own + share),
                            ('recoverable-amount', ra), ('impairment-loss', loss)]:
            values[f'{name}@{member}'] = value
        split = allocate(loss, [share, own])
        values[f'loss-to-corporate@{member}'], values[f'loss-to-own-assets@{member}'] = split
    values['group-before'] = 844
    values['group-carrying-amount'] = 844 - sum(losses)
    values['group-loss'] = 0
    values['total-impairment'] = sum(losses)
    return values


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', choices=['demo', 'custom-weight', 'no-impairment'], default='demo')
    parser.add_argument('--verify', type=Path)
    args = parser.parse_args()
    values = expected(args.case)
    if args.verify:
        actual = json.loads(args.verify.read_text())
        for key, value in values.items():
            assert Decimal(actual[key]) == Decimal(value), f'{key}: {actual[key]} != {value}'
        print(f'Verified {len(values)} independently recomputed amounts for {args.case}')
    else:
        print(json.dumps(values, indent=2))
