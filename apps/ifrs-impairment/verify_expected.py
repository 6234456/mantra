#!/usr/bin/env python3
"""Independent arithmetic for the demonstration (Fraction/Decimal; no Mantra dependencies).
IE8 inputs are attributed in README.md. Largest remainders intentionally give B's split 11/31.
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
    carrying = [100, 150, 200]
    weights = [100, 600, 800] if case == 'custom-weight' else [100, 300, 400]
    recoverable = [1000, 1000, 1000] if case == 'no-impairment' else [199, 164, 271]
    shares = allocate(150, weights)
    losses = [max(0, own + share - ra) for own, share, ra in zip(carrying, shares, recoverable)]
    values = {}
    for member, own, share, ra, loss in zip('ABC', carrying, shares, recoverable, losses):
        for name, value in [('corporate-share', share), ('carrying-amount-allocated', own + share),
                            ('recoverable-amount', ra), ('impairment-loss', loss)]:
            values[f'{name}@{member}'] = value
        split = allocate(loss, [share, own])
        values[f'loss-to-corporate@{member}'], values[f'loss-to-own-assets@{member}'] = split
    values['group-before'] = 650
    values['group-carrying-amount'] = 650 - sum(losses)
    values['group-loss'] = 0
    values['total-impairment'] = sum(losses)
    return values


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', choices=['ie8', 'custom-weight', 'no-impairment'], default='ie8')
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
