"""Offline generator checks. They do not compile Kotlin, execute DSL or calculate finances."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[3]
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("mantra_site", ROOT / "scripts/generate-docs-site.py")
sitegen = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sitegen)


class MarkdownTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.site = sitegen.Site(ROOT, Path(self.temporary.name))
        self.origin = ROOT / "docs/site/index.md"

    def test_code_in_link_label_and_raw_html_are_preserved_as_text(self):
        result = self.site.inline('[`public API`](site:reference/api.html) <script>alert(1)</script>',
                                  self.origin, "index.html")
        self.assertIn('<a href="reference/api.html"><code>public API</code></a>', result)
        self.assertNotIn("<script>", result)
        self.assertIn("&lt;script&gt;", result)
        code = self.site.inline('`[literal](javascript:danger)`', self.origin, "index.html")
        self.assertEqual(code, '<code>[literal](javascript:danger)</code>')

    def test_tables_keep_code_pipes_regex_backslashes_and_escaped_pipes(self):
        text = '| Rule | Description |\n| --- | --- |\n| `:min|:max` | a\\|b and `\\d+` |'
        result = self.site.markdown(text, self.origin, "index.html")
        self.assertEqual(result.count("<td>"), 2)
        self.assertIn('<code>:min|:max</code>', result)
        self.assertIn('a|b and <code>\\d+</code>', result)

    def test_unsupported_schemes_and_site_escape_are_rejected(self):
        for address in ("javascript:alert", "file:///etc/passwd", "site:../outside.html"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                self.site.link(address, self.origin, "index.html")

    def test_repository_source_must_be_public_and_confined(self):
        for source in (ROOT / ".deps/normein/README.md", ROOT.parent / "outside.md"):
            with self.subTest(source=source), self.assertRaises(ValueError):
                self.site.source_file(source, "index.html")

    def test_encoded_literal_and_decoded_anchors_are_checked_without_hiding_missing_ids(self):
        output = self.site.output / "index.html"
        for identity in ("member%2Fkind", "member/kind"):
            output.write_text(f'<div id="{identity}"></div><a href="#member%2Fkind">open</a>')
            self.assertEqual(sitegen.check_links(self.site.output)["local_links"], 1)
        output.write_text('<div id="other"></div><a href="#member%2Fkind">open</a>')
        with self.assertRaisesRegex(ValueError, "missing anchor"):
            sitegen.check_links(self.site.output)

    def test_duplicate_headings_have_distinct_checked_anchors(self):
        body = self.site.markdown('# Intro\n\n# Intro\n\n[Repeat](#intro-1)', self.origin, "index.html")
        output = self.site.output / "index.html"
        output.write_text(body)
        self.assertEqual(sitegen.check_links(self.site.output)["local_links"], 1)


class LinkTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)

    def test_missing_file_missing_anchor_and_escape_are_failures(self):
        for link in ("missing.html", "#missing", "../outside.txt"):
            with self.subTest(link=link):
                (self.output / "index.html").write_text(f'<h1 id="present">Title</h1><a href="{link}">Go</a>')
                with self.assertRaises(ValueError):
                    sitegen.check_links(self.output)

    def test_external_links_are_not_fetched_and_encoded_local_links_work(self):
        (self.output / "a b.txt").write_text("source")
        (self.output / "index.html").write_text(
            '<a href="a%20b.txt">Source</a><a href="https://example.invalid/docs">External</a>')
        self.assertEqual(sitegen.check_links(self.output), {"html_documents": 1, "local_links": 1})

    def test_empty_output_and_duplicate_ids_are_failures(self):
        with self.assertRaises(ValueError):
            sitegen.check_links(self.output)
        (self.output / "index.html").write_text('<h1 id="same">A</h1><p id="same">B</p>')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            sitegen.check_links(self.output)


class ArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repository"
        self.directory = self.root / "apps/demo"
        (self.directory / "build/out").mkdir(parents=True)
        (self.directory / "README.md").write_text("# Demo")
        self.site = sitegen.Site(self.root, Path(self.temporary.name) / "site")
        self.application = dict(id="demo", title="Demo", description="Fictional", verification="verify.py",
                                schema="schema.mantra", case="case-main.mantra", focus="Boundary cases")

    def test_missing_outputs_are_explicit_without_dead_download_links(self):
        result = sitegen.application_page(self.site, self.application, False)
        self.assertEqual(result.count("not generated"), 3)
        self.assertNotIn("site:downloads/", result)
        with self.assertRaisesRegex(ValueError, "missing"):
            sitegen.application_page(self.site, self.application, True)

    def test_real_outputs_are_copied_byte_for_byte_and_hashed(self):
        files = self.directory / "build/out"
        (files / "case-main.html").write_text('<h1 id="paper">Paper</h1><a href="#paper">Back</a>')
        (files / "case-main.txt").write_text("Real paper\n")
        with zipfile.ZipFile(files / "case-main.xlsx", "w") as workbook:
            workbook.writestr("xl/workbook.xml", "<workbook/>")
        result = sitegen.application_page(self.site, self.application, True)
        self.assertEqual(len(self.site.artifacts), 3)
        self.assertEqual(result.count("site:downloads/"), 3)
        for artifact in self.site.artifacts:
            original = self.root / artifact["source"]
            copied = self.site.output / artifact["output"]
            self.assertEqual(original.read_bytes(), copied.read_bytes())
            self.assertEqual(sitegen.digest(copied), artifact["sha256"])

    def test_existing_broken_html_is_reported_and_strict_mode_fails(self):
        (self.directory / "build/out/case-main.html").write_text('<a href="#absent">Broken</a>')
        result = sitegen.application_page(self.site, self.application, False)
        self.assertIn("invalid links", result)
        self.assertNotIn("site:downloads/demo/case-main.html", result)
        self.assertEqual(self.site.artifact_issues[0]["issues"], ["missing anchor: #absent"])
        with self.assertRaisesRegex(ValueError, "invalid links"):
            sitegen.application_page(self.site, self.application, True)

    def test_invalid_workbook_is_never_published(self):
        with zipfile.ZipFile(self.directory / "build/out/case-main.xlsx", "w") as workbook:
            workbook.writestr("other.xml", "not a workbook")
        with self.assertRaisesRegex(ValueError, "not an XLSX"):
            sitegen.application_page(self.site, self.application, False)


class RepositoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.output = Path(cls.temporary.name) / "site"
        cls.metadata = sitegen.generate(ROOT, cls.output)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_real_checkout_generates_all_application_pages_and_local_indexes(self):
        applications = json.loads((ROOT / "docs/site/applications.json").read_text())
        self.assertEqual(len(applications), len([path for path in (ROOT / "apps").iterdir() if (path / "README.md").is_file()]))
        for application in applications:
            self.assertTrue((self.output / "apps" / (application["id"] + ".html")).is_file())
        for name in ("dsl", "functions", "diagnostics", "api"):
            self.assertTrue((self.output / "reference" / (name + ".html")).is_file())
        self.assertGreaterEqual(self.metadata["validation"]["html_documents"], 16)
        self.assertGreater(self.metadata["validation"]["local_links"], 100)
        self.assertEqual(sitegen.check_links(self.output), self.metadata["validation"])

    def test_indexes_come_from_real_sources_without_internal_helpers(self):
        catalog = self.metadata["calculation_catalog"]
        self.assertEqual(catalog["semantics"], "2")
        self.assertEqual(set(catalog["functions"]), {
            "alloc/capped", "alloc/pro-rata", "alloc/waterfall",
            "calc/converge", "calc/stepwise",
            "dim/max", "dim/min", "dim/rollup", "dim/sum",
            "fin/df", "fin/npv", "fin/pmt",
            "table/band", "table/count-where", "table/sum-where",
        })
        functions_page = (self.output / "reference/functions.html").read_text()
        for name in ("table/count-where", "table/sum-where"):
            self.assertIn(f"<code>{name}</code>", functions_page)
        self.assertTrue(all(not name.startswith("mantra-internal/") for name in catalog["functions"]))
        self.assertFalse(catalog["runtime_catalog_checked"])
        self.assertGreater(self.metadata["public_declarations"], 50)
        diagnostics = self.metadata["diagnostic_inventory"]
        self.assertTrue(diagnostics["checked_against_source"])
        self.assertGreater(len(diagnostics["static_codes"]), 100)
        self.assertIn("MANTRA-RUN-LIMIT", diagnostics["static_codes"])
        self.assertIn("Mantra", (self.output / "reference/api.html").read_text())
        for name, expected in self.metadata["source_files"].items():
            self.assertEqual(sitegen.digest(ROOT / name), expected)
        self.assertTrue(all(".deps" not in name for name in self.metadata["source_files"]))

    def test_site_is_offline_and_delivered_api_is_explicit(self):
        for path in self.output.glob("**/*.html"):
            if "downloads" in path.parts or "api" in path.relative_to(self.output).parts:
                continue
            text = path.read_text()
            self.assertIn('<html lang="en">', text)
            self.assertNotIn("<script", text)
            self.assertNotIn('href="https://fonts', text)
            self.assertIn('href="', text)
        self.assertIn("Embedded execution and package APIs", (self.output / "reference/api.html").read_text())
        self.assertIn("Compile once and stream typed cases", (self.output / "embedding.html").read_text())

    def test_executable_catalog_comparison_rejects_mismatches(self):
        site = sitegen.Site(ROOT, self.output)
        catalog = Path(self.temporary.name) / "catalog.txt"
        catalog.write_text("Functions\n" + "\n".join(
            f"  {name:16} {summary}" for name, summary in self.metadata["calculation_catalog"]["functions"].items()))
        self.assertTrue(sitegen.functions(site, catalog)[1]["runtime_catalog_checked"])
        catalog.write_text("  calc/converge Incorrect summary\n")
        with self.assertRaisesRegex(ValueError, "catalog differs"):
            sitegen.functions(site, catalog)

    def test_stale_diagnostic_directory_cannot_be_generated(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "scripts/check-diagnostics.py").write_bytes((ROOT / "scripts/check-diagnostics.py").read_bytes())
            source = root / "mantra-core/src/main/kotlin/Example.kt"
            source.parent.mkdir(parents=True)
            source.write_text('val code = "MANTRA-NEW-CODE"')
            (root / "docs").mkdir()
            (root / "docs/diagnostics.md").write_text("# Diagnostics\n")
            with self.assertRaisesRegex(ValueError, "MANTRA-NEW-CODE"):
                sitegen.diagnostic_inventory(sitegen.Site(root, root / "build/site"))


class OutputOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name) / "site"

    def tiny_generate(self, root, output, *unused):
        output.mkdir(exist_ok=True)
        text = '<h1 id="main">Generated</h1><a href="#main">Main</a>'
        (output / "index.html").write_text(text)
        metadata = {"generator": sitegen.GENERATOR,
                    "output_files": {"index.html": sitegen.digest(output / "index.html")}}
        (output / "build-manifest.json").write_text(json.dumps(metadata))
        return metadata

    def test_rebuild_only_replaces_owned_unmodified_files_and_removes_stale_owned_files(self):
        with patch.object(sitegen, "generate_content", self.tiny_generate):
            metadata = sitegen.generate(ROOT, self.output)
            stale = self.output / "stale.html"
            stale.write_text("Old generated file")
            metadata["output_files"]["stale.html"] = sitegen.digest(stale)
            (self.output / "build-manifest.json").write_text(json.dumps(metadata))
            sitegen.generate(ROOT, self.output)
        self.assertFalse(stale.exists())
        self.assertTrue((self.output / "index.html").is_file())

    def test_user_files_and_modified_generated_outputs_are_preserved(self):
        with patch.object(sitegen, "generate_content", self.tiny_generate):
            sitegen.generate(ROOT, self.output)
            index = self.output / "index.html"
            index.write_text("Edited by user")
            with self.assertRaisesRegex(ValueError, "modified"):
                sitegen.generate(ROOT, self.output)
            self.assertEqual(index.read_text(), "Edited by user")
            user = self.output / "notes.txt"
            user.write_text("User notes")
            with self.assertRaisesRegex(ValueError, "not owned"):
                sitegen.generate(ROOT, self.output)
            self.assertEqual(user.read_text(), "User notes")

    def test_failed_generation_keeps_the_previous_site(self):
        with patch.object(sitegen, "generate_content", self.tiny_generate):
            sitegen.generate(ROOT, self.output)
        before = (self.output / "index.html").read_bytes()
        with patch.object(sitegen, "generate_content", side_effect=ValueError("bad catalog")):
            with self.assertRaisesRegex(ValueError, "bad catalog"):
                sitegen.generate(ROOT, self.output)
        self.assertEqual((self.output / "index.html").read_bytes(), before)
        self.assertFalse(list(self.output.parent.glob(".mantra-docs-*")))

    def test_nonempty_unowned_and_source_outputs_are_rejected(self):
        self.output.mkdir()
        note = self.output / "note.txt"
        note.write_text("Preserve me")
        with self.assertRaisesRegex(ValueError, "no documentation build manifest"):
            sitegen.generate(ROOT, self.output)
        self.assertEqual(note.read_text(), "Preserve me")
        for output in (ROOT, ROOT / "docs/site/generated", ROOT / "scripts/generated"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                sitegen.generate(ROOT, output)

    def test_symbolic_output_and_unconfined_manifest_are_rejected(self):
        self.output.symlink_to(self.output.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symbolic"):
            sitegen.generate(ROOT, self.output)
        self.output.unlink()
        self.output.mkdir()
        manifest = {"generator": sitegen.GENERATOR, "output_files": {"../outside.txt": "unused"}}
        (self.output / "build-manifest.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "unconfined"):
            sitegen.generate(ROOT, self.output)


if __name__ == "__main__":
    unittest.main()
