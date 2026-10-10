#!/usr/bin/env python3
"""Start the live local workbench and verify its real calculation API (POSIX)."""
from __future__ import annotations

import argparse
from contextlib import contextmanager, nullcontext
import hashlib
import http.client
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote
import webbrowser

from release_processes import CleanupError, run_owned, stop_gradle, termination_guard


ROOT = Path(__file__).resolve().parents[1]
API = "/api/v1/packages"
MAX_RESPONSE = 16 * 1024 * 1024


class DevError(RuntimeError):
    """A local prerequisite, process or functional readiness check failed."""


def node_supported(version: str) -> bool:
    match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", version.strip())
    return bool(match and (int(match[1]) >= 24 or (int(match[1]) == 22 and int(match[2]) >= 13)))


def java_major(version: str) -> int | None:
    match = re.search(r'(?:java|openjdk|javac)(?: version)?\s+"?(\d+)', version)
    return int(match[1]) if match else None


def probe(arguments: list[str], env: dict[str, str]) -> str:
    try:
        result = run_owned(arguments, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, timeout=20)
    except OSError as error:
        raise DevError(f"Cannot run {arguments[0]}: {error}") from error
    if result.returncode:
        raise DevError(f"{arguments[0]} prerequisite check failed: {result.stdout.strip()}")
    return result.stdout.strip()


def prerequisites(env: dict[str, str]) -> dict[str, str]:
    if os.name != "posix" or sys.version_info < (3, 10):
        raise DevError("Use macOS or Linux with Python 3.10+; Windows users can use WSL.")
    jdk = Path(env["JAVA_HOME"]) if env.get("JAVA_HOME") else None
    tools = {}
    for name in ("java", "javac", "node", "npm", "git"):
        executable = str(jdk / "bin" / name) if jdk and name in {"java", "javac"} else shutil.which(name)
        if not executable or not os.access(executable, os.X_OK):
            raise DevError(f"Missing {name}; install a full JDK 21, Node.js 22.13+ (22.x) or 24+, npm and Git.")
        tools[name] = executable
    versions = {name: probe([tool, "-version" if name in {"java", "javac"} else "--version"], env)
                for name, tool in tools.items()}
    if java_major(versions["java"]) != 21 or java_major(versions["javac"]) != 21:
        raise DevError("Both java and javac must be JDK 21. Set JAVA_HOME to a full JDK 21 and update PATH.")
    if not node_supported(versions["node"]):
        raise DevError(f"Unsupported Node.js {versions['node']}; use 22.13+ (22.x) or 24+.")
    print(f"Toolchain ready: JDK 21, Node.js {versions['node']}, npm {versions['npm']}, Python {sys.version.split()[0]}.",
          flush=True)
    return tools


def require_free_port(port: int) -> None:
    # Test by binding, without adopting or terminating any existing listener.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        try:
            listener.bind(("127.0.0.1", port))
        except OSError as error:
            raise DevError(f"127.0.0.1:{port} is unavailable. Stop your existing service or choose another UI port.") from error


class Services:
    """Own only the process groups created by this invocation."""

    def __init__(self):
        self.children: list[tuple[str, subprocess.Popen]] = []
        self._reaper = None

    def _own_orphans(self):
        # Some Linux containers run a PID 1 that never reaps orphaned Vite children.
        # Adopt this invocation's descendants so its process groups can fully disappear.
        if sys.platform == "linux" and self._reaper is None:
            import ctypes
            library = ctypes.CDLL(None, use_errno=True)
            previous = ctypes.c_int()
            if library.prctl(37, ctypes.byref(previous), 0, 0, 0) or library.prctl(36, 1, 0, 0, 0):
                raise DevError("Cannot establish Linux subprocess ownership for reliable cleanup.")
            self._reaper = (library, previous.value)

    def start(self, name: str, arguments: list[str], *, env: dict[str, str], log):
        self._own_orphans()
        process = subprocess.Popen(arguments, cwd=ROOT, env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        self.children.append((name, process))
        return process

    def check(self) -> None:
        for name, process in self.children:
            code = process.poll()
            if code is not None:
                raise DevError(f"{name} exited with status {code}; see the session logs.")

    def close(self) -> None:
        problems = []
        # Ignore repeat cancellation while bounded cleanup is in progress.
        previous = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
        try:
            for sig in previous:
                signal.signal(sig, signal.SIG_IGN)
            for name, process in reversed(self.children):
                try:
                    self._stop_group(process)
                except (DevError, OSError, subprocess.TimeoutExpired) as error:
                    problems.append(f"{name}: {error}")
        finally:
            if self._reaper is not None:
                library, previous_reaper = self._reaper
                library.prctl(36, previous_reaper, 0, 0, 0)
                self._reaper = None
            for sig, handler in previous.items():
                signal.signal(sig, handler)
        if problems:
            raise DevError("Incomplete service cleanup: " + "; ".join(problems))

    @staticmethod
    def _stop_group(process) -> None:
        def alive():
            process.poll()
            # waitpid is bounded to this owned group, including adopted grandchildren.
            while True:
                try:
                    child, _ = os.waitpid(-process.pid, os.WNOHANG)
                    if child == 0:
                        break
                except ChildProcessError:
                    break
            try:
                os.killpg(process.pid, 0)
                return True
            except ProcessLookupError:
                return False

        for value, grace in ((signal.SIGTERM, 5), (signal.SIGKILL, 5)):
            try:
                os.killpg(process.pid, value)
            except ProcessLookupError:
                break
            deadline = time.monotonic() + grace
            while alive() and time.monotonic() < deadline:
                time.sleep(0.02)
            if not alive():
                break
        process.wait(timeout=1)
        if alive():
            raise DevError(f"Owned process group {process.pid} remains alive.")


def request(port: int, path: str, timeout: float = 5) -> tuple[str, bytes]:
    # Loopback requests must not be sent through HTTP_PROXY/HTTPS_PROXY.
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        content = response.read(MAX_RESPONSE + 1)
        if response.status != 200 or len(content) > MAX_RESPONSE:
            raise DevError(f"{path}: HTTP {response.status} or response exceeds 16 MiB.")
        return response.getheader("content-type", ""), content
    finally:
        connection.close()


def document(port: int, path: str, timeout: float = 5) -> dict:
    content_type, content = request(port, path, timeout)
    value = json.loads(content)
    if not content_type.startswith("application/json") or value.get("contract") != "mantra.packages/1":
        raise DevError(f"{path}: expected a mantra.packages/1 JSON document.")
    if not isinstance(value.get("data"), dict):
        raise DevError(f"{path}: missing document data.")
    return value["data"]


def wait_ready(services: Services, port: int, path: str, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    last = "no response"
    while time.monotonic() < deadline:
        services.check()
        try:
            content_type, body = request(port, path, min(5, max(0.1, deadline - time.monotonic())))
            if path == API:
                value = json.loads(body)
                if value.get("contract") != "mantra.packages/1" or not isinstance(value.get("data", {}).get("packages"), list):
                    raise DevError("Workspace API does not match the package contract.")
            elif not content_type.startswith("text/html") or b"/@vite/client" not in body:
                raise DevError("Frontend is not the Vite development server.")
            elif not re.search(rb'<meta name="mantra-session-token" content="[a-f0-9]{64}"', body):
                raise DevError("Frontend is missing the backend session metadata required for live editing.")
            return
        except (OSError, http.client.HTTPException, ValueError, DevError) as error:
            last = str(error)
        time.sleep(0.2)
    raise DevError(f"Timed out waiting for 127.0.0.1:{port}{path}: {last}")


def functional_check(port: int) -> str:
    workspace = document(port, API, timeout=30)
    cases = [case for package in workspace.get("packages", []) for case in package.get("cases", [])]
    for case in cases:
        path = f"/api/v1/package-cases/{quote(case['id'], safe='')}/run"
        result = document(port, path, timeout=60)
        if result.get("succeeded") is not True:
            continue  # Demonstration packages also include intentional calculation failures.
        run = result.get("document", {})
        values = run.get("data", {}).get("values")
        if run.get("contract") != "mantra.workbench/4" or not isinstance(values, dict) or not values:
            raise DevError(f"Calculation readiness returned no current values for {case['id']}.")
        if result.get("binding", {}).get("editableCase") is not True:
            raise DevError(f"Local host case is not editable: {case['id']}.")
        return case["id"]
    raise DevError("Workspace has no runnable case; check application document diagnostics.")


def package_config(workspace: Path, case_root: Path, session: Path) -> Path:
    workspace = workspace.resolve(strict=True)
    manifests = [workspace / "manifest.json"] if (workspace / "manifest.json").is_file() else sorted(workspace.glob("*/manifest.json"))
    if not manifests or len(manifests) > 64:
        raise DevError("Select a directory containing one package manifest or up to 64 package subdirectories.")
    mounts, editable = [], []
    package_roots = [manifest.parent.resolve() for manifest in manifests]
    case_root.mkdir(parents=True, exist_ok=True)
    for manifest in manifests:
        mount = manifest.parent.name
        data = json.loads(manifest.read_bytes())
        mounts.append({"mount": mount, "manifest": str(manifest), "directoryPolicy": "trusted-local"})
        for case in data["cases"]:
            raw = case["path"]
            path = PurePosixPath(raw)
            if path.is_absolute() or ".." in path.parts or "\\" in raw or not path.parts:
                raise DevError(f"Unsafe case path in {manifest}: {raw}")
            source = (manifest.parent / path).resolve(strict=True)
            if not source.is_relative_to(manifest.parent.resolve()):
                raise DevError(f"Case resource escapes its package: {raw}")
            target = case_root / mount / path
            if any(target.resolve().is_relative_to(root) for root in package_roots):
                raise DevError("Editable case copies must be outside mounted package directories.")
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.resolve().is_relative_to(case_root.resolve()):
                raise DevError(f"Local case path escapes the owned directory: {target}")
            try:
                with target.open("xb") as output:
                    output.write(source.read_bytes())
            except FileExistsError:
                if not target.is_file() or target.is_symlink():
                    raise DevError(f"Existing local case is not a regular file: {target}") from None
            editable.append({"case": f"{mount}/{raw}", "root": str(case_root.resolve()),
                             "path": f"{mount}/{raw}", "maxBytes": 1_048_576, "directoryPolicy": "trusted-local"})
    if len(editable) > 256:
        raise DevError("Local development supports up to 256 editable package cases.")
    config = {"contract": "mantra.package-workspace/1",
              "limits": {"manifestBytes": 262_144, "resourceBytes": 2_097_152, "totalBytes": 67_108_864,
                         "resources": 2048, "jsonDepth": 32, "containerEntries": 8192},
              "mounts": mounts, "policies": [], "editableCases": editable}
    destination = session / "packages.json"
    destination.write_text(json.dumps(config, indent=2) + "\n")
    print(f"Editable case copies: {case_root}\nCaptured package resources reload after restarting scripts/dev.sh.", flush=True)
    return destination


def dependency_fingerprint(tools: dict[str, str], env: dict[str, str]) -> str:
    digest = hashlib.sha256()
    for name in ("package.json", "package-lock.json"):
        digest.update((ROOT / "workbench-ui" / name).read_bytes())
    digest.update(probe([tools["node"], "--version"], env).encode())
    digest.update(probe([tools["npm"], "--version"], env).encode())
    return digest.hexdigest()


def command(arguments: list[str], env: dict[str, str], timeout=1200) -> None:
    print("Running: " + " ".join(arguments), flush=True)
    completed = run_owned(arguments, cwd=ROOT, env=env, timeout=timeout)
    if completed.returncode:
        raise DevError(f"Command failed with status {completed.returncode}: {' '.join(arguments)}")


@contextmanager
def gradle_environment(session: Path, env: dict[str, str]):
    home = session / "gradle-home"
    home.mkdir()
    shared = Path(env.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).resolve()
    for name in ("caches", "wrapper", "jdks", "init.d"):
        source = shared / name
        if not source.is_dir() and name != "init.d":
            source = ROOT / "build" / "dev" / "gradle-cache" / name
            source.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            (home / name).symlink_to(source, target_is_directory=True)
    owned_env = dict(env, GRADLE_USER_HOME=str(home))
    try:
        yield owned_env
    finally:
        # The registry is session-owned; --stop cannot stop the user's shared daemons.
        with (session / "gradle-stop.log").open("w") as log:
            stop_gradle(ROOT / "gradlew", home, cwd=ROOT, env=owned_env, stdout=log)


def run(options) -> None:
    # CLI arguments are relative to the caller, while all subprocesses use repository cwd.
    options.workspace = options.workspace.resolve(strict=True)
    if options.case_dir is not None:
        options.case_dir = options.case_dir.resolve()
    env = os.environ.copy()
    tools = prerequisites(env)
    require_free_port(8080)
    require_free_port(options.ui_port)
    output = ROOT / "build" / "dev"
    output.mkdir(parents=True, exist_ok=True)
    fingerprint = dependency_fingerprint(tools, env)
    stamp = output / "dependencies.sha256"
    vite = ROOT / "workbench-ui" / "node_modules" / "vite" / "bin" / "vite.js"
    installed = stamp.is_file() and stamp.read_text().strip() == fingerprint and vite.is_file()
    if options.reinstall or not installed:
        command([tools["npm"], "--prefix", "workbench-ui", "ci"], env)
        stamp.write_text(fingerprint + "\n")
    session = Path(tempfile.mkdtemp(prefix="session-", dir=output))
    print(f"Session logs: {session}", flush=True)
    services = Services()
    build_context = nullcontext(env) if options.skip_build else gradle_environment(session, env)
    with build_context as build_env:
        gradle = [str(ROOT / "gradlew"), "--no-daemon", "--console=plain", "--max-workers=2",
                  "-Pkotlin.compiler.execution.strategy=in-process"]
        if options.check:
            command(gradle + ["check"], build_env)
            command([tools["npm"], "--prefix", "workbench-ui", "test"], env)
        if not options.skip_build:
            command(gradle + [":mantra-cli:installDist"], build_env)
        elif not (ROOT / "mantra-cli" / "build" / "install" / "mantra" / "bin" / "mantra").is_file():
            raise DevError("No installed CLI; first run scripts/dev.sh without --skip-build.")
        config = package_config(options.workspace, options.case_dir or output / "cases", session)
        # Check again after builds; another local service may have claimed a port meanwhile.
        require_free_port(8080)
        require_free_port(options.ui_port)
        with (session / "backend.log").open("w") as backend_log, (session / "vite.log").open("w") as vite_log:
            try:
                cli = ROOT / "mantra-cli" / "build" / "install" / "mantra" / "bin" / "mantra"
                bootstrap = session / "bootstrap-ui"
                bootstrap.mkdir()
                (bootstrap / "index.html").write_text('<!doctype html><html><head></head><body></body></html>\n')
                services.start("Mantra backend", [str(cli), "serve", str(options.workspace), "--port", "8080",
                                                 "--packages", str(config), "--ui", str(bootstrap)], env=env, log=backend_log)
                wait_ready(services, 8080, API, options.timeout)
                live_env = dict(env, VITE_WORKBENCH_MODE="live", NODE_USE_ENV_PROXY="0")
                services.start("Vite", [tools["npm"], "--prefix", "workbench-ui", "run", "dev", "--",
                                       "--host", "127.0.0.1", "--port", str(options.ui_port), "--strictPort"],
                               env=live_env, log=vite_log)
                wait_ready(services, options.ui_port, "/", options.timeout)
                case = functional_check(options.ui_port)
                url = f"http://127.0.0.1:{options.ui_port}"
                print(f"Live workbench ready: {url}\nBackend: http://127.0.0.1:8080\nCalculation verified through Vite: {case}",
                      flush=True)
                if options.check:
                    print("Development checks passed; shutting down owned services.", flush=True)
                    return
                if not options.no_browser:
                    try:
                        if not webbrowser.open(url):
                            print(f"Open {url} in your browser.", flush=True)
                    except webbrowser.Error:
                        print(f"Open {url} in your browser.", flush=True)
                print("Edit frontend files for hot reload. Press Ctrl+C to stop.", flush=True)
                while True:
                    services.check()
                    time.sleep(0.5)
            finally:
                services.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="run Gradle check, frontend tests and live HTTP checks, then exit")
    parser.add_argument("--no-browser", action="store_true", help="do not open a browser")
    parser.add_argument("--reinstall", action="store_true", help="rerun npm ci even if the dependency fingerprint matches")
    parser.add_argument("--skip-build", action="store_true", help="reuse the installed CLI for frontend-only development")
    parser.add_argument("--workspace", type=Path, default=ROOT / "apps", help="workspace to serve (default: repository apps)")
    parser.add_argument("--case-dir", type=Path, help="directory for persistent editable copies (default: build/dev/cases)")
    parser.add_argument("--ui-port", type=int, default=5173, help="Vite loopback port (default: 5173; backend remains 8080)")
    parser.add_argument("--timeout", type=float, default=120, help="per-service startup timeout in seconds (default: 120)")
    options = parser.parse_args()
    if options.ui_port == 8080 or not 1 <= options.ui_port <= 65535 or options.timeout <= 0:
        parser.error("choose a UI port from 1..65535 other than 8080 and a positive timeout")
    if options.check and options.skip_build:
        parser.error("--check requires current JVM outputs; omit --skip-build")
    try:
        with termination_guard():
            run(options)
    except KeyboardInterrupt:
        print("Stopped; owned development services cleaned up.", flush=True)
        return 130
    except (DevError, CleanupError, OSError, ValueError, http.client.HTTPException, subprocess.TimeoutExpired) as error:
        print(f"Mantra development failed: {error}", file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
