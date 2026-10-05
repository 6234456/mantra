"""Verify clean-consumer isolation without invoking Gradle or resolving dependencies."""
from contextlib import redirect_stdout
from importlib.util import module_from_spec, spec_from_file_location
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, mock
import os
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[2]
SPEC = spec_from_file_location("mantra_clean_consumer", ROOT / "scripts/check-clean-consumer.py")
CONSUMER = module_from_spec(SPEC)
SPEC.loader.exec_module(CONSUMER)


class CleanConsumerIsolationTest(TestCase):
    def prepare(self, directory):
        root = Path(directory)
        fixture = root / "release-fixtures/mantra-clean-consumer"
        shutil.copytree(ROOT / "release-fixtures/mantra-clean-consumer", fixture,
                        ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
        executable = root / "installed-gradle"
        executable.touch()
        staged = root / "staging"
        staged.mkdir()
        return root, fixture, executable, staged

    def exercise(self, root, executable, staged, exit_code, metadata_mode="pom"):
        calls = []

        def fake_gradle(command, **options):
            calls.append((command, options))
            if "--stop" in command:
                return subprocess.CompletedProcess(command, 0, "")
            home = Path(command[command.index("--gradle-user-home") + 1])
            home.mkdir()
            (home / "owned-cache").write_text("fresh")
            return subprocess.CompletedProcess(command, exit_code, "\n".join(CONSUMER.MARKERS))

        sensitive = {"NORMEIN_BUILD_PATH": "/unrelated/private-checkout", "MANTRA_SIGNING_KEY": "test-only-key",
                     "MANTRA_SIGNING_KEY_ID": "test-only-id", "MANTRA_SIGNING_PASSWORD": "test-only-password",
                     "MANTRA_CENTRAL_USERNAME": "test-only-user", "MANTRA_CENTRAL_PASSWORD": "test-only-token",
                     "SIGNING_KEY": "test-only-alias-key", "SIGNING_PASSWORD": "test-only-alias-password",
                     "CENTRAL_TOKEN_USERNAME": "test-only-alias-user", "CENTRAL_TOKEN_PASSWORD": "test-only-alias-token"}
        with mock.patch.dict(os.environ, sensitive), \
                mock.patch.object(CONSUMER.subprocess, "run", side_effect=fake_gradle), \
                redirect_stdout(StringIO()):
            if exit_code:
                with self.assertRaisesRegex(AssertionError, "Staged consumers failed"):
                    CONSUMER.verify(root, executable, "1.0.0-rc.1", staged, metadata_mode)
            else:
                CONSUMER.verify(root, executable, "1.0.0-rc.1", staged, metadata_mode)
        return calls

    def test_public_kernel_consumer_never_receives_source_or_kernel_repository(self):
        with TemporaryDirectory(prefix="mantra-consumer-test-") as directory:
            root, _, executable, staged = self.prepare(directory)
            calls = self.exercise(root, executable, staged, 0)
            command, options = calls[0]
            self.assertEqual(command[-1], "smoke")
            self.assertIn(f"-PmantraRepository={staged.resolve()}", command)
            self.assertIn("-PmantraMetadataMode=pom", command)
            self.assertFalse(any("normeinRepository" in argument or "normeinBuildPath" in argument
                                 for argument in command))
            self.assertNotIn("NORMEIN_BUILD_PATH", options["env"])
            self.assertFalse(any(name.startswith(("MANTRA_SIGNING", "MANTRA_CENTRAL")) for name in options["env"]))
            self.assertTrue(CONSUMER.RELEASE_SECRET_ALIASES.isdisjoint(options["env"]))
            home = Path(command[command.index("--gradle-user-home") + 1])
            self.assertFalse(home.exists(), "The fresh cache must be removed after execution")
            self.assertTrue(staged.exists(), "An explicit staged repository is not a disposable cache")
            self.assertIn("--stop", calls[1][0])
            self.assertEqual(calls[1][0][calls[1][0].index("--gradle-user-home") + 1], str(home))

    def test_default_gradle_metadata_mode_is_explicit_and_still_drops_release_secrets(self):
        with TemporaryDirectory(prefix="mantra-consumer-test-") as directory:
            root, _, executable, staged = self.prepare(directory)
            calls = self.exercise(root, executable, staged, 0, metadata_mode="gradle")
            self.assertIn("-PmantraMetadataMode=gradle", calls[0][0])
            self.assertFalse(any(name.startswith(("MANTRA_SIGNING", "MANTRA_CENTRAL"))
                                 for name in calls[0][1]["env"]))
            self.assertFalse(any(name.startswith(("MANTRA_SIGNING", "MANTRA_CENTRAL"))
                                 for name in calls[1][1]["env"]))
            self.assertTrue(CONSUMER.RELEASE_SECRET_ALIASES.isdisjoint(calls[0][1]["env"]))
            self.assertTrue(CONSUMER.RELEASE_SECRET_ALIASES.isdisjoint(calls[1][1]["env"]))

    def test_invalid_metadata_mode_fails_before_process_execution(self):
        with TemporaryDirectory(prefix="mantra-consumer-test-") as directory:
            root, _, executable, staged = self.prepare(directory)
            with mock.patch.object(CONSUMER.subprocess, "run") as invocation:
                with self.assertRaisesRegex(AssertionError, "Metadata mode"):
                    CONSUMER.verify(root, executable, "1.0.0-rc.1", staged, "local")
                invocation.assert_not_called()

    def test_failed_consumer_still_stops_owned_daemon_and_removes_fresh_cache(self):
        with TemporaryDirectory(prefix="mantra-consumer-test-") as directory:
            root, _, executable, staged = self.prepare(directory)
            calls = self.exercise(root, executable, staged, 1)
            self.assertEqual(len(calls), 2)
            command = calls[0][0]
            self.assertFalse(Path(command[command.index("--gradle-user-home") + 1]).exists())
            self.assertIn("--stop", calls[1][0])
            self.assertTrue(staged.exists())

    def test_fixture_rejects_local_or_source_kernel_escape_before_execution(self):
        for escape in ["mavenLocal()", "includeBuild(\"/private/kernel\")", "val normeinRepository = \"/private/staging\""]:
            with self.subTest(escape=escape), TemporaryDirectory(prefix="mantra-consumer-test-") as directory:
                root, fixture, executable, staged = self.prepare(directory)
                with (fixture / "build.gradle.kts").open("a") as output:
                    output.write("\n" + escape + "\n")
                with mock.patch.object(CONSUMER.subprocess, "run") as invocation:
                    with self.assertRaises(AssertionError):
                        CONSUMER.verify(root, executable, "1.0.0-rc.1", staged)
                    invocation.assert_not_called()
