#!/usr/bin/env python3
"""Offline, bounded six-library bundle preparation. Never upload or publish.

The CLI requires an explicit installed gpgv, public keyring and full fingerprint.
There is no credential, endpoint, upload, publish, or skip-verification option.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import stat
import struct
import subprocess
import tempfile
import time
from dataclasses import dataclass
from typing import Callable
import xml.etree.ElementTree as ET
from zipfile import ZIP_DEFLATED, ZIP_STORED, BadZipFile, ZipFile, ZipInfo

GROUP = "com.xqiou.mantra"
GROUP_PATH = "com/xqiou/mantra"
MODULES = (
    "mantra-core", "mantra-render", "mantra-excel",
    "mantra-workbench", "mantra-server", "mantra-packages",
)
PROJECT_URL = "https://github.com/6234456/mantra"
DEVELOPER_URL = "https://github.com/6234456"
SCM_CONNECTION = "scm:git:https://github.com/6234456/mantra.git"
SCM_DEVELOPER_CONNECTION = "scm:git:ssh://git@github.com/6234456/mantra.git"
PRIMARY_SUFFIXES = (".pom", ".jar", "-sources.jar", "-javadoc.jar")
HASHES = ("md5", "sha1", "sha256", "sha512")
REQUIRED_RUNTIME = {
    "mantra-render": {("org.apache.pdfbox", "pdfbox")},
    "mantra-workbench": {(GROUP, "mantra-excel"), ("org.apache.poi", "poi-ooxml"),
                         ("com.fasterxml.jackson.core", "jackson-databind")},
    "mantra-server": {("com.fasterxml.jackson.core", "jackson-databind")},
    "mantra-packages": {("com.fasterxml.jackson.core", "jackson-databind")},
}
REQUIRED_COMPILE = {
    "mantra-core": {("com.xqiou", "normein-dsl"), ("org.jetbrains.kotlin", "kotlin-stdlib")},
    "mantra-render": {(GROUP, "mantra-core"), ("org.jetbrains.kotlin", "kotlin-stdlib")},
    "mantra-excel": {(GROUP, "mantra-render"), ("org.apache.poi", "poi-ooxml"),
                     ("org.jetbrains.kotlin", "kotlin-stdlib")},
    "mantra-workbench": {(GROUP, "mantra-core"), (GROUP, "mantra-render"), (GROUP, "mantra-packages"),
                         ("org.jetbrains.kotlin", "kotlin-stdlib")},
    "mantra-server": {(GROUP, "mantra-workbench"), ("org.jetbrains.kotlin", "kotlin-stdlib")},
    "mantra-packages": {(GROUP, "mantra-core"), ("org.jetbrains.kotlin", "kotlin-stdlib")},
}
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
FINGERPRINT = re.compile(r"(?:[A-F0-9]{40}|[A-F0-9]{64})\Z")
POM_NS = "http://maven.apache.org/POM/4.0.0"


class BundleError(ValueError):
    """Preparation failed; nothing is published."""


@dataclass(frozen=True)
class Bounds:
    primary_bytes: int = 64 * 1024 * 1024
    total_input_bytes: int = 256 * 1024 * 1024
    pom_bytes: int = 1024 * 1024
    signature_bytes: int = 128 * 1024
    keyring_bytes: int = 4 * 1024 * 1024
    checksum_bytes: int = 1024
    entries_per_jar: int = 20000
    total_inflated_bytes: int = 256 * 1024 * 1024
    files_per_directory: int = 128
    verifier_output_bytes: int = 64 * 1024
    verifier_seconds: float = 5.0


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BundleError(message)


def stable_version(version: str) -> str:
    require(len(version) <= 64 and VERSION.fullmatch(version) is not None,
            "Choose an explicit stable major.minor.patch version; SNAPSHOT/prerelease is refused by project policy")
    return version


def no_symlinks(path: Path) -> Path:
    absolute = Path(os.path.abspath(path))
    for item in (absolute, *absolute.parents):
        require(not item.is_symlink(), f"Symlink path is refused: {item}")
    return absolute


def bounded_read(path: Path, limit: int) -> bytes:
    path = no_symlinks(path)
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError as exc:
        raise BundleError(f"Required readable regular file missing: {path.name}") from exc
    with os.fdopen(descriptor, "rb") as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode), f"Not a regular file: {path.name}")
        require(before.st_size <= limit, f"File exceeds byte bound: {path.name}")
        result = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
    require(len(result) <= limit, f"File exceeds byte bound: {path.name}")
    require((before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns)
            and len(result) == after.st_size, f"File changed while capturing: {path.name}")
    return result


def directory_names(path: Path, maximum: int) -> set[str]:
    path = no_symlinks(path)
    require(path.is_dir(), f"Required directory missing: {path}")
    names: set[str] = set()
    with os.scandir(path) as entries:
        for entry in entries:
            require(len(names) < maximum, f"Directory exceeds entry bound: {path}")
            require(not entry.is_symlink(), f"Symlink entry is refused: {entry.name}")
            names.add(entry.name)
    return names


def digest(data: bytes, algorithm: str) -> str:
    # MD5 and SHA-1 are Central compatibility sidecars, not the trust primitive.
    return hashlib.new(algorithm, data, usedforsecurity=False).hexdigest()


def required_text(parent: ET.Element, name: str) -> str:
    fields = parent.findall(f"{{{POM_NS}}}{name}")
    require(len(fields) == 1, f"POM must have exactly one {name}")
    value = (fields[0].text or "").strip()
    require(bool(value) and "${" not in value, f"POM {name} must be explicit nonblank text")
    return value


def validate_pom(data: bytes, artifact: str, version: str, normein_version: str) -> None:
    try:
        authored = data.decode("utf-8", errors="strict")
    except UnicodeError as exc:
        raise BundleError("POM must be UTF-8") from exc
    require("\x00" not in authored and "<!DOCTYPE" not in authored.upper() and "<!ENTITY" not in authored.upper(),
            "POM external declarations are refused")
    try:
        root = ET.fromstring(authored)
    except ET.ParseError as exc:
        raise BundleError(f"Malformed POM: {artifact}") from exc
    require(root.tag == f"{{{POM_NS}}}project", "POM must use Maven 4.0.0 namespace")
    require(required_text(root, "modelVersion") == "4.0.0", "Unexpected POM modelVersion")
    require(required_text(root, "groupId") == GROUP and required_text(root, "artifactId") == artifact
            and required_text(root, "version") == version, f"Wrong POM GAV for {artifact}")
    require(root.find(f"{{{POM_NS}}}parent") is None, "POM parent indirection is refused for this release kit")
    require(root.find(f"{{{POM_NS}}}profiles") is None, "POM profiles require a separate effective-model review")
    require(not root.findall(f".//{{{POM_NS}}}exclusions"), "POM dependency exclusions require separate review")
    require(not root.findall(f".//{{{POM_NS}}}systemPath"), "POM local systemPath dependencies are refused")
    packaging = root.findall(f"{{{POM_NS}}}packaging")
    require(not packaging or (len(packaging) == 1 and (packaging[0].text or "").strip() == "jar"),
            "Only jar packaging is supported")
    required_text(root, "name")
    required_text(root, "description")
    require(required_text(root, "url") == PROJECT_URL, "Wrong project URL")
    require(root.find(f"{{{POM_NS}}}repositories") is None
            and root.find(f"{{{POM_NS}}}pluginRepositories") is None, "POM repository overrides are refused")
    licenses = root.findall(f"{{{POM_NS}}}licenses/{{{POM_NS}}}license")
    require(any(required_text(item, "name") in {"Apache-2.0", "The Apache License, Version 2.0"}
                and required_text(item, "url") == "https://www.apache.org/licenses/LICENSE-2.0.txt"
                for item in licenses), "Apache-2.0 license metadata missing")
    developers = root.findall(f"{{{POM_NS}}}developers/{{{POM_NS}}}developer")
    require(any(required_text(item, "id") == "6234456" and required_text(item, "name")
                and required_text(item, "url") == DEVELOPER_URL for item in developers),
            "Reviewed developer identity/name/public GitHub URL missing")
    scm = root.findall(f"{{{POM_NS}}}scm")
    require(len(scm) == 1, "POM SCM metadata missing or duplicated")
    require(required_text(scm[0], "url") == PROJECT_URL
            and required_text(scm[0], "connection") == SCM_CONNECTION
            and required_text(scm[0], "developerConnection") == SCM_DEVELOPER_CONNECTION,
            "Wrong SCM metadata")
    def check_dependency_version(group: str, name: str, dependency_version: str) -> None:
        require(not any(value in dependency_version for value in ("SNAPSHOT", "${", "[", "]", "(", ")", ",", "+"))
                and dependency_version not in {"LATEST", "RELEASE"}, "Dependency must have an exact non-SNAPSHOT version")
        if group == GROUP:
            require(name in MODULES and dependency_version == version, "Wrong internal library dependency version")
        if (group, name) == ("com.xqiou", "normein-dsl"):
            require(artifact == "mantra-core" and dependency_version == normein_version, "Wrong pinned Normein dependency")

    # Kotlin's actual generated POM manages stdlib locally, omitting its direct version.
    # Accept only explicit in-document management, never a remote parent/BOM fetch.
    managed: dict[tuple[str, str], str] = {}
    management = root.findall(f"{{{POM_NS}}}dependencyManagement")
    require(len(management) <= 1, "Duplicate dependency management")
    for item in root.findall(f"{{{POM_NS}}}dependencyManagement/{{{POM_NS}}}dependencies/{{{POM_NS}}}dependency"):
        group, name, dependency_version = (required_text(item, field) for field in ("groupId", "artifactId", "version"))
        key = (group, name)
        require(key not in managed, "Duplicate managed dependency")
        require(item.findtext(f"{{{POM_NS}}}scope", "compile").strip() == "compile"
                and item.findtext(f"{{{POM_NS}}}type", "jar").strip() == "jar"
                and item.find(f"{{{POM_NS}}}classifier") is None, "Remote/imported BOM or managed classifier needs separate review")
        check_dependency_version(group, name, dependency_version)
        managed[key] = dependency_version
    dependencies: dict[tuple[str, str], str] = {}
    for item in root.findall(f"{{{POM_NS}}}dependencies/{{{POM_NS}}}dependency"):
        group, name = (required_text(item, field) for field in ("groupId", "artifactId"))
        key = (group, name)
        require(key not in dependencies, f"Duplicate dependency: {group}:{name}")
        versions = item.findall(f"{{{POM_NS}}}version")
        require(len(versions) <= 1, "Duplicate dependency version")
        dependency_version = required_text(item, "version") if versions else managed.get(key)
        require(dependency_version is not None, "Dependency version missing from both direct and local managed declarations")
        check_dependency_version(group, name, dependency_version)
        scope = item.findtext(f"{{{POM_NS}}}scope", "compile").strip()
        dependencies[key] = scope
        require(item.findtext(f"{{{POM_NS}}}optional", "false").strip() == "false", "Optional dependencies require separate review")
        require(item.findtext(f"{{{POM_NS}}}type", "jar").strip() == "jar"
                and item.find(f"{{{POM_NS}}}classifier") is None, "Dependency classifier/type needs separate review")
    require(all(dependencies.get(key) == "compile" for key in REQUIRED_COMPILE[artifact]),
            f"Public compile dependencies incomplete for {artifact}")
    require(all(dependencies.get(key) == "runtime" for key in REQUIRED_RUNTIME.get(artifact, set())),
            f"Runtime dependencies incomplete for {artifact}")


def validate_jar(data: bytes, suffix: str, bounds: Bounds, inflated: list[int]) -> None:
    # Check standard single-disk EOCD count before ZipFile can allocate central-directory objects.
    end = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    require(end >= 0 and end + 22 <= len(data), "JAR EOCD missing")
    _, disk, cd_disk, local_count, total_count, cd_size, cd_offset, comment_size = struct.unpack_from("<4s4H2IH", data, end)
    require(disk == cd_disk == 0 and local_count == total_count
            and 0 < total_count <= bounds.entries_per_jar and total_count != 65535
            and cd_size != 0xFFFFFFFF and cd_offset != 0xFFFFFFFF
            and cd_offset + cd_size == end and end + 22 + comment_size == len(data),
            "JAR must be bounded standard single-disk ZIP without trailing bytes")
    try:
        with ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            require(len(entries) == total_count, "JAR entry count disagrees with EOCD")
            seen: set[str] = set()
            useful = False
            for entry in entries:
                name = entry.filename
                require(name not in seen, "Duplicate JAR entry")
                seen.add(name)
                require(name and not name.startswith("/") and "\\" not in name and "\x00" not in name
                        and ".." not in PurePosixPath(name).parts, "Unsafe JAR member name")
                require(not entry.flag_bits & 1 and entry.compress_type in {ZIP_STORED, ZIP_DEFLATED}
                        and not stat.S_ISLNK(entry.external_attr >> 16), "Unsupported JAR member")
                require(inflated[0] + entry.file_size <= bounds.total_inflated_bytes, "JAR inflated byte bound exceeded")
                with archive.open(entry) as stream:
                    while chunk := stream.read(65536):
                        inflated[0] += len(chunk)
                        require(inflated[0] <= bounds.total_inflated_bytes, "JAR actual inflated byte bound exceeded")
                if suffix == ".jar":
                    useful |= name.startswith("com/xqiou/mantra/") and name.endswith(".class") and entry.file_size > 0
                elif suffix == "-sources.jar":
                    useful |= name.endswith((".kt", ".java")) and entry.file_size > 0
                else:
                    useful |= name.endswith("index.html") and entry.file_size > 0
            require(useful, f"Missing real binary/source/API-HTML content: {suffix}")
    except (OSError, RuntimeError, ValueError, BadZipFile) as exc:
        if isinstance(exc, BundleError):
            raise
        raise BundleError("Invalid or corrupt JAR") from exc


def validate_status(status: bytes, expected_fingerprint: str) -> None:
    require(len(status) <= Bounds().verifier_output_bytes, "Verifier status output exceeds bound")
    try:
        lines = status.decode("ascii", errors="strict").splitlines()
    except UnicodeError as exc:
        raise BundleError("Invalid gpgv status encoding") from exc
    valid = []
    bad = {"BADSIG", "ERRSIG", "NO_PUBKEY", "EXPSIG", "EXPKEYSIG", "REVKEYSIG", "FAILURE", "ERROR", "NODATA"}
    for line in lines:
        require(line.startswith("[GNUPG:] "), "Unexpected verifier stdout")
        fields = line[len("[GNUPG:] "):].split()
        require(bool(fields) and fields[0] not in bad, "Signature verifier reported a failure")
        if fields[0] == "VALIDSIG":
            # sign-fpr date timestamp expiry version reserved pubalgo hashalgo class [primary-fpr]
            require(len(fields) in {10, 11} and FINGERPRINT.fullmatch(fields[1]) is not None,
                    "Malformed VALIDSIG status")
            primary = fields[10] if len(fields) == 11 else fields[1]
            require(FINGERPRINT.fullmatch(primary) is not None, "Malformed primary-key fingerprint")
            require(expected_fingerprint in {fields[1], primary}, "Signature is from an unreviewed key")
            require(fields[8] in {"8", "9", "10", "11"}, "Only SHA-2 PGP signature digests are accepted")
            valid.append(fields)
    require(len(valid) == 1, "Exactly one expected-key valid detached signature is required")


class GpgvVerifier:
    """Cryptographic matching only. Current authorization/revocation needs human review.

    gpgv does not perform key expiration/revocation policy checks. Its keyring must
    be an independently reviewed public keyring, not a secret or default keyring.
    """

    def __init__(self, executable: Path, keyring: Path, fingerprint: str, bounds: Bounds):
        require(FINGERPRINT.fullmatch(fingerprint) is not None, "An explicit full uppercase signer fingerprint is required")
        self.executable = no_symlinks(executable)
        require(self.executable.is_absolute() and self.executable.is_file()
                and os.access(self.executable, os.X_OK), "Explicit installed regular gpgv executable missing")
        self.keyring = bounded_read(keyring, bounds.keyring_bytes)
        require(bool(self.keyring), "Public keyring is empty")
        self.fingerprint, self.bounds = fingerprint, bounds

    def __call__(self, primary: bytes, signature: bytes) -> None:
        with tempfile.TemporaryDirectory(prefix="mantra-central-verify-") as temporary:
            location = Path(temporary)
            (location / "public-keyring.gpg").write_bytes(self.keyring)
            (location / "artifact").write_bytes(primary)
            (location / "artifact.asc").write_bytes(signature)
            command = [str(self.executable), "--homedir", str(location), "--keyring",
                       str(location / "public-keyring.gpg"), "--status-fd", "1",
                       str(location / "artifact.asc"), str(location / "artifact")]
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                       stderr=subprocess.DEVNULL, env={"LC_ALL": "C", "PATH": os.defpath})
            captured = bytearray()
            try:
                require(process.stdout is not None, "gpgv stdout pipe unavailable")
                os.set_blocking(process.stdout.fileno(), False)
                deadline = time.monotonic() + self.bounds.verifier_seconds
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stdout, selectors.EVENT_READ)
                    while selector.get_map():
                        remaining = deadline - time.monotonic()
                        require(remaining > 0, "gpgv verification timed out")
                        for key, _ in selector.select(min(remaining, 0.1)):
                            chunk = os.read(key.fileobj.fileno(), 4096)
                            if not chunk:
                                selector.unregister(key.fileobj)
                            else:
                                captured.extend(chunk)
                                require(len(captured) <= self.bounds.verifier_output_bytes, "gpgv status byte bound exceeded")
                    require(process.wait(timeout=max(0.01, deadline - time.monotonic())) == 0,
                            "gpgv could not verify detached signature")
                validate_status(bytes(captured), self.fingerprint)
            except subprocess.TimeoutExpired as exc:
                raise BundleError("gpgv verification timed out") from exc
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait()
                if process.stdout is not None:
                    process.stdout.close()


@dataclass(frozen=True)
class PreparedBundle:
    data: bytes
    receipt: dict


def prepare_bundle(repository: Path, version: str, normein_version: str,
                   verifier: Callable[[bytes, bytes], None] | None,
                   fingerprint: str, bounds: Bounds = Bounds()) -> PreparedBundle:
    stable_version(version)
    stable_version(normein_version)
    require(verifier is not None, "No signature verifier configured; unsigned preparation is refused")
    require(FINGERPRINT.fullmatch(fingerprint) is not None, "Reviewed signer fingerprint missing")
    root = no_symlinks(repository) / GROUP_PATH
    require(directory_names(root, len(MODULES) + 1) == set(MODULES), "Staging namespace must contain exactly the six libraries")
    retained = 0
    inflated = [0]
    files: dict[str, bytes] = {}
    primaries = []
    excluded = []

    def capture(path: Path, maximum: int) -> bytes:
        nonlocal retained
        result = bounded_read(path, min(maximum, bounds.total_input_bytes - retained))
        retained += len(result)
        require(retained <= bounds.total_input_bytes, "Total retained staging byte bound exceeded")
        return result

    for module in MODULES:
        directory = root / module / version
        names = directory_names(directory, bounds.files_per_directory)
        prefix = f"{module}-{version}"
        expected = {prefix + suffix for suffix in PRIMARY_SUFFIXES}
        allowed = set()
        for name in (*expected, prefix + ".module"):
            allowed.add(name)
            allowed.add(name + ".asc")
            for algorithm in HASHES:
                allowed.add(name + "." + algorithm)
                allowed.add(name + ".asc." + algorithm)
        require(names <= allowed, f"Unexpected release-directory files: {sorted(names - allowed)}")
        excluded.extend(f"{GROUP_PATH}/{module}/{version}/{name}" for name in sorted(names) if name.startswith(prefix + ".module"))
        for suffix in PRIMARY_SUFFIXES:
            name = prefix + suffix
            primary = capture(directory / name, bounds.pom_bytes if suffix == ".pom" else bounds.primary_bytes)
            signature = capture(directory / (name + ".asc"), bounds.signature_bytes)
            require(signature.startswith(b"-----BEGIN PGP SIGNATURE-----")
                    and b"-----END PGP SIGNATURE-----" in signature, "Detached ASCII-armored PGP signature missing")
            if suffix == ".pom":
                validate_pom(primary, module, version, normein_version)
            else:
                validate_jar(primary, suffix, bounds, inflated)
            verifier(primary, signature)
            relative = f"{GROUP_PATH}/{module}/{version}/{name}"
            files[relative], files[relative + ".asc"] = primary, signature
            for algorithm in HASHES:
                expected_digest = digest(primary, algorithm)
                sidecar = directory / (name + "." + algorithm)
                if algorithm in {"md5", "sha1"} or sidecar.name in names:
                    raw = capture(sidecar, bounds.checksum_bytes)
                    try:
                        actual = raw.decode("ascii").strip().lower()
                    except UnicodeError as exc:
                        raise BundleError("Non-ASCII checksum sidecar") from exc
                    require(actual == expected_digest, f"Checksum mismatch: {name}.{algorithm}")
                # Add all four canonical sidecars; SHA-2 can be generated from captured signed bytes.
                files[relative + "." + algorithm] = (expected_digest + "\n").encode("ascii")
            primaries.append({"path": relative, "bytes": len(primary), "sha256": digest(primary, "sha256")})
    require(len(files) == 144 and len(primaries) == 24, "Unexpected bundle artifact cardinality")
    buffer = io.BytesIO()
    with ZipFile(buffer, "w", compression=ZIP_STORED, allowZip64=False) as archive:
        for name in sorted(files):
            info = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = ZIP_STORED
            archive.writestr(info, files[name])
    bundle = buffer.getvalue()
    require(len(bundle) <= bounds.total_input_bytes + 1024 * 1024, "Output ZIP byte bound exceeded")
    return PreparedBundle(bundle, {
        "status": "LOCAL_VERIFIED_BUNDLE_NOT_UPLOADED", "published": False,
        "group": GROUP, "version": version, "libraries": list(MODULES),
        "normeinDependency": f"com.xqiou:normein-dsl:{normein_version}",
        "namespaceOwnership": "NOT_VERIFIED", "publicNormeinAvailability": "NOT_VERIFIED",
        "signerAuthorizationAndRevocation": "REQUIRES_SEPARATE_REVIEW",
        "signerFingerprint": fingerprint, "entryCount": len(files),
        "primaryArtifacts": primaries, "excludedGradleModuleMetadata": excluded,
        "bundleBytes": len(bundle), "bundleSha256": digest(bundle, "sha256"),
    })


def write_new_bundle(output: Path, data: bytes) -> None:
    output = no_symlinks(output)
    require(output.parent.is_dir(), "Output parent must exist")
    try:
        descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o644)
    except OSError as exc:
        raise BundleError("Output must be a new writable file; existing output is never replaced") from exc
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        output.unlink(missing_ok=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--normein-version", required=True)
    parser.add_argument("--gpgv", type=Path, required=True)
    parser.add_argument("--public-keyring", type=Path, required=True)
    parser.add_argument("--signer-fingerprint", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        stable_version(args.version)
        stable_version(args.normein_version)
        verifier = GpgvVerifier(args.gpgv, args.public_keyring, args.signer_fingerprint, Bounds())
        prepared = prepare_bundle(args.repository, args.version, args.normein_version, verifier, args.signer_fingerprint)
        write_new_bundle(args.output, prepared.data)
    except (BundleError, OSError) as exc:
        parser.exit(1, f"Bundle preparation refused: {exc}\nNo upload or publication was attempted.\n")
    print(json.dumps(prepared.receipt, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
