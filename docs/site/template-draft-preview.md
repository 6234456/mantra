# Preview a template draft with the real engine

The `/template-preview` page edits an in-memory schema/layout draft and renders its results with
the running Mantra engine. It also accepts example scalar inputs and requests Explain from that
same candidate calculation. Previewing keeps the workspace files unchanged.

## Start a file workspace

From the repository root, with JDK 21 and the supported Node.js version installed:

```sh
npm --prefix workbench-ui ci
./gradlew --no-daemon :mantra-cli:installDist
VITE_WORKBENCH_MODE=live npm --prefix workbench-ui run build
mantra-cli/build/install/mantra/bin/mantra serve docs/patterns \
  --ui workbench-ui/dist --port 8080
```

Open <http://127.0.0.1:8080/template-preview>. To open a specific example directly, use
<http://127.0.0.1:8080/template-preview?case=capped-allocation%2Fcase-demo.mantra>.
The ordinary live workbench also has a **Template draft** link for its current file-workspace case.

Use a plain file workspace such as `docs/patterns`. The CLI treats a directory containing
`manifest.json`, or immediate children containing that file, as a package workspace. The template
preview page currently requires the file-workspace endpoints, so the default `apps` package host
does not enable this page.

The server supplies the session metadata needed for preview requests. Open its HTTP URL rather
than `dist/index.html` or the static documentation gallery. For a cloud environment, use a local
TCP tunnel or supported loopback forwarding to expose the cloud backend as
`http://127.0.0.1:8080` on your computer. The server validates a loopback `Host` and its listening
port; a public preview proxy must preserve that backend host or the request receives `403`.

For frontend hot reload, keep the backend above running and start a second terminal:

```sh
VITE_WORKBENCH_MODE=live npm --prefix workbench-ui run dev -- --host 127.0.0.1 --port 5173
```

Then open <http://127.0.0.1:5173/template-preview>. Vite obtains session metadata from the backend
on port 8080 and proxies its API. Rebuild the CLI after backend changes; rebuilding only the UI
does not add new server endpoints.

## Edit and preview

1. Choose an **Example case**. The page captures the saved source set and displays its complete
   SHA-256 revision. **Participating document** lists the schema, layout and other captured DSL
   documents. The root schema and layout are editable; the case, includes and parameters display
   their read-only reason.
2. Click **Preview draft** once to render the engine tables and eligible scalar input fields.
   Edit labels, formulas, classes or supported layout text, then preview again. Source changes
   stay in this browser page; there is no template-file Save or Publish operation.
3. In **Example input overrides**, check a field and enter its raw text. Unchecked fields retain
   the saved case value. A checked empty field is a submitted override, subject to candidate
   parsing and validation. The candidate schema and layout locale determine how input text is
   parsed. This page exposes scalar fields; table or dimensioned overrides are outside this slice.
4. Select an addressable value cell, then click **Preview draft** again to obtain **Draft Explain**
   for that address from the same candidate. **Show zero rows** affects presentation and requires
   another preview.

**Current engine preview** means the response matches the current draft sequence, captured source
revisions and editing context. Editing or changing a preview option marks the existing result as
**Previous engine preview**. A canceled or delayed older request cannot replace the current draft.

A business validation finding can return values and a failed check. A runtime failure can return
partial tables and is labeled **runtime failure**. These results retain the engine's own status;
the browser does not calculate replacement values or treat a failed run as successful.

## Invalid drafts and source conflicts

`422` reports technically invalid source or input text. Diagnostics display the engine code,
original message and available document location. The source draft remains editable, and any
previous engine preview retains its previous label. Repair the draft and preview again.

`409` means a participating saved source changed. The page retains the draft and marks previous
results as previous; further preview is blocked until **Reload saved source** captures a new base.
Copy draft text you want to keep before accepting the reload confirmation. Reloading discards the
page's source changes and example overrides. Switching example cases also requests confirmation
before discarding a changed draft. Leaving the page can discard this in-memory
draft; the recorded `/authoring` prototype's browser recovery is a separate feature.

## Scope and API

`GET /api/v1/cases/{encoded-case}/template-sources` returns a source snapshot. Its revision covers
the complete captured case graph, including data, dependencies and linked cases. `baseRevisions`
contains every participating source identity and SHA-256; the editable document list contains
opaque handles, source text and roles.

`POST /api/v1/cases/{encoded-case}/template-preview` uses the session token and submits the complete
`baseRevision`/`baseRevisions`, `draftSequence`, source replacements by handle and example inputs.
One isolated candidate produces Structure, Run, Paper, Difference, diagnostics and optional
Explain. The server checks the saved source graph again before replying. It does not write files,
advance source history or publish a build.

Keep schema id/version, layout id, include paths and the captured dependency closure unchanged.
New dependencies or binding changes are rejected. A technically invalid saved baseline returns
`422` when capturing sources. Package workspaces do not provide these endpoints, and the page does
not fall back to fixture responses or the package API.

This source-first page implements a bounded preview slice. Semantic property owners, minimal
patches, multi-document saves and template publication remain separate work. Request limits,
response schemas and failure semantics are defined in the
[workbench contract](../workbench/contract.md#71-操作).
