#!/usr/bin/env python3
"""Enforce the milestone executed-test floors after Gradle has produced JUnit XML reports."""

from pathlib import Path
from collections.abc import Mapping
import sys
import xml.etree.ElementTree as ET


MINIMUM_TESTS = {
    'mantra-core': 213,
    'mantra-render': 36,
    'mantra-excel': 66,
    'mantra-workbench': 65,
    'mantra-server': 21,
    'mantra-cli': 11,
    'apps/de-est': 31,
    'apps/ifrs-impairment': 27,
    'apps/cost-accounting': 27,
    'apps/ifrs-income-taxes': 23,
    'apps/fixed-assets': 21,
    'apps/ifrs-leases': 20,
    'apps/de-gewst': 18,
    'apps/circular-calculation': 19,
    'benchmarks': 3,
}


def check(root: Path, minimums: Mapping[str, int] = MINIMUM_TESTS) -> list[str]:
    problems = []
    for module, minimum in minimums.items():
        reports = sorted((root / module / 'build/test-results/test').glob('TEST-*.xml'))
        if not reports:
            problems.append(f'{module}: no JUnit XML reports; run the module test task')
            continue
        executed = failures = errors = 0
        for report in reports:
            try:
                suite = ET.parse(report).getroot()
                if suite.tag != 'testsuite':
                    raise ValueError('expected a Gradle testsuite root')
                counts = {name: int(suite.attrib[name]) for name in ('tests', 'skipped', 'failures', 'errors')}
                cases = suite.findall('testcase')
                actual = {
                    'tests': len(cases),
                    'skipped': sum(case.find('skipped') is not None for case in cases),
                    'failures': sum(case.find('failure') is not None for case in cases),
                    'errors': sum(case.find('error') is not None for case in cases),
                }
                if counts != actual:
                    raise ValueError('suite counts disagree with testcase results')
            except (ET.ParseError, OSError, KeyError, ValueError) as error:
                problems.append(f'{report.relative_to(root)}: invalid JUnit XML: {error}')
                continue
            executed += counts['tests'] - counts['skipped']
            failures += counts['failures']
            errors += counts['errors']
        if executed < minimum:
            problems.append(f'{module}: {executed} executed tests below minimum {minimum} (skipped tests do not count)')
        if failures or errors:
            problems.append(f'{module}: {failures} test failures and {errors} test errors')
    return problems


if __name__ == '__main__':
    findings = check(Path(__file__).resolve().parent.parent)
    if findings:
        print('\n'.join(findings), file=sys.stderr)
        sys.exit(1)
    print('Test counts passed: every module meets its milestone minimum with no test failures or errors')
