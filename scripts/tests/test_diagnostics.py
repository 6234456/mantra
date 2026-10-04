import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('diagnostics', Path(__file__).parents[1] / 'check-diagnostics.py')
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)


class DiagnosticsReferenceTest(unittest.TestCase):
    def file(self, root, path, text):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')

    def test_single_segment_codes_and_kernel_cause_codes_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.file(root, 'mantra-core/src/main/kotlin/Core.kt',
                      'error("MANTRA-CYCLE")\nfail("DSL-MANTRA-ALLOC-ZERO-BASIS")\n')
            self.file(root, 'docs/diagnostics.md', '| `MANTRA-CYCLE` | structural | Cycle |\n')
            self.assertIn('DSL-MANTRA-ALLOC-ZERO-BASIS', diagnostics.check(root)[0])
            self.file(root, 'docs/diagnostics.md',
                      '| `MANTRA-CYCLE` | structural | Cycle |\n'
                      '| `DSL-MANTRA-ALLOC-ZERO-BASIS` | cause | Zero basis |\n')
            self.assertEqual(diagnostics.check(root), [])

    def test_business_code_additions_cannot_silently_skip_the_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.file(root, 'mantra-core/src/main/kotlin/Validation.kt', 'error("MANTRA-CHECK-FAILED")')
            self.file(root, 'docs/diagnostics.md', '# Reference\n')
            self.assertIn('MANTRA-CHECK-FAILED', diagnostics.check(root)[0])

    def test_stale_and_duplicate_rows_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.file(root, 'mantra-workbench/src/main/kotlin/Editor.kt', 'error("MANTRA-WORKBENCH-EDIT")')
            self.file(root, 'docs/diagnostics.md',
                      '| `MANTRA-WORKBENCH-EDIT` | structural | Edit |\n'
                      '| `MANTRA-WORKBENCH-EDIT` | structural | Edit |\n'
                      '| `MANTRA-REMOVED` | structural | Removed |\n')
            problems = diagnostics.check(root)
            self.assertTrue(any('removed static codes' in problem for problem in problems))
            self.assertTrue(any('Duplicate' in problem for problem in problems))

    def test_only_library_and_tool_main_source_is_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.file(root, 'mantra-server/src/main/kotlin/Server.kt',
                      'error("MANTRA-WORKBENCH-REQUEST")\nstartsWith("MANTRA-INPUT-")\n')
            for path in ['mantra-core/src/test/kotlin/Test.kt', 'apps/demo/src/main/kotlin/App.kt',
                         '.deps/normein/src/main/kotlin/Kernel.kt', 'mantra-core/build/generated/Generated.kt']:
                self.file(root, path, 'error("MANTRA-IGNORED")')
            self.file(root, 'docs/diagnostics.md', '| `MANTRA-WORKBENCH-REQUEST` | structural | Request |\n')
            self.assertEqual(diagnostics.check(root), [])

    def test_missing_document_and_empty_checkout_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertIn('No static diagnostic', diagnostics.check(root)[0])
            self.file(root, 'mantra-core/src/main/kotlin/Core.kt', 'error("MANTRA-CYCLE")')
            self.assertIn('missing', diagnostics.check(root)[0])


if __name__ == '__main__':
    unittest.main()
