"""Check missing, skipped and failing report regressions in the milestone test-count gate."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


spec = importlib.util.spec_from_file_location('test_counts', Path(__file__).parents[1] / 'check-test-counts.py')
test_counts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(test_counts)


class TestCountsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='mantra-test-counts-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def report(self, outcomes):
        directory = self.root / 'mantra-demo/build/test-results/test'
        directory.mkdir(parents=True, exist_ok=True)
        suite = ET.Element('testsuite', tests=str(len(outcomes)),
                           skipped=str(outcomes.count('skipped')), failures=str(outcomes.count('failure')),
                           errors=str(outcomes.count('error')))
        for index, outcome in enumerate(outcomes):
            case = ET.SubElement(suite, 'testcase', name=f'test-{index}')
            if outcome != 'pass':
                ET.SubElement(case, outcome)
        path = directory / 'TEST-demo.xml'
        ET.ElementTree(suite).write(path, encoding='utf-8')
        return path

    def test_missing_module_results_fail_instead_of_passing_vacuously(self):
        self.assertEqual(
            test_counts.check(self.root, {'mantra-demo': 1}),
            ['mantra-demo: no JUnit XML reports; run the module test task'],
        )

    def test_skipped_tests_cannot_satisfy_the_executed_test_floor(self):
        self.report(['pass', 'pass', 'skipped'])
        self.assertEqual(test_counts.check(self.root, {'mantra-demo': 2}), [])
        self.assertEqual(
            test_counts.check(self.root, {'mantra-demo': 3}),
            ['mantra-demo: 2 executed tests below minimum 3 (skipped tests do not count)'],
        )

    def test_failures_errors_and_damaged_reports_fail_the_gate(self):
        path = self.report(['pass', 'failure', 'error'])
        self.assertEqual(
            test_counts.check(self.root, {'mantra-demo': 3}),
            ['mantra-demo: 1 test failures and 1 test errors'],
        )
        for damaged in (
            '<testsuite',
            '<testsuite tests="1" skipped="0" failures="0" errors="0"><testcase><failure/></testcase></testsuite>',
        ):
            with self.subTest(report=damaged):
                path.write_text(damaged, encoding='utf-8')
                findings = test_counts.check(self.root, {'mantra-demo': 1})
                self.assertTrue(any('invalid JUnit XML' in finding for finding in findings))


if __name__ == '__main__':
    unittest.main()
