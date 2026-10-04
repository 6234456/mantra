#!/usr/bin/env python3
"""Independent CSV/statistical gate; reads no Mantra modules and never rewrites samples."""
import argparse
import csv
from decimal import Decimal
from pathlib import Path
import hashlib
import json
import math

SCENARIOS = {
    'small': (25, 5, 25), 'lines': (250, 5, 25), 'members': (25, 50, 25),
    'table-rows': (25, 5, 1000), 'combined': (250, 50, 1000),
}
ORIGINAL = {'plan', 'calculate', 'calculate-audit', 'explain', 'paper', 'xlsx'}
PERIOD = {'calculate', 'calculate-audit', 'explain', 'paper-matrix', 'paper-transpose',
          'xlsx-matrix', 'xlsx-transpose', 'session-recalc', 'session-repeat'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(directory, periods):
    raw = directory / 'samples.csv'
    summary = directory / 'summary.csv'
    with raw.open(newline='', encoding='utf-8') as source:
        samples = list(csv.DictReader(source))
    with summary.open(newline='', encoding='utf-8') as source:
        summaries = list(csv.DictReader(source))
    groups = {}
    for row in samples:
        key = row['operation'] if periods else (row['scenario'], row['operation'])
        if not periods:
            dimensions = tuple(int(row[field]) for field in ['lines', 'members', 'table_rows'])
            assert dimensions == SCENARIOS[row['scenario']], (key, dimensions)
        groups.setdefault(key, []).append(row)
    expected = PERIOD if periods else {(name, op) for name in SCENARIOS for op in ORIGINAL}
    assert set(groups) == expected, ('unexpected groups', set(groups) ^ expected)
    assert len(samples) == len(expected) * 10, 'All ten measured repetitions must be retained'
    expected_summaries = {}
    for key, rows in groups.items():
        assert sorted(int(row['repetition']) for row in rows) == list(range(1, 11)), key
        times = sorted(Decimal(row['wall_ms']) for row in rows)
        assert all(time >= 0 and time.is_finite() for time in times), key
        values = {
            'samples': len(rows), 'median_ms': (times[4] + times[5]) / 2,
            'p95_ms': times[math.ceil(len(times) * .95) - 1],
            'min_ms': times[0], 'max_ms': times[-1],
            'max_heap_peak_bytes': max(int(row['heap_peak_bytes']) for row in rows),
            'max_heap_increase_bytes': max(max(0, int(row['heap_peak_bytes']) - int(row['heap_before_bytes'])) for row in rows),
        }
        assert values['max_heap_peak_bytes'] <= 1024 ** 3, ('heap bound', key)
        if periods and key.startswith('session-'):
            assert all(row['full_rebuild'] == 'false' and int(row['execution_sessions']) == 2 for row in rows), key
            if key == 'session-repeat':
                assert all(int(row['evaluated_tasks']) == 0 and int(row['formula_evaluations']) == 0 for row in rows), key
            else:
                assert all(int(row['evaluated_tasks']) == 41 and int(row['formula_evaluations']) == 10
                           and int(row['invalidated_tasks']) == 41 and int(row['reused_tasks']) > 0 for row in rows), key
        expected_summaries[key] = values
    seen = set()
    for row in summaries:
        key = row['operation'] if periods else (row['scenario'], row['operation'])
        assert key in expected_summaries and key not in seen, ('summary group', key)
        seen.add(key)
        for field, expected_value in expected_summaries[key].items():
            actual = Decimal(row[field])
            # Samples are published to 0.001 ms, whereas summaries use original nanosecond times.
            tolerance = Decimal('.001') if field == 'median_ms' else Decimal(0)
            assert abs(actual - Decimal(expected_value)) <= tolerance, (key, field, actual, expected_value)
    assert seen == expected, 'Missing summary rows'
    def limit(key, field, ceiling):
        actual = expected_summaries[key][field]
        assert actual <= Decimal(ceiling), ('budget', key, field, actual, ceiling)
    if periods:
        limit('calculate', 'median_ms', '250')
        assert expected_summaries['calculate-audit']['median_ms'] <= expected_summaries['calculate']['median_ms'] * 2
        limit('session-recalc', 'median_ms', '100')
        limit('xlsx-matrix', 'median_ms', '40000'); limit('xlsx-matrix', 'p95_ms', '60000')
        limit('xlsx-transpose', 'median_ms', '20000'); limit('xlsx-transpose', 'p95_ms', '30000')
    else:
        limit(('combined', 'calculate'), 'median_ms', '1500')
        assert expected_summaries[('combined', 'calculate-audit')]['median_ms'] <= expected_summaries[('combined', 'calculate')]['median_ms'] * Decimal('1.5')
        limit(('combined', 'xlsx'), 'median_ms', '60000'); limit(('combined', 'xlsx'), 'p95_ms', '150000')
    return {'groups': len(groups), 'samples': len(samples), 'samplesSha256': sha(raw), 'summarySha256': sha(summary),
            'budgetsPassed': True, 'maximumHeapPoolPeakBytes': max(v['max_heap_peak_bytes'] for v in expected_summaries.values())}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--original', required=True, type=Path)
    parser.add_argument('--periods', required=True, type=Path)
    args = parser.parse_args()
    original = verify(args.original, False)
    periods = verify(args.periods, True)
    print(json.dumps({'original': original, 'periods': periods, 'groups': original['groups'] + periods['groups'],
                      'samples': original['samples'] + periods['samples'], 'engineExecuted': False}, indent=2))


if __name__ == '__main__':
    main()
