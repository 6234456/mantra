"""Dummy POSIX children only; run after measurements, never launch Gradle/GPG."""
from importlib.util import module_from_spec, spec_from_file_location
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

SPEC = spec_from_file_location("release_processes", Path(__file__).parents[1] / "release_processes.py")
processes = module_from_spec(SPEC)
sys.modules[SPEC.name] = processes
SPEC.loader.exec_module(processes)

CHILD = r'''
import json, os, pathlib, signal, subprocess, sys, time
grandchild = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"])
def stop(signum, frame):
    try:
        grandchild.terminate()
        grandchild.wait(timeout=2)
    finally:
        raise SystemExit(0)
signal.signal(signal.SIGTERM, stop)
pathlib.Path(sys.argv[1]).write_text(json.dumps({"launcher":os.getpid(), "grandchild":grandchild.pid,
                                              "group":os.getpgrp()}))
while True:
    time.sleep(.02)
'''


@unittest.skipUnless(os.name == "posix", "Owned POSIX process-group regression")
class OwnedProcessTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mantra-process-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.script = self.root / "dummy.py"
        self.script.write_text(CHILD)
        self.ready = self.root / "ready.json"
        self.started = []
        self.addCleanup(self.clean_leftovers)

    def clean_leftovers(self):
        # Test-owned groups only, also when an assertion fails.
        for child in self.started:
            if processes._group_alive(child.pid):
                processes._terminate_group(child)

    def launch_factory(self, *, interrupt=False):
        actual_popen = subprocess.Popen

        def launch(*arguments, **options):
            self.assertTrue(options["start_new_session"])
            child = actual_popen(*arguments, **options)
            self.started.append(child)
            deadline = time.monotonic() + 5
            while not self.ready.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(self.ready.exists(), "The dummy parent must start its grandchild before cancellation")
            if interrupt:
                child.communicate = mock.Mock(side_effect=KeyboardInterrupt)
            return child
        return launch

    def run_child(self, *, interrupt=False):
        with mock.patch.object(processes.subprocess, "Popen", side_effect=self.launch_factory(interrupt=interrupt)):
            processes.run_owned([sys.executable, str(self.script), str(self.ready)], cwd=self.root,
                                env={}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=.05)

    def assert_all_exited(self):
        identities = json.loads(self.ready.read_text())
        self.assertEqual(identities["group"], identities["launcher"])
        self.assertNotEqual(identities["group"], os.getpgrp())
        self.assertFalse(processes._pid_alive(identities["launcher"]))
        self.assertFalse(processes._pid_alive(identities["grandchild"]))
        self.assertFalse(processes._group_alive(identities["group"]))

    def test_timeout_terminates_and_waits_dummy_child_and_grandchild(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_child()
        self.assert_all_exited()

    def test_keyboard_cancel_terminates_and_waits_dummy_child_and_grandchild(self):
        with self.assertRaises(KeyboardInterrupt):
            self.run_child(interrupt=True)
        self.assert_all_exited()

    def test_real_sigterm_runs_owner_cleanup_and_ignores_repeat_during_cleanup(self):
        owner_script = self.root / "owner.py"
        cleanup = self.root / "cleanup.json"
        owner_script.write_text('''
import json, os, pathlib, signal, subprocess, sys
sys.path.insert(0, sys.argv[1])
import release_processes as processes
previous = signal.getsignal(signal.SIGTERM)
with processes.termination_guard():
    try:
        processes.run_owned([sys.executable, sys.argv[2], sys.argv[3]], cwd=pathlib.Path(sys.argv[2]).parent,
                            env=dict(os.environ), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    except KeyboardInterrupt:
        # A second actual TERM must not interrupt the bounded cleanup phase.
        os.kill(os.getpid(), signal.SIGTERM)
        pathlib.Path(sys.argv[4]).write_text(json.dumps({"cleanupRan":True}))
assert signal.getsignal(signal.SIGTERM) == previous
''')
        owner = subprocess.Popen([sys.executable, str(owner_script), str(Path(__file__).parents[1].resolve()),
                                  str(self.script), str(self.ready), str(cleanup)], env={}, start_new_session=True)
        self.started.append(owner)
        # The owner's launcher uses a second deliberately isolated group.
        try:
            deadline = time.monotonic() + 5
            while not self.ready.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(self.ready.exists())
            os.kill(owner.pid, signal.SIGTERM)
            owner.wait(timeout=15)
            self.assertEqual(owner.returncode, 0)
            self.assertTrue(json.loads(cleanup.read_text())["cleanupRan"])
            self.assert_all_exited()
        finally:
            if self.ready.exists():
                group = json.loads(self.ready.read_text())["group"]
                if processes._group_alive(group):
                    processes._signal_group(group, signal.SIGTERM)
                    deadline = time.monotonic() + 2
                    while processes._group_alive(group) and time.monotonic() < deadline:
                        time.sleep(.02)
                    if processes._group_alive(group):
                        processes._signal_group(group, signal.SIGKILL)
                        deadline = time.monotonic() + 3
                        while processes._group_alive(group) and time.monotonic() < deadline:
                            time.sleep(.02)
                    if processes._group_alive(group):
                        raise processes.CleanupError("Dummy nested process cleanup remains incomplete", group)

    def test_termination_guard_restores_caller_handler_on_normal_exit(self):
        previous = signal.getsignal(signal.SIGTERM)
        with processes.termination_guard():
            self.assertNotEqual(signal.getsignal(signal.SIGTERM), previous)
        self.assertEqual(signal.getsignal(signal.SIGTERM), previous)

    def test_successful_launcher_cannot_leave_background_child_unchecked(self):
        launcher = self.root / "background.py"
        launcher.write_text('''
import json, os, pathlib, subprocess, sys
child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"])
pathlib.Path(sys.argv[1]).write_text(json.dumps({"launcher":os.getpid(), "grandchild":child.pid,
                                              "group":os.getpgrp()}))
''')
        # DEVNULL ensures communicate can finish even while a child is alive.
        result = processes.run_owned([sys.executable, str(launcher), str(self.ready)], cwd=self.root,
                                     env={}, stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL, timeout=5)
        self.assertEqual(result.returncode, 0)
        self.assert_all_exited()

    def test_gradle_stop_nonzero_refuses_cleanup_even_if_no_pid_is_recorded(self):
        home = self.root / "gradle-home"
        home.mkdir()
        with mock.patch.object(processes, "run_owned", return_value=subprocess.CompletedProcess([], 1)) as command:
            with self.assertRaisesRegex(processes.CleanupError, "stop command failed"):
                processes.stop_gradle(self.root / "installed-gradle", home, cwd=self.root, env={})
        self.assertEqual(command.call_args.args[0][-2:], ["--gradle-user-home", str(home)])
        self.assertTrue(home.exists())

    def test_zero_stop_exit_does_not_override_live_recorded_daemon(self):
        home = self.root / "gradle-home"
        registry = home / "daemon/9.4.0"
        registry.mkdir(parents=True)
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"], env={}, start_new_session=True)
        self.started.append(child)
        (registry / f"daemon-{child.pid}.out.log").touch()
        with mock.patch.object(processes, "run_owned", return_value=subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(processes.CleanupError, "exit was not verified"):
                processes.stop_gradle(self.root / "installed-gradle", home, cwd=self.root, env={}, exit_wait=.02)
        self.assertTrue(home.exists())
        self.assertTrue(processes._pid_alive(child.pid))

    def test_stop_verifies_actual_recorded_pid_exit_before_return(self):
        home = self.root / "gradle-home"
        registry = home / "daemon/9.4.0"
        registry.mkdir(parents=True)
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"], env={}, start_new_session=True)
        self.started.append(child)
        (registry / f"daemon-{child.pid}.out.log").touch()

        def stop(*arguments, **options):
            processes._terminate_group(child)
            return subprocess.CompletedProcess([], 0)

        with mock.patch.object(processes, "run_owned", side_effect=stop):
            evidence = processes.stop_gradle(self.root / "installed-gradle", home, cwd=self.root, env={})
        self.assertEqual(evidence["daemonPids"], [child.pid])
        self.assertTrue(evidence["daemonExitVerified"])
        self.assertFalse(processes._pid_alive(child.pid))

    def test_missing_home_does_not_invoke_shared_daemon_stop(self):
        with mock.patch.object(processes, "run_owned") as command:
            result = processes.stop_gradle(self.root / "installed-gradle", self.root / "not-started",
                                           cwd=self.root, env={})
        command.assert_not_called()
        self.assertFalse(result["stopCommandRun"])

    def test_non_posix_refuses_execution_before_popen(self):
        with mock.patch.object(processes.os, "name", "nt"), mock.patch.object(processes.subprocess, "Popen") as command:
            with self.assertRaisesRegex(processes.CleanupError, "requires POSIX"):
                processes.run_owned(["ignored"], cwd=self.root, env={})
        command.assert_not_called()
