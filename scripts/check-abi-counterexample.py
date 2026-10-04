#!/usr/bin/env python3
"""Execute a real ABI/binary break in an isolated temporary fixture, never in production sources."""
from pathlib import Path
from tempfile import TemporaryDirectory
import argparse
import hashlib
import json
import shutil
import subprocess
import sys


def invoke(command: list[str], cwd: Path, succeeds: bool = True) -> str:
    completed = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300, check=False)
    if (completed.returncode == 0) != succeeds:
        raise AssertionError(f"Unexpected exit {completed.returncode}: {command}\n{completed.stdout[-12000:]}")
    return completed.stdout


def class_hashes(directory: Path) -> dict[str, str]:
    return {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest() for path in directory.rglob("*.class")}


def verify(root: Path, gradle: Path) -> None:
    source = root / "release-fixtures/abi-counterexample"
    with TemporaryDirectory(prefix="mantra-abi-counterexample-") as temporary:
        fixture = Path(temporary) / "fixture"
        shutil.copytree(source, fixture, ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
        command = [str(gradle), "--no-daemon", "--max-workers=1", "--console=plain", "-p", str(fixture),
                   "-Pkotlin.compiler.execution.strategy=in-process"]
        invoke(command + [":library:updateLegacyAbi", ":consumer:writeRuntimeClasspath", ":consumer:writeJavaExecutable"], fixture)
        dumps = list((fixture / "library/api").rglob("*.api"))
        assert dumps, "ABI update must generate a real baseline"
        baseline = "\n".join(path.read_text() for path in dumps).replace(".", "/")
        assert "com/xqiou/abi/model/PublicRecord" in baseline, "Public model class excluded from baseline"
        invoke(command + [":library:check"], fixture)
        classpath = (fixture / "consumer/build/runtime-classpath.txt").read_text()
        java = (fixture / "consumer/build/java-executable.txt").read_text()
        java_main = "com.xqiou.abi.consumer.JavaBinaryConsumer"
        kotlin_main = "com.xqiou.abi.consumer.KotlinBinaryConsumerKt"
        assert "JAVA_BINARY_OK" in invoke([java, "-cp", classpath, java_main], fixture)
        assert "KOTLIN_BINARY_OK" in invoke([java, "-cp", classpath, kotlin_main], fixture)
        previous = class_hashes(fixture / "consumer/build/classes")
        assert previous, "Both consumers must really be compiled"
        declaration = fixture / "library/src/main/kotlin/com/xqiou/abi/model/PublicRecord.kt"
        declaration.write_text(declaration.read_text().replace("val value: Int", "val value: Long"))
        # Build ONLY the changed library. Recompiling the consumers would hide the actual binary break.
        invoke(command + [":library:jar"], fixture)
        evidence = root / "build/release-checks"
        evidence.mkdir(parents=True, exist_ok=True)
        (evidence / "abi-consumer-class-hashes.json").write_text(json.dumps(previous, indent=2, sort_keys=True) + "\n")
        for task in [":library:checkLegacyAbi", ":library:check"]:
            rejected = invoke(command + [task], fixture, succeeds=False)
            (evidence / ("abi-rejection-" + task.rsplit(":", 1)[1] + ".log")).write_text(rejected)
            assert "Task :library:checkLegacyAbi FAILED" in rejected, rejected[-12000:]
        assert previous == class_hashes(fixture / "consumer/build/classes"), "Old consumer binaries were rebuilt"
        for main in [java_main, kotlin_main]:
            broken = invoke([java, "-cp", classpath, main], fixture, succeeds=False)
            (evidence / ("abi-binary-" + main.rsplit(".", 1)[1] + ".log")).write_text(broken)
            assert "NoSuchMethodError" in broken, broken
    assert not Path(temporary).exists(), "Task fixture cleanup failed"
    print("ABI gate rejected a real public model Int-to-Long break; unchanged Java/Kotlin binaries both failed")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--gradle", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    verify(root, (args.gradle or root / "gradlew").resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
