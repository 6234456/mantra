#!/usr/bin/env python3
"""Start the installed CLI, check actual package HTTP/PDF evidence, remove owned state.

No browser, installation, package mutation or business-specific route is involved.
Requires a POSIX host so the complete task-owned process group can be terminated.
"""

import argparse
import datetime
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote


HASH = re.compile(r"[0-9a-f]{64}\Z")
STARTED = re.compile(r"http://127\.0\.0\.1:([0-9]+)/")
MAX_RESPONSE = 16 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def strict_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, f"Duplicate JSON member: {key}")
        value[key] = item
    return value


def read_json(content):
    return json.loads(content.decode("utf-8", errors="strict"), object_pairs_hook=strict_object)


def manifests(apps):
    mounted = {}
    # Matches PackageDirectoryWorkspace's immediate child discovery, without assuming a count.
    for directory in sorted(apps.iterdir()):
        if directory.is_symlink() or not directory.is_dir():
            continue
        manifest = directory / "manifest.json"
        if not manifest.is_file():
            continue
        document = read_json(manifest.read_bytes())
        require(document["format"] == "mantra.package/1", f"Unexpected package: {manifest}")
        require(document["cases"], f"No actual case in {manifest}")
        resources = {entry["path"]: entry for entry in document["resources"]}
        require(len(resources) == len(document["resources"]), f"Duplicate resource in {manifest}")
        mounted[directory.name] = (directory.resolve(), document, resources)
    require(mounted, f"No mounted application manifests in {apps}")
    return mounted


def request(port, path, method="GET", body=None, headers=None, timeout=180):
    # http.client does not consult HTTP proxy environment variables or follow redirects.
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        content = response.read(MAX_RESPONSE + 1)
        require(len(content) <= MAX_RESPONSE, "Bounded smoke response exceeded 16 MiB")
        return response.status, {key.lower(): value for key, value in response.getheaders()}, content
    finally:
        connection.close()


def envelope(port, path):
    status, headers, body = request(port, path)
    require(status == 200, f"{path}: HTTP {status}, {body[:1200]!r}")
    require(headers.get("content-type", "").startswith("application/json"), f"No JSON MIME: {path}")
    require(headers.get("cache-control") == "no-store", f"Cacheable evidence: {path}")
    require(headers.get("x-content-type-options") == "nosniff", f"No nosniff: {path}")
    value = read_json(body)
    require(set(value) == {"contract", "revision", "data"}, f"Unexpected package envelope: {path}")
    require(value["contract"] == "mantra.packages/1", f"Incorrect package contract: {path}")
    revision = value["revision"]
    require(revision is None or (isinstance(revision, str) and HASH.fullmatch(revision)), f"Invalid graph revision: {path}")
    require(isinstance(value["data"], dict), f"Missing structured package data: {path}")
    return value


def iso_date(value):
    if value is None:
        return None
    require(isinstance(value, str), "Parameter date must be ISO text or null")
    date = datetime.date.fromisoformat(value)
    require(date.isoformat() == value, f"Noncanonical parameter date: {value}")
    return date


def source_evidence(source, mounted):
    require(source["endExclusive"] is True, "Parameter end date must remain exclusive")
    require(HASH.fullmatch(source["packageRevision"]), "No actual captured package revision")
    require(HASH.fullmatch(source["sha256"]), "No actual parameter resource SHA-256")
    matching = [entry for entry in mounted.values()
                if entry[1]["id"] == source["packageId"] and entry[1]["version"] == source["packageVersion"]]
    require(len(matching) == 1, "Parameter provenance must identify exactly one mounted package")
    directory, manifest, resources = matching[0]
    parameter = next((entry for entry in manifest["parameters"] if entry["id"] == source["set"]), None)
    require(parameter is not None, "Parameter provenance points to an undeclared set")
    require(parameter["path"] == source["resource"], "Parameter resource differs from the manifest")
    require(parameter["schema"]["id"] == source["schema"], "Parameter schema identity differs")
    require(parameter["schema"]["version"] == source["schemaVersion"], "Parameter schema version differs")
    descriptor = resources[source["resource"]]
    require(descriptor["sha256"] == source["sha256"], "Parameter captured digest differs from the manifest")
    resource = (directory / source["resource"]).resolve(strict=True)
    require(resource.is_relative_to(directory), "Manifest parameter resource escaped its package")
    content = resource.read_bytes()
    require(len(content) == descriptor["byteLength"], "Parameter byte length differs")
    require(hashlib.sha256(content).hexdigest() == source["sha256"], "Parameter captured digest differs from actual bytes")
    start = iso_date(source["validFrom"])
    end = iso_date(source["validUntil"])
    date = iso_date(source["effectiveDate"])
    require(start is None or end is None or start < end, "Invalid parameter validity interval")
    # This route currently mounts declared choices. Generic effective-date/what-if choices
    # are accepted only with their real explicit date and accurately reported eligibility.
    if source["mode"] == "declared":
        require(date is None and source["validForDate"] is None, "Declared parameters invented a date policy")
    else:
        require(source["mode"] in {"effective-date", "what-if"} and date is not None, "Invalid parameter date policy")
        eligible = (start is None or start <= date) and (end is None or date < end)
        require(source["validForDate"] is eligible, "Incorrect end-exclusive parameter eligibility")
        if source["mode"] == "effective-date":
            require(eligible, "Effective-date selection chose an ineligible parameter resource")
    # Verify actual authored bounds independently, rather than accepting two matching JSON fields.
    authored = content.decode("utf-8", errors="strict")
    for field, actual in (("valid-from", start), ("valid-until", end)):
        match = re.search(r":" + field + r'\s+"([0-9]{4}-[0-9]{2}-[0-9]{2})"', authored)
        expected = datetime.date.fromisoformat(match.group(1)) if match else None
        require(actual == expected, f"Parameter {field} differs from the authored resource")
    return start is not None or end is not None


def check_packages(port, mounted):
    workspace = envelope(port, "/api/v1/packages")["data"]
    packages = workspace["packages"]
    require({entry["mount"] for entry in packages} == set(mounted), "Mounted apps differ from actual manifest directories")
    require(len(packages) == len(mounted), "Duplicate mount entry")
    dated_sources = 0
    pdfs = 0
    cases_checked = 0
    evidence = []
    for package in packages:
        mount = package["mount"]
        _, manifest, _ = mounted[mount]
        require(package["id"] == manifest["id"] and package["version"] == manifest["version"], "Package identity differs")
        require(package["readOnly"] is True and HASH.fullmatch(package["revision"]), "Package capture must be read-only and versioned")
        expected = {mount + "/" + entry["path"]: entry for entry in manifest["cases"]}
        require({entry["id"] for entry in package["cases"]} == set(expected), "Package cases differ from pinned manifest")
        require(len(package["cases"]) == package["caseCount"] == len(expected), "Package case count differs")
        chosen = None
        for case in package["cases"]:
            declared = expected[case["id"]]
            require(case["caseId"] == declared["id"], "Mounted case identity differs")
            require(case["schema"] == declared["schema"]["id"], "Mounted case schema differs")
            require(case["schemaVersion"] == declared["schema"]["version"], "Mounted exact schema version differs")
            route = "/api/v1/package-cases/" + quote(case["id"], safe="")
            result = envelope(port, route + "/run")
            data = result["data"]
            require(data["case"] == case["id"], "Run belongs to a different canonical case")
            for source in data["parameterSources"]:
                dated_sources += int(source_evidence(source, mounted))
            cases_checked += 1
            if data["succeeded"]:
                require(HASH.fullmatch(result["revision"]), "Succeeded run has no graph revision")
                binding = data["binding"]
                require(binding["packageRevision"] == package["revision"], "Run changed captured package revision")
                require(binding["schemaVersion"] == case["schemaVersion"], "Run changed pinned schema version")
                require(binding["resourcesReadOnly"] is True, "Run treated captured package resources as writable")
                document = data["document"]
                require(isinstance(document, dict) and document["contract"] == "mantra.workbench/4", "No current wire4 document")
                require(document["revision"] == result["revision"], "Embedded run document has another graph revision")
                chosen = (case["id"], route, result["revision"])
                break
            require(data["diagnostics"], "Failed current run has no diagnostic evidence")
        require(chosen is not None, f"No technically successful case for package {mount}")
        case_id, route, revision = chosen
        parameters = envelope(port, route + "/parameters")
        require(parameters["revision"] == revision, "Unchanged parameter request changed graph revision")
        for source in parameters["data"]["parameterSources"]:
            dated_sources += int(source_evidence(source, mounted))
        status, headers, binary = request(port, route + "/export.pdf")
        require(status == 200, f"PDF {case_id}: HTTP {status}, {binary[:1200]!r}")
        require(headers.get("content-type") == "application/pdf", "PDF was returned as JSON/text")
        require(binary.startswith(b"%PDF-") and binary.rstrip().endswith(b"%%EOF"), "Incomplete binary PDF")
        require(int(headers.get("content-length", "-1")) == len(binary), "PDF Content-Length differs")
        require(headers.get("cache-control") == "no-store" and headers.get("x-content-type-options") == "nosniff", "Unsafe PDF response headers")
        pdfs += 1
        evidence.append({"mount": mount, "case": case_id, "revision": revision, "pdfBytes": len(binary)})
    require(dated_sources > 0, "No actual dated parameter provenance was checked")
    # This independent smoke also exercises the shared boundary; detailed CAS/body-limit
    # conformance remains in the actual Kotlin HTTP integration suite.
    status, _, _ = request(port, "/api/v1/packages", headers={"Host": "outside.invalid"})
    require(status == 403, "Package index accepted an untrusted Host")
    arbitrary_case = packages[0]["cases"][0]["id"]
    status, _, _ = request(port, "/api/v1/package-cases/" + quote(arbitrary_case, safe="") + "/undo",
                           method="POST", body=b"{}", headers={"Content-Type": "application/json"})
    require(status == 403, "Package mutation accepted no session token")
    return {"packages": len(packages), "casesChecked": cases_checked, "datedSourcesChecked": dated_sources,
            "binaryPdfs": pdfs, "evidence": evidence}


def group_exists(identifier):
    try:
        os.killpg(identifier, 0)
        return True
    except ProcessLookupError:
        return False


def signal_owned(identifier, number):
    try:
        os.killpg(identifier, number)
    except ProcessLookupError:
        pass


def stop_owned(process):
    if process is None:
        return
    if group_exists(process.pid):
        signal_owned(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    if group_exists(process.pid):
        signal_owned(process.pid, signal.SIGKILL)
    process.wait(timeout=5)
    deadline = time.monotonic() + 5
    while group_exists(process.pid) and time.monotonic() < deadline:
        time.sleep(0.05)
    require(not group_exists(process.pid), f"Task-owned process group {process.pid} survives cleanup")


def java_option(name, value):
    # The JVM parses these quoted options, never a shell or normal user profile.
    return '"-D' + name + '=' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'


def check_incomplete_headers(port):
    # These provider properties are read once. Exercise their real JVM startup behavior
    # in this owned child process, independently of the application's body deadline.
    with socket.create_connection(("127.0.0.1", port), timeout=5) as stalled:
        stalled.settimeout(5)
        stalled.sendall(b"GET /api/v1/packages HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Unfinished: ")
        started = time.monotonic()
        try:
            require(stalled.recv(1) == b"", "Incomplete headers received a response instead of closing")
        except (ConnectionResetError, BrokenPipeError):
            pass
        elapsed = time.monotonic() - started
        require(elapsed < 5, "Incomplete headers exceeded the configured provider deadline")
    status, _, _ = request(port, "/api/v1/packages", timeout=5)
    require(status == 200, "Transport did not recover after the incomplete-header deadline")
    return {"startupMaxRequestSeconds": 2, "incompleteHeadersClosed": True,
            "closeElapsedMilliseconds": round(elapsed * 1000), "subsequentStatus": status}


def verify(cli, apps, startup_seconds):
    require(os.name == "posix", "This process-group cleanup smoke requires POSIX")
    cli = cli.resolve(strict=True)
    apps = apps.resolve(strict=True)
    require(cli.is_file() and os.access(cli, os.X_OK), f"Installed CLI is not executable: {cli}")
    mounted = manifests(apps)
    temporary = Path(tempfile.mkdtemp(prefix="mantra-package-workbench-smoke-")).resolve()
    saved_signals = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}

    def interrupted(number, _frame):
        raise KeyboardInterrupt(f"Smoke interrupted by signal {number}")

    for number in saved_signals:
        signal.signal(number, interrupted)
    process = None
    log_file = None
    failure = None
    evidence = None
    try:
        ui = temporary / "ui"
        ui.mkdir()
        (ui / "index.html").write_text("<!doctype html><title>Task-owned HTTP smoke</title>", encoding="utf-8")
        for name in ("java-tmp", "preferences", "cache", "profile"):
            (temporary / name).mkdir()
        environment = os.environ.copy()
        environment.update({"TMPDIR": str(temporary / "java-tmp"), "TMP": str(temporary / "java-tmp"),
                            "TEMP": str(temporary / "java-tmp"), "XDG_CACHE_HOME": str(temporary / "cache")})
        environment["JAVA_TOOL_OPTIONS"] = environment.get("JAVA_TOOL_OPTIONS", "") + " " + \
            java_option("java.io.tmpdir", temporary / "java-tmp") + " " + \
            java_option("java.util.prefs.userRoot", temporary / "preferences") + " " + \
            java_option("sun.net.httpserver.maxReqTime", 2) + " " + \
            java_option("sun.net.httpserver.timerMillis", 100)
        log = temporary / "server.log"
        log_file = log.open("wb")
        argv = [str(cli), "serve", str(apps), "--directory-policy", "trusted-local", "--port", "0", "--ui", str(ui)]
        # Do not lose ownership if a signal arrives between child creation and assignment.
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, set(saved_signals))
        try:
            process = subprocess.Popen(argv, cwd=temporary, env=environment, stdin=subprocess.DEVNULL,
                                       stdout=log_file, stderr=subprocess.STDOUT, start_new_session=True)
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        deadline = time.monotonic() + startup_seconds
        port = None
        while time.monotonic() < deadline:
            output = log.read_bytes()
            require(len(output) <= 1_048_576, "CLI startup log exceeded 1 MiB")
            match = STARTED.search(output.decode("utf-8", errors="replace"))
            if match:
                port = int(match.group(1))
                break
            require(process.poll() is None, f"Installed CLI exited before serving: {output[-8000:]!r}")
            time.sleep(0.05)
        require(port is not None and 0 < port <= 65535, "CLI did not report a real ephemeral loopback port")
        evidence = check_packages(port, mounted)
        evidence["headerDeadline"] = check_incomplete_headers(port)
    except BaseException as problem:
        failure = problem
        if (temporary / "server.log").exists():
            print((temporary / "server.log").read_bytes()[-8000:].decode("utf-8", errors="replace"), file=sys.stderr)
        raise
    finally:
        # A second interruption must not strand the owned JVM during bounded teardown.
        for number in saved_signals:
            signal.signal(number, signal.SIG_IGN)
        try:
            stop_owned(process)
            if log_file is not None:
                log_file.close()
            shutil.rmtree(temporary)
            require(not temporary.exists(), "Task-owned server/cache/profile/log directory survives cleanup")
        except BaseException as cleanup:
            if failure is not None:
                failure.add_note(f"Cleanup incomplete: {cleanup}; owned state: {temporary}")
            else:
                raise
        finally:
            for number, handler in saved_signals.items():
                signal.signal(number, handler)
    # This actual marker is emitted only after the real requests and complete cleanup succeed.
    print("MANTRA_PACKAGE_WORKBENCH_SMOKE_ACTUAL " + json.dumps(evidence, sort_keys=True))


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", type=Path, default=root / "mantra-cli/build/install/mantra/bin/mantra")
    parser.add_argument("--apps", type=Path, default=root / "apps")
    parser.add_argument("--startup-seconds", type=int, default=60)
    arguments = parser.parse_args()
    require(1 <= arguments.startup_seconds <= 300, "Startup deadline must be between 1 and 300 seconds")
    verify(arguments.cli, arguments.apps, arguments.startup_seconds)
