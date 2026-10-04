import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { validateEdit } from "../src/edits";
import { ClientLifecycle } from "../src/lifecycle";

function fixture(body: (root: string, uri: string) => void): void {
  const root = mkdtempSync(join(tmpdir(), "mantra-vscode-client-"));
  try {
    const file = join(root, "schema.mantra");
    writeFileSync(file, "abc");
    body(root, pathToFileURL(file).toString());
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}
function rename(uri: string, version: number, start = 0, end = 3) {
  return {
    documentChanges: [
      {
        textDocument: { uri, version },
        edits: [
          {
            range: {
              start: { line: 0, character: start },
              end: { line: 0, character: end },
            },
            newText: "changed",
          },
        ],
      },
    ],
  };
}
test("rename accepts the exact current open version and refuses stale or closed documents", () =>
  fixture((root, uri) => {
    const docs = [{ uri, version: 5, text: "abc" }];
    assert.equal(validateEdit(rename(uri, 5), root, docs)[0].version, 5);
    assert.throws(() => validateEdit(rename(uri, 4), root, docs), /changed/);
    assert.throws(() => validateEdit(rename(uri, 5), root, []), /closed/);
    assert.throws(
      () => validateEdit({ changes: { [uri]: [] } }, root, docs),
      /versioned/,
    );
  }));
test("rename rejects real symlink escapes and file operations", () =>
  fixture((root, uri) => {
    const outside = mkdtempSync(join(tmpdir(), "mantra-vscode-outside-"));
    try {
      writeFileSync(join(outside, "secret.mantra"), "abc");
      symlinkSync(join(outside, "secret.mantra"), join(root, "alias.mantra"));
      const escaped = pathToFileURL(join(root, "alias.mantra")).toString();
      assert.throws(
        () =>
          validateEdit(rename(escaped, 1), root, [
            { uri: escaped, version: 1, text: "abc" },
          ]),
        /outside/,
      );
      assert.throws(
        () =>
          validateEdit(
            { documentChanges: [{ kind: "delete", uri }] },
            root,
            [],
          ),
        /Resource/,
      );
    } finally {
      rmSync(outside, { recursive: true, force: true });
    }
  }));
test("UTF-16 positions and disjoint ranges are checked before any application", () =>
  fixture((root, uri) => {
    const docs = [{ uri, version: 1, text: "a😀b" }];
    assert.throws(
      () => validateEdit(rename(uri, 1, 1, 2), root, docs),
      /surrogate/,
    );
    const edit = rename(uri, 1, 0, 3);
    edit.documentChanges[0].edits.push(
      rename(uri, 1, 1, 3).documentChanges[0].edits[0],
    );
    assert.throws(() => validateEdit(edit, root, docs), /Overlapping/);
    assert.equal(validateEdit(rename(uri, 1, 1, 3), root, docs).length, 1);
  }));
test("deactivation during start stops the owned client once after start settles", async () => {
  let resolve!: () => void;
  const events: string[] = [];
  const client = new ClientLifecycle(
    () => ({
      start: () => {
        events.push("start");
        return new Promise<void>((done) => {
          resolve = done;
        });
      },
      stop: async (timeout) => {
        events.push(`stop:${timeout}`);
      },
    }),
    (error) => {
      throw error;
    },
  );
  const starting = client.start();
  await Promise.resolve();
  const closing = client.close();
  resolve();
  await starting;
  await closing;
  await client.start();
  assert.deepEqual(events, ["start", "stop:5000"]);
});
test("a failed start is stopped and explicit restart creates a new owned client", async () => {
  let attempts = 0;
  const stops: number[] = [];
  const client = new ClientLifecycle(
    () => {
      const id = ++attempts;
      return {
        start: async () => {
          if (id === 1) throw new Error("missing executable");
        },
        stop: async () => {
          stops.push(id);
        },
      };
    },
    () => {},
  );
  await assert.rejects(client.start(), /missing/);
  await client.restart();
  await client.close();
  assert.deepEqual(stops, [1, 2]);
});
