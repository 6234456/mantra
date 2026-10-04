import { realpathSync } from "node:fs";
import { isAbsolute, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

export interface Snapshot {
  uri: string;
  version: number;
  text: string;
}
interface Position {
  line: number;
  character: number;
}
interface Range {
  start: Position;
  end: Position;
}
interface Edit {
  range: Range;
  newText: string;
}
export interface PreparedEdit {
  uri: string;
  version: number;
  edits: Edit[];
}

export function canonical(uri: string): string {
  const parsed = new URL(uri);
  if (
    parsed.protocol !== "file:" ||
    parsed.search ||
    parsed.hash ||
    parsed.hostname !== ""
  ) {
    throw new Error("Only local file documents are supported");
  }
  return realpathSync(fileURLToPath(parsed));
}
export function confined(root: string, uri: string): string {
  const path = canonical(uri);
  const suffix = relative(realpathSync(root), path);
  if (suffix === ".." || suffix.startsWith(`..${sep}`) || isAbsolute(suffix)) {
    throw new Error("Edit is outside this workspace");
  }
  return path;
}
function integer(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}
function offset(text: string, position: Position): number {
  if (!position || !integer(position.line) || !integer(position.character))
    throw new Error("Invalid position");
  let start = 0;
  for (let line = 0; line < position.line; line++) {
    const newline = text.indexOf("\n", start);
    if (newline < 0) throw new Error("Line is outside document");
    start = newline + 1;
  }
  const newline = text.indexOf("\n", start);
  let end = newline < 0 ? text.length : newline;
  if (end > start && text[end - 1] === "\r") end--;
  const result = start + position.character;
  if (result > end) throw new Error("Character is outside line");
  if (
    result > 0 &&
    result < text.length &&
    /[\uD800-\uDBFF]/.test(text[result - 1]) &&
    /[\uDC00-\uDFFF]/.test(text[result])
  ) {
    throw new Error("Position splits a UTF-16 surrogate pair");
  }
  return result;
}

/** Preflight only; the IDE owns application and undo. Never searches or rewrites symbols. */
export function validateEdit(
  raw: unknown,
  root: string,
  open: readonly Snapshot[],
): PreparedEdit[] {
  if (!raw || typeof raw !== "object")
    throw new Error("Missing workspace edit");
  const value = raw as { documentChanges?: unknown; changes?: unknown };
  if (
    value.changes !== undefined ||
    !Array.isArray(value.documentChanges) ||
    value.documentChanges.length > 64
  ) {
    throw new Error("Only bounded, versioned text document edits are accepted");
  }
  const snapshots = new Map(
    open.map((document) => [confined(root, document.uri), document]),
  );
  const seen = new Set<string>();
  const prepared: PreparedEdit[] = value.documentChanges.map((change) => {
    if (!change || typeof change !== "object" || "kind" in change)
      throw new Error("Resource operations are refused");
    const document = change.textDocument;
    if (
      !document ||
      typeof document.uri !== "string" ||
      !integer(document.version)
    )
      throw new Error("Missing document version");
    const path = confined(root, document.uri);
    if (seen.has(path)) throw new Error("Repeated document edit");
    seen.add(path);
    const snapshot = snapshots.get(path);
    if (!snapshot || snapshot.version !== document.version)
      throw new Error("Document is closed or changed; request rename again");
    if (!Array.isArray(change.edits) || change.edits.length > 4096)
      throw new Error("Invalid text edits");
    const bounds = change.edits
      .map((edit: Edit) => {
        if (
          !edit ||
          typeof edit.newText !== "string" ||
          edit.newText.length > 1024 * 1024
        )
          throw new Error("Invalid replacement");
        const start = offset(snapshot.text, edit.range?.start);
        const end = offset(snapshot.text, edit.range?.end);
        if (start > end) throw new Error("Reversed range");
        return { start, end };
      })
      .sort((a: { start: number }, b: { start: number }) => a.start - b.start);
    for (let i = 1; i < bounds.length; i++) {
      if (
        bounds[i].start < bounds[i - 1].end ||
        bounds[i].start === bounds[i - 1].start
      )
        throw new Error("Overlapping text edits");
    }
    const edits = change.edits as Edit[];
    let updated = snapshot.text;
    const ordered = edits
      .map((edit) => ({
        edit,
        start: offset(snapshot.text, edit.range.start),
        end: offset(snapshot.text, edit.range.end),
      }))
      .sort((a, b) => b.start - a.start);
    for (const item of ordered)
      updated =
        updated.slice(0, item.start) +
        item.edit.newText +
        updated.slice(item.end);
    if (updated.length > 65_536)
      throw new Error("Renamed document exceeds server source limit");
    return { uri: document.uri, version: document.version, edits };
  });
  let total = open.reduce((sum, document) => sum + document.text.length, 0);
  for (const document of prepared) {
    const snapshot = snapshots.get(confined(root, document.uri))!;
    for (const edit of document.edits)
      total +=
        edit.newText.length -
        (offset(snapshot.text, edit.range.end) -
          offset(snapshot.text, edit.range.start));
  }
  if (total > 4 * 1024 * 1024)
    throw new Error("Renamed workspace exceeds server source limit");
  return prepared;
}
