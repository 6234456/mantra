#!/usr/bin/env python3
"""Independent stdlib-only corpus runner. There is deliberately no update/record mode."""
from __future__ import annotations
import argparse
from decimal import Decimal, InvalidOperation
import hashlib
import json
import re
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
from typing import Any

EXPECTED_FIELDS = {"succeeded", "validationPassed", "values", "diagnostics"}
DIAGNOSTIC_FIELDS = {"code", "category", "severity", "effect"}
CATEGORIES = {"parsing", "structural", "evaluation", "business"}
SEVERITIES = {"error", "warning", "info"}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON object key: {key}")
        result[key] = value
    return result


def document_path(root: Path, relative: str) -> Path:
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise ValueError(f"Invalid corpus path: {relative!r}")
    path = root / relative
    if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Corpus path is not an ordinary in-root file: {relative}")
    return path


def verify_corpus(root: Path) -> dict[str, Any]:
    manifest = load_json(root / "manifest.json")
    if manifest["format"] != "mantra-conformance-corpus/1":
        raise ValueError("Unsupported corpus manifest")
    inventory = manifest["files"]
    if len({item["path"] for item in inventory}) != len(inventory):
        raise ValueError("Duplicate inventory path")
    if inventory != sorted(inventory, key=lambda item: item["path"]):
        raise ValueError("Inventory must be sorted")
    expected_paths = set()
    ids = set()
    for vector in manifest["vectors"]:
        if vector["id"] in ids:
            raise ValueError("Duplicate vector ID")
        ids.add(vector["id"])
        if not vector["clauses"] or any(not clause.startswith("S") for clause in vector["clauses"]):
            raise ValueError("Vector has no specification clauses")
        expected_paths.update([vector["schema"], vector["case"], vector["expected"], *vector["parameters"]])
        if len(vector["queries"]) != len(set(vector["queries"])):
            raise ValueError("Duplicate query")
        if vector["options"] not in ([], ["--max-formulas", "0"]):
            raise ValueError("Unsupported vector host option")
    if expected_paths != {item["path"] for item in inventory}:
        raise ValueError("Vector documents do not match inventory")
    actual_paths = {str(path.relative_to(root)) for path in (root / "vectors").rglob("*") if path.is_file()}
    if actual_paths != expected_paths:
        raise ValueError("Missing or unlisted vector document")
    digest_material = []
    for item in inventory:
        data = document_path(root, item["path"]).read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if type(item["bytes"]) is not int or len(data) != item["bytes"] or digest != item["sha256"]:
            raise ValueError(f"Frozen byte inventory mismatch: {item['path']}")
        digest_material.append(f"{item['path']}\t{item['bytes']}\t{item['sha256']}\n")
    digest = hashlib.sha256("".join(digest_material).encode("utf-8")).hexdigest()
    if digest != manifest["corpusSha256"]:
        raise ValueError("Corpus digest mismatch")
    for vector in manifest["vectors"]:
        expected = load_json(document_path(root, vector["expected"]))
        canonical_response(expected)
        if set(expected["values"]) != set(vector["queries"]):
            raise ValueError(f"Expected query keys differ: {vector['id']}")
    return manifest


def canonical_value(value: Any) -> Any:
    # Only typed Num objects are numerical; Boolean false, text "0" and nil stay distinct.
    if value is None:
        return ("nil",)
    if type(value) is bool:
        return ("boolean", value)
    if type(value) is str:
        return ("text", value)
    if type(value) is list:
        return ("vector", tuple(canonical_value(element) for element in value))
    if type(value) is not dict or len(value) != 1:
        raise ValueError(f"Invalid typed value: {value!r}")
    if "n" in value:
        if type(value["n"]) is not str or not re.fullmatch(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?", value["n"]):
            raise ValueError("A Num requires an exact decimal string")
        try:
            number = Decimal(value["n"])
        except InvalidOperation as error:
            raise ValueError("Invalid exact decimal") from error
        if not number.is_finite():
            raise ValueError("A Num must be finite")
        return ("number", number)
    if "kw" in value and type(value["kw"]) is str:
        return ("keyword", value["kw"])
    if "date" in value and type(value["date"]) is str:
        from datetime import date
        if date.fromisoformat(value["date"]).isoformat() != value["date"]:
            raise ValueError("Date must be canonical ISO yyyy-mm-dd")
        return ("date", value["date"])
    if "map" in value and type(value["map"]) is list:
        entries = []
        for pair in value["map"]:
            if type(pair) is not list or len(pair) != 2:
                raise ValueError("A map entry requires [key,value]")
            entries.append((canonical_value(pair[0]), canonical_value(pair[1])))
        if len({key for key, _ in entries}) != len(entries):
            raise ValueError("Duplicate typed map key")
        # The wire map is explicitly ordered: no sorting or key coercion.
        return ("map", tuple(entries))
    raise ValueError(f"Unknown typed value: {value!r}")


def canonical_response(response: Any) -> Any:
    if type(response) is not dict or set(response) != EXPECTED_FIELDS:
        raise ValueError("Response requires exactly succeeded, validationPassed, values, diagnostics")
    if type(response["succeeded"]) is not bool or type(response["validationPassed"]) is not bool:
        raise ValueError("Statuses must be Boolean")
    if type(response["values"]) is not dict or type(response["diagnostics"]) is not list:
        raise ValueError("Values/diagnostics must be object/list")
    findings = []
    for finding in response["diagnostics"]:
        if type(finding) is not dict or set(finding) != DIAGNOSTIC_FIELDS or any(type(v) is not str for v in finding.values()):
            raise ValueError("Diagnostic fields must be exact strings")
        if finding["category"] not in CATEGORIES or finding["severity"] not in SEVERITIES:
            raise ValueError("Unknown category or severity")
        expected_effect = "validation-failure" if finding["category"] == "business" and finding["severity"] == "error" else "technical-failure" if finding["severity"] == "error" else "warning" if finding["severity"] == "warning" else "information"
        if finding["effect"] != expected_effect:
            raise ValueError("Diagnostic effect is inconsistent with category/severity")
        findings.append(tuple(finding[key] for key in ("code", "category", "severity", "effect")))
    if findings != sorted(findings):
        raise ValueError("Diagnostics must be sorted; duplicate findings remain significant")
    return (response["succeeded"], response["validationPassed"],
            tuple(sorted((query, canonical_value(value)) for query, value in response["values"].items())), tuple(findings))


def execute_vector(root: Path, vector: dict[str, Any], command: list[str], timeout: float) -> tuple[bool, str]:
    expected = load_json(root / vector["expected"])
    with tempfile.TemporaryDirectory(prefix="mantra-conformance-") as temporary:
        destination = Path(temporary)
        sources = [vector["schema"], vector["case"], *vector["parameters"]]
        copied = {}
        for index, relative in enumerate(sources):
            target = destination / f"document-{index}.mantra"
            target.write_bytes((root / relative).read_bytes())
            copied[relative] = str(target)
        arguments = [*command, "--schema", copied[vector["schema"]], "--case", copied[vector["case"]]]
        for relative in vector["parameters"]:
            arguments += ["--parameter", copied[relative]]
        for query in vector["queries"]:
            arguments += ["--query", query]
        arguments += vector["options"]
        try:
            completed = subprocess.run(arguments, text=True, encoding="utf-8", capture_output=True, timeout=timeout, check=False)
        except subprocess.TimeoutExpired:
            return False, "Adapter timed out"
        if completed.returncode != 0:
            return False, f"Adapter exit {completed.returncode}: {completed.stderr[-4000:]}"
        try:
            actual = json.loads(completed.stdout, object_pairs_hook=unique_object)
            if canonical_response(actual) != canonical_response(expected):
                return False, f"Expected {json.dumps(expected, sort_keys=True)}\nActual   {json.dumps(actual, sort_keys=True)}"
        except (ValueError, TypeError, KeyError) as error:
            return False, f"Invalid adapter protocol: {error}; stdout={completed.stdout[-4000:]}"
        return True, ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adapter", help="External executable command; no shell is invoked")
    parser.add_argument("--vector", action="append", help="Select a frozen vector ID (repeatable)")
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--verify-only", action="store_true", help="Verify frozen bytes and protocol without running an adapter")
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parent
    try:
        manifest = verify_corpus(root)
        if arguments.timeout <= 0:
            raise ValueError("Timeout must be positive")
        selected = set(arguments.vector or [item["id"] for item in manifest["vectors"]])
        if not selected.issubset({item["id"] for item in manifest["vectors"]}):
            raise ValueError("Unknown vector ID")
        vectors = [item for item in manifest["vectors"] if item["id"] in selected]
        if arguments.verify_only:
            print(json.dumps({"verified": len(vectors), "executed": 0, "corpusSha256": manifest["corpusSha256"]}))
            return 0
        command = shlex.split(arguments.adapter or "")
        if not command:
            raise ValueError("Supply --adapter or --verify-only")
        failures = []
        for vector in vectors:
            success, detail = execute_vector(root, vector, command, arguments.timeout)
            if not success:
                failures.append({"id": vector["id"], "detail": detail})
        print(json.dumps({"passed": len(vectors) - len(failures), "failed": len(failures), "corpusSha256": manifest["corpusSha256"], "failures": failures}, indent=2))
        return 1 if failures else 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"Conformance runner: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
