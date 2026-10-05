#!/usr/bin/env python3
"""Execute staged POM consumers with fresh caches and a Maven Central kernel; no source composite."""
from pathlib import Path
import tempfile
import argparse
import json
import os
import shutil
import subprocess

import release_processes as processes

MARKERS = [
    "MANTRA_JAVA_EXCEL_CONSUMER_OK",
    "MANTRA_KOTLIN_CORE_CONSUMER_OK",
    "MANTRA_KOTLIN_WORKBENCH_CONSUMER_OK",
    "MANTRA_KOTLIN_SERVER_CONSUMER_OK",
    "MANTRA_KOTLIN_PACKAGES_CONSUMER_OK",
]
RELEASE_SECRET_ALIASES = {"SIGNING_KEY", "SIGNING_PASSWORD", "CENTRAL_TOKEN_USERNAME", "CENTRAL_TOKEN_PASSWORD"}


def verify(root: Path, executable: Path, version: str, mantra: Path, metadata_mode: str = "pom", *,
           temporary_parent: Path | None = None) -> None:
    processes.require_posix()
    assert executable.is_file(), "Provide the already installed Gradle executable, not a new distribution"
    assert mantra.is_dir(), "The explicitly staged Mantra artifact repository is required"
    assert metadata_mode in {"pom", "gradle"}, "Metadata mode must be pom or gradle"
    source = root / "release-fixtures/mantra-clean-consumer"
    for script in source.rglob("*.gradle.kts"):
        # A comment may document the restriction; executable includeBuild is never allowed.
        lines = [line for line in script.read_text().splitlines() if not line.strip().startswith("//")]
        content = "\n".join(lines)
        assert "includeBuild(" not in content, f"Consumer must not source-substitute: {script}"
        assert "mavenLocal(" not in content, f"Consumer must not resolve a user-local artifact: {script}"
        assert "normeinRepository" not in content, f"Consumer kernel must resolve from Maven Central: {script}"
    directory = Path(tempfile.mkdtemp(prefix="mantra-clean-consumer-", dir=temporary_parent)).resolve()
    fixture = directory / "fixture"
    home = directory / "gradle-home"
    environment = dict(os.environ)
    environment.pop("NORMEIN_BUILD_PATH", None)
    for name in tuple(environment):
        if name.startswith(("MANTRA_SIGNING", "MANTRA_CENTRAL")) or name in RELEASE_SECRET_ALIASES:
            environment.pop(name)
    command = [str(executable), "--no-daemon", "--max-workers=1", "--console=plain", "--no-configuration-cache",
               "--gradle-user-home", str(home), "-p", str(fixture),
               "-Pkotlin.compiler.execution.strategy=in-process"]
    evidence = root / "build/release-checks/clean-consumer.log"
    cleanup_receipt = evidence.with_name("clean-consumer-cleanup.json")
    receipt = {"status": "CLEANUP_FAILED", "published": False, "metadataMode": metadata_mode,
               "processGroupsStopped": True, "gradleStopped": False, "temporaryStateRemoved": False}
    failure = None
    try:
        shutil.copytree(source, fixture, ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
        completed = processes.run_owned(command + [f"-PmantraVersion={version}",
                f"-PmantraRepository={mantra.resolve()}", f"-PmantraMetadataMode={metadata_mode}", "smoke"],
                cwd=fixture, env=environment, text=True, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, timeout=900)
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(completed.stdout)
        if completed.returncode != 0:
            raise AssertionError(f"Staged consumers failed with {completed.returncode}:\n{completed.stdout[-16000:]}")
        for marker in MARKERS:
            assert marker in completed.stdout, f"Consumer was not executed: {marker}\n{completed.stdout[-16000:]}"
    except BaseException as error:
        failure = error
        if isinstance(error, processes.CleanupError) and error.group is not None:
            receipt.update(processGroupsStopped=False, unverifiedProcessGroup=error.group)
    finally:
        try:
            stopped = processes.stop_gradle(executable, home, cwd=fixture, env=environment,
                                            stdout=subprocess.DEVNULL)
            receipt.update(gradleStopped=True, verifiedGradleDaemonPids=stopped["daemonPids"])
        except BaseException as error:
            if isinstance(error, processes.CleanupError) and error.group is not None:
                receipt.update(processGroupsStopped=False, unverifiedProcessGroup=error.group)
        if receipt["gradleStopped"] and receipt["processGroupsStopped"]:
            try:
                shutil.rmtree(directory)
            except OSError:
                pass
        receipt["temporaryStateRemoved"] = not directory.exists()
        if receipt["temporaryStateRemoved"]:
            receipt["status"] = "CLEANUP_VERIFIED"
        else:
            receipt["retainedTaskDirectory"] = str(directory)
        cleanup_receipt.parent.mkdir(parents=True, exist_ok=True)
        cleanup_receipt.write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n")
    if receipt["status"] != "CLEANUP_VERIFIED":
        raise processes.CleanupError("Consumer cleanup failed; task evidence and cleanup receipt retained",
                                     receipt.get("unverifiedProcessGroup"))
    if failure is not None:
        raise failure
    print(f"Staged Java/Kotlin consumers executed in {metadata_mode} metadata mode with fresh caches "
          "and Maven Central kernel; temporary state removed")
    print(f"Actual consumer output: {evidence}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--mantra-repository", type=Path, required=True)
    parser.add_argument("--metadata-mode", choices=("pom", "gradle"), default="pom")
    args = parser.parse_args()
    with processes.termination_guard():
        verify(args.root.resolve(), args.gradle.resolve(), args.version, args.mantra_repository, args.metadata_mode)
