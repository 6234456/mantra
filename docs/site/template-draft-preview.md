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
3. After the first preview, use **Preview panel** to request one panel or return to **All panels**.
   The list comes from the engine's Structure. Changing the panel clears the previous Explain
   selection and marks the existing candidate as previous until you preview again. **Show zero rows**
   changes the preview context; it does not edit the layout's visibility settings.
4. Enable **Compare with saved template** to calculate the captured original schema/layout with
   saved example inputs. This independent baseline excludes all draft text and input overrides.
   **Changes from saved template** displays the engine's exact before, after and delta text,
   including added/removed values, member coordinates and parameter changes. The saved Paper is
   read-only; its cells cannot change the draft's Explain selection.
   A source-only formatting or title change can correctly report no value changes.
5. In **Example input overrides**, check a field and enter its raw text. Unchecked fields retain
   the saved case value. A checked empty field is a submitted override, subject to candidate
   parsing and validation. The candidate schema and layout locale determine how input text is
   parsed. This page exposes scalar fields; table or dimensioned overrides are outside this slice.
6. Select an addressable value cell, then click **Preview draft** again to obtain **Draft Explain**
   for that address from the same candidate. **Show zero rows** affects presentation and requires
   another preview.

**Current engine preview** means the response matches the current draft sequence, captured source
revisions and editing context. Editing or changing a preview option marks the existing result as
**Previous engine preview**. A canceled or delayed older request cannot replace the current draft.

A business validation finding can return values and a failed check. A runtime failure can return
partial tables and is labeled **runtime failure**. These results retain the engine's own status;
the browser does not calculate replacement values or treat a failed run as successful.

The comparison baseline follows the same captured source revision, panel and zero-row option.
Editing the draft or its temporary inputs keeps that baseline independent. Changing the panel,
zero-row option, case or source snapshot refreshes the baseline and discards obsolete replies.
Baseline errors have their own retry action; a missing panel in the saved template does not
replace a valid draft with guessed rows. A baseline `409` invalidates the shared captured revision
and blocks further previews until a new source snapshot is captured.

An empty candidate Paper stays empty and explains that no rows are visible. It never copies rows
from the saved template or an earlier nonempty preview. A stale comparison is explicitly labeled
**Previous draft comparison**, including while an invalid source draft retains the earlier Paper.

Comparison is optional and performs an additional isolated calculation. The ordinary draft
preview does not make this extra request when comparison is off. Preview remains explicit through
**Preview draft**; this slice does not add automatic typing previews or multi-case batch acceptance.

Expand **Advanced layout settings** for typed root-layout controls, including display precision,
locale, zero/negative formatting and visibility. Inspect the current source before applying fields;
use explicit overrides, reset to inheritance or cancel unapplied fields. See
[Edit advanced layout settings](layout-settings.md) for the complete list and conflict behavior.

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

This source-first page implements a bounded preview slice. Advanced layout settings add a typed
root-layout catalog and reversible source patches; general semantic owners, multi-document saves
and template publication remain separate work. Request limits,
response schemas and failure semantics are defined in the
[workbench contract](../workbench/contract.md#71-操作).
