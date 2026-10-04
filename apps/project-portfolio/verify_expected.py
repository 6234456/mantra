#!/usr/bin/env python3
"""Verify engine serialization against separately frozen Decimal/Fraction references."""
import argparse
from decimal import Decimal as D
import importlib.util
import json
from pathlib import Path

APP = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('independent_reference', APP / 'independent/reference.py')
SOURCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SOURCE)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--verify-values', type=Path)
    parser.add_argument('--fixture', choices=sorted(SOURCE.fixtures()), default='demo')
    arguments = parser.parse_args()
    SOURCE.self_test()
    if arguments.verify_values:
        expected, summary = SOURCE.compute(SOURCE.fixtures()[arguments.fixture])
        actual = json.loads(arguments.verify_values.read_text())
        for key, value in expected.items():
            assert key in actual, ('Missing numeric evidence', key)
            assert D(actual[key]) == value, (key, actual[key], value)
        for key in summary['undefined']:
            assert key not in actual, ('Undefined value was serialized as numeric', key, actual[key])
        print(f'Verified {len(expected)} numeric values and {len(summary["undefined"])} undefined addresses for {arguments.fixture}')
