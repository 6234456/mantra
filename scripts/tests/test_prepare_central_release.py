"""Verify identity/key boundaries without creating a key or invoking a build."""
import base64
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
from tempfile import TemporaryDirectory
import subprocess
import json
import sys

HELPER_SPEC = importlib.util.spec_from_file_location("release_processes", Path(__file__).parents[1] / "release_processes.py")
HELPER = importlib.util.module_from_spec(HELPER_SPEC)
sys.modules[HELPER_SPEC.name] = HELPER
HELPER_SPEC.loader.exec_module(HELPER)

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

    def installed_gradle(self, temporary):
        home = Path(temporary) / "existing-gradle-cache"
        executable = home / "wrapper/dists/gradle-9.4.0-bin/installed/gradle-9.4.0/bin/gradle"
        executable.parent.mkdir(parents=True)
        executable.touch()
        return home, executable

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
                existing_home, _ = self.installed_gradle(temporary)

                def fake_process(arguments, **options):
                    if arguments[0] == "git":
                        return subprocess.CompletedProcess(arguments, 0,
                            "a" * 40 + "\n" if "rev-parse" in arguments else "")
                    if "--kill" in arguments and cleanup_timeout:
                        raise subprocess.TimeoutExpired(arguments, 30)
                    return subprocess.CompletedProcess(arguments, 1, b"", b"not-logged")

                with mock.patch.object(prepare.subprocess, "run", side_effect=fake_process), \
                        mock.patch.object(prepare.shutil, "which", side_effect=lambda name: "/installed/" + name), \
                        mock.patch.object(prepare.processes, "stop_gradle", return_value={"daemonPids": []}), \
                        mock.patch.dict(prepare.os.environ, {"MANTRA_SIGNING_KEY": KEY,
                            "MANTRA_SIGNING_PASSWORD": "synthetic-only", "GRADLE_USER_HOME": str(existing_home)}, clear=True):
                    with self.assertRaisesRegex(RuntimeError, "cleanup is incomplete"):
                        prepare.prepare(Path(temporary), "1.0.0", "a" * 40, FINGERPRINT, output)
                receipt = json.loads((output / "preparation-receipt.json").read_text())
                self.assertEqual("PREPARATION_FAILED", receipt["status"])
                self.assertTrue(receipt["cleanupFailure"])
                self.assertFalse(receipt["temporaryAgentStopped"])
                self.assertFalse(receipt["temporaryKeyAndStagingRemoved"])
                self.assertNotIn("synthetic-only", json.dumps(receipt))
                # The intentionally failed cleanup retained its task; this is a
                # mocked GPG run, so remove only the test's own directory.
                retained = Path(receipt["retainedTaskDirectory"])
                prepare.shutil.rmtree(retained)

    @unittest.skipUnless(prepare.os.name == "posix", "GnuPG UNIX socket path regression")
    def test_long_tmpdir_does_not_extend_gnupg_home_and_failed_import_cleans_it(self):
        with TemporaryDirectory() as temporary:
            output = Path(temporary) / "failed-output"
            existing_home, _ = self.installed_gradle(temporary)
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
                        "MANTRA_SIGNING_PASSWORD": "synthetic-only", "GRADLE_USER_HOME": str(existing_home)}, clear=True), \
                    mock.patch.object(prepare.processes, "stop_gradle", return_value={"daemonPids": []}), \
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

    def test_signing_timeout_stops_only_owned_home_before_removing_task(self):
        with TemporaryDirectory() as temporary:
            existing_home, executable = self.installed_gradle(temporary)
            output = Path(temporary) / "timeout-output"
            commands = []
            stops = []

            def fake_process(arguments, **options):
                if arguments[0] == "git":
                    return subprocess.CompletedProcess(arguments, 0,
                        "a" * 40 + "\n" if "rev-parse" in arguments else "")
                if "--list-secret-keys" in arguments:
                    return subprocess.CompletedProcess(arguments, 0,
                        self.listing("sec", "u", "", "scSC").encode(), b"")
                return subprocess.CompletedProcess(arguments, 0, b"public-only", b"")

            def timeout(command, **options):
                commands.append((command, options))
                raise subprocess.TimeoutExpired(command, 1)

            def stop(gradle, home, **options):
                stops.append(home)
                self.assertEqual(gradle, executable.resolve())
                self.assertNotEqual(home, existing_home)
                self.assertTrue(home.is_dir())
                self.assertFalse(any(name.startswith("MANTRA_SIGNING") for name in options["env"]))
                return {"daemonPids": [123]}

            with mock.patch.object(prepare.subprocess, "run", side_effect=fake_process), \
                    mock.patch.object(prepare.shutil, "which", side_effect=lambda name: "/installed/" + name), \
                    mock.patch.object(prepare.processes, "run_owned", side_effect=timeout), \
                    mock.patch.object(prepare.processes, "stop_gradle", side_effect=stop), \
                    mock.patch.dict(prepare.os.environ, {"MANTRA_SIGNING_KEY": KEY,
                        "MANTRA_SIGNING_PASSWORD": "synthetic-only", "GRADLE_USER_HOME": str(existing_home)}, clear=True):
                with self.assertRaisesRegex(RuntimeError, "preparation failed"):
                    prepare.prepare(Path(temporary), "1.0.0", "a" * 40, FINGERPRINT, output)
            self.assertEqual(len(commands), 1)
            command, options = commands[0]
            self.assertEqual(command[0], str(executable.resolve()))
            self.assertIn("--no-configuration-cache", command)
            self.assertEqual(Path(command[command.index("--gradle-user-home") + 1]), stops[0])
            self.assertIn("MANTRA_SIGNING_KEY", options["env"])
            self.assertFalse(stops[0].parent.exists())
            self.assertTrue(existing_home.exists())
            receipt = json.loads((output / "preparation-receipt.json").read_text())
            self.assertEqual(receipt["status"], "PREPARATION_FAILED")
            self.assertTrue(receipt["temporaryGradleStopped"])
            self.assertTrue(receipt["temporaryKeyAndStagingRemoved"])

    def test_failed_gradle_stop_retains_task_and_refuses_prepared_success(self):
        with TemporaryDirectory() as temporary:
            existing_home, _ = self.installed_gradle(temporary)
            output = Path(temporary) / "failed-stop"

            def fake_process(arguments, **options):
                if arguments[0] == "git":
                    return subprocess.CompletedProcess(arguments, 0,
                        "a" * 40 + "\n" if "rev-parse" in arguments else "")
                return subprocess.CompletedProcess(arguments, 1 if "--import" in arguments else 0, b"", b"")

            with mock.patch.object(prepare.subprocess, "run", side_effect=fake_process), \
                    mock.patch.object(prepare.shutil, "which", side_effect=lambda name: "/installed/" + name), \
                    mock.patch.object(prepare.processes, "stop_gradle", side_effect=prepare.processes.CleanupError("stop failed")), \
                    mock.patch.dict(prepare.os.environ, {"MANTRA_SIGNING_KEY": KEY,
                        "MANTRA_SIGNING_PASSWORD": "synthetic-only", "GRADLE_USER_HOME": str(existing_home)}, clear=True):
                with self.assertRaisesRegex(RuntimeError, "cleanup is incomplete"):
                    prepare.prepare(Path(temporary), "1.0.0", "a" * 40, FINGERPRINT, output)
            receipt = json.loads((output / "preparation-receipt.json").read_text())
            self.assertEqual(receipt["status"], "PREPARATION_FAILED")
            self.assertFalse(receipt["temporaryGradleStopped"])
            self.assertFalse(receipt["temporaryKeyAndStagingRemoved"])
            retained = Path(receipt["retainedTaskDirectory"])
            self.assertTrue(retained.is_dir())
            self.assertFalse((retained / "gnupg").exists(), "Stopped GPG private files must be scrubbed")
            prepare.shutil.rmtree(retained)

    def test_unverified_worker_group_prevents_parent_deletion_after_daemons_stop(self):
        with TemporaryDirectory() as temporary:
            existing_home, _ = self.installed_gradle(temporary)
            output = Path(temporary) / "unverified-worker"

            def fake_process(arguments, **options):
                if arguments[0] == "git":
                    return subprocess.CompletedProcess(arguments, 0,
                        "a" * 40 + "\n" if "rev-parse" in arguments else "")
                if "--list-secret-keys" in arguments:
                    return subprocess.CompletedProcess(arguments, 0,
                        self.listing("sec", "u", "", "scSC").encode(), b"")
                return subprocess.CompletedProcess(arguments, 0, b"public-only", b"")

            with mock.patch.object(prepare.subprocess, "run", side_effect=fake_process), \
                    mock.patch.object(prepare.shutil, "which", side_effect=lambda name: "/installed/" + name), \
                    mock.patch.object(prepare.processes, "run_owned",
                        side_effect=prepare.processes.CleanupError("Worker group unverified", group=12345)), \
                    mock.patch.object(prepare.processes, "stop_gradle", return_value={"daemonPids": []}), \
                    mock.patch.dict(prepare.os.environ, {"MANTRA_SIGNING_KEY": KEY,
                        "MANTRA_SIGNING_PASSWORD": "synthetic-only", "GRADLE_USER_HOME": str(existing_home)}, clear=True):
                with self.assertRaisesRegex(RuntimeError, "cleanup is incomplete"):
                    prepare.prepare(Path(temporary), "1.0.0", "a" * 40, FINGERPRINT, output)
            receipt = json.loads((output / "preparation-receipt.json").read_text())
            self.assertTrue(receipt["temporaryGradleStopped"])
            self.assertFalse(receipt["temporaryProcessesStopped"])
            self.assertFalse(receipt["temporaryKeyAndStagingRemoved"])
            self.assertEqual(receipt["unverifiedProcessGroup"], 12345)
            retained = Path(receipt["retainedTaskDirectory"])
            self.assertTrue(retained.is_dir())
            prepare.shutil.rmtree(retained)  # No real worker was started in this test.
