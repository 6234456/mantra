#!/usr/bin/env python3
"""Execute staged POM consumers with fresh caches and a Maven Central kernel; no source composite."""
from pathlib import Path
from tempfile import TemporaryDirectory
import argparse
import os
import shutil
import subprocess

MARKERS = [
    "MANTRA_JAVA_EXCEL_CONSUMER_OK",
    "MANTRA_KOTLIN_CORE_CONSUMER_OK",
    "MANTRA_KOTLIN_WORKBENCH_CONSUMER_OK",
    "MANTRA_KOTLIN_SERVER_CONSUMER_OK",
    "MANTRA_KOTLIN_PACKAGES_CONSUMER_OK",
]
RELEASE_SECRET_ALIASES = {"SIGNING_KEY", "SIGNING_PASSWORD", "CENTRAL_TOKEN_USERNAME", "CENTRAL_TOKEN_PASSWORD"}


def verify(root: Path, executable: Path, version: str, mantra: Path, metadata_mode: str = "pom") -> None:
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
    with TemporaryDirectory(prefix="mantra-clean-consumer-") as temporary:
        directory = Path(temporary)
        fixture = directory / "fixture"
        home = directory / "gradle-home"
        shutil.copytree(source, fixture, ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
        command = [str(executable), "--no-daemon", "--max-workers=1", "--console=plain",
                   "--gradle-user-home", str(home), "-p", str(fixture),
                   "-Pkotlin.compiler.execution.strategy=in-process"]
        environment = dict(os.environ)
        environment.pop("NORMEIN_BUILD_PATH", None)
        for name in tuple(environment):
            if name.startswith(("MANTRA_SIGNING", "MANTRA_CENTRAL")) or name in RELEASE_SECRET_ALIASES:
                environment.pop(name)
        try:
            completed = subprocess.run(command + [f"-PmantraVersion={version}",
                f"-PmantraRepository={mantra.resolve()}", f"-PmantraMetadataMode={metadata_mode}", "smoke"],
                cwd=fixture, env=environment, text=True, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, timeout=900, check=False)
            evidence = root / "build/release-checks/clean-consumer.log"
            evidence.parent.mkdir(parents=True, exist_ok=True)
            evidence.write_text(completed.stdout)
            if completed.returncode != 0:
                raise AssertionError(f"Staged consumers failed with {completed.returncode}:\n{completed.stdout[-16000:]}")
            for marker in MARKERS:
                assert marker in completed.stdout, f"Consumer was not executed: {marker}\n{completed.stdout[-16000:]}"
        finally:
            # This owns only the new task home; never stop a pre-existing user's Gradle daemon.
            subprocess.run(command[:1] + ["--stop", "--gradle-user-home", str(home)], cwd=fixture,
                           env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=60, check=False)
    assert not Path(temporary).exists(), "Consumer fixture/cache cleanup failed"
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
    verify(args.root.resolve(), args.gradle.resolve(), args.version, args.mantra_repository, args.metadata_mode)
