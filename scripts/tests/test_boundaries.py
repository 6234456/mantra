"""Exercise regressions that the architecture gate must reject."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('boundaries', Path(__file__).parents[1] / 'check-boundaries.py')
boundaries = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundaries)


class BoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='mantra-boundaries-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write('apps/demo/schema.mantra', '(schema demo/calculation (section tax-result "Result"))')
        self.write('apps/demo/build.gradle.kts', 'dependencies { testImplementation(project(":mantra-core")) }')

    def write(self, path, text):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)

    def test_domain_tokens_rejected_but_test_fixtures_allowed(self):
        self.write('workbench-ui/src/page.tsx', 'if (id === "tax-result") {}')
        self.write('workbench-ui/src/page.test.tsx', 'const sample = "tax-result"')
        self.assertEqual(1, len(boundaries.check(self.root)))

    def test_schema_ids_not_reduced_to_unrelated_substrings(self):
        self.write('mantra-core/src/main/kotlin/Code.kt', 'val path = "demo/calculation"')
        self.assertEqual(1, len(boundaries.check(self.root)))

    def test_qualified_internal_references_caught_in_consumer_tests(self):
        self.write('mantra-excel/src/test/kotlin/Code.kt', 'val a: com.xqiou.mantra.core.engine.Coord')
        self.assertEqual(1, len(boundaries.check(self.root)))

    def test_dependencies_flow_only_towards_libraries(self):
        self.write('mantra-core/build.gradle.kts', 'api(project(":apps:demo"))')
        self.write('apps/demo/build.gradle.kts', 'testImplementation(project(":apps:other"))')
        self.assertEqual(2, len(boundaries.check(self.root)))

    def test_gradle_dependency_syntax_variants_are_checked(self):
        for dependency in ['project(path = ":apps:demo")', 'project( ":apps:demo" )']:
            self.write('mantra-core/build.gradle.kts', f'api({dependency})')
            self.assertEqual(1, len(boundaries.check(self.root)))

    def test_shared_acceptance_code_must_use_public_api(self):
        self.write('build-support/app-acceptance/Code.kt', 'import com.xqiou.mantra.core.engine.Coord')
        self.assertEqual(1, len(boundaries.check(self.root)))

    def test_comments_do_not_count_as_api_usage(self):
        self.write('mantra-excel/src/main/kotlin/Code.kt', '// com.xqiou.mantra.core.engine.Coord\nval url = "https://example.test"')
        self.assertEqual([], boundaries.check(self.root))

    def test_empty_domain_catalog_does_not_pass(self):
        self.write('apps/demo/schema.mantra', ';; (schema fake/id)\n(schema "fake/id")')
        self.assertTrue(boundaries.check(self.root))

    def test_apps_cannot_publish(self):
        self.write('apps/demo/build.gradle.kts', 'plugins { `maven-publish` }')
        self.assertEqual(1, len(boundaries.check(self.root)))

    def test_benchmarks_use_public_api_and_only_library_dependencies(self):
        self.write('benchmarks/src/test/kotlin/Code.kt', 'import com.xqiou.mantra.core.engine.Coord')
        self.write('benchmarks/build.gradle.kts', 'implementation(project(":apps:demo"))')
        self.assertEqual(2, len(boundaries.check(self.root)))

    def test_benchmarks_remain_domain_independent(self):
        self.write('benchmarks/src/main/kotlin/Code.kt', 'val node = "tax-result"')
        self.assertEqual(1, len(boundaries.check(self.root)))


if __name__ == '__main__':
    unittest.main()
