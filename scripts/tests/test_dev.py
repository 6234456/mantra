"""Regression checks for local launcher ownership, isolation and readiness."""
from contextlib import redirect_stdout
from argparse import Namespace
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("mantra_dev", SCRIPTS / "dev.py")
dev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dev)


class DevPrerequisitesTest(unittest.TestCase):
    def test_node_engine_matches_supported_stable_releases(self):
        for version in ("v22.13.0", "22.14.1", "v24.0.0", "v26.1.0"):
            self.assertTrue(dev.node_supported(version), version)
        for version in ("v20.19.0", "v22.12.9", "v23.13.0", "v24.0.0-rc.1", "bad"):
            self.assertFalse(dev.node_supported(version), version)

    def test_jdk_requires_matching_java_and_javac_21(self):
        versions = {"java": 'openjdk version "21.0.11"', "javac": "javac 17.0.1",
                    "node": "v24.1.0", "npm": "11.0.0", "git": "git version 2.43.0"}
        with mock.patch.object(dev.shutil, "which", side_effect=lambda name: name), \
                mock.patch.object(dev.os, "access", return_value=True), \
                mock.patch.object(dev, "probe", side_effect=lambda args, env: versions[args[0]]):
            with self.assertRaisesRegex(dev.DevError, "Both java and javac"):
                dev.prerequisites({})

    def test_occupied_port_is_reported_without_adopting_listener(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            with self.assertRaisesRegex(dev.DevError, "unavailable"):
                dev.require_free_port(listener.getsockname()[1])
            with socket.create_connection(listener.getsockname(), timeout=1):
                pass


class DevPackageConfigTest(unittest.TestCase):
    def test_relative_workspace_keeps_caller_meaning_when_backend_uses_repository_cwd(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            root = Path(directory)
            repository = root / "repository"
            caller = root / "caller"
            package = caller / "apps" / "demo"
            package.mkdir(parents=True)
            (package / "case.mantra").write_text("case")
            (package / "manifest.json").write_text(json.dumps({"cases": [{"path": "case.mantra"}]}))
            cli = repository / "mantra-cli/build/install/mantra/bin/mantra"
            cli.parent.mkdir(parents=True)
            cli.touch()
            vite = repository / "workbench-ui/node_modules/vite/bin/vite.js"
            vite.parent.mkdir(parents=True)
            vite.touch()
            output = repository / "build/dev"
            output.mkdir(parents=True)
            (output / "dependencies.sha256").write_text("installed\n")
            options = Namespace(workspace=Path("apps"), case_dir=None, ui_port=5173, reinstall=False,
                                skip_build=True, check=False, no_browser=True, timeout=1)
            services = mock.Mock()
            services.check.side_effect = KeyboardInterrupt
            previous = Path.cwd()
            try:
                os.chdir(caller)
                with mock.patch.object(dev, "ROOT", repository), \
                        mock.patch.object(dev, "prerequisites", return_value={"npm": "npm"}), \
                        mock.patch.object(dev, "dependency_fingerprint", return_value="installed"), \
                        mock.patch.object(dev, "require_free_port"), \
                        mock.patch.object(dev, "Services", return_value=services), \
                        mock.patch.object(dev, "wait_ready"), \
                        mock.patch.object(dev, "functional_check", return_value="demo/case.mantra"):
                    with self.assertRaises(KeyboardInterrupt):
                        dev.run(options)
            finally:
                os.chdir(previous)
            arguments = services.start.call_args_list[0].args[1]
            self.assertEqual(arguments[2], str(caller / "apps"))
            config = json.loads(next(output.glob("session-*/packages.json")).read_text())
            self.assertEqual(config["mounts"][0]["manifest"], str(package / "manifest.json"))
            services.close.assert_called_once()

    def test_cases_are_copied_outside_packages_and_existing_edits_survive_restart(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            root = Path(directory)
            package = root / "apps" / "demo"
            package.mkdir(parents=True)
            original = b"fictional original case\n"
            (package / "case.mantra").write_bytes(original)
            manifest = {"cases": [{"path": "case.mantra"}]}
            (package / "manifest.json").write_text(json.dumps(manifest))
            session = root / "session"
            session.mkdir()
            cases = root / "owned-cases"
            config = dev.package_config(root / "apps", cases, session)
            saved = json.loads(config.read_text())
            target = cases / "demo" / "case.mantra"
            self.assertEqual(target.read_bytes(), original)
            self.assertEqual(saved["editableCases"][0]["case"], "demo/case.mantra")
            self.assertEqual(saved["editableCases"][0]["root"], str(cases))
            target.write_text("local edit\n")
            dev.package_config(root / "apps", cases, session)
            self.assertEqual(target.read_text(), "local edit\n")
            self.assertEqual((package / "case.mantra").read_bytes(), original)

    def test_manifest_case_path_cannot_escape_package(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            root = Path(directory)
            package = root / "demo"
            package.mkdir()
            (package / "manifest.json").write_text(json.dumps({"cases": [{"path": "../outside.mantra"}]}))
            with self.assertRaisesRegex(dev.DevError, "Unsafe case path"):
                dev.package_config(package, root / "cases", root)

    def test_case_directory_cannot_write_inside_a_mounted_package(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            root = Path(directory)
            package = root / "demo"
            package.mkdir()
            original = package / "case.mantra"
            original.write_text("original")
            (package / "manifest.json").write_text(json.dumps({"cases": [{"path": "case.mantra"}]}))
            with self.assertRaisesRegex(dev.DevError, "outside mounted package"):
                dev.package_config(package, package / "local", root)
            self.assertEqual(original.read_text(), "original")
            self.assertFalse((package / "local" / "demo").exists())

    def test_gradle_registry_is_owned_while_existing_downloads_are_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shared = root / "shared"
            for name in ("caches", "wrapper", "jdks", "init.d"):
                (shared / name).mkdir(parents=True)
            (shared / "daemon").mkdir()
            session = root / "session"
            session.mkdir()
            with mock.patch.object(dev, "stop_gradle") as stop:
                with dev.gradle_environment(session, {"GRADLE_USER_HOME": str(shared)}) as env:
                    owned = Path(env["GRADLE_USER_HOME"])
                    self.assertEqual((owned / "caches").resolve(), shared / "caches")
                    self.assertFalse((owned / "daemon").exists())
                self.assertEqual(stop.call_args.args[1], owned)
                self.assertNotEqual(stop.call_args.args[1], shared)

    def test_fresh_machine_keeps_wrapper_and_dependency_downloads_across_sessions(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(dev, "stop_gradle"):
            root = Path(directory)
            with mock.patch.object(dev, "ROOT", root):
                cache = None
                for index in range(2):
                    session = root / f"session-{index}"
                    session.mkdir()
                    with dev.gradle_environment(session, {"GRADLE_USER_HOME": str(root / "missing")}) as env:
                        selected = (Path(env["GRADLE_USER_HOME"]) / "caches").resolve()
                        if cache is None:
                            cache = selected
                            (cache / "reusable-download").write_text("valid cache")
                        self.assertEqual(selected, cache)
                        self.assertEqual((selected / "reusable-download").read_text(), "valid cache")


class DevHttpTest(unittest.TestCase):
    def setUp(self):
        documents = {
            dev.API: {"contract": "mantra.packages/1", "data": {"packages": [{"cases": [
                {"id": "demo/failing.mantra"}, {"id": "demo/case with space.mantra"}]}]}},
            "/api/v1/package-cases/demo%2Ffailing.mantra/run": {
                "contract": "mantra.packages/1", "data": {"succeeded": False}},
            "/api/v1/package-cases/demo%2Fcase%20with%20space.mantra/run": {
                "contract": "mantra.packages/1", "data": {"succeeded": True, "binding": {"editableCase": True},
                    "document": {"contract": "mantra.workbench/4", "data": {"values": {"amount": {}}}}}},
        }
        self.documents = documents

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                body = json.dumps(documents.get(self.path, {})).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.port = self.server.server_port

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_calculation_readiness_skips_intentional_failures_and_encodes_case_paths(self):
        with mock.patch.dict(os.environ, {"HTTP_PROXY": "http://127.0.0.1:1", "NO_PROXY": ""}):
            self.assertEqual(dev.functional_check(self.port), "demo/case with space.mantra")

    def test_live_check_rejects_read_only_case_binding(self):
        path = "/api/v1/package-cases/demo%2Fcase%20with%20space.mantra/run"
        self.documents[path]["data"]["binding"]["editableCase"] = False
        with self.assertRaisesRegex(dev.DevError, "not editable"):
            dev.functional_check(self.port)

    def test_readiness_rejects_an_unexpected_contract(self):
        self.documents[dev.API]["contract"] = "unknown/1"
        with self.assertRaisesRegex(dev.DevError, "Timed out"):
            dev.wait_ready(dev.Services(), self.port, dev.API, 0.1)


@unittest.skipUnless(os.name == "posix", "POSIX process-group ownership")
class DevProcessTest(unittest.TestCase):
    def test_background_descendant_is_removed_after_its_launcher_exits(self):
        with tempfile.TemporaryDirectory() as directory, open(os.devnull, "w") as log:
            pid_file = Path(directory) / "child"
            script = '''import pathlib,subprocess,sys
child=subprocess.Popen([sys.executable,"-c","import time; time.sleep(30)"])
pathlib.Path(sys.argv[1]).write_text(str(child.pid))
raise SystemExit(7)
'''
            services = dev.Services()
            process = services.start("exited launcher", [sys.executable, "-c", script, str(pid_file)],
                                     env=os.environ.copy(), log=log)
            try:
                self.assertEqual(process.wait(timeout=5), 7)
                child = int(pid_file.read_text())
            finally:
                services.close()
            with self.assertRaises(ProcessLookupError):
                os.kill(child, 0)

    def test_child_exit_is_an_error_and_cleanup_leaves_unrelated_service_running(self):
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
        services = dev.Services()
        try:
            with open(os.devnull, "w") as log:
                child = services.start("failed backend", [sys.executable, "-c", "raise SystemExit(7)"],
                                       env=os.environ.copy(), log=log)
                child.wait(timeout=5)
                with self.assertRaisesRegex(dev.DevError, "status 7"):
                    services.check()
                services.close()
            self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=5)

    def test_sigterm_cancellation_cleans_the_owned_subprocess_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / "pids.json"
            child_script = '''import json,os,pathlib,signal,subprocess,sys,time
child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
def stop(signum, frame):
    child.terminate()
    child.wait(timeout=5)
    raise SystemExit(0)
signal.signal(signal.SIGTERM, stop)
pathlib.Path(sys.argv[1]).write_text(json.dumps([os.getpid(),child.pid]))
while True: time.sleep(0.1)
'''
            launcher = '''import os,sys,time
sys.path.insert(0,sys.argv[1])
import dev
services=dev.Services()
try:
    with dev.termination_guard():
        try:
            services.start("tree",[sys.executable,"-c",sys.argv[2],sys.argv[3]],env=os.environ.copy(),log=sys.stdout)
            while True: time.sleep(0.1)
        finally: services.close()
except KeyboardInterrupt: raise SystemExit(130)
'''
            process = subprocess.Popen([sys.executable, "-c", launcher, str(SCRIPTS), child_script, str(pid_file)],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            try:
                deadline = time.monotonic() + 5
                while not pid_file.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(pid_file.exists(), "task subprocess tree did not start")
                pids = json.loads(pid_file.read_text())
                process.send_signal(signal.SIGTERM)
                output, _ = process.communicate(timeout=15)
                self.assertEqual(process.returncode, 130, output)
                for pid in pids:
                    with self.assertRaises(ProcessLookupError):
                        os.kill(pid, 0)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
