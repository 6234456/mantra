"""Exercise the workflow's source gate offline; never read release secrets or call GitHub."""
import os
from pathlib import Path
from unittest import TestCase, mock


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/publish.yml"


def source_gate():
    lines = WORKFLOW.read_text().splitlines()
    start = next(index for index, line in enumerate(lines)
                 if 'cat > "$RUNNER_TEMP/mantra-central-source-gate.py"' in line)
    end = next(index for index in range(start + 1, len(lines)) if lines[index].strip() == "PY")
    code = "\n".join(line[10:] for line in lines[start + 1:end])
    namespace = {"__name__": "central_source_gate_test"}
    exec(compile(code, str(WORKFLOW), "exec"), namespace)
    return namespace


class PublishSourceGateTest(TestCase):
    def setUp(self):
        self.gate = source_gate()
        self.source = "a" * 40
        self.environment = {
            "GITHUB_REPOSITORY": "6234456/mantra", "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": self.source, "SOURCE_COMMIT": self.source,
            "RELEASE_VERSION": "1.0.0", "SIGNER_FINGERPRINT": "B" * 40, "SIGNER_POLICY": "B" * 40,
            "GITHUB_TOKEN": "test-only-read-token",
        }

    def run_record(self, **changes):
        result = {"id": 101, "run_number": 10, "run_attempt": 1, "head_sha": self.source,
                  "head_branch": "main", "event": "push", "status": "completed", "conclusion": "success",
                  "head_repository": {"full_name": "6234456/mantra"},
                  "html_url": "https://github.com/6234456/mantra/actions/runs/101"}
        result.update(changes)
        return result

    def verify(self, runs, final_source=None):
        responses = [{"commit": {"sha": self.source}}, {"workflow_runs": runs},
                     {"commit": {"sha": final_source or self.source}}]
        invocation = mock.Mock(side_effect=responses)
        with mock.patch.dict(os.environ, self.environment, clear=True), \
                mock.patch.dict(self.gate, {"fetch": invocation}):
            result = self.gate["verify_source"](self.source)
        return result, invocation

    def test_exact_main_with_matching_policy_and_green_ci_records_public_identity(self):
        with mock.patch.dict(os.environ, self.environment, clear=True):
            self.assertEqual(self.gate["validate_inputs"](), self.source)
        receipt, invocation = self.verify([self.run_record()])
        self.assertEqual(receipt["sourceCommit"], self.source)
        self.assertEqual(receipt["ciRunId"], 101)
        self.assertFalse(receipt["published"])
        self.assertEqual(len(invocation.call_args_list), 3)
        self.assertIn("head_sha=" + self.source, invocation.call_args_list[1].args[0])

    def test_untrusted_ref_revision_injection_or_policy_mismatch_fails_before_fetch(self):
        rejected = [("GITHUB_REPOSITORY", "someone/fork"), ("GITHUB_REF", "refs/heads/feature"),
                    ("SOURCE_COMMIT", "b" * 40), ("SOURCE_COMMIT", self.source + "$(secret)"),
                    ("RELEASE_VERSION", "1.0.0; echo secret"), ("RELEASE_VERSION", "1.0.0-rc.1"),
                    ("SIGNER_POLICY", ""), ("SIGNER_FINGERPRINT", "C" * 40)]
        for name, value in rejected:
            with self.subTest(name=name, value=value), \
                    mock.patch.dict(os.environ, {**self.environment, name: value}, clear=True), \
                    mock.patch.dict(self.gate, {"fetch": mock.Mock()}) as _:
                with self.assertRaises(ValueError):
                    self.gate["validate_inputs"]()
                self.gate["fetch"].assert_not_called()

    def test_older_green_run_cannot_override_newer_failed_running_or_cancelled_ci(self):
        for changes in [{"conclusion": "failure"}, {"conclusion": "cancelled"},
                        {"status": "in_progress", "conclusion": None}]:
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "latest exact-main CI"):
                self.verify([self.run_record(), self.run_record(id=102, run_number=11, **changes)])

    def test_latest_failed_attempt_cannot_reuse_success_of_earlier_attempt(self):
        with self.assertRaisesRegex(ValueError, "latest exact-main CI"):
            self.verify([self.run_record(), self.run_record(run_attempt=2, conclusion="failure")])

    def test_foreign_pull_request_or_wrong_revision_ci_does_not_qualify(self):
        for changes in [{"head_sha": "b" * 40}, {"head_branch": "feature"}, {"event": "pull_request"},
                        {"head_repository": {"full_name": "someone/fork"}}]:
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "No exact-main CI"):
                self.verify([self.run_record(**changes)])

    def test_main_update_during_ci_validation_rejects_signed_preparation(self):
        with self.assertRaisesRegex(ValueError, "main changed"):
            self.verify([self.run_record()], final_source="b" * 40)

    def test_network_redirects_cannot_move_read_token_to_another_origin(self):
        with self.assertRaisesRegex(ValueError, "redirects are refused"):
            self.gate["NoRedirect"]().redirect_request(None, None, 302, "Moved", {}, "https://other.invalid/")
