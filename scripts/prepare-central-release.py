#!/usr/bin/env python3
"""Prepare signed, verified local release bytes; never upload or publish."""
from __future__ import annotations

import argparse
import base64
from contextlib import redirect_stdout
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from zipfile import ZipFile

import release_processes as processes

VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
COMMIT = re.compile(r"[a-f0-9]{40}\Z")
FINGERPRINT = re.compile(r"(?:[A-F0-9]{40}|[A-F0-9]{64})\Z")
KERNEL_SHA256 = "83a101ac90ae0c2a50bdc9a4b103d1c3de015df8bcbcafa4f63bbe5aa1562cc8"
SECRET_NAMES = {"SIGNING_KEY", "SIGNING_PASSWORD", "CENTRAL_TOKEN_USERNAME", "CENTRAL_TOKEN_PASSWORD"}


def validate_identity(version: str, commit: str, fingerprint: str) -> None:
    if len(version) > 64 or not VERSION.fullmatch(version):
        raise ValueError("An explicit stable major.minor.patch version is required")
    if not COMMIT.fullmatch(commit) or not FINGERPRINT.fullmatch(fingerprint):
        raise ValueError("Exact lowercase source commit and full uppercase signer fingerprint are required")


def signing_key(value: str) -> str:
    if len(value) > 128 * 1024:
        raise ValueError("Signing key exceeds the configured byte bound")
    if not value.startswith("-----BEGIN PGP PRIVATE KEY BLOCK-----"):
        try:
            value = base64.b64decode("".join(value.split()), validate=True).decode("ascii")
        except (ValueError, UnicodeError) as error:
            raise ValueError("Signing key must be armored ASCII or strict base64 of armored ASCII") from error
    if (not value.startswith("-----BEGIN PGP PRIVATE KEY BLOCK-----")
            or value.count("-----BEGIN PGP PRIVATE KEY BLOCK-----") != 1
            or value.count("-----END PGP PRIVATE KEY BLOCK-----") != 1
            or value.split("-----END PGP PRIVATE KEY BLOCK-----", 1)[1].strip()):
        raise ValueError("Exactly one armored private-key block is required")
    return value


def without_secrets(environment: dict[str, str]) -> dict[str, str]:
    return {key: value for key, value in environment.items()
            if key not in SECRET_NAMES and not key.startswith(("MANTRA_SIGNING", "MANTRA_CENTRAL"))
            and key != "NORMEIN_BUILD_PATH"}


def signing_fingerprints(listing: str, now: int) -> set[str]:
    """Select usable secret signing keys; never treat a short ID as the verification identity."""
    eligible = False
    primary_usable = True
    result = set()
    for line in listing.splitlines():
        fields = line.split(":")
        if fields[0] in {"sec", "ssb"}:
            if len(fields) < 12:
                raise ValueError("Incomplete GnuPG key listing")
            valid = fields[1] not in {"r", "e", "d", "i"}
            valid = valid and (not fields[6] or int(fields[6]) > now)
            if fields[0] == "sec":
                primary_usable = valid
            # Lowercase s means this particular key can sign; uppercase S is an aggregate capability.
            eligible = primary_usable and valid and "s" in fields[11] and "D" not in fields[11]
        elif fields[0] == "fpr" and eligible:
            if len(fields) < 10 or not FINGERPRINT.fullmatch(fields[9]):
                raise ValueError("Invalid full GnuPG fingerprint")
            result.add(fields[9])
            eligible = False
    return result


def select_signing_fingerprint(listing: str, expected: str, now: int) -> str:
    usable = signing_fingerprints(listing, now)
    if expected in usable:
        return expected
    primary = None
    current_kind = None
    candidates = set()
    for line in listing.splitlines():
        fields = line.split(":")
        if fields[0] in {"sec", "ssb"}:
            current_kind = fields[0]
        elif fields[0] == "fpr" and len(fields) >= 10:
            if current_kind == "sec":
                primary = fields[9]
            elif current_kind == "ssb" and primary == expected and fields[9] in usable:
                candidates.add(fields[9])
            current_kind = None
    if len(candidates) == 1:
        return candidates.pop()
    raise ValueError("Full fingerprint must identify a usable signing key or one unambiguous signing subkey")


def command(arguments: list[str], *, cwd: Path, environment: dict[str, str], log: Path,
            timeout: int = 1200) -> None:
    with log.open("ab") as output:
        completed = processes.run_owned(arguments, cwd=cwd, env=environment, stdout=output,
                                        stderr=subprocess.STDOUT, timeout=timeout)
    if completed.returncode:
        raise RuntimeError(f"Release preparation command failed; inspect {log.name}")


def prepare(root: Path, version: str, commit: str, fingerprint: str, output: Path) -> dict:
    processes.require_posix()
    validate_identity(version, commit, fingerprint)
    clean_environment = without_secrets(dict(os.environ))
    actual = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, env=clean_environment,
                            capture_output=True, text=True, check=True, timeout=30).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root,
                           env=clean_environment, capture_output=True, text=True, check=True, timeout=30).stdout
    if actual != commit or dirty.strip():
        raise ValueError("Release preparation requires the exact clean source commit")
    output = output.resolve()
    key = signing_key(os.environ.get("MANTRA_SIGNING_KEY", ""))
    password = os.environ.get("MANTRA_SIGNING_PASSWORD", "")
    if not password:
        raise ValueError("MANTRA_SIGNING_PASSWORD is required")
    executables = {}
    for name in ("gpg", "gpgv", "gpgconf"):
        found = shutil.which(name)
        if not found:
            raise ValueError(f"An already installed {name} is required")
        executables[name] = str(Path(found).resolve())
    # Resolve an already installed distribution before allocating a task home.
    # The signing build must not register a secret-bearing daemon in ~/.gradle.
    gradle_home = Path(clean_environment.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    candidates = list(gradle_home.glob("wrapper/dists/gradle-9.4.0-bin/*/gradle-9.4.0/bin/gradle"))
    if len(candidates) != 1 or not candidates[0].is_file():
        raise ValueError("One already installed Gradle 9.4.0 executable is required")
    gradle = candidates[0].resolve()
    output.mkdir(parents=True, exist_ok=False)
    log = output / "preparation.log"
    receipt = {"status": "PREPARATION_FAILED", "published": False, "sourceCommit": commit,
               "version": version, "signerFingerprint": fingerprint, "temporaryProcessesStopped": True}
    failure = None
    # macOS TMPDIR can exceed GnuPG's local socket-path limit. Keep the task-owned
    # directory short on POSIX without changing the user's global GnuPG home.
    temporary_parent = Path("/tmp").resolve()
    task = Path(tempfile.mkdtemp(prefix="mantra-release-", dir=temporary_parent)).resolve()
    home = task / "gnupg"
    owned_gradle_home = task / "gradle-home"
    consumer_temporary = task / "consumer-temporary"
    gpg = [executables["gpg"], "--no-options", "--homedir", str(home), "--batch"]

    def key_command(arguments: list[str], data: bytes | None = None) -> bytes:
        result = subprocess.run(gpg + arguments, input=data, env=clean_environment,
                                capture_output=True, check=False, timeout=60)
        if result.returncode:
            raise RuntimeError("GnuPG release-key operation failed; no secret material is logged")
        return result.stdout

    try:
        home.mkdir(mode=0o700)
        owned_gradle_home.mkdir(mode=0o700)
        consumer_temporary.mkdir(mode=0o700)
        try:
            key_command(["--import"], key.encode("ascii"))
            listing = key_command(["--with-colons", "--list-secret-keys"]).decode("utf-8")
            signing_fingerprint = select_signing_fingerprint(listing, fingerprint, int(time.time()))
            public_keyring = output / "signer-public.gpg"
            public_keyring.write_bytes(key_command(["--export", fingerprint]))
            stage = task / "staging"
            signing_environment = dict(clean_environment, MANTRA_SIGNING_KEY_ID=signing_fingerprint[-16:],
                                       MANTRA_SIGNING_KEY=key, MANTRA_SIGNING_PASSWORD=password)
            command([str(gradle), "--no-daemon", "--max-workers=1",
                     "--no-configuration-cache",
                     "--gradle-user-home", str(owned_gradle_home),
                     "-Pkotlin.compiler.execution.strategy=in-process",
                     f"-PmantraReleaseVersion={version}", "-PmantraSignRelease=true",
                     f"-PmantraStagingPath={stage}", "stageLibraries", "verifyLocalStaging",
                     ":mantra-cli:installDist"], cwd=root, environment=signing_environment, log=log)
            del signing_environment
            kernel = root / "mantra-cli/build/install/mantra/lib/normein-dsl-0.3.0.jar"
            if hashlib.sha256(kernel.read_bytes()).hexdigest() != KERNEL_SHA256:
                raise ValueError("Installed release does not use the verified public kernel binary")
            bundle = output / f"mantra-{version}-central.zip"
            result = processes.run_owned(["python3", str(root / "scripts/central-bundle.py"),
                "--repository", str(stage), "--version", version, "--normein-version", "0.3.0",
                "--gpgv", executables["gpgv"], "--public-keyring", str(public_keyring),
                "--signer-fingerprint", fingerprint, "--output", str(bundle)],
                cwd=root, env=clean_environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, timeout=180)
            if result.returncode:
                raise RuntimeError("Signed bundle verification failed: " + result.stderr[:2000])
            bundle_receipt = json.loads(result.stdout)
            bundle_receipt["sourceCommit"] = commit
            (output / "bundle-receipt.json").write_text(json.dumps(bundle_receipt, indent=2) + "\n")
            repository = task / "pom-only-repository"
            with ZipFile(bundle) as archive:
                for entry in archive.infolist():
                    target = repository / entry.filename
                    if not target.resolve().is_relative_to(repository):
                        raise ValueError("Invalid validated-bundle path")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(entry))
            # Keep consumer cleanup on this Python stack. An outer subprocess
            # kill must not bypass its finally while Gradle owns another group.
            specification = importlib.util.spec_from_file_location(
                "mantra_release_clean_consumer", root / "scripts/check-clean-consumer.py")
            consumer = importlib.util.module_from_spec(specification)
            specification.loader.exec_module(consumer)
            for mode in ("pom", "gradle"):
                with log.open("a") as output_log, redirect_stdout(output_log):
                    consumer.verify(root, gradle, version, repository, mode,
                                    temporary_parent=consumer_temporary)
                shutil.copyfile(root / "build/release-checks/clean-consumer.log", output / f"consumer-{mode}.log")
                shutil.copyfile(root / "build/release-checks/clean-consumer-cleanup.json",
                                output / f"consumer-{mode}-cleanup.json")
            receipt.update(status="SIGNED_VERIFIED_RELEASE_PREPARED_NOT_UPLOADED",
                           bundleSha256=bundle_receipt["bundleSha256"],
                           normeinJarSha256=KERNEL_SHA256, consumerModes=["pom", "gradle"],
                           actualSigningKeyFingerprint=signing_fingerprint)
        except BaseException as error:
            failure = error
            if isinstance(error, processes.CleanupError) and error.group is not None:
                receipt.update(temporaryProcessesStopped=False, unverifiedProcessGroup=error.group)
    except BaseException as error:
        failure = error
    finally:
        # This parent also owns any interrupted consumer's nested fresh home.
        # Attempt all stops even if one fails; never stop the shared user home.
        receipt["temporaryGradleStopped"] = True
        homes = [owned_gradle_home]
        children = list(consumer_temporary.glob("mantra-clean-consumer-*"))
        if len(children) > 4 or any(child.is_symlink() for child in children):
            receipt["temporaryGradleStopped"] = False
        else:
            homes.extend(child / "gradle-home" for child in children if child.is_dir())
        daemon_pids = set()
        for owned_home in homes:
            try:
                with log.open("ab") as output_log:
                    stopped = processes.stop_gradle(gradle, owned_home, cwd=root,
                                                    env=clean_environment, stdout=output_log)
                daemon_pids.update(stopped["daemonPids"])
            except BaseException:
                receipt["temporaryGradleStopped"] = False
        receipt["verifiedGradleDaemonPids"] = sorted(daemon_pids)
        try:
            if home.exists():
                stopped = subprocess.run([executables["gpgconf"], "--homedir", str(home), "--kill", "all"],
                                         env=clean_environment, capture_output=True, timeout=30, check=False)
                receipt["temporaryAgentStopped"] = stopped.returncode == 0
            else:
                receipt["temporaryAgentStopped"] = True
        except BaseException as error:
            receipt["temporaryAgentStopped"] = False
            if failure is None:
                failure = error
        if (receipt["temporaryAgentStopped"] and receipt["temporaryGradleStopped"]
                and receipt["temporaryProcessesStopped"]):
            try:
                shutil.rmtree(task)
            except OSError:
                pass
        elif receipt["temporaryAgentStopped"] and home.exists():
            # Remove on-disk private keys after stopping GPG, even if Gradle
            # exit could not be verified and its task evidence must be retained.
            try:
                shutil.rmtree(home)
            except OSError:
                pass
    receipt["temporaryKeyAndStagingRemoved"] = not task.exists()
    cleanup_failed = (not receipt["temporaryAgentStopped"] or not receipt["temporaryGradleStopped"]
                      or not receipt["temporaryProcessesStopped"]
                      or not receipt["temporaryKeyAndStagingRemoved"])
    if cleanup_failed:
        receipt.update(status="PREPARATION_FAILED", cleanupFailure=True, retainedTaskDirectory=str(task))
    (output / "preparation-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    if cleanup_failed:
        raise RuntimeError("Release preparation cleanup is incomplete; failure receipt retained")
    if failure is not None:
        raise RuntimeError("Release preparation failed; cleanup receipt retained") from failure
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--signer-fingerprint", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    with processes.termination_guard():
        receipt = prepare(Path(__file__).resolve().parents[1], args.version, args.source_commit,
                          args.signer_fingerprint, args.output_dir)
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
