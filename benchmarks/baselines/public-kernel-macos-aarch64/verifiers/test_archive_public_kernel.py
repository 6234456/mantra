"""Small synthetic verifier checks only; no engine, real performance data or build invocation."""
import hashlib
import json
from pathlib import Path
import runpy
import tempfile
import unittest

API = runpy.run_path(str(Path(__file__).with_name("archive_public_kernel.py")))
Failure = API["VerificationError"]


def record(index=0, value="1.00", reference=False):
    result = {"index": index, "caseId": f"lease-batch-{index + 1:05d}", "values": {"node[P1]": value}}
    if reference:
        result.update(validationPassed=True, expectedBusiness=[])
    return result


class EvidencePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mantra-public-evidence-test-", dir="/private/tmp")
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)

    def write_rows(self, actual, expected, suffix=b""):
        one, two = self.root / "actual.jsonl", self.root / "expected.jsonl"
        for path, rows in ((one, actual), (two, expected)):
            path.write_bytes(b"".join((json.dumps(row) + "\n").encode() for row in rows) + suffix)
        return one, two

    def compare(self, actual, expected, count=1):
        one, two = self.write_rows(actual, expected)
        return API["compare_streams"](one, two, count=count, values_per_case=1)

    def test_exact_decimal_and_independent_stream_hashes(self):
        one, two = self.write_rows([record(value="1.00"), record(1, "-0.00")],
                                   [record(value="1", reference=True), record(1, "0", True)])
        result = API["compare_streams"](one, two, count=2, values_per_case=1)
        self.assertEqual(2, result["distinctCaseIds"])
        self.assertEqual(2, result["independentNumericComparisons"])
        self.assertEqual(hashlib.sha256(one.read_bytes()).hexdigest(), result["actualSha256"])
        self.assertEqual(hashlib.sha256(two.read_bytes()).hexdigest(), result["referenceSha256"])

    def test_decimal_difference_fails(self):
        with self.assertRaisesRegex(Failure, "Exact Decimal mismatch"):
            self.compare([record(value="1.0000000000000000001")], [record(value="1", reference=True)])

    def test_nonfinite_decimal_fails(self):
        for value in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(value=value), self.assertRaisesRegex(Failure, "Non-finite decimal"):
                self.compare([record(value=value)], [record(reference=True)])

    def test_missing_and_extra_keys_fail(self):
        for values in ({}, {"node[P1]": "1", "unexpected": "0"}):
            actual = record()
            actual["values"] = values
            with self.subTest(values=values), self.assertRaisesRegex(Failure, "Numeric key set differs"):
                self.compare([actual], [record(reference=True)])

    def test_wrong_or_repeated_id_fails(self):
        actual = record(1)
        actual["caseId"] = "lease-batch-00001"
        with self.assertRaisesRegex(Failure, "Case ID mismatch"):
            self.compare([record(), actual], [record(reference=True), record(1, reference=True)], 2)

    def test_boolean_index_fails(self):
        actual = record()
        actual["index"] = False
        with self.assertRaisesRegex(Failure, "Record index mismatch"):
            self.compare([actual], [record(reference=True)])

    def test_failed_reference_business_status_fails(self):
        expected = record(reference=True)
        expected["validationPassed"] = False
        with self.assertRaisesRegex(Failure, "BUSINESS status"):
            self.compare([record()], [expected])

    def test_duplicate_json_key_is_not_last_value_wins(self):
        with self.assertRaisesRegex(Failure, "Duplicate JSON property"):
            API["parse_json"](b'{"a":1,"a":2}')

    def test_json_nonfinite_literal_fails(self):
        with self.assertRaisesRegex(Failure, "Non-finite JSON literal"):
            API["parse_json"](b'{"a":NaN}')

    def test_trailing_record_fails(self):
        one, two = self.write_rows([record(), record(1)], [record(reference=True), record(1, reference=True)])
        with self.assertRaisesRegex(Failure, "trailing JSONL"):
            API["compare_streams"](one, two, count=1, values_per_case=1)

    def test_oversized_record_fails(self):
        one, two = self.write_rows([record()], [record(reference=True)])
        one.write_bytes(b" " * (API["LINE_LIMIT"] + 1) + b"\n")
        with self.assertRaisesRegex(Failure, "oversized JSONL"):
            API["compare_streams"](one, two, count=1, values_per_case=1)

    def test_missing_newline_fails(self):
        one, two = self.write_rows([record()], [record(reference=True)])
        one.write_bytes(one.read_bytes().removesuffix(b"\n"))
        with self.assertRaisesRegex(Failure, "missing record newline"):
            API["compare_streams"](one, two, count=1, values_per_case=1)

    def test_invalid_utf8_fails(self):
        with self.assertRaises(UnicodeDecodeError):
            API["parse_json"](b'{"a":"\xff"}')

    def test_source_hash_includes_paths_and_bytes(self):
        for module in API["SOURCE_MODULES"]:
            (self.root / module).mkdir()
        (self.root / "normein-build.lock").write_text("baseline=historical\n")
        source = self.root / "mantra-core/a.kt"
        source.write_text("source\n")
        first = API["fingerprint"](self.root)
        source.rename(source.with_name("b.kt"))
        second = API["fingerprint"](self.root)
        self.assertNotEqual(first["libraryAndHarnessSourceSha256"], second["libraryAndHarnessSourceSha256"])
        source = source.with_name("b.kt")
        source.write_text("changed\n")
        third = API["fingerprint"](self.root)
        self.assertNotEqual(second["libraryAndHarnessSourceSha256"], third["libraryAndHarnessSourceSha256"])

    def test_archive_is_complete_and_preserves_historical_file(self):
        parent = self.root / "benchmarks/baselines"
        parent.mkdir(parents=True)
        history = parent / "historical.txt"
        history.write_bytes(b"historical unchanged")
        source = self.root / "input.txt"
        source.write_text("small evidence\n")
        inventory = {"original/paper.txt": {"bytes": source.stat().st_size, "sha256": API["digest"](source)}}
        result = API["archive"](self.root, {"original/paper.txt": source}, inventory, {"report.json": {"status": "PASS"}})
        archive = self.root / result["path"]
        manifest = API["small_json"](archive / "archive-manifest.json")
        self.assertEqual(source.read_bytes(), (archive / "original/paper.txt").read_bytes())
        self.assertIn("report.json", manifest["files"])
        self.assertEqual(b"historical unchanged", history.read_bytes())
        self.assertFalse(list(parent.glob(".public-kernel-evidence-*")))
        with self.assertRaisesRegex(Failure, "Archive already exists"):
            API["archive"](self.root, {}, {}, {})
        self.assertEqual(b"historical unchanged", history.read_bytes())

    def test_changed_archive_input_never_publishes(self):
        parent = self.root / "benchmarks/baselines"
        parent.mkdir(parents=True)
        source = self.root / "input.txt"
        source.write_text("new content")
        inventory = {"paper.txt": {"bytes": 1, "sha256": "0" * 64}}
        with self.assertRaisesRegex(Failure, "Evidence changed"):
            API["archive"](self.root, {"paper.txt": source}, inventory, {})
        self.assertFalse((parent / "public-kernel-macos-aarch64").exists())
        self.assertFalse(list(parent.glob(".public-kernel-evidence-*")))


if __name__ == "__main__":
    unittest.main()
