# Mantra

Mantra is a general calculation-schema library built on the [Normein DSL](https://github.com/6234456/normein). It combines exact decimal evaluation, dependency graphs, allocation, dimensions, conditions and explicit rounding with working papers, audit information and formula-preserving Excel workbooks.

This monorepo contains the engine and domain demonstration applications under `apps/`. Domain rules live in their schemas; the engine and reference workbench provide generic capabilities.

**The applications are demonstrations only. They are not production tax or accounting software and do not provide tax or accounting advice.** Their simplifications and verification sources are documented in each application's README. Mantra is pre-1.0; APIs and DSL contracts may change with documented version changes. Library artifacts have not yet been published; coordinated publication with `normein-dsl` is planned in the [roadmap](docs/roadmap.md).

```text
schema + case + parameters ─▶ mantra-core ─▶ calculation values and traces
                                      │
                               + layout ─▶ mantra-render ─▶ HTML / Text
                                      └──▶ mantra-excel  ─▶ XLSX
                                      └──▶ workbench    ─▶ HTTP / JSON / SSE
```

## Quick start

Requirements: JDK 21, Git, and Node.js 22.12 or newer for the workbench frontend and browser tests. Normein targets JVM 17; Mantra uses a JDK 21 toolchain.

Create the pinned Normein checkout. The default bootstrap source is SSH; HTTPS or an existing local clone can be selected explicitly:

```bash
NORMEIN_SOURCE=https://github.com/6234456/normein.git scripts/bootstrap-normein.sh
./gradlew test
./gradlew :mantra-cli:installDist
```

The full kernel commit is in [normein-build.lock](normein-build.lock): `0a3ae1de844c92635fbbc03406a13cb0e8920c03` (language semantics 25, standard library 33). To use a separate clean checkout at that commit, set `NORMEIN_BUILD_PATH` or pass `-PnormeinBuildPath=/path/to/checkout`. The build rejects a different commit or tracked modifications.

Render the impairment demonstration as an HTML working paper:

```bash
mantra-cli/build/install/mantra/bin/mantra run apps/ifrs-impairment/schema.mantra \
  --case apps/ifrs-impairment/case-ie8.mantra \
  --layout apps/ifrs-impairment/layout.mantra --format html --out out/ias36.html
```

Render the income-tax demonstration as text, including its audit appendix:

```bash
mantra-cli/build/install/mantra/bin/mantra run apps/de-est/schema.mantra \
  --case apps/de-est/case-mustermann.mantra --layout apps/de-est/layout.mantra --audit
```

Export an editable cost workbook and inspect its formula coverage report:

```bash
mantra-cli/build/install/mantra/bin/mantra run apps/cost-accounting/schema.mantra \
  --case apps/cost-accounting/case-demo.mantra \
  --layout apps/cost-accounting/layout.mantra --format xlsx --out out/costs.xlsx
```

Application tests write generated papers and workbooks to `apps/<application>/build/out/`.

## Reference workbench

Build the frontend in live mode, then serve the applications locally:

```bash
cd workbench-ui
npm ci
VITE_WORKBENCH_MODE=live npm run build
cd ..
mantra-cli/build/install/mantra/bin/mantra serve apps --port 8090 --ui workbench-ui/dist
```

Open `http://127.0.0.1:8090/` in a browser. Workbench v1 implements structure, working-paper and diagnostic views, Explain, Compare, parameter layers, case editing with preview and undo/redo, formula authoring, data-source import, export previews and SSE updates. Edits are written to the case documents so that results remain reproducible outside the workbench. The service listens only on loopback and confines file access to the workspace; it is a local reference tool.

Explain already uses the kernel's individual expression steps. The paper and XLSX audit appendices still use substituted root values; unifying them with Explain is planned for M1.

## CLI

After `installDist`, use `mantra-cli/build/install/mantra/bin/mantra`:

| Command | Purpose |
| --- | --- |
| `run <schema> --case <case>` | Calculate and render Text, HTML or XLSX; add `--layout`, `--format`, `--out` or `--audit` |
| `check <schema> [--case <case>]` | Read and compile the schema; inspect structure and dependencies |
| `catalog` | List schema forms, calculation functions, column contents and layout presets |
| `fixtures <case> [more cases...] --out <dir> [--workspace apps]` | Generate versioned workbench JSON fixtures |
| `diff <schema> --case <case> --variant-parameters <file[,file...]>` | Compare parameter sets or another case (`--variant-case`); JSON or Text |
| `explain <schema> --case <case> --address <node> [--coord <member[,member...]>]` | Explain one value with bounded source steps; JSON or Text |
| `serve <workspace> [--port 8080] [--ui workbench-ui/dist]` | Start the local workbench service |

Run `mantra help` for full options. XLSX exports report any formulas that fall back to values; they never silently promise full recalculation coverage.

## A minimal schema

```clojure
(schema demo/allocation {:title "Shared costs"}
  (input pool :decimal {:default 1200})
  (dimension team {:members [{:key :A :label "Team A"}
                            {:key :B :label "Team B"}]})
  (section costs "Costs by team" {:per team}
    (field weight "Allocation weight" {:default 1 :op :info})
    (line share "Allocated costs" (alloc/pro-rata pool all.weight 2) {:spread true})
    (total total-cost "Total costs")))
```

Schemas define input contracts and calculations. Cases provide inputs, parameter overrides and allowed extensions. Layouts arrange and format the same values without changing them. See the [DSL reference](docs/dsl-reference.md) for the host forms and `mantra.calc@1` functions.

## Repository structure

| Path | Responsibility |
| --- | --- |
| `mantra-core` | Document readers and models, exact evaluation, public `core.api` / `core.view` contracts, structure and trace; planner and execution internals stay in `core.engine` |
| `mantra-render` | Layout DSL, presets, working-paper grid, HTML and Text rendering |
| `mantra-excel` | Formula-preserving XLSX export, workbook descriptions and coverage reports |
| `mantra-workbench` | Workspace catalog, revisions, Explain, Compare, edits, authoring and imports |
| `mantra-server` | Loopback HTTP service, workspace safety, SSE and frontend hosting |
| `mantra-cli` | The seven commands listed above |
| `workbench-ui` | Generic React / TypeScript workbench frontend |
| `apps/de-est` | German income-tax demonstration; Gradle project `:apps:de-est` |
| `apps/ifrs-impairment` | IAS 36 impairment and allocation demonstration; `:apps:ifrs-impairment` |
| `apps/cost-accounting` | Manufacturing-order and product costs in a SAP CO style; `:apps:cost-accounting` |

Applications depend only on public library APIs and are not published as library artifacts. Future domains are added directly under `apps/`.

## Verification and sources

- [Income tax](apps/de-est/README.md): statute-based tariff formulas and an independent recomputation script; fictional case data.
- [Impairment](apps/ifrs-impairment/README.md): IAS 36 Illustrative Example 8 figures, plus an independently computed custom-weight case. The largest-remainder allocation differs from the printed B-unit split: 11/31 rather than 12/30. This is documented rather than hidden.
- [Cost accounting](apps/cost-accounting/README.md): fictional cost data with an independent source/order/product reconciliation.

IFRS source material retains its owners' rights. See [third-party notices](THIRD_PARTY_NOTICES.md) for dependency licenses, source attribution and the remaining IFRS publication review.

Frontend verification:

```bash
cd workbench-ui
npm ci
npm test
npm run build
MANTRA_TEST_CHROME="/path/to/installed/chrome" npm run test:e2e
```

Browser tests use an installed Chrome-compatible executable with a task-specific temporary profile and clean up their processes and profile. They do not download a browser. For interactive checks, prefer the Codex built-in Browser. See [CONTRIBUTING.md](CONTRIBUTING.md) for the complete contribution and verification workflow.

## Documentation and community

- [Architecture](docs/architecture.md) and [engine/application boundary](docs/engine-application-boundary.md)
- [DSL reference](docs/dsl-reference.md) and [Normein RFCs](docs/rfc/)
- [Workbench contract](docs/workbench/contract.md), [UI specification](docs/workbench/ui-spec.md) and [work packages](docs/workbench/work-packages.md)
- [Long-term roadmap and R1–R10 decisions](docs/roadmap.md)
- [Contributing](CONTRIBUTING.md), [support](SUPPORT.md) and [security policy](SECURITY.md)

Project-owned code is licensed under [Apache License 2.0](LICENSE). Dependency and third-party source rights are described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
