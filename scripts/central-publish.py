#!/usr/bin/env python3
"""Bounded, explicit USER_MANAGED Central operations; never auto-publish or delete.

Credentials come only from MANTRA_CENTRAL_USERNAME/PASSWORD or
CENTRAL_TOKEN_USERNAME/PASSWORD. They never enter arguments, receipts or errors.
See https://central.sonatype.org/publish/publish-portal-api/.
"""
from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import re
import socket
import stat
import struct
import tempfile
import threading
import time
from typing import Mapping
from urllib.parse import urlencode
from zipfile import BadZipFile, ZipFile, ZIP_STORED
import xml.etree.ElementTree as ET

HOST = "central.sonatype.com"
GROUP = "com.xqiou.mantra"
GROUP_PATH = "com/xqiou/mantra"
MODULES = (
    "mantra-core", "mantra-render", "mantra-excel",
    "mantra-workbench", "mantra-server", "mantra-packages",
)
SUFFIXES = (".pom", ".jar", "-sources.jar", "-javadoc.jar")
HASHES = ("md5", "sha1", "sha256", "sha512")
STATES = {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"}
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
FINGERPRINT = re.compile(r"(?:[A-F0-9]{40}|[A-F0-9]{64})\Z")
CHUNK = 65536


class PublishError(ValueError):
    """A bounded operation was refused or its remote result is uncertain."""


@dataclass(frozen=True)
class Bounds:
    bundle_bytes: int = 257 * 1024 * 1024
    receipt_bytes: int = 256 * 1024
    response_bytes: int = 512 * 1024
    request_seconds: float = 120.0


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PublishError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def expected_identity(version: str, source_commit: str, bundle_sha256: str) -> dict:
    require(len(version) <= 64 and VERSION.fullmatch(version) is not None,
            "An explicit stable major.minor.patch version is required")
    require(COMMIT.fullmatch(source_commit) is not None, "Expected source commit must be 40 lowercase hex digits")
    require(SHA256.fullmatch(bundle_sha256) is not None, "Expected bundle SHA-256 must be 64 lowercase hex digits")
    return {"group": GROUP, "version": version, "sourceCommit": source_commit,
            "bundleSha256": bundle_sha256, "libraries": list(MODULES),
            "gavs": sorted(f"{GROUP}:{module}:{version}" for module in MODULES)}


def regular_path(path: Path) -> Path:
    result = Path(os.path.abspath(path))
    require(all(not item.is_symlink() for item in (result, *result.parents)), "Symbolic-link paths are refused")
    return result


def bounded_read(path: Path, limit: int) -> bytes:
    path = regular_path(path)
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "rb") as stream:
            before = os.fstat(stream.fileno())
            require(stat.S_ISREG(before.st_mode) and before.st_size <= limit, "Input must be a bounded regular file")
            data = stream.read(limit + 1)
            after = os.fstat(stream.fileno())
        require(len(data) <= limit and len(data) == after.st_size
                and (before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns),
                "Input changed during capture or exceeds its byte bound")
        return data
    except OSError:
        raise PublishError("A required bounded input could not be read") from None


def strict_json(data: bytes) -> dict:
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "Duplicate JSON keys are refused")
            result[key] = value
        return result

    def nonfinite(_):
        raise PublishError("Nonfinite JSON numbers are refused")

    try:
        result = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=pairs, parse_constant=nonfinite)
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise PublishError("A receipt or response is not bounded strict UTF-8 JSON") from None
    require(type(result) is dict, "A JSON object is required")
    return result


def authentication(environment: Mapping[str, str]) -> str:
    pairs = [(environment.get("MANTRA_CENTRAL_USERNAME"), environment.get("MANTRA_CENTRAL_PASSWORD")),
             (environment.get("CENTRAL_TOKEN_USERNAME"), environment.get("CENTRAL_TOKEN_PASSWORD"))]
    selected = [pair for pair in pairs if any(value is not None for value in pair)]
    require(bool(selected), "Central username/password environment variables are missing")
    require(all(all(type(value) is str and value for value in pair) for pair in selected),
            "Central credential environment mapping is incomplete")
    require(all(pair == selected[0] for pair in selected), "Conflicting credential environment mappings are refused")
    username, password = selected[0]
    require(":" not in username and all(32 < ord(char) < 127 for char in username + password)
            and len(username) + len(password) <= 8192, "Central credential environment values are invalid")
    return "Bearer " + base64.b64encode((username + ":" + password).encode("ascii")).decode("ascii")


def verify_bundle(data: bytes, review_data: bytes, identity: dict, bounds: Bounds) -> dict:
    require(len(data) <= bounds.bundle_bytes and sha256(data) == identity["bundleSha256"], "Bundle SHA-256 mismatch")
    require(len(review_data) <= bounds.receipt_bytes, "Review receipt exceeds its byte bound")
    review = strict_json(review_data)
    require(review.get("status") == "LOCAL_VERIFIED_BUNDLE_NOT_UPLOADED" and review.get("published") is False,
            "An offline verified, not-uploaded bundle receipt is required")
    require(review.get("group") == GROUP and review.get("version") == identity["version"]
            and type(review.get("libraries")) is list and len(review["libraries"]) == 6
            and all(type(module) is str for module in review["libraries"])
            and set(review["libraries"]) == set(MODULES), "Review receipt must identify exactly the six expected GAVs")
    require(review.get("bundleSha256") == identity["bundleSha256"] and type(review.get("bundleBytes")) is int
            and review["bundleBytes"] == len(data) and review.get("entryCount") == 144, "Review bundle identity mismatch")
    require("sourceCommit" not in review or review["sourceCommit"] == identity["sourceCommit"], "Review source commit mismatch")
    require(type(review.get("signerFingerprint")) is str and FINGERPRINT.fullmatch(review["signerFingerprint"]) is not None,
            "Review signer fingerprint is missing")
    require(review.get("normeinDependency") == "com.xqiou:normein-dsl:0.3.0", "Review must use the pinned public DSL")
    primaries = {
        f"{GROUP_PATH}/{module}/{identity['version']}/{module}-{identity['version']}{suffix}"
        for module in MODULES for suffix in SUFFIXES
    }
    records = review.get("primaryArtifacts")
    require(type(records) is list and len(records) == 24, "Review must contain exactly 24 primary artifacts")
    observed = {}
    for item in records:
        require(type(item) is dict and type(item.get("path")) is str and item["path"] not in observed,
                "Duplicate or malformed reviewed artifact")
        require(type(item.get("bytes")) is int and 0 < item["bytes"] <= bounds.bundle_bytes
                and type(item.get("sha256")) is str and SHA256.fullmatch(item["sha256"]) is not None,
                "Reviewed artifact size/hash is invalid")
        observed[item["path"]] = item
    require(set(observed) == primaries, "Reviewed artifact paths do not match the six expected GAVs")
    expected_files = {name + suffix for name in primaries for suffix in ("", ".asc", *("." + h for h in HASHES))}
    end = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    require(end >= 0 and end + 22 <= len(data), "Bundle ZIP EOCD is missing")
    _, disk, cd_disk, count, total, size, offset, comment = struct.unpack_from("<4s4H2IH", data, end)
    require(disk == cd_disk == 0 and count == total == 144 and offset + size == end
            and end + 22 + comment == len(data), "Bundle must be the bounded 144-entry release ZIP")
    try:
        with ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            require(len(entries) == 144 and {entry.filename for entry in entries} == expected_files
                    and all(entry.compress_type == ZIP_STORED and not entry.flag_bits & 1
                            and not stat.S_ISLNK(entry.external_attr >> 16) for entry in entries),
                    "Bundle layout differs from the reviewed release")
            for name, record in observed.items():
                entry = archive.getinfo(name)
                require(entry.file_size == record["bytes"], "Bundle primary size differs from review")
                digest = hashlib.sha256()
                with archive.open(entry) as stream:
                    while chunk := stream.read(CHUNK):
                        digest.update(chunk)
                require(digest.hexdigest() == record["sha256"], "Bundle primary digest differs from review")
                if name.endswith(".pom"):
                    require(entry.file_size <= 1024 * 1024, "Bundle POM exceeds its byte bound")
                    pom_data = archive.read(entry)
                    require(b"<!DOCTYPE" not in pom_data.upper() and b"<!ENTITY" not in pom_data.upper(), "POM declarations are refused")
                    pom = ET.fromstring(pom_data.decode("utf-8", errors="strict"))
                    ns = "{http://maven.apache.org/POM/4.0.0}"
                    module = name.split("/")[3]
                    for field, expected in (("groupId", GROUP), ("artifactId", module), ("version", identity["version"])):
                        values = pom.findall(ns + field)
                        require(len(values) == 1 and (values[0].text or "").strip() == expected, "Bundle POM GAV mismatch")
    except (BadZipFile, OSError, UnicodeError, ET.ParseError, RuntimeError, RecursionError):
        raise PublishError("Bundle ZIP/POM content is invalid") from None
    return {**identity, "reviewReceiptSha256": sha256(review_data), "bundleBytes": len(data),
            "signerFingerprint": review["signerFingerprint"], "publishingType": "USER_MANAGED",
            "deploymentName": f"mantra-{identity['version']}-{identity['sourceCommit'][:12]}-{identity['bundleSha256'][:12]}"}


class PortalClient:
    def __init__(self, authorization: str, bounds: Bounds = Bounds(), connection_factory=None, clock=time.monotonic):
        self._authorization = authorization
        self.bounds, self._clock = bounds, clock
        self._connection = connection_factory or http.client.HTTPSConnection

    def _post(self, path: str, parts: tuple[bytes, ...], content_type: str, expected_status: int) -> bytes:
        require(sum(len(part) for part in parts) <= self.bounds.bundle_bytes + 4096, "Request exceeds its byte bound")
        deadline = self._clock() + self.bounds.request_seconds
        connection = None
        response = None
        last_socket = [None]
        expired = threading.Event()

        def expire():
            expired.set()
            # Interrupt a response/header read even when a peer keeps delivering small chunks.
            # Do not close HTTPResponse's buffered reader from another thread.
            if last_socket[0] is not None:
                try:
                    last_socket[0].shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

        watchdog = threading.Timer(self.bounds.request_seconds, expire)
        watchdog.daemon = True

        def checkpoint():
            remaining = deadline - self._clock()
            require(remaining > 0 and not expired.is_set(), "Portal request exceeded its total deadline")
            if connection is not None and connection.sock is not None:
                last_socket[0] = connection.sock
                connection.sock.settimeout(remaining)
            return remaining

        try:
            watchdog.start()
            connection = self._connection(HOST, 443, timeout=checkpoint())
            connection.set_debuglevel(0)
            connection.putrequest("POST", path)
            for name, value in (("Authorization", self._authorization), ("Content-Type", content_type),
                                ("Accept", "application/json, text/plain"), ("Content-Length", str(sum(map(len, parts))))):
                connection.putheader(name, value)
            checkpoint()
            connection.endheaders()
            for part in parts:
                for offset in range(0, len(part), CHUNK):
                    checkpoint()
                    connection.send(memoryview(part)[offset:offset + CHUNK])
            checkpoint()
            response = connection.getresponse()
            require(not 300 <= response.status < 400, "Portal redirect refused; authentication was not forwarded")
            require(response.status == expected_status, f"Portal operation returned unexpected HTTP {response.status}")
            length = response.getheader("Content-Length")
            require(length is None or type(length) is str and len(length) <= 12 and length.isascii() and length.isdigit()
                    and int(length) <= self.bounds.response_bytes, "Portal response exceeds its byte bound")
            data = bytearray()
            while True:
                checkpoint()
                chunk = response.read(min(CHUNK, self.bounds.response_bytes + 1 - len(data)))
                if not chunk:
                    break
                data.extend(chunk)
                require(len(data) <= self.bounds.response_bytes, "Portal response exceeds its byte bound")
            checkpoint()
            return bytes(data)
        except (OSError, socket.timeout, http.client.HTTPException):
            raise PublishError("Portal transport failed; no automatic retry was attempted") from None
        finally:
            watchdog.cancel()
            watchdog.join()
            if response is not None:
                response.close()
            if connection is not None:
                connection.close()

    def upload(self, data: bytes, name: str) -> str:
        require(len(data) <= self.bounds.bundle_bytes, "Upload exceeds its byte bound")
        boundary = "mantra-" + sha256(data)
        head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"mantra.zip\"\r\n"
                "Content-Type: application/zip\r\n\r\n").encode("ascii")
        tail = f"\r\n--{boundary}--\r\n".encode("ascii")
        path = "/api/v1/publisher/upload?" + urlencode({"publishingType": "USER_MANAGED", "name": name})
        data = self._post(path, (head, data, tail), "multipart/form-data; boundary=" + boundary, 201)
        try:
            deployment_id = data.decode("ascii", errors="strict").strip()
        except UnicodeError:
            raise PublishError("Portal returned an invalid deployment ID") from None
        require(UUID.fullmatch(deployment_id) is not None, "Portal returned an invalid deployment ID")
        return deployment_id

    def status(self, deployment_id: str, expected_gavs: list[str]) -> dict:
        require(UUID.fullmatch(deployment_id) is not None, "Invalid expected deployment ID")
        data = strict_json(self._post("/api/v1/publisher/status?" + urlencode({"id": deployment_id}), (), "application/json", 200))
        require(data.get("deploymentId") == deployment_id and type(data.get("deploymentState")) is str
                and data["deploymentState"] in STATES,
                "Portal status identity/state is invalid")
        purls = data.get("purls")
        expected = {"pkg:maven/" + gav.replace(":", "/", 1).replace(":", "@", 1) for gav in expected_gavs}
        if purls is not None:
            require(type(purls) is list and len(purls) <= 6 and all(type(purl) is str for purl in purls)
                    and len(set(purls)) == len(purls) and set(purls) <= expected,
                    "Portal artifacts do not match the six reviewed GAVs")
        if data["deploymentState"] in {"VALIDATED", "PUBLISHING", "PUBLISHED"}:
            require(type(purls) is list and set(purls) == expected, "Validated/published status must identify all six reviewed GAVs")
        errors = data.get("errors")
        return {"deploymentId": deployment_id, "deploymentState": data["deploymentState"],
                "remoteErrorCount": len(errors) if type(errors) in (dict, list) else 0}

    def publish(self, deployment_id: str) -> None:
        require(UUID.fullmatch(deployment_id) is not None, "Invalid expected deployment ID")
        require(not self._post("/api/v1/publisher/deployment/" + deployment_id, (), "application/json", 204),
                "Publish acceptance response must have an empty body")


@contextmanager
def receipt_lock(path: Path):
    path = regular_path(path)
    require(path.parent.is_dir(), "Receipt parent must already exist")
    lock = path.with_name(path.name + ".lock")
    try:
        descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    except OSError:
        raise PublishError("Receipt is locked; no remote operation was attempted") from None
    try:
        os.close(descriptor)
        yield path
    finally:
        lock.unlink(missing_ok=True)


def write_receipt(path: Path, data: dict, bounds: Bounds) -> None:
    payload = (json.dumps(data, sort_keys=True, indent=2) + "\n").encode("utf-8")
    require(len(payload) <= bounds.receipt_bytes, "Deployment receipt exceeds its byte bound")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".mantra-central-", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
        directory = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    except OSError:
        raise PublishError("Deployment receipt could not be persisted; reconcile the Portal before retrying") from None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def load_deployment(path: Path, identity: dict, expected_id: str, bounds: Bounds) -> dict:
    require(UUID.fullmatch(expected_id) is not None, "An exact stored deployment ID is required")
    data = strict_json(bounded_read(path, bounds.receipt_bytes))
    require(data.get("format") == "mantra-central-deployment/1" and data.get("host") == HOST
            and data.get("publishingType") == "USER_MANAGED" and data.get("deploymentId") == expected_id,
            "Stored deployment identity/host/mode mismatch")
    require(all(data.get(key) == value for key, value in identity.items()), "Stored source/version/bundle/GAV identity mismatch")
    require(type(data.get("reviewReceiptSha256")) is str and SHA256.fullmatch(data["reviewReceiptSha256"]) is not None
            and type(data.get("signerFingerprint")) is str and FINGERPRINT.fullmatch(data["signerFingerprint"]) is not None,
            "Stored review identity is invalid")
    expected_name = f"mantra-{identity['version']}-{identity['sourceCommit'][:12]}-{identity['bundleSha256'][:12]}"
    require(data.get("deploymentName") == expected_name and type(data.get("bundleBytes")) is int
            and 0 < data["bundleBytes"] <= bounds.bundle_bytes, "Stored bundle/name identity is invalid")
    phases = {"UPLOADED_NOT_PUBLISHED", "PUBLISH_REQUEST_PREPARED", "PUBLISH_OUTCOME_UNKNOWN",
              "PUBLISH_REQUEST_ACCEPTED", "REMOTE_STATE_OBSERVED"}
    require(type(data.get("phase")) is str and data["phase"] in phases
            and (data.get("lastObservedState") is None or type(data["lastObservedState"]) is str and data["lastObservedState"] in STATES)
            and type(data.get("published")) is bool and type(data.get("publishAttempted")) is bool,
            "Stored lifecycle fields are invalid")
    require("remoteErrorCount" not in data or type(data["remoteErrorCount"]) is int
            and 0 <= data["remoteErrorCount"] <= bounds.response_bytes, "Stored error count is invalid")
    # Return only known fields; remote payloads and unreviewed receipt fields are never echoed.
    keys = {*identity, "format", "host", "publishingType", "deploymentId", "reviewReceiptSha256", "signerFingerprint",
            "bundleBytes", "deploymentName", "phase", "lastObservedState", "remoteErrorCount", "published", "publishAttempted"}
    return {key: value for key, value in data.items() if key in keys}


def operate(args, environment=None, client_factory=None, bounds: Bounds = Bounds()) -> dict:
    identity = expected_identity(args.version, args.source_commit, args.bundle_sha256)
    environment = os.environ if environment is None else environment
    client_factory = client_factory or (lambda authorization: PortalClient(authorization, bounds))
    with receipt_lock(args.deployment_receipt) as path:
        if args.operation == "upload":
            require(not path.exists(), "Existing deployment receipt is never replaced by another upload")
            data = bounded_read(args.bundle, bounds.bundle_bytes)
            verified = verify_bundle(data, bounded_read(args.review_receipt, bounds.receipt_bytes), identity, bounds)
            client = client_factory(authentication(environment))
            receipt = {**verified, "format": "mantra-central-deployment/1", "host": HOST,
                       "deploymentId": None, "phase": "UPLOAD_REQUEST_PREPARED", "lastObservedState": None,
                       "published": False, "publishAttempted": False}
            write_receipt(path, receipt, bounds)
            try:
                receipt["deploymentId"] = client.upload(data, verified["deploymentName"])
            except Exception:
                receipt["phase"] = "UPLOAD_OUTCOME_UNKNOWN"
                write_receipt(path, receipt, bounds)
                raise PublishError("Upload outcome is uncertain; use the saved receipt and reconcile the Portal, without automatic retry") from None
            receipt["phase"] = "UPLOADED_NOT_PUBLISHED"
            write_receipt(path, receipt, bounds)
            return receipt
        receipt = load_deployment(path, identity, args.deployment_id, bounds)
        client = client_factory(authentication(environment))
        if args.operation == "publish":
            require(receipt.get("publishAttempted") is False, "A publish was already attempted; inspect status/Portal before any retry")
        observed = client.status(args.deployment_id, identity["gavs"])
        receipt.update(lastObservedState=observed["deploymentState"], remoteErrorCount=observed["remoteErrorCount"],
                       published=observed["deploymentState"] == "PUBLISHED", phase="REMOTE_STATE_OBSERVED")
        write_receipt(path, receipt, bounds)
        if args.operation == "status":
            return receipt
        require(observed["deploymentState"] == "VALIDATED", "Publish requires a freshly observed VALIDATED deployment")
        receipt.update(phase="PUBLISH_REQUEST_PREPARED", publishAttempted=True)
        write_receipt(path, receipt, bounds)
        try:
            client.publish(args.deployment_id)
        except Exception:
            receipt["phase"] = "PUBLISH_OUTCOME_UNKNOWN"
            write_receipt(path, receipt, bounds)
            raise PublishError("Publish outcome is uncertain; inspect status/Portal, without automatic retry or deletion") from None
        receipt["phase"] = "PUBLISH_REQUEST_ACCEPTED"
        write_receipt(path, receipt, bounds)
        return receipt


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Invalid Central CLI arguments. Credentials are environment-only.\n")


def main(argv=None) -> int:
    parser = SafeArgumentParser(description=__doc__)
    operations = parser.add_subparsers(dest="operation", required=True)
    for operation in ("upload", "status", "publish"):
        command = operations.add_parser(operation)
        command.add_argument("--deployment-receipt", type=Path, required=True)
        command.add_argument("--version", required=True)
        command.add_argument("--source-commit", required=True)
        command.add_argument("--bundle-sha256", required=True)
        if operation == "upload":
            command.add_argument("--bundle", type=Path, required=True)
            command.add_argument("--review-receipt", type=Path, required=True)
        else:
            command.add_argument("--deployment-id", required=True)
    args = parser.parse_args(argv)
    try:
        receipt = operate(args)
    except (PublishError, OSError):
        # Never echo untrusted response bodies, exception text, environment values or argparse Namespace.
        parser.exit(1, "Central operation refused or uncertain. Inspect the saved local receipt; no automatic retry or deletion occurred.\n")
    print(json.dumps(receipt, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
