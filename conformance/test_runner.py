"""Runner checks do not invoke Mantra or derive corpus answers from any adapter."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("corpus_runner", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunnerTest(unittest.TestCase):
    def test_decimal_value_equality_preserves_types_and_map_order(self):
        self.assertEqual(runner.canonical_value({"n": "1.00"}), runner.canonical_value({"n": "1"}))
        distinct = [None, False, "0", {"n": "0"}, {"kw": "0"}]
        self.assertEqual(5, len({runner.canonical_value(value) for value in distinct}))
        entries = [[{"kw": "A"}, {"n": "1"}], ["A", {"n": "2"}]]
        self.assertNotEqual(runner.canonical_value({"map": entries}), runner.canonical_value({"map": list(reversed(entries))}))
        for value in ({"n": 0}, {"n": "NaN"}, {"n": "Infinity"}, {"n": "1", "kw": "one"}, {"n": "1_0"}, {"n": " 1 "}, 0):
            with self.assertRaises(ValueError):
                runner.canonical_value(value)

    def test_duplicate_diagnostic_counts_and_sorted_effects_are_significant(self):
        finding = dict(code="MANTRA-CHECK-FAILED", category="business", severity="warning", effect="warning")
        response = dict(succeeded=True, validationPassed=True, values={}, diagnostics=[finding])
        repeated = {**response, "diagnostics": [finding, finding]}
        self.assertNotEqual(runner.canonical_response(response), runner.canonical_response(repeated))
        with self.assertRaises(ValueError):
            runner.canonical_response({**response, "diagnostics": [{**finding, "effect": "technical-failure"}]})
        with self.assertRaises(ValueError):
            json.loads('{"succeeded":true,"succeeded":false}', object_pairs_hook=runner.unique_object)

    def test_frozen_source_and_expected_bytes_are_both_verified(self):
        original = Path(__file__).parent
        self.assertEqual(24, len(runner.verify_corpus(original)["vectors"]))
        for relative in ("vectors/01-typed-facts/schema.mantra", "vectors/01-typed-facts/expected.json"):
            with tempfile.TemporaryDirectory(prefix="mantra-conformance-inventory-") as directory:
                temporary = Path(directory)
                shutil.copytree(original / "vectors", temporary / "vectors")
                shutil.copy2(original / "manifest.json", temporary / "manifest.json")
                with (temporary / relative).open("ab") as target:
                    target.write(b" ")
                with self.assertRaisesRegex(ValueError, "Frozen byte inventory mismatch"):
                    runner.verify_corpus(temporary)

    def test_unlisted_vector_document_cannot_escape_inventory(self):
        original = Path(__file__).parent
        with tempfile.TemporaryDirectory(prefix="mantra-conformance-inventory-") as directory:
            temporary = Path(directory)
            shutil.copytree(original / "vectors", temporary / "vectors")
            shutil.copy2(original / "manifest.json", temporary / "manifest.json")
            (temporary / "vectors/unlisted.mantra").write_text("(case unlisted)\n")
            with self.assertRaisesRegex(ValueError, "unlisted"):
                runner.verify_corpus(temporary)


if __name__ == "__main__":
    unittest.main()
