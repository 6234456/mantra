#!/usr/bin/env python3
"""Recompute synthetic cost flow using ordinary Decimal arithmetic, independently of Mantra."""
import argparse
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path


def expected(zero_pools=False):
    direct = [Decimal(5000), Decimal(3000), Decimal(4400)]
    quantities = [Decimal(100), Decimal(50), Decimal(80)]
    primary, secondary = (Decimal(0), Decimal(0)) if zero_pools else (Decimal(900), Decimal(1800))
    weights = [Decimal(10), Decimal(30), Decimal(20)]
    actual = [amount + (primary + secondary) * weight / sum(weights) for amount, weight in zip(direct, weights)]
    standard = [quantity * price for quantity, price in zip(quantities, map(Decimal, [58, 64, 62]))]
    values = {'direct-primary-total': sum(direct), 'primary-pool': primary, 'secondary-pool': secondary,
              'source-cost-total': sum(actual), 'unassigned-cost': Decimal(0), 'product-crossfoot': Decimal(0),
              'total-variance': sum(actual) - sum(standard)}
    for index, member in enumerate(['O100', 'O101', 'O200']):
        values[f'primary-allocated@{member}'] = primary * weights[index] / sum(weights)
        values[f'secondary-allocated@{member}'] = secondary * weights[index] / sum(weights)
        values[f'actual-order-cost@{member}'] = actual[index]
    for member, indices in [('A', [0, 1]), ('B', [2])]:
        quantity = sum(quantities[i] for i in indices)
        cost = sum(actual[i] for i in indices)
        target = sum(standard[i] for i in indices)
        for name, value in [('product-quantity', quantity), ('product-actual-cost', cost),
                            ('product-standard-cost', target), ('product-variance', cost - target),
                            ('actual-weighted-unit', (cost / quantity).quantize(Decimal('.0001'), rounding=ROUND_HALF_UP)),
                            ('standard-weighted-unit', (target / quantity).quantize(Decimal('.0001'), rounding=ROUND_HALF_UP))]:
            values[f'{name}@{member}'] = value
    assert sum(actual) == sum(direct) + primary + secondary
    return values


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--zero-pools', action='store_true')
    parser.add_argument('--verify', type=Path)
    args = parser.parse_args()
    values = expected(args.zero_pools)
    if args.verify:
        actual = json.loads(args.verify.read_text())
        for key, value in values.items():
            assert Decimal(actual[key]) == value, f'{key}: {actual[key]} != {value}'
        print(f'Verified {len(values)} independently recomputed amounts')
    else:
        print(json.dumps({key: str(value) for key, value in values.items()}, indent=2))
