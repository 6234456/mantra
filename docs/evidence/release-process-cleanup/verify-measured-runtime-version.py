#!/usr/bin/env python3
"""Verify the exact retained measurement JAR without launching a JVM.

This verifier recognizes only the recorded direct LDC/ARETURN getters. It does
not infer an embedded version from a filename or a generated source file.
"""

import argparse
import hashlib
import io
import json
import struct
import zipfile
from pathlib import Path


MEASURED_SHA = "ffee7db6d28ee0c56142492908471ff4cd87bc242c0a46104be0424f40d62812"
ARCHIVE = Path("benchmarks/baselines/public-kernel-macos-aarch64")


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, size):
        end = self.offset + size
        if end > len(self.data):
            raise ValueError("truncated classfile")
        result = self.data[self.offset:end]
        self.offset = end
        return result

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def u4(self):
        return struct.unpack(">I", self.take(4))[0]


def parse_class(data):
    reader = Reader(data)
    if reader.u4() != 0xCAFEBABE:
        raise ValueError("not a classfile")
    minor, major = reader.u2(), reader.u2()
    pool = [None] * reader.u2()
    index = 1
    while index < len(pool):
        tag = reader.u1()
        if tag == 1:
            entry = (tag, reader.take(reader.u2()))
        elif tag in (3, 4):
            entry = (tag, reader.take(4))
        elif tag in (5, 6):
            entry = (tag, reader.take(8))
        elif tag in (7, 8, 16, 19, 20):
            entry = (tag, reader.u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            entry = (tag, reader.u2(), reader.u2())
        elif tag == 15:
            entry = (tag, reader.u1(), reader.u2())
        else:
            raise ValueError(f"unknown constant-pool tag {tag}")
        pool[index] = entry
        index += 2 if tag in (5, 6) else 1

    def ascii_utf(index):
        if pool[index][0] != 1:
            raise ValueError("expected CONSTANT_Utf8")
        # Inspected names and version literals are ASCII. Unrelated Kotlin
        # metadata remains bytes, avoiding modified-UTF8 replacement decoding.
        return pool[index][1].decode("ascii", errors="strict")

    def attributes():
        values = []
        for _ in range(reader.u2()):
            name = ascii_utf(reader.u2())
            values.append((name, reader.take(reader.u4())))
        return values

    reader.take(6)  # access_flags, this_class, super_class
    reader.take(reader.u2() * 2)  # interfaces
    groups = []
    for _ in range(2):  # fields then methods
        members = []
        for _ in range(reader.u2()):
            access = reader.u2()
            name, descriptor = ascii_utf(reader.u2()), ascii_utf(reader.u2())
            members.append((access, name, descriptor, attributes()))
        groups.append(members)
    attributes()  # class attributes
    if reader.offset != len(data):
        raise ValueError("trailing classfile bytes")
    return pool, ascii_utf, groups[0], groups[1], {"major": major, "minor": minor}


def string_constant(pool, ascii_utf, index):
    if pool[index][0] != 8:
        raise ValueError("expected CONSTANT_String")
    return ascii_utf(pool[index][1])


def inspect_runtime(data):
    pool, ascii_utf, _, methods, version = parse_class(data)
    getters = {}
    for access, name, descriptor, attributes in methods:
        if name not in ("getMantra", "getNormein"):
            continue
        if not access & 0x0001 or descriptor != "()Ljava/lang/String;":
            raise ValueError("version getter is not the public no-argument API")
        payloads = [payload for attr, payload in attributes if attr == "Code"]
        if len(payloads) != 1:
            raise ValueError("version getter must have exactly one Code attribute")
        code_reader = Reader(payloads[0])
        code_reader.take(4)  # max_stack, max_locals
        code = code_reader.take(code_reader.u4())
        if len(code) == 3 and code[0] == 0x12 and code[-1] == 0xB0:
            constant_index = code[1]
            instructions = ["ldc", "areturn"]
        elif len(code) == 4 and code[0] == 0x13 and code[-1] == 0xB0:
            constant_index = struct.unpack(">H", code[1:3])[0]
            instructions = ["ldc_w", "areturn"]
        else:
            raise ValueError("getter is not the recorded direct LDC/ARETURN form")
        if code_reader.u2() != 0:
            raise ValueError("version getter has exception handlers")
        getters[name] = {
            "descriptor": descriptor,
            "bytecodeHex": code.hex(),
            "instructions": instructions,
            "constantPoolIndex": constant_index,
            "returnedLiteral": string_constant(pool, ascii_utf, constant_index),
        }
    if set(getters) != {"getMantra", "getNormein"}:
        raise ValueError("missing public version getter")
    return {"classfileVersion": version, "getters": getters}


def inspect_generated(data):
    pool, ascii_utf, fields, _, version = parse_class(data)
    constants = {}
    for _, name, descriptor, attributes in fields:
        if name not in ("MANTRA", "NORMEIN"):
            continue
        if descriptor != "Ljava/lang/String;":
            raise ValueError("version constant is not a String")
        payloads = [payload for attr, payload in attributes if attr == "ConstantValue"]
        if len(payloads) != 1 or len(payloads[0]) != 2:
            raise ValueError("version field lacks a unique ConstantValue")
        index = struct.unpack(">H", payloads[0])[0]
        constants[name] = string_constant(pool, ascii_utf, index)
    if set(constants) != {"MANTRA", "NORMEIN"}:
        raise ValueError("missing generated version constant")
    return {"classfileVersion": version, "constantValues": constants}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--runtime-before", type=Path, default=ARCHIVE / "runtime-before.json")
    parser.add_argument("--runtime-after", type=Path, default=ARCHIVE / "runtime-after.json")
    args = parser.parse_args()
    data = args.jar.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != MEASURED_SHA:
        raise ValueError("JAR digest differs from the exact measured core")
    correlations = []
    for receipt in (args.runtime_before, args.runtime_after):
        record = json.loads(receipt.read_text(encoding="utf-8"))
        matches = [item for item in record["runtimeJars"] if Path(item["path"]).name == "mantra-core-1.0.0-rc.1.jar"]
        if len(matches) != 1 or matches[0]["sha256"] != digest or matches[0]["bytes"] != len(data):
            raise ValueError("retained JAR does not match the immutable measurement receipt")
        correlations.append({
            "receipt": str(receipt),
            "receiptSha256": hashlib.sha256(receipt.read_bytes()).hexdigest(),
            "sourceCommit": record["sourceCommit"],
            "matchedRuntimeJar": matches[0],
        })
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        runtime_path = "com/xqiou/mantra/core/api/RuntimeVersions.class"
        generated_path = "com/xqiou/mantra/core/api/BuildVersions.class"
        runtime_data, generated_data = jar.read(runtime_path), jar.read(generated_path)
    runtime, generated = inspect_runtime(runtime_data), inspect_generated(generated_data)
    if runtime["getters"]["getMantra"]["returnedLiteral"] != "1.0.0-rc.1":
        raise ValueError("measured public Mantra getter does not return RC")
    if runtime["getters"]["getNormein"]["returnedLiteral"] != "0.3.0":
        raise ValueError("measured public Normein getter does not return 0.3.0")
    if generated["constantValues"] != {"MANTRA": "1.0.0-rc.1", "NORMEIN": "0.3.0"}:
        raise ValueError("generated version constants differ")
    print(json.dumps({
        "status": "PASS",
        "method": "stdlib classfile parsing; no JVM execution",
        "scope": "exact retained core JAR matched to both 390-sample measurement runtime receipts; batch uses a separate CLI classpath not inventoried by those receipts",
        "jar": {"name": args.jar.name, "bytes": len(data), "sha256": digest},
        "correlations": correlations,
        "runtimeVersions": {"path": runtime_path, "sha256": hashlib.sha256(runtime_data).hexdigest(), **runtime},
        "buildVersions": {"path": generated_path, "sha256": hashlib.sha256(generated_data).hexdigest(), **generated},
        "metadataBoundary": "archived engineVersion field was a wrapper declaration; public-getter identity is independently established here from digest-bound bytecode",
    }, indent=2))


if __name__ == "__main__":
    main()
