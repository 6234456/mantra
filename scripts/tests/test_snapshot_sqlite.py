"""Real SQLite extraction, archive integrity and optional real-engine cross-source checks."""

from contextlib import closing
from decimal import Decimal
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("snapshot_sqlite", SCRIPTS / "snapshot-sqlite.py")
snapshot = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = snapshot
SPEC.loader.exec_module(snapshot)


class SqliteSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.owned = tempfile.TemporaryDirectory()
        self.addCleanup(self.owned.cleanup)
        self.root = Path(self.owned.name).resolve()
        self.database = self.root / "facts.sqlite"
        with closing(sqlite3.connect(self.database)) as database:
            database.execute("CREATE TABLE facts (code TEXT PRIMARY KEY, amount_text TEXT, enabled INTEGER, memo TEXT, period TEXT)")
            database.executemany("INSERT INTO facts VALUES (?, ?, ?, ?, ?)", [
                ("B", "2.5000000000000000000000000001", 1, "precise", "P1"),
                ("A", "0.00", 0, None, "P1"),
                ("C", "90.00", 1, "other period", "P2"),
            ])
            database.commit()
        self.query = self.root / "select.sql"
        self.query.write_text("SELECT code AS record_id, amount_text, enabled, memo FROM facts WHERE period = :period ORDER BY code\n")
        self.parameters = self.root / "parameters.json"
        self.parameters.write_text('{"period":"P1"}')
        self.mapping = self.root / "columns.json"
        self.mapping_value = {
            "id": {"source": "record_id", "type": "text", "null": "error"},
            "amount": {"source": "amount_text", "type": "decimal"},
            "active": {"source": "enabled", "type": "boolean"},
            "memo": {"source": "memo", "type": "text", "null": "omit"},
        }
        self.mapping.write_text(json.dumps(self.mapping_value))
        (self.root / "snapshots").mkdir()
        self.bounds = snapshot.Bounds()

    def extract(self, name="first", bounds=None):
        return snapshot.extract(self.root, self.database, self.query, self.parameters,
                                self.mapping, "records", self.root / "snapshots" / name, bounds or self.bounds)

    def archive(self, name="first"):
        directory = self.root / "snapshots" / name
        return directory, json.loads((directory / "manifest.json").read_text())

    def assert_no_published(self, name="first"):
        self.assertFalse((self.root / "snapshots" / name).exists())
        self.assertEqual(list((self.root / "snapshots").glob(".mantra-snapshot-*")), [])

    def test_exact_decimal_zero_false_absent_keys_and_source_order(self):
        result = self.extract()
        directory, manifest = self.archive()
        data = (directory / "inputs.json").read_bytes()
        self.assertEqual(data, b'{"inputs":{"records":[{"active":false,"amount":0.00,"id":"A"},'
                              b'{"active":true,"amount":2.5000000000000000000000000001,"id":"B","memo":"precise"}]}}\n')
        self.assertEqual(result["snapshotSha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(manifest["snapshot"]["rows"], 2)
        self.assertEqual(manifest["mapping"]["amount"]["null"], "include")
        self.assertEqual(manifest["source"]["database"], "facts.sqlite")
        self.assertEqual(manifest["source"]["schema"][0]["name"], "sqlite_autoindex_facts_1")
        self.assertTrue(snapshot.verify_bundle(self.root, directory, self.bounds)["verified"])

    def test_null_is_explicit_and_distinct_from_zero_false_and_missing(self):
        self.mapping_value["memo"]["null"] = "include"
        self.mapping.write_text(json.dumps(self.mapping_value))
        self.extract()
        data = snapshot.strict_json((self.root / "snapshots/first/inputs.json").read_bytes(), decimals=True)
        first = data["inputs"]["records"][0]
        self.assertIsNone(first["memo"])
        self.assertEqual(first["amount"], Decimal("0.00"))
        self.assertIs(first["active"], False)
        self.mapping_value["memo"]["null"] = "error"
        self.mapping.write_text(json.dumps(self.mapping_value))
        with self.assertRaisesRegex(snapshot.SnapshotError, "null source cell"):
            self.extract("rejected")
        self.assert_no_published("rejected")

    def test_read_only_extraction_is_deterministic_and_existing_same_bundle_is_not_rewritten(self):
        before = self.database.read_bytes()
        first = self.extract()
        directory, _ = self.archive()
        modification = {path.name: path.stat().st_mtime_ns for path in directory.iterdir()}
        repeated = self.extract()
        other = self.extract("second")
        self.assertFalse(repeated["created"])
        self.assertEqual(first["snapshotSha256"], other["snapshotSha256"])
        self.assertEqual(first["manifestSha256"], other["manifestSha256"])
        self.assertEqual(modification, {path.name: path.stat().st_mtime_ns for path in directory.iterdir()})
        self.assertEqual(self.database.read_bytes(), before)

    def test_changed_database_creates_new_identity_and_old_bundle_verifies_without_the_database(self):
        first = self.extract()
        old_directory, old_manifest = self.archive()
        old_files = {path.name: path.read_bytes() for path in old_directory.iterdir()}
        with closing(sqlite3.connect(self.database)) as database:
            database.execute("UPDATE facts SET amount_text = ? WHERE code = ?", ("4.2500", "B"))
            database.commit()
        changed = self.extract("second")
        _, new_manifest = self.archive("second")
        self.assertNotEqual(first["snapshotSha256"], changed["snapshotSha256"])
        self.assertNotEqual(first["extractionSha256"], changed["extractionSha256"])
        self.assertNotEqual(old_manifest["fingerprints"]["database"], new_manifest["fingerprints"]["database"])
        with self.assertRaisesRegex(snapshot.SnapshotError, "Existing snapshot differs"):
            self.extract()
        self.database.unlink()
        self.assertTrue(snapshot.verify_bundle(self.root, old_directory, self.bounds,
                                               first["manifestSha256"])["verified"])
        self.assertEqual(old_files, {path.name: path.read_bytes() for path in old_directory.iterdir()})

    def test_committed_wal_facts_are_captured_before_checkpoint_and_source_is_unchanged(self):
        with closing(sqlite3.connect(self.database)) as writer:
            writer.execute("PRAGMA journal_mode=WAL")
            writer.execute("PRAGMA wal_autocheckpoint=0")
            writer.execute("UPDATE facts SET amount_text = '6.1250' WHERE code='B'")
            writer.commit()
            main_bytes = self.database.read_bytes()
            self.assertTrue(self.database.with_name("facts.sqlite-wal").exists())
            self.extract()
            self.assertIn(b'"amount":6.1250', (self.root / "snapshots/first/inputs.json").read_bytes())
            self.assertEqual(self.database.read_bytes(), main_bytes)

    def test_real_blob_invalid_decimal_and_boolean_are_rejected_without_partial_bundles(self):
        invalid = [
            ("SELECT code AS record_id, 0.1 AS amount_text, enabled, memo FROM facts ORDER BY code", "SQLite REAL"),
            ("SELECT code AS record_id, x'0102' AS amount_text, enabled, memo FROM facts ORDER BY code", "SQLite BLOB"),
            ("SELECT code AS record_id, 'NaN' AS amount_text, enabled, memo FROM facts ORDER BY code", "finite decimal text"),
            ("SELECT code AS record_id, '1e1000000' AS amount_text, enabled, memo FROM facts ORDER BY code", "exponent budget"),
            ("SELECT code AS record_id, amount_text, 2 AS enabled, memo FROM facts ORDER BY code", "integer 0/1"),
        ]
        self.parameters.write_text("{}")
        for sql, message in invalid:
            with self.subTest(message=message):
                self.query.write_text(sql)
                with self.assertRaisesRegex(snapshot.SnapshotError, message):
                    self.extract()
                self.assert_no_published()

    def test_parameters_are_bound_as_values_and_never_interpolated(self):
        self.parameters.write_text(json.dumps({"period": "P1' OR 1=1 --"}))
        result = self.extract()
        self.assertEqual(result["rows"], 0)
        self.assertEqual((self.root / "snapshots/first/inputs.json").read_text(), '{"inputs":{"records":[]}}\n')

    def test_writes_multiple_statements_pragmas_extensions_and_volatile_functions_are_rejected(self):
        before = self.database.read_bytes()
        self.parameters.write_text("{}")
        invalid = [
            "DELETE FROM facts",
            "PRAGMA user_version=20",
            "SELECT code AS record_id, amount_text, enabled, memo FROM facts ORDER BY code; DELETE FROM facts",
            "SELECT code AS record_id, random() AS amount_text, enabled, memo FROM facts ORDER BY code",
            "SELECT code AS record_id, datetime('now') AS amount_text, enabled, memo FROM facts ORDER BY code",
            "SELECT code AS record_id, load_extension('not-loaded') AS amount_text, enabled, memo FROM facts ORDER BY code",
            "WITH x AS (SELECT 1) DELETE FROM facts RETURNING code",
        ]
        for sql in invalid:
            with self.subTest(sql=sql):
                self.query.write_text(sql)
                with self.assertRaises(snapshot.SnapshotError):
                    self.extract()
                self.assert_no_published()
                self.assertEqual(self.database.read_bytes(), before)

    def test_query_requires_outer_ordering_and_unique_aliases_and_existing_source_columns(self):
        invalid = [
            ("SELECT code AS record_id, amount_text, enabled, memo FROM facts", "top-level ORDER BY"),
            ("SELECT code AS record_id, 'ORDER BY code' AS amount_text, enabled, memo FROM facts", "top-level ORDER BY"),
            ("SELECT code AS record_id, amount_text, enabled, memo FROM (SELECT * FROM facts ORDER BY code)", "top-level ORDER BY"),
            ("SELECT code AS record_id, amount_text, enabled, memo, code AS memo FROM facts ORDER BY code", "aliases must be unique"),
            ("SELECT code AS record_id, amount_text, enabled FROM facts ORDER BY code", "absent query alias"),
        ]
        self.parameters.write_text("{}")
        for sql, message in invalid:
            with self.subTest(message=message):
                self.query.write_text(sql)
                with self.assertRaisesRegex(snapshot.SnapshotError, message):
                    self.extract()
                self.assert_no_published()

    def test_comments_quoted_aliases_and_cte_ordering_are_parsed_without_false_rejections(self):
        self.query.write_text("-- ignored ORDER BY fake\nWITH chosen AS (SELECT * FROM facts WHERE period=:period)\n"
                              'SELECT code AS "record_id", amount_text, enabled, memo FROM chosen ORDER BY code')
        self.assertEqual(self.extract()["rows"], 2)

    def test_row_database_output_and_time_budgets_fail_whole_extractions(self):
        for bounds, message in [
            (snapshot.Bounds(max_rows=1), "row budget"),
            (snapshot.Bounds(max_database_bytes=100), "Database image byte budget"),
            (snapshot.Bounds(max_bytes=100), "byte budget|too big"),
            (snapshot.Bounds(timeout=0.000001), "time budget"),
        ]:
            with self.subTest(message=message):
                with self.assertRaisesRegex(snapshot.SnapshotError, message):
                    self.extract(bounds=bounds)
                self.assert_no_published()

    def test_expensive_recursive_query_is_interrupted_by_deadline_not_truncated(self):
        self.parameters.write_text("{}")
        self.query.write_text("WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<1000000000) "
                              "SELECT 'A' AS record_id, sum(n) AS amount_text, 1 AS enabled, 'large' AS memo FROM seq ORDER BY 1")
        with self.assertRaisesRegex(snapshot.SnapshotError, "time budget"):
            self.extract(bounds=snapshot.Bounds(timeout=0.05))
        self.assert_no_published()

    def test_sqlite_generated_values_cannot_bypass_the_output_byte_budget(self):
        self.parameters.write_text("{}")
        self.query.write_text("SELECT code AS record_id, zeroblob(100000000) AS amount_text, enabled, memo FROM facts ORDER BY code")
        with self.assertRaisesRegex(snapshot.SnapshotError, "too big"):
            self.extract(bounds=snapshot.Bounds(max_bytes=4096))
        self.assert_no_published()

    def test_strict_json_rejects_duplicate_keys_nonfinite_floats_and_invalid_descriptors(self):
        for content in ('{"period":"P1","period":"P2"}', '{"period":0.1}', '{"period":NaN}', '[]'):
            with self.subTest(parameters=content):
                self.parameters.write_text(content)
                with self.assertRaises(snapshot.SnapshotError):
                    self.extract()
                self.assert_no_published()
        self.parameters.write_text('{"period":"P1"}')
        self.mapping.write_text('{"amount":{"source":"amount_text","type":"decimal","extra":true}}')
        with self.assertRaisesRegex(snapshot.SnapshotError, "Invalid mapping descriptor"):
            self.extract()
        self.assert_no_published()
        for descriptor in ({"source": "amount_text", "type": []},
                           {"source": "amount_text", "type": "decimal", "null": {}}):
            self.mapping.write_text(json.dumps({"amount": descriptor}))
            with self.assertRaises(snapshot.SnapshotError):
                self.extract()
            self.assert_no_published()

    def test_paths_cannot_escape_follow_symlinks_or_replace_source_database(self):
        outside = self.root.parent / "outside.sqlite"
        for supplied in (outside, self.root / "snapshots/../facts.sqlite"):
            with self.subTest(path=supplied):
                with self.assertRaises(snapshot.SnapshotError):
                    snapshot.protected_path(self.root, supplied)
        linked = self.root / "linked.sqlite"
        linked.symlink_to(self.database)
        with self.assertRaisesRegex(snapshot.SnapshotError, "Symlink"):
            snapshot.extract(self.root, linked, self.query, self.parameters, self.mapping,
                             "records", self.root / "snapshots/first", self.bounds)
        redirected = self.root / "redirected"
        redirected.symlink_to(self.root / "snapshots", target_is_directory=True)
        with self.assertRaisesRegex(snapshot.SnapshotError, "Symlink"):
            snapshot.extract(self.root, self.database, self.query, self.parameters, self.mapping,
                             "records", redirected / "first", self.bounds)
        with self.assertRaisesRegex(snapshot.SnapshotError, "Output cannot"):
            snapshot.extract(self.root, self.database, self.query, self.parameters, self.mapping,
                             "records", self.root, self.bounds)
        self.assert_no_published()

    def test_write_failure_and_destination_race_leave_no_published_half_bundle(self):
        actual_write = snapshot.synced_write

        def fail_second(path, content):
            if path.name == "manifest.json":
                raise OSError("fictional disk failure")
            actual_write(path, content)

        with mock.patch.object(snapshot, "synced_write", side_effect=fail_second):
            with self.assertRaisesRegex(OSError, "disk failure"):
                self.extract()
        self.assert_no_published()
        directory = self.root / "snapshots/first"

        def concurrent_publish(path, content):
            actual_write(path, content)
            if path.name == "manifest.json":
                directory.mkdir()
                (directory / "unrelated.txt").write_text("preserve another writer")

        with mock.patch.object(snapshot, "synced_write", side_effect=concurrent_publish):
            with self.assertRaisesRegex(snapshot.SnapshotError, "appeared during extraction"):
                self.extract()
        self.assertEqual((directory / "unrelated.txt").read_text(), "preserve another writer")
        self.assertFalse((directory / "inputs.json").exists())
        self.assertEqual(list((self.root / "snapshots").glob(".mantra-snapshot-*")), [])

    def test_snapshot_manifest_or_expected_digest_tampering_is_detected(self):
        result = self.extract()
        directory, manifest = self.archive()
        with self.assertRaisesRegex(snapshot.SnapshotError, "expected SHA-256"):
            snapshot.verify_bundle(self.root, directory, self.bounds, "0" * 64)
        data_path = directory / "inputs.json"
        original = data_path.read_bytes()
        data_path.write_bytes(original.replace(b"0.00", b"1.00"))
        with self.assertRaisesRegex(snapshot.SnapshotError, "SHA-256"):
            snapshot.verify_bundle(self.root, directory, self.bounds)
        data_path.write_bytes(original)
        manifest["query"]["parameters"]["period"] = "P2"
        (directory / "manifest.json").write_bytes(snapshot.encode_json(manifest))
        with self.assertRaisesRegex(snapshot.SnapshotError, "fingerprint mismatch"):
            snapshot.verify_bundle(self.root, directory, self.bounds)
        self.assertTrue(result["verified"])

    def test_cli_extract_and_verify_are_standalone_without_extra_dependencies(self):
        arguments = [sys.executable, str(SCRIPTS / "snapshot-sqlite.py"), "extract", "--root", str(self.root),
                     "--database", "facts.sqlite", "--query-file", "select.sql", "--params", "parameters.json",
                     "--mapping", "columns.json", "--input", "records", "--out", "snapshots/cli"]
        extracted = subprocess.run(arguments, text=True, capture_output=True, check=True, timeout=5)
        result = json.loads(extracted.stdout)
        verified = subprocess.run([sys.executable, str(SCRIPTS / "snapshot-sqlite.py"), "verify", "--root", str(self.root),
                                   "--snapshot", "snapshots/cli", "--expected-manifest-sha256", result["manifestSha256"]],
                                  text=True, capture_output=True, check=True, timeout=5)
        self.assertEqual(json.loads(verified.stdout)["snapshotSha256"], result["snapshotSha256"])
        self.assertEqual(extracted.stderr, "")

    @unittest.skipUnless(os.name == "posix", "POSIX signal cleanup")
    def test_interrupted_cli_cleans_private_database_and_does_not_publish_partial_bundle(self):
        self.query.write_text("WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<1000000000) "
                              "SELECT 'A' AS record_id, sum(n) AS amount_text, 1 AS enabled, 'large' AS memo FROM seq ORDER BY 1")
        self.parameters.write_text("{}")
        for interruption in (signal.SIGTERM, signal.SIGINT):
            with self.subTest(signal=interruption):
                process = subprocess.Popen([sys.executable, str(SCRIPTS / "snapshot-sqlite.py"), "extract", "--root", str(self.root),
                                            "--database", "facts.sqlite", "--query-file", "select.sql", "--mapping", "columns.json",
                                            "--input", "records", "--out", "snapshots/interrupted"],
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    deadline = time.monotonic() + 3
                    while not list((self.root / "snapshots").glob(".mantra-snapshot-*/captured.sqlite")):
                        self.assertIsNone(process.poll(), "Extractor exited before signal test")
                        self.assertLess(time.monotonic(), deadline)
                        time.sleep(0.01)
                    process.send_signal(interruption)
                    stdout, stderr = process.communicate(timeout=3)
                    self.assertEqual(process.returncode, 130, stderr)
                    self.assertEqual(stdout, "")
                    self.assertIn("Snapshot interrupted", stderr)
                    self.assert_no_published("interrupted")
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.communicate()

    @unittest.skipUnless(os.environ.get("MANTRA_SNAPSHOT_TEST_CLI"), "Set MANTRA_SNAPSHOT_TEST_CLI for real engine equivalence")
    def test_real_mantra_cli_values_and_input_diagnostics_match_sqlite_csv_and_json(self):
        # All three channels receive the same finite decimal tokens, zero/false and record order.
        with closing(sqlite3.connect(self.database)) as database:
            database.execute("UPDATE facts SET memo='zero' WHERE code='A'")
            database.commit()
        self.extract()
        (self.root / "direct.json").write_text(
            '{"inputs":{"records":[{"id":"A","amount":0.00,"active":false,"memo":"zero"},'
            '{"id":"B","amount":2.5000000000000000000000000001,"active":true,"memo":"precise"}]}}\n')
        (self.root / "direct.csv").write_text("id;amount;active;memo\nA;0,00;false;zero\n"
                                              "B;2,5000000000000000000000000001;true;precise\n")
        (self.root / "schema.mantra").write_text('''(schema snapshot/example
  {:title "Fictional source equivalence" :version "1.0.0" :mainline [summary]}
  (input records :table {:columns {:id :keyword :amount :decimal :active :boolean :memo :text}})
  (section summary "Supplied facts" {:op :info :panel true}
    (info amount-total "Exact amount" (table/sum-where records {} :amount))
    (info inactive-count "Inactive rows" (table/count-where records {:active false}) {:type :integer})))
''')
        sources = {
            "sqlite": '(json {:path "snapshots/first/inputs.json"})',
            "json": '(json {:path "direct.json"})',
            "csv": '(csv {:path "direct.csv" :input "records"})',
        }
        cases = []
        for name, source in sources.items():
            case = self.root / f"case-{name}.mantra"
            case.write_text(f'(case {name} {{:schema "snapshot/example"}} (sources {source}))\n')
            cases.append(str(case))
        output = self.root / "engine"
        result = subprocess.run([os.environ["MANTRA_SNAPSHOT_TEST_CLI"], "fixtures", *cases,
                                 "--workspace", str(self.root), "--out", str(output)],
                                text=True, capture_output=True, timeout=45)
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        index = json.loads((output / "index.json").read_text())

        def normalized(value):
            if isinstance(value, dict):
                # Channel labels intentionally differ; source provenance is retained by the engine.
                result = {key: normalized(entry) for key, entry in value.items() if key not in {"source", "origin"}}
                if set(result) == {"map"}:
                    result["map"] = sorted(result["map"], key=lambda pair: json.dumps(pair[0], sort_keys=True))
                return result
            return [normalized(entry) for entry in value] if isinstance(value, list) else value

        values = []
        diagnostics = []
        origins = set()
        for entry in index["cases"]:
            run = json.loads((output / entry["files"]["run"].removeprefix("/fixtures/")).read_text())["data"]
            self.assertTrue(run["succeeded"], run)
            self.assertEqual(run["values"]["amount-total"][""]["value"], {"n": "2.5000000000000000000000000001"})
            self.assertEqual(run["values"]["inactive-count"][""]["value"], {"n": "1"})
            origins.add(run["values"]["records"][""]["origin"])
            values.append(normalized(run["values"]))
            diagnostics.append(json.loads((output / entry["files"]["diagnostics"].removeprefix("/fixtures/")).read_text())["data"])
        self.assertEqual(len(values), 3)
        self.assertEqual(values[0], values[1])
        self.assertEqual(values[0], values[2])
        self.assertEqual(origins, {"source:json:inputs.json", "source:json:direct.json", "source:csv:direct.csv"})
        self.assertEqual(diagnostics, [{"diagnostics": []}] * 3)


if __name__ == "__main__":
    unittest.main()
