#!/usr/bin/env python3
"""Exercise the installed language server as a real bounded stdio process; own all temporary state."""
import json
import argparse
import os
from pathlib import Path
import select
import shutil
import subprocess
from tempfile import TemporaryDirectory
import time

ROOT = Path(__file__).resolve().parents[1]


def verify(command=None, label='standalone'):
    evidence = []
    with TemporaryDirectory(prefix="mantra-lsp-smoke-") as temporary:
        workspace = Path(temporary).resolve()
        source = ROOT / "docs/site/examples/invoice"
        for file in source.glob("*.mantra"):
            shutil.copyfile(file, workspace / file.name)
        executable = ROOT / "mantra-lsp/build/install/mantra-lsp/bin/mantra-lsp"
        process = subprocess.Popen(command or [str(executable)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            def send(value):
                body = json.dumps(value, ensure_ascii=False).encode("utf-8")
                process.stdin.write(f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body)
                process.stdin.flush()
                evidence.append({"sent": value})
            def exact(count, end):
                result = bytearray()
                while len(result) < count:
                    remaining = end - time.monotonic()
                    assert remaining > 0 and select.select([process.stdout], [], [], remaining)[0], "LSP response deadline"
                    chunk = os.read(process.stdout.fileno(), count - len(result))
                    assert chunk, "LSP exited before its response"
                    result.extend(chunk)
                return bytes(result)
            def receive():
                end = time.monotonic() + 20
                header = bytearray()
                while not header.endswith(b"\r\n\r\n"):
                    header.extend(exact(1, end))
                    assert len(header) <= 8192
                fields = dict(line.split(b":", 1) for line in bytes(header[:-4]).split(b"\r\n"))
                length = int(fields[b"Content-Length"])
                assert 0 < length <= 1048576
                value = json.loads(exact(length, end))
                evidence.append({"received": value})
                return value
            def request(identifier, method, params):
                send({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params})
                while True:
                    value = receive()
                    if value.get("id") == identifier:
                        assert "error" not in value, value
                        return value.get("result")
            capabilities = request(1, "initialize", {"rootUri": workspace.as_uri(), "capabilities": {}})["capabilities"]
            assert capabilities["positionEncoding"] == "utf-16"
            send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
            for file in sorted(workspace.glob("*.mantra")):
                send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {
                    "uri": file.as_uri(), "languageId": "mantra", "version": 1, "text": file.read_text()}}})
            schema = workspace / "schema.mantra"
            text = schema.read_text()
            offset = text.index("(* basis") + 3
            prefix = text[:offset]
            position = {"line": prefix.count("\n"), "character": len(prefix.rsplit("\n", 1)[-1].encode("utf-16-le")) // 2}
            params = {"textDocument": {"uri": schema.as_uri()}, "position": position}
            assert request(2, "textDocument/definition", params), "No actual definition"
            assert request(3, "textDocument/hover", params), "No actual hover"
            references = request(4, "textDocument/references", dict(params, context={"includeDeclaration": True}))
            assert len(references) >= 2, references
            renamed = request(5, "textDocument/rename", dict(params, newName="supplied-basis"))
            assert renamed["documentChanges"], renamed
            assert all(change["textDocument"]["version"] == 1 for change in renamed["documentChanges"])
            request(6, "shutdown", {})
            send({"jsonrpc": "2.0", "method": "exit", "params": {}})
            process.stdin.close()
            process.stdin = None
            remaining, stderr = process.communicate(timeout=10)
            assert process.returncode == 0, stderr.decode("utf-8", "replace")
            assert not remaining, "Unexpected unframed stdout after shutdown"
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=10)
            output = ROOT / f"build/lsp-smoke/{label}-transcript.json"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n")
    assert not Path(temporary).exists(), "LSP workspace cleanup failed"
    print("Installed stdio LSP initialized, navigated, hovered, found references and returned versioned rename; process and workspace removed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('--label', choices=['standalone', 'cli'], default='standalone')
    parser.add_argument('--command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    verify(args.command, args.label)
