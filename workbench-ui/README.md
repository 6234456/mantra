# Mantra Workbench UI (WP8)

React, TypeScript, and Vite frontend for the read-only workbench. All displayed calculation values and business labels come from the `mantra.workbench/1` documents in `docs/workbench/contract.md`. UI source contains no schema or node-specific branches.

## Run

```sh
cd workbench-ui
npm ci
npm run dev
npm run build
npm test
npm run test:e2e
```

Fixture mode is the default. `npm run dev` and `npm run build` copy the tracked
WP3 golden documents into `public/fixtures/` automatically. The copied directory
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
        "explains": { "node%40member": "/fixtures/example/explain.json" }
      }
    }
  ]
}
```

Each document file is the full contract envelope (`contract`, `revision`, `engine`, `data`). The optional `explains` keys use `addressToPath` from `src/address.ts`. WP3 supplies the manifest and three golden case sets; Explain fixtures are added with WP4.

`npm run types:generate` derives `src/generated/contract.ts` from the checked-in JSON Schemas. The generated file is committed, and `npm test`, `npm run typecheck`, and `npm run build` fail if it is stale. `src/types.ts` uses those wire types and refines the schema's intentionally open presentation objects for UI components.

`npm test` exercises all three tracked golden cases through the full React fixture adapter in jsdom. `npm run test:e2e` starts Vite and the already-installed system Chrome with a separate task-specific temporary profile, then checks overview → panel → addressed Paper cell in all three cases. It stops both processes and removes the profile on success or failure. Set `MANTRA_TEST_CHROME` to another installed Chrome-compatible executable if needed; the script never downloads a browser.

For the server, use `VITE_WORKBENCH_MODE=live npm run dev`. Vite proxies `/api` to `http://127.0.0.1:8080`; set the server to that port or adjust the proxy. A production build for server hosting must also set `VITE_WORKBENCH_MODE=live`. Live mode removes generated fixtures before starting or building, so they are not served with the live UI.

## Current scope and WP3/WP4 handoff

- Implemented: shared header and navigation, overview mainline and branch map, compass and breadcrumbs, tiered and matrix Paper tables, cell selection in the URL, keyboard movement across selectable cells, inspector with Paper audit fallback, choice comparison, and lazy provenance tree.
- The Paper row JSON is expected to contain cell objects with `text`, `address`, `editable`, and controlled `style` as specified in contract §6.4. If WP3 serializes a wrapper around these rows, update only `src/data.ts` or an adapter normalization function.
- Choice comparison uses Paper option rows when Explain is unavailable. The inspector reads member-specific Paper audit entries. Provenance requires Explain and exposes a continue control after depth five.
- The UI deliberately has no local arithmetic or number formatting. It reads `display` and cell `text` from the engine.
- The zero-row toggle requires a contract field or endpoint that exposes both shown and hidden rows. Current Paper reflects only the layout's visibility choice, so the toggle is deferred.
- Editing, formula authoring, import, export, diagnostics and parameters are later work packages. Their links have placeholder states in this shell.

No browser binaries or test daemons are installed by this package. Fonts use local system fallbacks; the UI makes no remote font request and remains usable offline.
