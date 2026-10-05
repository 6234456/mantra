"""Verify identity/key boundaries without creating a key or invoking a build."""
import base64
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
from tempfile import TemporaryDirectory
import subprocess
import json

SPEC = importlib.util.spec_from_file_location("mantra_prepare_release", Path(__file__).parents[1] / "prepare-central-release.py")
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)
FINGERPRINT = "A" * 40
KEY = "-----BEGIN PGP PRIVATE KEY BLOCK-----\ntest-only\n-----END PGP PRIVATE KEY BLOCK-----\n"


class PreparationBoundaryTest(unittest.TestCase):
    def test_only_exact_stable_identity_is_accepted(self):
        prepare.validate_identity("1.0.0", "a" * 40, FINGERPRINT)
        for version, commit, fingerprint in [("1.0.0-rc.1", "a" * 40, FINGERPRINT),
                ("01.0.0", "a" * 40, FINGERPRINT), ("1.0.0", "main", FINGERPRINT),
                ("1.0.0", "a" * 40, "A" * 16)]:
            with self.subTest(version=version, commit=commit), self.assertRaises(ValueError):
                prepare.validate_identity(version, commit, fingerprint)

    def test_private_key_input_is_bounded_and_never_accepts_extra_blocks(self):
        self.assertEqual(KEY, prepare.signing_key(KEY))
        encoded = base64.b64encode(KEY.encode()).decode()
        self.assertEqual(KEY, prepare.signing_key(encoded[:20] + "\n" + encoded[20:]))
        for value in ["not-a-key", KEY + KEY, KEY + "extra", "x" * (128 * 1024 + 1)]:
            with self.subTest(length=len(value)), self.assertRaises(ValueError):
                prepare.signing_key(value)

    def test_child_environment_excludes_both_secret_aliases_and_source_selection(self):
        names = [*prepare.SECRET_NAMES, "MANTRA_SIGNING_KEY", "MANTRA_CENTRAL_PASSWORD", "NORMEIN_BUILD_PATH"]
        environment = {name: "never-forward" for name in names} | {"PATH": "/installed/tools"}
        self.assertEqual({"PATH": "/installed/tools"}, prepare.without_secrets(environment))

    def listing(self, kind, validity, expiry, capabilities, fingerprint=FINGERPRINT):
        fields = [kind, validity, "2048", "1", "SHORTID", "0", expiry, "", "", "", "", capabilities, "", "", ""]
        return ":".join(fields) + "\nfpr:::::::::" + fingerprint + ":\n"

    def test_expired_revoked_disabled_and_aggregate_only_signing_keys_are_rejected(self):
        self.assertEqual({FINGERPRINT}, prepare.signing_fingerprints(self.listing("sec", "u", "200", "scSC"), 100))
        for validity, expiry, capabilities in [("r", "200", "s"), ("u", "50", "s"),
                ("u", "200", "sD"), ("u", "200", "SC"), ("i", "200", "s")]:
            with self.subTest(validity=validity, expiry=expiry, capabilities=capabilities):
                self.assertEqual(set(), prepare.signing_fingerprints(self.listing("sec", validity, expiry, capabilities), 100))
        revoked_primary = self.listing("sec", "r", "200", "cSC")
        subkey = self.listing("ssb", "u", "200", "s", "B" * 40)
        self.assertEqual(set(), prepare.signing_fingerprints(revoked_primary + subkey, 100))
        usable_primary = self.listing("sec", "u", "200", "cSC")
        self.assertEqual({"B" * 40}, prepare.signing_fingerprints(usable_primary + subkey, 100))
        self.assertEqual("B" * 40, prepare.select_signing_fingerprint(usable_primary + subkey, FINGERPRINT, 100))
        self.assertEqual("B" * 40, prepare.select_signing_fingerprint(usable_primary + subkey, "B" * 40, 100))
        with self.assertRaises(ValueError):
            prepare.select_signing_fingerprint(revoked_primary + subkey, FINGERPRINT, 100)
        with self.assertRaises(ValueError):
            prepare.select_signing_fingerprint(usable_primary + subkey + self.listing("ssb", "u", "200", "s", "C" * 40), FINGERPRINT, 100)

    def test_untracked_source_refuses_signing_before_output_or_secret_read(self):
        with TemporaryDirectory() as temporary:
            output = Path(temporary) / "not-created"
            replies = [subprocess.CompletedProcess([], 0, "a" * 40 + "\n"),
                       subprocess.CompletedProcess([], 0, "?? mantra-core/src/main/kotlin/Injected.kt\n")]
            with mock.patch.object(prepare.subprocess, "run", side_effect=replies) as process, \
                    mock.patch.dict(prepare.os.environ, {}, clear=True):
                with self.assertRaisesRegex(ValueError, "exact clean source commit"):
                    prepare.prepare(Path(temporary), "1.0.0", "a" * 40, FINGERPRINT, output)
                self.assertIn("--untracked-files=all", process.call_args_list[1].args[0])
            self.assertFalse(output.exists())

    def test_cleanup_failure_preserves_failed_receipt_even_after_signing_preparation_error(self):
        for cleanup_timeout in [False, True]:
            with self.subTest(timeout=cleanup_timeout), TemporaryDirectory() as temporary:
                output = Path(temporary) / "failed-output"

                def fake_process(arguments, **options):
                    if arguments[0] == "git":
                        return subprocess.CompletedProcess(arguments, 0,
                            "a" * 40 + "\n" if "rev-parse" in arguments else "")
                    if "--kill" in arguments and cleanup_timeout:
                        raise subprocess.TimeoutExpired(arguments, 30)
                    return subprocess.CompletedProcess(arguments, 1, b"", b"not-logged")

                with mock.patch.object(prepare.subprocess, "run", side_effect=fake_process), \
                        mock.patch.object(prepare.shutil, "which", side_effect=lambda name: "/installed/" + name), \
                        mock.patch.dict(prepare.os.environ, {"MANTRA_SIGNING_KEY": KEY,
                            "MANTRA_SIGNING_PASSWORD": "synthetic-only"}, clear=True):
                    with self.assertRaisesRegex(RuntimeError, "cleanup is incomplete"):
                        prepare.prepare(Path(temporary), "1.0.0", "a" * 40, FINGERPRINT, output)
                receipt = json.loads((output / "preparation-receipt.json").read_text())
                self.assertEqual("PREPARATION_FAILED", receipt["status"])
                self.assertTrue(receipt["cleanupFailure"])
                self.assertFalse(receipt["temporaryAgentStopped"])
                self.assertTrue(receipt["temporaryKeyAndStagingRemoved"])
                self.assertNotIn("synthetic-only", json.dumps(receipt))

    @unittest.skipUnless(prepare.os.name == "posix", "GnuPG UNIX socket path regression")
    def test_long_tmpdir_does_not_extend_gnupg_home_and_failed_import_cleans_it(self):
        with TemporaryDirectory() as temporary:
            output = Path(temporary) / "failed-output"
            long_parent = Path(temporary) / ("long-inherited-tmpdir-" * 6)
            long_parent.mkdir()
            homes = []

            def fake_process(arguments, **options):
                if arguments[0] == "git":
                    return subprocess.CompletedProcess(arguments, 0,
                        "a" * 40 + "\n" if "rev-parse" in arguments else "")
                home = Path(arguments[arguments.index("--homedir") + 1])
                homes.append(home)
                self.assertTrue(home.is_dir())
                self.assertEqual(home.parent.parent, Path("/tmp").resolve())
                self.assertEqual(home.stat().st_mode & 0o777, 0o700)
                self.assertFalse(home.is_relative_to(long_parent))
                if "--import" in arguments:
                    return subprocess.CompletedProcess(arguments, 1, b"", b"synthetic import failure")
                self.assertIn("--kill", arguments)
                return subprocess.CompletedProcess(arguments, 0, b"", b"")

            with mock.patch.object(prepare.subprocess, "run", side_effect=fake_process), \
                    mock.patch.object(prepare.shutil, "which", side_effect=lambda name: "/installed/" + name), \
                    mock.patch.object(prepare.tempfile, "tempdir", str(long_parent)), \
                    mock.patch.dict(prepare.os.environ, {"TMPDIR": str(long_parent), "MANTRA_SIGNING_KEY": KEY,
                        "MANTRA_SIGNING_PASSWORD": "synthetic-only"}, clear=True), \
                    mock.patch.object(prepare, "command") as build:
                with self.assertRaisesRegex(RuntimeError, "preparation failed; cleanup receipt retained"):
                    prepare.prepare(Path(temporary), "1.0.0", "a" * 40, FINGERPRINT, output)
                build.assert_not_called()
            self.assertEqual(len(homes), 2)
            self.assertEqual(homes[0], homes[1])
            self.assertFalse(homes[0].parent.exists())
            receipt = json.loads((output / "preparation-receipt.json").read_text())
            self.assertEqual(receipt["status"], "PREPARATION_FAILED")
            self.assertTrue(receipt["temporaryAgentStopped"])
            self.assertTrue(receipt["temporaryKeyAndStagingRemoved"])
            self.assertFalse(receipt["published"])
            self.assertNotIn("synthetic-only", json.dumps(receipt))
