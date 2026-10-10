# Mantra Workbench UI (WP8)

React, TypeScript, and Vite frontend for the workbench. All displayed calculation values and business labels come from the `mantra.workbench/4` documents in `docs/workbench/contract.md`. Fixture mode is read-only; live mode supports input editing, imports and formula authoring. UI source contains no schema or node-specific branches.

## Run

```sh
cd workbench-ui
npm ci
npm run check
npm run dev
npm run build
npm test
npm run test:e2e
```

Fixture mode is the default. `npm run dev` and `npm run build` copy the tracked
versioned golden documents into `public/fixtures/` automatically. The copied directory
is generated and ignored by Git. Its `index.json` has this shape:

```json
{
  "cases": [
    {
      "id": "path/to/case.mantra",
      "title": "Case title",
      "files": {
        "structure": "/fixtures/example/structure.json",
        "run": "/fixtures/example/run.json",
        "paper": "/fixtures/example/paper.json",
        "export-preview": "/fixtures/example/export-preview.json",
        "export-preview:Overview": "/fixtures/example/export-preview-0.json",
        "diagnostics": "/fixtures/example/diagnostics.json",
        "parameters": "/fixtures/example/parameters.json",
        "explains": { "node@member": "/fixtures/example/explain.json" }
      }
    }
  ]
}
```

Each document file is the full contract envelope (`contract`, `revision`, `engine`, `data`). The optional `explains` keys use `addressToPath` from `src/address.ts`; for example, `aggregate%2Enode` identifies a weighted aggregate. Fixture synchronization also discovers tracked Compare golden files and lists their variant parameter sets in the copied manifest. Tracked fixtures cover the repository demonstrations and business-validation boundary cases.

`npm run types:generate` derives `src/generated/contract.ts` from the checked-in JSON Schemas. The generated file is committed, and `npm test`, `npm run typecheck`, and `npm run build` fail if it is stale. `src/types.ts` uses those wire types and refines the schema's intentionally open presentation objects for UI components.

`npm run check` runs the pinned Prettier, ESLint and TypeScript checks. `npm run format` applies the
formatter to UI source and scripts; generated contracts use the same formatter when regenerated.
ESLint checks recommended JavaScript/TypeScript rules and React hook dependencies, with a 1200
nonblank-line file limit and 120-column code limit (long string and template literals are exempt).
Parameters and variables intentionally unused by fixture adapters use an underscore prefix.
The root `./gradlew check` also runs these frontend checks, so install `npm ci` before invoking it.

`npm test` exercises the React fixture adapter in jsdom, including business findings, input-cell navigation, package capabilities and weighted-ratio provenance. `npm run test:e2e` starts Vite and the already-installed system Chrome with a separate task-specific temporary profile, then runs 13 flows across all ten applications, checking overview → panel → addressed Paper cell → export → worksheet. It stops both processes and removes the profile on success or failure. Set `MANTRA_TEST_CHROME` to another installed Chrome-compatible executable if needed; the script never downloads a browser.

For the server, use `VITE_WORKBENCH_MODE=live npm run dev`. Vite proxies `/api` to `http://127.0.0.1:8080`; set the server to that port or adjust the proxy. A production build for server hosting must also set `VITE_WORKBENCH_MODE=live`. Live mode removes generated fixtures before starting or building, so they are not served with the live UI.

## Current scope

- Implemented: shared header and navigation, overview mainline and branch map, compass and breadcrumbs, tiered and matrix Paper tables, cell selection in the URL, keyboard movement across selectable cells, inspector with Paper audit fallback, choice comparison, and lazy provenance tree.
- The Paper row JSON is expected to contain cell objects with `text`, `address`, `editable`, and controlled `style` as specified in contract §6.4. If WP3 serializes a wrapper around these rows, update only `src/data.ts` or an adapter normalization function.
- Choice comparison uses Paper option rows when Explain is unavailable. The inspector reads member-specific Paper audit entries. Provenance requires Explain and exposes a continue control after depth five.
- The UI deliberately has no local arithmetic or number formatting. It reads `display` and cell `text` from the engine.
- Live panel tables can reveal zero rows through `includeZero=true` on the Paper endpoint; unchecking restores the bound layout's visibility. The server renders these rows without changing inputs, calculation values, explicitly hidden rows or exports. Paper responses declare `browsing`; static fixture previews disable the toggle. Find in table highlights matching rows and moves between them with the arrow buttons or Enter/Shift+Enter, preserving all rows and their order.
- Parameter layers, effective values and read-only parameter-set comparison use Parameters and Compare documents. `/cases/{case}/parameters?compare=<set-id>` is directly linkable and follows browser history. Comparison shows mainline, every changed panel/node, and effective parameter changes. The diagnostics page filters category and severity, shows source locations, row/column positions, and links input findings to their editor or calculated findings to their panel. Business errors set `validationPassed` to false while valid edits can still be saved; an unchanged implicit zero or false can be confirmed as an explicit required fact.
- Weighted aggregates display engine-owned numerator/denominator totals, rounding, included members, fixed context, undefined reasons and truncation. They use `aggregate.<node>` addresses. Total parts and Paper audit fallback show the same evidence; the UI never computes a ratio or invents kernel steps.
- The diagnostics response contains locations but no source text. The detail view shows line, column and offsets; a line-numbered source excerpt requires a later source-text API.
- Export shows workbook sheets, a bounded cell preview, formulas, and the exporter fidelity report. Fixture mode uses tracked previews; live mode offers XLSX, HTML, text and PDF downloads. Live edits retain raw input text on rejection and refresh the server's calculated effects after saving.

No browser binaries or test daemons are installed by this package. Fonts use local system fallbacks; the UI makes no remote font request and remains usable offline.

## Parameter scenarios

The Scenarios page compares up to eight named parameter sets against the current case. Each
column uses the existing read-only Compare operation, replacing the case's bound parameter sets
with one selected set while retaining case overrides. At most two comparisons run at a time;
failed scenarios retain their own error while other results remain available. Reload scenarios
repeats the comparison batch. Leaving the page cancels pending requests.

Selections use `/cases/{case}/scenarios?scenario=<set-id>&scenario=<another-set-id>` and follow browser
history. The navigation link retains the most recent selection for each case while the app stays
open. Unknown ids are rejected locally, and an over-limit URL sends no comparisons. Tables show
the engine's base, variant and delta display strings, including parameter provenance and changed
nodes. Mainline rows and scenario details have 50-row pages; the UI computes no financial values.
Each result retains its own comparison revision because the comparisons are independent snapshots.

Captured package scenarios require an explicitly chosen effective date. The page sends no comparison
until a valid ISO date is selected; the server checks parameter validity for that date and exact schema.
Package resources keep their scoped ids (`<mount>/<parameter-path>`), and selections and the date
remain together in the URL: `?scenario=<scoped-id>&effectiveDate=2026-07-01`. Choosing a different date
cancels the previous batch and discards its late results. The ordinary Parameters page remains
unavailable for package comparisons; the Scenarios page declares the separate capability.

Live mode calculates the selected scenarios; fixture mode reads only available tracked Compare
documents and reports missing combinations explicitly. Adapters without parameter comparison
capability show an explanation instead of sending unsupported requests.

Package mounts expose only their declared capabilities: captured sources/parameters are read-only, while explicitly mapped host cases can edit inputs, select eligible parameter layers and apply reviewed migrations with stale-revision checks and undo/redo. The UI persists system/light/dark appearance and uses the generated diagnostic catalogue for English/German messages; this does not claim every legacy page label is translated.
