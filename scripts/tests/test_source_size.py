import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('source_size', Path(__file__).parents[1] / 'check-source-size.py')
source_size = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source_size)


class SourceSizeTest(unittest.TestCase):
    def make_file(self, root, relative, contents):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding='utf-8')

    def test_counts_nonblank_lines_and_rejects_the_next_line(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_file(root, 'mantra-core/src/main/kotlin/Small.kt', 'a\n\nb\n')
            self.assertEqual(source_size.check(root, maximum=2), [])
            self.make_file(root, 'mantra-core/src/main/kotlin/Small.kt', 'a\n\nb\nc\n')
            self.assertEqual(
                source_size.check(root, maximum=2),
                ['mantra-core/src/main/kotlin/Small.kt: 3 nonblank lines exceeds 2; split by responsibility'],
            )

    def test_checks_app_test_shared_and_benchmark_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources = [
                'apps/demo/src/test/kotlin/Example.kt',
                'mantra-core/src/test/kotlin/Example.kt',
                'build-support/app-acceptance/Example.kt',
                'benchmarks/src/main/kotlin/Example.kt',
            ]
            for path in sources:
                self.make_file(root, path, 'a\nb\n')
            self.assertEqual(len(source_size.check(root, maximum=1)), len(sources))

    def test_excludes_kernel_and_generated_build_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_file(root, 'mantra-core/src/main/kotlin/Small.kt', 'a\n')
            self.make_file(root, '.deps/normein/normein-dsl/src/main/kotlin/Large.kt', 'a\nb\n')
            self.make_file(root, 'mantra-core/build/generated/Large.kt', 'a\nb\n')
            self.assertEqual(source_size.check(root, maximum=1), [])

    def test_empty_checkout_is_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(len(source_size.check(Path(directory))), 1)


if __name__ == '__main__':
    unittest.main()
