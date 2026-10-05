"""Own release subprocess groups and verify task-home daemon shutdown (POSIX only).

Never inspect process environments or stop a user's shared Gradle home. Callers
must retain task directories and report failed cleanup if verification fails.
"""
from __future__ import annotations

from contextlib import contextmanager
import os
from pathlib import Path
import re
import signal
import subprocess
import time


class CleanupError(RuntimeError):
    """Task-owned process exit could not be verified within its bound."""

    def __init__(self, message: str, group: int | None = None):
        super().__init__(message)
        self.group = group


def require_posix() -> None:
    if os.name != "posix":
        raise CleanupError("Release process cleanup requires POSIX process-group support")


@contextmanager
def termination_guard():
    """Turn the first catchable CLI termination into normal bounded cleanup.

    Subsequent TERM signals cannot interrupt that cleanup. Restore the caller's
    exact handler when leaving the context; never alter the user's process group.
    """
    require_posix()
    previous = signal.getsignal(signal.SIGTERM)

    def terminate(signum, frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, terminate)
    try:
        yield
    finally:
        signal.signal(signal.SIGTERM, previous)


def _group_alive(group: int) -> bool:
    try:
        os.killpg(group, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _signal_group(group: int, value: int) -> None:
    try:
        os.killpg(group, value)
    except ProcessLookupError:
        pass


def _terminate_group(process, grace: float = 2.0, kill_wait: float = 10.0) -> None:
    # start_new_session gives this launcher its own group. Never signal a group
    # discovered through a broad process-name search or the caller's session.
    group = process.pid
    _signal_group(group, signal.SIGTERM)
    deadline = time.monotonic() + grace
    while _group_alive(group) and time.monotonic() < deadline:
        process.poll()  # Reap our direct child; otherwise its zombie retains the group.
        time.sleep(0.02)
    if _group_alive(group):
        _signal_group(group, signal.SIGKILL)
    deadline = time.monotonic() + kill_wait
    while _group_alive(group) and time.monotonic() < deadline:
        process.poll()
        time.sleep(0.02)
    try:
        process.wait(timeout=max(0.01, deadline - time.monotonic()))
    except subprocess.TimeoutExpired:
        raise CleanupError("Task launcher did not exit after process-group termination", group) from None
    if _group_alive(group):
        raise CleanupError("Task process group remains alive; cleanup is incomplete", group)


def run_owned(arguments: list[str], *, cwd: Path, env: dict[str, str],
              stdout=None, stderr=None, text: bool = False, timeout: float = 1200):
    """Run one owned launcher; cancellation/timeout terminates and waits its group.

    A detached Gradle daemon is additionally checked through its task-owned home
    by stop_gradle(); successful launcher exit alone does not prove daemon exit.
    """
    require_posix()
    process = subprocess.Popen(arguments, cwd=cwd, env=env, stdout=stdout,
                               stderr=stderr, text=text, start_new_session=True)
    try:
        output, errors = process.communicate(timeout=timeout)
    except BaseException:
        try:
            _terminate_group(process)
        except BaseException:
            raise CleanupError("Task process-group exit was not verified", process.pid) from None
        raise
    if _group_alive(process.pid):
        # A launcher may exit while a background child still holds the group.
        # File-backed stdout must not make that a successful cleanup assertion.
        try:
            _terminate_group(process)
        except BaseException:
            raise CleanupError("Task process-group exit was not verified", process.pid) from None
    return subprocess.CompletedProcess(arguments, process.returncode, output, errors)


def _daemon_pids(home: Path) -> set[int]:
    # Every home here was newly created by a caller for this task. The log name
    # records the actual daemon PID without reading logs or secret environments.
    result = set()
    for item in (home / "daemon").glob("*/daemon-*.out.log"):
        if item.is_symlink() or not item.resolve().is_relative_to(home.resolve()):
            raise CleanupError("Unexpected task daemon registry path")
        match = re.fullmatch(r"daemon-([1-9][0-9]*)\.out\.log", item.name)
        if match is None or not 1 < int(match[1]) <= 2147483647:
            raise CleanupError("Invalid task daemon PID record")
        result.add(int(match[1]))
        if len(result) > 32:
            raise CleanupError("Task daemon count exceeds the cleanup bound")
    return result


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def stop_gradle(executable: Path, home: Path, *, cwd: Path, env: dict[str, str],
                stdout=None, timeout: float = 60, exit_wait: float = 30) -> dict:
    """Stop only a task home, then observe every recorded daemon PID disappear.

    Never kill a PID merely because a stale log contains it; a reused PID causes
    conservative cleanup failure. Callers must not delete that home's evidence.
    """
    require_posix()
    if not home.exists():
        return {"stopCommandRun": False, "daemonPids": [], "daemonExitVerified": True}
    if home.is_symlink() or not home.is_dir():
        raise CleanupError("Gradle cleanup requires the task's regular home directory")
    pids = _daemon_pids(home)
    completed = run_owned([str(executable), "--stop", "--gradle-user-home", str(home)],
                          cwd=cwd, env=env, stdout=stdout, stderr=subprocess.STDOUT, timeout=timeout)
    pids.update(_daemon_pids(home))
    if completed.returncode:
        raise CleanupError("Task-owned Gradle stop command failed")
    deadline = time.monotonic() + exit_wait
    while any(_pid_alive(pid) for pid in pids) and time.monotonic() < deadline:
        time.sleep(0.02)
    if any(_pid_alive(pid) for pid in pids):
        raise CleanupError("Task-owned Gradle daemon exit was not verified")
    return {"stopCommandRun": True, "daemonPids": sorted(pids), "daemonExitVerified": True}
