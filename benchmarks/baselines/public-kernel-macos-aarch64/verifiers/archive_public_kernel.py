#!/usr/bin/env python3
"""Verify completed public-kernel measurements without running an engine or rewriting inputs."""
from __future__ import annotations

import argparse
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


KERNEL_SHA = "83a101ac90ae0c2a50bdc9a4b103d1c3de015df8bcbcafa4f63bbe5aa1562cc8"
KERNEL_VERSION = "0.3.0"
ENGINE_VERSION = "1.0.0-rc.1"
SCENARIOS = ("small", "lines", "members", "table-rows", "combined")
SOURCE_MODULES = ("mantra-core", "mantra-render", "mantra-excel", "benchmarks")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
HEX40 = re.compile(r"[0-9a-f]{40}\Z")
LINE_LIMIT = 65_536
SMALL_FILE_LIMIT = 4 * 1024 * 1024
ARCHIVE_LIMIT = 32 * 1024 * 1024


class VerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def pairs(entries):
    result = {}
    for key, value in entries:
        require(key not in result, f"Duplicate JSON property: {key}")
        result[key] = value
    return result


def invalid_constant(value):
    raise VerificationError(f"Non-finite JSON literal: {value}")


def parse_json(data):
    if isinstance(data, bytes):
        data = data.decode("utf-8", errors="strict")
    return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid_constant)


def small_json(path):
    require(path.is_file() and not path.is_symlink(), f"Missing/linked JSON input: {path}")
    require(path.stat().st_size <= SMALL_FILE_LIMIT, f"JSON metadata too large: {path}")
    return parse_json(path.read_bytes())


def integer(record, key, minimum=0):
    value = record.get(key)
    require(type(value) is int and value >= minimum, f"Invalid integer {key}: {value!r}")
    return value


def fingerprint(root):
    files = sorted(
        [path for module in SOURCE_MODULES for path in (root / module).rglob("*.kt")
         if "build" not in path.relative_to(root).parts] + [root / "normein-build.lock"],
        key=lambda path: path.relative_to(root).as_posix(),
    )
    result = hashlib.sha256()
    records = []
    for path in files:
        require(not path.is_symlink(), f"Source fingerprint symlink: {path}")
        relative = path.relative_to(root).as_posix()
        data = path.read_bytes()
        result.update(relative.encode("utf-8"))
        result.update(b"\0")
        result.update(data)
        records.append({"path": relative, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    return {"fileCount": len(files), "libraryAndHarnessSourceSha256": result.hexdigest(), "files": records}


def runtime_jars(root):
    directory = root / "benchmarks/build/install/mantra-benchmark/lib"
    require(directory.is_dir() and not directory.is_symlink(), "Installed benchmark lib directory missing/linked")
    jars = sorted(directory.glob("*.jar"))
    require(len(jars) == 25, f"Expected exactly 25 runtime JARs, found {len(jars)}")
    result = []
    for path in jars:
        require(path.is_file() and not path.is_symlink(), f"Runtime JAR is missing/linked: {path}")
        result.append({"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size, "sha256": digest(path)})
    return result


def properties(path):
    require(path.is_file() and not path.is_symlink(), f"Environment file missing/linked: {path}")
    require(path.stat().st_size <= SMALL_FILE_LIMIT, f"Environment metadata too large: {path}")
    result = {}
    for line in path.read_text(encoding="utf-8", errors="strict").splitlines():
        require("=" in line, f"Malformed environment line: {line!r}")
        key, value = line.split("=", 1)
        require(key and key not in result, f"Duplicate/empty environment key: {key}")
        result[key] = value
    return result


def verify_identity(root, evidence, original, periods):
    before_path = evidence / "public-source-before.json"
    after_path = evidence / "public-source-after.json"
    before, after = small_json(before_path), small_json(after_path)
    require(before == after and digest(before_path) == digest(after_path), "Before/after source inventories differ")
    require(before == fingerprint(root), "Current library/harness source inventory differs from measured source")
    source_sha = before.get("libraryAndHarnessSourceSha256")
    require(isinstance(source_sha, str) and HEX64.fullmatch(source_sha), "Invalid source fingerprint")
    runtime_before_path = evidence / "public-runtime-before.json"
    runtime_after_path = evidence / "public-runtime-after.json"
    runtime_before, runtime_after = small_json(runtime_before_path), small_json(runtime_after_path)
    require(runtime_before == runtime_after, "Before/after runtime identities differ")
    commit = runtime_before.get("sourceCommit")
    require(isinstance(commit, str) and HEX40.fullmatch(commit), "Runtime capture needs a full source commit")
    require(runtime_before.get("engineVersion") == ENGINE_VERSION, "Wrong captured engine version")
    require(runtime_before.get("normeinVersion") == KERNEL_VERSION, "Wrong captured kernel version")
    require(runtime_before.get("normeinJarSha256") == KERNEL_SHA, "Wrong captured public kernel SHA-256")
    ci = small_json(evidence / "implementation-ci.json")
    require(ci.get("sourceCommit") == commit and ci.get("status") == "completed" and
            ci.get("conclusion") == "success", "Implementation CI receipt is not successful for the measured commit")
    require(ci.get("formalCentralWorkflowRun") is False, "Implementation CI cannot be relabelled as a Central release")
    actual_jars = runtime_jars(root)
    require(runtime_before.get("runtimeJars") == actual_jars, "Current installed runtime JAR inventory differs")
    kernel = [entry for entry in actual_jars if Path(entry["path"]).name == "normein-dsl-0.3.0.jar"]
    require(len(kernel) == 1 and kernel[0]["sha256"] == KERNEL_SHA, "Installed public kernel JAR bytes differ")
    require(any(Path(entry["path"]).name == f"mantra-core-{ENGINE_VERSION}.jar" for entry in actual_jars),
            "Installed engine JAR has the wrong version")
    environments = {}
    for name, directory in (("original", original), ("periods", periods)):
        env = properties(directory / "environment.txt")
        require(env.get("mantraRevision") == commit, f"{name}: measurement source commit differs")
        require(env.get("workingTreeModified") == "false", f"{name}: measurement source was modified")
        require(env.get("libraryAndHarnessSourceSha256") == source_sha, f"{name}: source fingerprint differs")
        require(env.get("normeinVersion") == KERNEL_VERSION, f"{name}: wrong runtime kernel version")
        require(env.get("normeinRuntimeJarSha256") == KERNEL_SHA, f"{name}: wrong runtime public JAR hash")
        require("normeinCommit" not in env, f"{name}: source-lock commit incorrectly labelled as runtime commit")
        require(env.get("normeinSourceBaselineCommit") == "0a3ae1de844c92635fbbc03406a13cb0e8920c03",
                f"{name}: historical source-baseline label changed")
        require(env.get("warmupPerOperation") == "5" and env.get("repetitionsPerOperation") == "10",
                f"{name}: warmup/repetition policy changed")
        require(env.get("maxHeapBytes") == str(2 * 1024 ** 3), f"{name}: benchmark maximum heap changed")
        require(env.get("xlsxReadMaxDurationSeconds") == "300", f"{name}: XLSX read duration changed")
        environments[name] = env
    for key in ("javaVersion", "javaVendor", "vmName", "kotlinVersion", "os", "availableProcessors", "maxHeapBytes"):
        require(environments["original"].get(key) == environments["periods"].get(key), f"Environment mismatch: {key}")
    return {"status": "PASS", "measuredSourceCommit": commit, "engineVersion": ENGINE_VERSION,
            "normeinVersion": KERNEL_VERSION, "normeinRuntimeJarSha256": KERNEL_SHA,
            "libraryAndHarnessSourceSha256": source_sha, "sourceFileCount": before["fileCount"],
            "beforeAfterEntireInventoryEqual": True, "runtimeJarCount": 25, "runtimeJarsUnchanged": True,
            "environmentIdentitiesMatched": True, "kernelSourceCommitClaimed": False,
            "engineExecutedByVerifier": False}


def performance(root, original, periods):
    verifier = root / "scripts/verify-performance.py"
    before = digest(verifier)
    result = subprocess.run(
        [sys.executable, "-E", "-B", str(verifier), "--original", str(original), "--periods", str(periods)],
        cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False, timeout=60,
    )
    require(result.returncode == 0, f"Independent unchanged performance budgets failed:\n{result.stderr}")
    report = parse_json(result.stdout)
    require(report.get("groups") == 39 and report.get("samples") == 390, "Not the complete 390-sample measurement")
    require(report.get("engineExecuted") is False, "Performance verifier must not execute the engine")
    require(report.get("original", {}).get("budgetsPassed") is True and
            report.get("periods", {}).get("budgetsPassed") is True, "Missing budget acceptance")
    require(digest(verifier) == before, "Independent performance verifier changed during checking")
    report["verifierSourceSha256"] = before
    return report


def json_line(stream, hasher, index, label):
    raw = stream.readline(LINE_LIMIT + 1)
    require(raw, f"{label}: ended before record {index}")
    require(len(raw) <= LINE_LIMIT, f"{label}: oversized JSONL record {index}")
    require(raw.endswith(b"\n"), f"{label}: missing record newline {index}")
    hasher.update(raw)
    result = parse_json(raw)
    require(isinstance(result, dict), f"{label}: record {index} is not an object")
    return result


def compare_streams(actual, reference, count=10_000, values_per_case=112):
    actual_sha, reference_sha = hashlib.sha256(), hashlib.sha256()
    seen = set()
    comparisons = 0
    for path in (actual, reference):
        require(path.is_file() and not path.is_symlink(), f"JSONL input missing/linked: {path}")
    with actual.open("rb") as engine, reference.open("rb") as expected:
        for index in range(count):
            one = json_line(engine, actual_sha, index, "actual")
            two = json_line(expected, reference_sha, index, "reference")
            require(set(one) == {"index", "caseId", "values"}, f"Unexpected actual fields at record {index}")
            require(type(one.get("index")) is int and one["index"] == index and
                    type(two.get("index")) is int and two["index"] == index, f"Record index mismatch: {index}")
            case_id = one.get("caseId")
            require(case_id == two.get("caseId") == f"lease-batch-{index + 1:05d}", f"Case ID mismatch: {index}")
            require(case_id not in seen, f"Repeated case ID: {case_id}")
            seen.add(case_id)
            require(two.get("validationPassed") is True and two.get("expectedBusiness") == [],
                    f"Unexpected reference BUSINESS status: {case_id}")
            left, right = one.get("values"), two.get("values")
            require(isinstance(left, dict) and isinstance(right, dict), f"Missing values: {case_id}")
            require(left.keys() == right.keys() and len(right) == values_per_case, f"Numeric key set differs: {case_id}")
            for key in right:
                require(isinstance(left[key], str) and isinstance(right[key], str), f"Numeric value is not text: {case_id}/{key}")
                try:
                    actual_number, expected_number = Decimal(left[key]), Decimal(right[key])
                except InvalidOperation as failure:
                    raise VerificationError(f"Invalid decimal: {case_id}/{key}") from failure
                require(actual_number.is_finite() and expected_number.is_finite(), f"Non-finite decimal: {case_id}/{key}")
                require(actual_number == expected_number, f"Exact Decimal mismatch: {case_id}/{key}")
                comparisons += 1
        require(engine.read(1) == b"" and expected.read(1) == b"", "Unexpected trailing JSONL records/bytes")
    return {"distinctCaseIds": len(seen), "independentNumericComparisons": comparisons,
            "actualSha256": actual_sha.hexdigest(), "referenceSha256": reference_sha.hexdigest()}


def batch(directory, reference, reference_manifest):
    manifest = small_json(reference_manifest)
    require(manifest.get("cases") == 10_000, "Reference must describe exactly 10,000 cases")
    descriptor = manifest.get("records", {})
    require(type(descriptor.get("byteLength")) is int and reference.stat().st_size == descriptor["byteLength"],
            "Frozen reference byte length differs")
    summary = small_json(directory / "summary.json")
    comparison = compare_streams(directory / "actual.jsonl", reference)
    require(comparison["referenceSha256"] == descriptor.get("sha256") == summary.get("referenceSha256"),
            "Frozen reference SHA differs from manifest/summary")
    require(comparison["actualSha256"] == summary.get("actualSha256"), "Actual JSONL SHA differs from summary")
    require(comparison["independentNumericComparisons"] == 1_120_000, "Incomplete independent numeric comparisons")
    for key in ("requestedCases", "completedCases", "checkedCases", "succeededCases"):
        require(integer(summary, key) == 10_000, f"Incomplete batch: {key}")
    require(integer(summary, "exactNumericComparisons") == 1_120_000, "Reported comparison count differs")
    for key in ("validationFailedCases", "technicalFailedCases"):
        require(integer(summary, key) == 0, f"Batch failures: {key}")
    require("batchFailure" in summary and summary["batchFailure"] is None, "Batch failed")
    elapsed = integer(summary, "elapsedMillis")
    peak = integer(summary, "peakHeapPoolSumBytes", 1)
    require(elapsed <= 300_000 and peak <= 1024 ** 3, "Batch timing/heap acceptance exceeded")
    stats, compilation = summary.get("statistics", {}), summary.get("compilation", {})
    formulas = integer(compilation, "formulaCount", 1)
    require(formulas == 22, "Unexpected compiled lease formula inventory")
    for key in ("syntaxCompilerCalls", "semanticCompilerCalls", "executionPlanCompilations"):
        require(integer(compilation, key) == formulas, f"Initial compilation accounting differs: {key}")
    opens = integer(stats, "sessionOpens", 1)
    require(opens == formulas == integer(stats, "sessionCloseAttempts") == integer(stats, "successfulSessionCloses"),
            "Session lifecycle not fully closed")
    for key in ("executionPlanCompilations", "valueOnlyEvidenceMaterializations"):
        require(integer(stats, key) == 0, f"Unexpected runtime work: {key}")
    for key in ("rowCycles", "physicalRowPreparations", "logicalExpressionEvaluations", "frameAllocations"):
        integer(stats, key, 1)
    require(HEX64.fullmatch(summary.get("packageRevision", "")), "Missing exact captured package revision")
    environment = summary.get("environment", {})
    require(environment.get("directoryPolicy") == "TRUSTED_LOCAL", "Batch must record its explicit directory policy")
    require(environment.get("kernelArtifact") == "application-code:mantra.calc:2", "Wrong composed calculation artifact")
    require(HEX64.fullmatch(environment.get("kernelSha256", "")), "Missing composed execution content identity")
    return {"status": "PASS", **comparison, "elapsedMillis": elapsed, "peakHeapPoolSumBytes": peak,
            "statistics": stats, "compilation": compilation, "packageRevision": summary["packageRevision"],
            "environment": environment, "referenceManifestSha256": digest(reference_manifest),
            "referenceBytes": reference.stat().st_size, "engineExecutedByVerifier": False}


def inputs(root, evidence, original, periods, directory, reference_manifest):
    files = {}
    for prefix, source in (("source-before", evidence / "public-source-before.json"),
                           ("source-after", evidence / "public-source-after.json"),
                           ("runtime-before", evidence / "public-runtime-before.json"),
                           ("runtime-after", evidence / "public-runtime-after.json")):
        files[f"{prefix}.json"] = source
    for label, source in (("original", original), ("periods", periods)):
        for name in ("environment.txt", "samples.csv", "summary.csv"):
            files[f"{label}/{name}"] = source / name
    for scenario in SCENARIOS:
        for name in ("verification.txt", "paper.txt"):
            files[f"original/{scenario}/{name}"] = original / scenario / name
        text = (original / scenario / "verification.txt").read_text(encoding="utf-8", errors="strict")
        require("Fallbacks: 0" in text and "Evaluation errors: 0" in text, f"{scenario}: missing zero fallback/error proof")
    for name in ("verification.txt", "paper-matrix.txt", "paper-transpose.txt"):
        files[f"periods/{name}"] = periods / name
    text = (periods / "verification.txt").read_text(encoding="utf-8", errors="strict")
    require(text.count("0 fallbacks, 0 evaluation errors.") == 2, "Missing both period export zero-fallback/error checks")
    files["batch/summary.json"] = directory / "summary.json"
    files["batch/reference-manifest.json"] = reference_manifest
    for name in ("process-start-observation.json", "canonical-signing-test.json", "implementation-ci.json",
                 "functional-verification.json", "static-comparison.json"):
        files[f"verification/{name}"] = evidence / name
        require(isinstance(small_json(evidence / name), dict), f"Additional evidence is not an object: {name}")
    files["verification/fixture-metadata-proof.json"] = (
        Path("/private/tmp/mantra-public-kernel-identity/fixture-metadata-proof.json")
    )
    require(isinstance(small_json(files["verification/fixture-metadata-proof.json"]), dict),
            "Fixture metadata proof is not an object")
    observation = small_json(evidence / "process-start-observation.json")
    require(observation.get("unrelatedUserProcessesPreserved") is True,
            "Process observation must preserve the unrelated-user-process boundary")
    require(set(observation) == {"observedAtUtc", "unrelatedUserProcessesPreserved", "note", "observedExecutableCounts"},
            "Process observation must contain aggregate counts only, without raw commands/process identities")
    counts = observation.get("observedExecutableCounts")
    require(isinstance(counts, dict) and all(type(value) is int and value >= 0 for value in counts.values()),
            "Process executable counts are invalid")
    signing = small_json(evidence / "canonical-signing-test.json")
    require(signing.get("testKeyOnly") is True and signing.get("uploaded") is False,
            "Canonical signing evidence must remain a local TEST-only, unuploaded result")
    require(signing.get("outerTestKeyAndBundleRemoved") is True,
            "Canonical signing result must preserve the TEST material cleanup receipt")
    files["verifiers/verify-performance.py"] = root / "scripts/verify-performance.py"
    files["verifiers/archive_public_kernel.py"] = Path(__file__).resolve()
    files["verifiers/test_archive_public_kernel.py"] = Path(__file__).with_name("test_archive_public_kernel.py")
    files["verifiers/README.md"] = Path(__file__).with_name("README.md")
    total = 0
    for name, path in files.items():
        require(path.is_file() and not path.is_symlink(), f"Archive input missing/linked: {name}")
        size = path.stat().st_size
        require(size <= SMALL_FILE_LIMIT, f"Archive file exceeds small-evidence ceiling: {name}")
        total += size
    require(total <= ARCHIVE_LIMIT, "Archive inputs exceed 32 MiB small-evidence ceiling")
    return files, {name: {"bytes": path.stat().st_size, "sha256": digest(path)} for name, path in files.items()}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".public-evidence-", delete=False) as temporary:
        name = Path(temporary.name)
        temporary.write(data)
    try:
        os.replace(name, path)
    finally:
        name.unlink(missing_ok=True)


def archive(root, files, recorded, reports):
    destination = root / "benchmarks/baselines/public-kernel-macos-aarch64"
    require(not destination.exists() and not destination.is_symlink(), f"Archive already exists; never overwrite: {destination}")
    parent = destination.parent
    require(parent.is_dir() and not parent.is_symlink(), "Archive parent missing/linked")
    with tempfile.TemporaryDirectory(dir=parent, prefix=".public-kernel-evidence-") as temporary:
        staging = Path(temporary) / "archive"
        staging.mkdir()
        for name, source in files.items():
            target = staging / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            require(target.stat().st_size == recorded[name]["bytes"] and digest(target) == recorded[name]["sha256"],
                    f"Evidence changed before/during archive copy: {name}")
        for name, report in reports.items():
            write_json(staging / name, report)
        contents = {path.relative_to(staging).as_posix(): {"bytes": path.stat().st_size, "sha256": digest(path)}
                    for path in sorted(staging.rglob("*")) if path.is_file()}
        require(all(not name.endswith((".xlsx", ".jar", ".jfr", ".jsonl")) for name in contents), "Unexpected large/binary archive input")
        write_json(staging / "archive-manifest.json", {"format": "mantra.public-kernel-performance-archive/1",
                  "files": contents, "historicalArchivesModified": False,
                  "omitted": ["XLSX", "runtime JARs", "JFR", "actual/reference JSONL", "signed TEST bundles"]})
        require(not destination.exists(), "Archive destination appeared during staging")
        # Atomic rename publishes only the complete staged archive; existing archives are never removed.
        staging.rename(destination)
    return {"path": destination.relative_to(root).as_posix(), "manifestSha256": digest(destination / "archive-manifest.json"),
            "files": len(contents), "historicalArchivesModified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--archive", action="store_true", help="Publish the fresh dedicated archive; refuses an existing destination")
    args = parser.parse_args()
    root = args.root.resolve()
    evidence = root / "build/release-checks/public-normein"
    original = root / "benchmarks/build/public-kernel-performance"
    periods = root / "benchmarks/build/public-kernel-period-performance"
    directory = root / "build/out/lease-batch-public-kernel-10000"
    reference = root / "build/out/lease-batch-reference.jsonl"
    reference_manifest = root / "build/out/lease-batch-reference.manifest.json"
    require(evidence.is_dir() and not evidence.is_symlink(), "Public measurement evidence directory missing/linked")
    if args.archive:
        target = root / "benchmarks/baselines/public-kernel-macos-aarch64"
        require(not target.exists() and not target.is_symlink(), "Dedicated archive already exists; use verification-only")
    files, recorded = inputs(root, evidence, original, periods, directory, reference_manifest)
    identity = verify_identity(root, evidence, original, periods)
    timings = performance(root, original, periods)
    actual_batch = batch(directory, reference, reference_manifest)
    for name, path in files.items():
        require(path.stat().st_size == recorded[name]["bytes"] and digest(path) == recorded[name]["sha256"],
                f"Input changed during independent verification: {name}")
    reports = {"source-runtime-verification.json": identity, "performance-independent.json": timings,
               "batch/independent-verification.json": actual_batch}
    result = {"format": "mantra.public-kernel-measurement-verification/1", "status": "PASS",
              "identity": identity, "performance": timings, "batch": actual_batch,
              "inputInventory": recorded, "engineExecutedByVerifier": False,
              "expectedValuesGenerated": False, "historicalArchivesModified": False,
              "measurementIsolation": "Sequential task-owned JVMs; unrelated user JVMs preserved; not whole-machine isolation",
              "additionalEvidenceScope": "Small existing receipts copied unchanged; TEST signing is not Maven publication"}
    if args.archive:
        result["archive"] = archive(root, files, recorded, reports)
    else:
        result["archive"] = None
    for name, report in (("public-source-runtime-verification.json", identity),
                         ("public-performance-independent.json", timings),
                         ("public-batch-independent.json", actual_batch),
                         ("public-measurement-verification.json", result)):
        write_json(evidence / name, report)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (VerificationError, OSError, ValueError, subprocess.TimeoutExpired) as failure:
        print(json.dumps({"status": "FAIL", "error": str(failure), "engineExecutedByVerifier": False}), file=sys.stderr)
        raise SystemExit(1)
