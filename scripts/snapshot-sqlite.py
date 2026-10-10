#!/usr/bin/env python3
"""Capture bounded read-only SQLite facts as an atomic, replayable JsonSource bundle."""

from __future__ import annotations

import argparse
from contextlib import closing
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import sqlite3
import stat
import sys
import tempfile
import time
from typing import Any


FORMAT = "mantra.data-snapshot/1"
TOOL_VERSION = 1
INTERRUPTED = False
CONFIG_BYTES = 64 * 1024
DECIMAL_DIGITS = 1024
DECIMAL_EXPONENT = 1024
INTEGER = re.compile(r"[+-]?[0-9]+\Z")
DECIMAL = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
VOLATILE_FUNCTIONS = {
    "random", "randomblob", "date", "time", "datetime", "julianday", "unixepoch", "strftime",
    "current_date", "current_time", "current_timestamp", "load_extension", "readfile", "writefile",
}


class SnapshotError(Exception):
    """Rejected source, extraction, archive or resource budget."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SnapshotError(message)


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def encode_json(value: Any) -> bytes:
    """Canonical JSON with exact Decimal number tokens, never floating-point conversion."""
    def encode(item: Any) -> str:
        if item is None:
            return "null"
        if item is True:
            return "true"
        if item is False:
            return "false"
        if isinstance(item, str):
            return json.dumps(item, ensure_ascii=False)
        if isinstance(item, int):
            return str(item)
        if isinstance(item, Decimal):
            require(item.is_finite(), "Non-finite decimal is not a JSON value")
            return str(item)
        if isinstance(item, list):
            return "[" + ",".join(encode(entry) for entry in item) + "]"
        if isinstance(item, dict):
            require(all(isinstance(key, str) for key in item), "JSON object keys must be strings")
            return "{" + ",".join(encode(key) + ":" + encode(item[key]) for key in sorted(item)) + "}"
        raise SnapshotError(f"Unsupported JSON value type: {type(item).__name__}")
    return (encode(value) + "\n").encode("utf-8")


def strict_json(content: bytes, *, decimals: bool = False) -> Any:
    def pairs(entries: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in entries:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def reject_float(_token: str) -> Any:
        raise SnapshotError("JSON decimal parameters are not accepted; use exact decimal text")

    def reject_constant(_token: str) -> Any:
        raise SnapshotError("Non-finite JSON numbers are not accepted")

    try:
        return json.loads(content.decode("utf-8"), object_pairs_hook=pairs,
                          parse_float=Decimal if decimals else reject_float, parse_constant=reject_constant)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise SnapshotError(f"Invalid JSON: {error}") from error


def protected_path(root: Path, supplied: str | Path, *, kind: str | None = None) -> Path:
    path = Path(supplied)
    path = path if path.is_absolute() else root / path
    require(".." not in path.parts, "Parent traversal is not allowed in snapshot paths")
    try:
        relative = path.relative_to(root)
    except ValueError as error:
        raise SnapshotError("Snapshot paths must remain inside --root") from error
    candidate = root
    for component in relative.parts:
        candidate = candidate / component
        require(not candidate.is_symlink(), f"Symlink paths are not accepted: {candidate}")
    resolved = path.resolve()
    require(resolved.is_relative_to(root), "Snapshot path escapes --root")
    if kind == "file":
        require(path.exists() and stat.S_ISREG(path.stat().st_mode), f"Expected regular input file: {path}")
    elif kind == "directory":
        require(path.is_dir(), f"Expected existing directory: {path}")
    return path


def read_bounded(path: Path, maximum: int) -> bytes:
    with path.open("rb") as source:
        content = source.read(maximum + 1)
    require(len(content) <= maximum, f"File byte budget exceeded: {path.name}")
    return content


@dataclass(frozen=True)
class Bounds:
    max_rows: int = 10_000
    max_bytes: int = 8 * 1024 * 1024
    max_database_bytes: int = 128 * 1024 * 1024
    timeout: float = 15

    def validate(self) -> None:
        require(0 <= self.max_rows <= 1_000_000, "Row budget must be between 0 and 1,000,000")
        require(0 < self.max_bytes <= 256 * 1024 * 1024, "Output byte budget must be in (0, 256 MiB]")
        require(0 < self.max_database_bytes <= 1024 * 1024 * 1024,
                "Database byte budget must be in (0, 1 GiB]")
        require(math.isfinite(self.timeout) and 0 < self.timeout <= 300, "Timeout must be in (0, 300] seconds")

    def manifest(self) -> dict[str, int]:
        return {"maxRows": self.max_rows, "maxBytes": self.max_bytes,
                "maxDatabaseBytes": self.max_database_bytes, "timeoutMilliseconds": math.ceil(self.timeout * 1000)}


def validate_mapping(value: Any) -> dict[str, dict[str, str]]:
    require(isinstance(value, dict) and 0 < len(value) <= 256, "Mapping must be an object of 1 to 256 columns")
    result = {}
    for target, descriptor in value.items():
        require(isinstance(target, str) and target and len(target) <= 256 and not any(ord(c) < 32 for c in target),
                "Target column ids must be nonempty printable strings of at most 256 characters")
        require(isinstance(descriptor, dict) and set(descriptor) <= {"source", "type", "null"},
                f"Invalid mapping descriptor for {target}")
        source = descriptor.get("source")
        require(isinstance(source, str) and source and len(source) <= 256, f"Missing source alias for {target}")
        require(isinstance(descriptor.get("type"), str) and descriptor["type"] in {"decimal", "integer", "boolean", "text"},
                f"Unknown mapping type for {target}")
        policy = descriptor.get("null", "include")
        require(isinstance(policy, str) and policy in {"include", "omit", "error"}, f"Unknown null policy for {target}")
        result[target] = {"source": source, "type": descriptor["type"], "null": policy}
    return result


def validate_parameters(value: Any) -> dict[str, Any]:
    require(isinstance(value, dict), "Parameters must be an object of named bindings")
    for name, parameter in value.items():
        require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) is not None, "Invalid named parameter")
        require(parameter is None or isinstance(parameter, (str, int)), "Parameters must be text, integers, booleans or null")
        if isinstance(parameter, int):
            require(-(2 ** 63) <= parameter < 2 ** 63, "SQLite integer parameter exceeds signed 64-bit range; use text")
    return value


def sql_tokens(sql: str) -> list[tuple[str, int]]:
    """Tokens outside strings/comments, retaining parentheses depth for outer ORDER BY."""
    tokens: list[tuple[str, int]] = []
    position = 0
    depth = 0
    while position < len(sql):
        if sql.startswith("--", position):
            end = sql.find("\n", position)
            position = len(sql) if end < 0 else end + 1
        elif sql.startswith("/*", position):
            end = sql.find("*/", position + 2)
            require(end >= 0, "Unclosed SQL comment")
            position = end + 2
        elif sql[position] in "'\"`[":
            opening = sql[position]
            closing = "]" if opening == "[" else opening
            position += 1
            while position < len(sql):
                if sql[position] == closing:
                    position += 1
                    if closing != "]" and position < len(sql) and sql[position] == closing:
                        position += 1
                    else:
                        break
                else:
                    position += 1
        elif sql[position] == "(":
            depth += 1
            position += 1
        elif sql[position] == ")":
            depth -= 1
            position += 1
        elif sql[position].isalpha() or sql[position] == "_":
            start = position
            position += 1
            while position < len(sql) and (sql[position].isalnum() or sql[position] == "_"):
                position += 1
            tokens.append((sql[start:position].upper(), depth))
        else:
            position += 1
    return tokens


def validate_query(sql: str) -> None:
    tokens = sql_tokens(sql)
    require(tokens and tokens[0][0] in {"SELECT", "WITH"}, "Query must be a SELECT or WITH ... SELECT statement")
    require(any(tokens[index] == ("ORDER", 0) and tokens[index + 1] == ("BY", 0)
                for index in range(len(tokens) - 1)), "Query requires a top-level ORDER BY with a stable tie-breaker")


def converted(value: Any, descriptor: dict[str, str], target: str) -> Any:
    require(not isinstance(value, float), f"Column {target}: SQLite REAL is not exact; use integer or decimal text")
    require(not isinstance(value, bytes), f"Column {target}: SQLite BLOB is not supported")
    kind = descriptor["type"]
    if kind == "text":
        require(isinstance(value, str), f"Column {target}: expected SQLite text")
        return value
    if kind == "boolean":
        require((isinstance(value, int) and value in (0, 1)) or value in ("false", "true"),
                f"Column {target}: expected integer 0/1 or exact text false/true")
        return value == 1 or value == "true"
    if kind == "integer":
        require(isinstance(value, int) or (isinstance(value, str) and INTEGER.fullmatch(value) is not None),
                f"Column {target}: expected base-10 integer text or SQLite integer")
        require(len(str(value).lstrip("+-")) <= DECIMAL_DIGITS, f"Column {target}: integer digit budget exceeded")
        return int(value)
    require(isinstance(value, int) or (isinstance(value, str) and DECIMAL.fullmatch(value) is not None),
            f"Column {target}: expected finite decimal text or SQLite integer")
    try:
        number = Decimal(value)
    except InvalidOperation as error:
        raise SnapshotError(f"Column {target}: invalid decimal text") from error
    details = number.as_tuple()
    require(number.is_finite() and len(details.digits) <= DECIMAL_DIGITS and
            isinstance(details.exponent, int) and abs(details.exponent) <= DECIMAL_EXPONENT,
            f"Column {target}: decimal digit or exponent budget exceeded")
    return number


def identity(manifest: dict[str, Any]) -> dict[str, Any]:
    return {"format": manifest["format"], "adapter": manifest["adapter"], "toolVersion": manifest["toolVersion"],
            "input": manifest["input"], "database": manifest["source"]["database"],
            "sqliteVersion": manifest["source"]["sqliteVersion"],
            "budgets": manifest["budgets"], "columns": manifest["query"]["columns"],
            "snapshotSha256": manifest["snapshot"]["sha256"],
            "fingerprints": {name: manifest["fingerprints"][name]
                             for name in ("database", "schema", "query", "parameters", "mapping")}}


def build_manifest(source: str, database_hash: str, schema: list[dict[str, Any]], sql: str,
                   parameters: dict[str, Any], mapping: dict[str, dict[str, str]], columns: list[str],
                   input_id: str, data: bytes, count: int, bounds: Bounds) -> dict[str, Any]:
    manifest = {
        "format": FORMAT, "adapter": "sqlite", "toolVersion": TOOL_VERSION, "input": input_id,
        "source": {"database": source, "sqliteVersion": sqlite3.sqlite_version, "schema": schema},
        "query": {"sql": sql, "parameters": parameters, "columns": columns}, "mapping": mapping,
        "fingerprints": {"database": database_hash, "schema": sha256(encode_json(schema)),
                         "query": sha256(sql.encode("utf-8")), "parameters": sha256(encode_json(parameters)),
                         "mapping": sha256(encode_json(mapping))},
        "snapshot": {"file": "inputs.json", "bytes": len(data), "rows": count, "sha256": sha256(data)},
        "budgets": bounds.manifest(),
    }
    manifest["fingerprints"]["extraction"] = sha256(encode_json(identity(manifest)))
    return manifest


def verify_bundle(root: Path, supplied: str | Path, bounds: Bounds,
                  expected_manifest_hash: str | None = None) -> dict[str, Any]:
    bounds.validate()
    directory = protected_path(root, supplied, kind="directory")
    require({path.name for path in directory.iterdir()} == {"inputs.json", "manifest.json"},
            "Snapshot directory must contain exactly inputs.json and manifest.json")
    data = read_bounded(protected_path(root, directory / "inputs.json", kind="file"), bounds.max_bytes)
    metadata = read_bounded(protected_path(root, directory / "manifest.json", kind="file"), bounds.max_bytes)
    require(len(data) + len(metadata) <= bounds.max_bytes, "Output bundle byte budget exceeded")
    if expected_manifest_hash is not None:
        require(HASH.fullmatch(expected_manifest_hash) is not None, "Expected manifest digest must be lowercase SHA-256")
        require(sha256(metadata) == expected_manifest_hash, "Manifest digest does not match expected SHA-256")
    manifest = strict_json(metadata)
    require(isinstance(manifest, dict) and manifest.get("format") == FORMAT and manifest.get("adapter") == "sqlite"
            and type(manifest.get("toolVersion")) is int and manifest["toolVersion"] == TOOL_VERSION,
            "Unsupported snapshot format or adapter version")
    try:
        snapshot = manifest["snapshot"]
        require(type(snapshot["bytes"]) is int and type(snapshot["rows"]) is int,
                "Snapshot byte and row counts must be integers")
        require(snapshot["file"] == "inputs.json" and snapshot["bytes"] == len(data) and
                snapshot["sha256"] == sha256(data), "Snapshot bytes or SHA-256 do not match manifest")
        values = strict_json(data, decimals=True)
        require(isinstance(values, dict) and set(values) == {"inputs"} and isinstance(values["inputs"], dict)
                and set(values["inputs"]) == {manifest["input"]}, "Snapshot must contain only its declared table input")
        records = values["inputs"][manifest["input"]]
        require(isinstance(records, list) and len(records) == snapshot["rows"] and len(records) <= bounds.max_rows
                and all(isinstance(record, dict) for record in records), "Snapshot table row count or shape is invalid")
        mapping = validate_mapping(manifest["mapping"])
        require(mapping == manifest["mapping"], "Manifest mapping must include explicit null policies")
        require(all(set(record) <= set(mapping) for record in records), "Snapshot contains unmapped target columns")
        for record in records:
            for target, descriptor in mapping.items():
                require(target in record or descriptor["null"] == "omit", "Snapshot omits a required mapped column")
                if target not in record:
                    continue
                value = record[target]
                if value is None:
                    require(descriptor["null"] == "include", "Snapshot null does not match its column policy")
                elif descriptor["type"] == "decimal":
                    require(not isinstance(value, bool) and isinstance(value, (int, Decimal)),
                            "Snapshot decimal column must contain JSON numbers")
                    number = Decimal(value)
                    details = number.as_tuple()
                    require(number.is_finite() and len(details.digits) <= DECIMAL_DIGITS and
                            isinstance(details.exponent, int) and abs(details.exponent) <= DECIMAL_EXPONENT,
                            "Snapshot decimal digit or exponent budget exceeded")
                elif descriptor["type"] == "integer":
                    require(isinstance(value, int) and not isinstance(value, bool), "Snapshot integer column is invalid")
                elif descriptor["type"] == "boolean":
                    require(isinstance(value, bool), "Snapshot boolean column is invalid")
                else:
                    require(isinstance(value, str), "Snapshot text column is invalid")
        parameters = validate_parameters(manifest["query"]["parameters"])
        validate_query(manifest["query"]["sql"])
        aliases = manifest["query"]["columns"]
        require(isinstance(aliases, list) and all(isinstance(alias, str) for alias in aliases)
                and len(aliases) == len(set(aliases)) and
                all(descriptor["source"] in aliases for descriptor in mapping.values()),
                "Manifest query aliases do not match the mapping")
        expected = {"schema": sha256(encode_json(manifest["source"]["schema"])),
                    "query": sha256(manifest["query"]["sql"].encode("utf-8")),
                    "parameters": sha256(encode_json(parameters)), "mapping": sha256(encode_json(mapping)),
                    "extraction": sha256(encode_json(identity(manifest)))}
        fingerprints = manifest["fingerprints"]
        require(all(isinstance(fingerprints[name], str) and HASH.fullmatch(fingerprints[name]) is not None
                    for name in ("database", *expected)), "Invalid manifest fingerprint")
        require(all(fingerprints[name] == digest for name, digest in expected.items()), "Manifest fingerprint mismatch")
    except (KeyError, TypeError, AttributeError) as error:
        raise SnapshotError("Incomplete or malformed snapshot manifest") from error
    return {"snapshotSha256": sha256(data), "manifestSha256": sha256(metadata),
            "extractionSha256": fingerprints["extraction"], "rows": len(records), "verified": True}


def read_facts(database: Path, image: Path, sql: str, parameters: dict[str, Any],
               mapping: dict[str, dict[str, str]], input_id: str, bounds: Bounds, deadline: float
               ) -> tuple[bytes, str, list[dict[str, Any]], list[str], int]:
    def checkpoint() -> None:
        require(time.monotonic() < deadline, "Extraction time budget exceeded")

    def backup_progress(_status: int, _remaining: int, total: int) -> None:
        checkpoint()
        require(total * page_size <= bounds.max_database_bytes, "Database image byte budget exceeded")

    checkpoint()
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True, timeout=min(bounds.timeout, 1))) as source:
        source.execute("PRAGMA query_only=ON")
        page_size = source.execute("PRAGMA page_size").fetchone()[0]
        pages = source.execute("PRAGMA page_count").fetchone()[0]
        require(pages * page_size <= bounds.max_database_bytes, "Database image byte budget exceeded")
        with closing(sqlite3.connect(image)) as destination:
            source.backup(destination, pages=256, progress=backup_progress, sleep=0.01)
    checkpoint()
    database_hash = sha256(read_bounded(image, bounds.max_database_bytes))
    with closing(sqlite3.connect(image.as_uri() + "?mode=ro", uri=True, timeout=min(bounds.timeout, 1))) as reader:
        reader.execute("PRAGMA query_only=ON")
        if hasattr(reader, "enable_load_extension"):
            try:
                reader.enable_load_extension(False)
            except sqlite3.NotSupportedError:
                pass  # This Python/SQLite build cannot load extensions at all.
        require(hasattr(reader, "setlimit"), "Python 3.11+ is required for SQLite resource limits")
        reader.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, min(bounds.max_bytes, bounds.max_database_bytes))
        reader.setlimit(sqlite3.SQLITE_LIMIT_SQL_LENGTH, CONFIG_BYTES)
        reader.setlimit(sqlite3.SQLITE_LIMIT_COLUMN, 512)
        reader.setlimit(sqlite3.SQLITE_LIMIT_EXPR_DEPTH, 100)
        reader.setlimit(sqlite3.SQLITE_LIMIT_VDBE_OP, 100_000)
        timed_out = False

        def progress() -> int:
            nonlocal timed_out
            timed_out = time.monotonic() >= deadline
            return int(timed_out)

        def authorizer(action: int, first: str | None, second: str | None,
                       _database: str | None, _trigger: str | None) -> int:
            if action in (sqlite3.SQLITE_SELECT, sqlite3.SQLITE_READ, sqlite3.SQLITE_RECURSIVE):
                return sqlite3.SQLITE_OK
            if action == sqlite3.SQLITE_FUNCTION and (second or first or "").lower() not in VOLATILE_FUNCTIONS:
                return sqlite3.SQLITE_OK
            return sqlite3.SQLITE_DENY

        reader.set_progress_handler(progress, 1000)
        reader.set_authorizer(authorizer)
        try:
            schema_cursor = reader.execute("SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY type, name")
            schema = []
            schema_bytes = 0
            for kind, name, table, statement in schema_cursor:
                checkpoint()
                entry = {"type": kind, "name": name, "table": table, "sql": statement}
                schema_bytes += len(encode_json(entry))
                require(schema_bytes <= bounds.max_bytes and len(schema) < 10_000, "Schema metadata budget exceeded")
                schema.append(entry)
            cursor = reader.execute(sql, parameters)
            require(cursor.description is not None, "Query must return records")
            columns = [description[0] for description in cursor.description]
            require(len(set(columns)) == len(columns), "Query aliases must be unique")
            positions = {name: columns.index(descriptor["source"]) for name, descriptor in mapping.items()
                         if descriptor["source"] in columns}
            require(len(positions) == len(mapping), "Mapping references an absent query alias")
            records = []
            estimated_bytes = len(encode_json({"inputs": {input_id: []}}))
            for row in cursor:
                checkpoint()
                require(len(records) < bounds.max_rows, "Result row budget exceeded; no rows were truncated")
                record = {}
                for target, descriptor in mapping.items():
                    value = row[positions[target]]
                    if value is None:
                        require(descriptor["null"] != "error", f"Column {target}: null source cell is not allowed")
                        if descriptor["null"] == "include":
                            record[target] = None
                    else:
                        record[target] = converted(value, descriptor, target)
                estimated_bytes += len(encode_json(record))
                require(estimated_bytes <= bounds.max_bytes, "Output byte budget exceeded")
                records.append(record)
            checkpoint()
            return encode_json({"inputs": {input_id: records}}), database_hash, schema, columns, len(records)
        except sqlite3.Error as error:
            if timed_out:
                raise SnapshotError("Extraction time budget exceeded") from error
            raise SnapshotError(f"Read-only query failed: {error}") from error


def synced_write(path: Path, content: bytes) -> None:
    with path.open("xb") as target:
        os.chmod(path, 0o600)
        target.write(content)
        target.flush()
        os.fsync(target.fileno())


def sync_directory(path: Path) -> None:
    if os.name == "posix":
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def extract(root: Path, database_path: str | Path, query_path: str | Path, parameter_path: str | Path | None,
            mapping_path: str | Path, input_id: str, output_path: str | Path, bounds: Bounds) -> dict[str, Any]:
    require(sys.version_info >= (3, 11), "Python 3.11+ is required for SQLite resource limits")
    bounds.validate()
    deadline = time.monotonic() + bounds.timeout
    database = protected_path(root, database_path, kind="file")
    query = protected_path(root, query_path, kind="file")
    mapping_file = protected_path(root, mapping_path, kind="file")
    output = protected_path(root, output_path)
    require(output != root and not database.is_relative_to(output), "Output cannot contain or replace the source database")
    protected_path(root, output.parent, kind="directory")
    require(not output.exists() or output.is_dir(), "Output bundle path must be a directory")
    require(isinstance(input_id, str) and input_id and len(input_id) <= 256 and not any(ord(c) < 32 for c in input_id),
            "Table input id must be a nonempty printable string of at most 256 characters")
    sql = read_bounded(query, CONFIG_BYTES).decode("utf-8")
    validate_query(sql)
    mapping = validate_mapping(strict_json(read_bounded(mapping_file, CONFIG_BYTES)))
    parameters = {} if parameter_path is None else validate_parameters(strict_json(read_bounded(
        protected_path(root, parameter_path, kind="file"), CONFIG_BYTES)))
    with tempfile.TemporaryDirectory(prefix=".mantra-snapshot-", dir=output.parent) as owned:
        working = Path(owned)
        data, database_hash, schema, columns, count = read_facts(
            database, working / "captured.sqlite", sql, parameters, mapping, input_id, bounds, deadline)
        manifest = build_manifest(str(database.relative_to(root)), database_hash, schema, sql,
                                  parameters, mapping, columns, input_id, data, count, bounds)
        metadata = encode_json(manifest)
        require(len(data) + len(metadata) <= bounds.max_bytes, "Output bundle byte budget exceeded")
        require(time.monotonic() < deadline, "Extraction time budget exceeded")
        if output.exists():
            existing = verify_bundle(root, output, bounds)
            require(read_bounded(output / "inputs.json", bounds.max_bytes) == data and
                    read_bounded(output / "manifest.json", bounds.max_bytes) == metadata,
                    "Existing snapshot differs; choose a new --out directory")
            return {**existing, "created": False, "directory": str(output)}
        bundle = working / "bundle"
        bundle.mkdir(mode=0o700)
        synced_write(bundle / "inputs.json", data)
        synced_write(bundle / "manifest.json", metadata)
        sync_directory(bundle)
        # Publishing a nonempty directory is a single atomic rename. Concurrent nonempty bundles
        # cannot be overwritten by rename; callers must choose a distinct destination on conflict.
        protected_path(root, output)
        require(not output.exists(), "Snapshot destination appeared during extraction; choose a new directory")
        os.rename(bundle, output)
        sync_directory(output.parent)
    return {"snapshotSha256": sha256(data), "manifestSha256": sha256(metadata),
            "extractionSha256": manifest["fingerprints"]["extraction"], "rows": count,
            "verified": True, "created": True, "directory": str(output)}


def parser() -> argparse.ArgumentParser:
    arguments = argparse.ArgumentParser(description=__doc__)
    commands = arguments.add_subparsers(dest="command", required=True)
    for name in ("extract", "verify"):
        command = commands.add_parser(name)
        command.add_argument("--root", type=Path, default=Path.cwd())
        command.add_argument("--max-rows", type=int, default=Bounds.max_rows)
        command.add_argument("--max-bytes", type=int, default=Bounds.max_bytes)
        command.add_argument("--max-database-bytes", type=int, default=Bounds.max_database_bytes)
        command.add_argument("--timeout", type=float, default=Bounds.timeout)
        if name == "extract":
            command.add_argument("--database", required=True)
            command.add_argument("--query-file", required=True)
            command.add_argument("--params")
            command.add_argument("--mapping", required=True)
            command.add_argument("--input", required=True)
            command.add_argument("--out", required=True)
        else:
            command.add_argument("--snapshot", required=True)
            command.add_argument("--expected-manifest-sha256")
    return arguments


def main(arguments: list[str] | None = None) -> int:
    options = parser().parse_args(arguments)
    try:
        root = options.root.resolve(strict=True)
        require(root.is_dir(), "--root must be an existing directory")
        bounds = Bounds(options.max_rows, options.max_bytes, options.max_database_bytes, options.timeout)
        if options.command == "extract":
            result = extract(root, options.database, options.query_file, options.params,
                             options.mapping, options.input, options.out, bounds)
        else:
            result = verify_bundle(root, options.snapshot, bounds, options.expected_manifest_sha256)
        print(encode_json(result).decode("utf-8"), end="")
        return 0
    except KeyboardInterrupt:
        print("Snapshot interrupted; no partial bundle was published", file=sys.stderr)
        return 130
    except (SnapshotError, sqlite3.Error, OSError, UnicodeError, ValueError, MemoryError) as error:
        # A signal arriving inside SQLite can be surfaced as OperationalError("interrupted")
        # instead of KeyboardInterrupt. Cleanup has already run through the owned contexts.
        if INTERRUPTED:
            print("Snapshot interrupted; no partial bundle was published", file=sys.stderr)
            return 130
        print(f"Snapshot rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    def interrupted(_signal: int, _frame: Any) -> None:
        global INTERRUPTED
        INTERRUPTED = True
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    raise SystemExit(main())
