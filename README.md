# Mantra

Mantra is a general calculation-schema library built on the [Normein DSL](https://github.com/6234456/normein). It combines exact decimal evaluation, dependency graphs, allocation, dimensions, conditions and explicit rounding with working papers, audit information and formula-preserving Excel workbooks.

This monorepo contains the engine and domain demonstration applications under `apps/`. Domain rules live in their schemas; the engine and reference workbench provide generic capabilities.

**The applications are demonstrations only. They are not production tax or accounting software and do not provide tax or accounting advice.** Their simplifications and verification sources are documented in each application's README. Mantra is pre-1.0; APIs and DSL contracts may change with documented version changes. Library artifacts have not yet been published; coordinated publication with `normein-dsl` is planned in the [roadmap](docs/roadmap.md).

```text
schema + case + parameters ─▶ mantra-core ─▶ calculation values and traces
                                      │
                               + layout ─▶ mantra-render ─▶ HTML / Text / PDF
                                      └──▶ mantra-excel  ─▶ XLSX
                                      └──▶ workbench    ─▶ HTTP / JSON / SSE
```

## Quick start

Requirements: JDK 21, Git, and Node.js 22.13+ (22.x) or 24+ for the workbench frontend and browser tests. Normein targets JVM 17; Mantra uses a JDK 21 toolchain.

Normein is currently private, so a full build requires authorized access or an existing authorized
local checkout. See the [kernel publication plan](docs/normein-publication.md) for the public artifact
release sequence. Create the pinned checkout using SSH by default, or select HTTPS explicitly:

```bash
NORMEIN_SOURCE=https://github.com/6234456/normein.git scripts/bootstrap-normein.sh
npm --prefix workbench-ui ci
./gradlew --no-daemon check
./gradlew :mantra-cli:installDist
```

The full kernel commit is in [normein-build.lock](normein-build.lock): `0a3ae1de844c92635fbbc03406a13cb0e8920c03` (language semantics 25, standard library 33). To use a separate clean checkout at that commit, set `NORMEIN_BUILD_PATH` or pass `-PnormeinBuildPath=/path/to/checkout`. The build rejects a different commit or tracked modifications.

Render the impairment demonstration as an HTML working paper:

```bash
mantra-cli/build/install/mantra/bin/mantra run apps/ifrs-impairment/schema.mantra \
  --case apps/ifrs-impairment/case-demo.mantra \
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
mantra-cli/build/install/mantra/bin/mantra serve apps --directory-policy trusted-local \
  --port 8090 --ui workbench-ui/dist
```

The example explicitly trusts a cooperative local directory. The default `strict-handles` policy
requires filesystem support for secure directory handles and never falls back silently. See the
[package authority and migration guide](docs/site/packages.md) for immutable mounts and explicitly
authorized editable host cases.

Open `http://127.0.0.1:8090/` in a browser. The reference workbench implements structure, working-paper and diagnostic views, Explain, Compare, parameter layers, case editing with preview and undo/redo, formula authoring, data-source import, export previews and SSE updates. Edits are written to the case documents so that results remain reproducible outside the workbench. The service listens only on loopback and confines file access to the workspace; it is a local reference tool.

Explain, paper and XLSX audit appendices share the kernel's source-level expression evidence. Ratio,
sum and first/last-period totals retain the engine's contributions and selected-member evidence.
Two-dimensional and transposed papers carry a separate calculation address for every numeric cell.

## CLI

After `installDist`, use `mantra-cli/build/install/mantra/bin/mantra`:

| Command | Purpose |
| --- | --- |
| `run <schema> --case <case>` | Calculate and render Text, HTML, XLSX or PDF; add `--layout`, `--format`, `--out` or `--audit` |
| `check <schema> [--case <case>]` | Compile the schema and linked case graph; inspect static structure without executing formulas or business checks |
| `catalog` | List schema forms, calculation functions, column contents and layout presets |
| `fixtures <case> [more cases...] --out <dir> [--workspace apps]` | Generate versioned workbench JSON fixtures |
| `diff <schema> --case <case> --variant-parameters <file[,file...]>` | Compare parameter sets or another case (`--variant-case`); JSON or Text |
| `explain <schema> --case <case> --address <node> [--coord <member[,member...]>]` | Explain one value with bounded source steps; JSON or Text |
| `serve <workspace> [--port 8080] [--ui workbench-ui/dist]` | Start the local workbench; manifest directories are captured as packages |
| `package-list`, `package-run`, `package-explain`, `package-migration-preview/apply` | Inspect captured packages and explicitly review/apply case migration |
| `lsp [--stdio]` | Start bounded static authoring services for VS Code and IntelliJ |

The [embedding guide](docs/site/embedding.md) demonstrates compiled templates, typed batches, dated
parameters and explicit dynamic XLSX capacities. The [English tutorial](docs/site/tutorial.md) starts
from an invoice schema and exports every supported format. Editor clients are in [editors/](editors/README.md).

Run `mantra help` for full options. XLSX exports report formulas replaced with verified calculation
values. An unsupported auxiliary formula with no verified value causes an explicit export error.
Large sums use compact ranges and bounded argument lists; Excel's formula size and nesting limits
remain enforced.

For a scoped total, use `--address aggregate.<node>` and `--coord dimension=member[,dimension=member...]`
in schema dimension order. Omitting `--coord` selects the complete aggregate.

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

For a paper with source-level audit steps, calculate through `Mantra.calculateForAudit`; `AuditOptions`
bounds capture and reports truncation. Ordinary `Mantra.calculate` remains available when no audit
is needed. Business failures preserve results: inspect `validationPassed` and diagnostics separately
from technical `succeeded`. The CLI and workbench capture audits for papers and exports by default.
XLSX main values and checks recalculate after edits; its original audit snapshot automatically shows
`outdated` until the original inputs, parameters and supplied-fact flags are restored. Re-export to
capture a new audit. `ExcelWorkbook.auditSnapshotStatusAddress()` exposes the snapshot-status cell.

Continuous periods support static intervals or generated months, quarters and years. A formula such
as `(prev closing opening-balance)` reads the preceding period, with a lazy explicit fallback for the
first period. Declare `:aggregate {:first year}` or `{:last year}` for opening or closing balances;
flows keep sum aggregation. `CalculationView.reduce(nodeId, fixed)` exposes the same scoped value
and evidence used by papers, Explain and XLSX. See the [period reference](docs/dsl-reference.md).

For repeated input edits, `Mantra.openSession(schema, case, parameters)` returns a closeable
`CalculationSession`. Its immutable results preserve prior values while `recalculate` invalidates
dependent member tasks and reuses unaffected work. Ordinary calculations use VALUE_ONLY kernel
execution; audit and Explain capture source-level traces explicitly. Open, recalculate and close a
session on the same thread; detached result snapshots may be read from other threads.

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
| `apps/ifrs-income-taxes` | Single-period IAS 12 tax-expense and rate reconciliation; `:apps:ifrs-income-taxes` |
| `apps/fixed-assets` | Multi-period cost, depreciation and carrying-amount roll-forward; `:apps:fixed-assets` |
| `apps/ifrs-leases` | IFRS 16 lease-liability and right-of-use asset roll-forward; `:apps:ifrs-leases` |

Applications depend only on public library APIs and are not published as library artifacts. Future domains are added directly under `apps/`.

## Verification and sources

- [Income tax](apps/de-est/README.md): statute-based tariff formulas and an independent recomputation script; fictional case data.
- [Impairment](apps/ifrs-impairment/README.md): fictional shared assets, discounted cash flows and capped allocation; independent Fraction arithmetic checks values, including insufficient-capacity cases.
- [Cost accounting](apps/cost-accounting/README.md): fictional cost data with an independent source/order/product reconciliation.
- [Income taxes](apps/ifrs-income-taxes/README.md): fictional tax-expense and rate adjustments, independently recomputed with Decimal arithmetic.
- [Fixed assets](apps/fixed-assets/README.md): fictional asset movements, partial years, residual floors and rounding boundaries, independently recomputed with Decimal arithmetic.
- [Leases](apps/ifrs-leases/README.md): fictional annual payments and discount rates, with independent Decimal verification of liability and depreciation schedules.

IFRS source material retains its owners' rights. See [third-party notices](THIRD_PARTY_NOTICES.md) for dependency licenses, source attribution and the completed demonstration source review.

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
- [Performance baseline](docs/performance-baseline.md), [M3 measurements](docs/performance-m3.md) and [kernel publication plan](docs/normein-publication.md)
- [DSL reference](docs/dsl-reference.md) and [Normein RFCs](docs/rfc/)
- [Workbench contract](docs/workbench/contract.md), [UI specification](docs/workbench/ui-spec.md) and [work packages](docs/workbench/work-packages.md)
- [Long-term roadmap and R1–R10 decisions](docs/roadmap.md)
- [Contributing](CONTRIBUTING.md), [support](SUPPORT.md) and [security policy](SECURITY.md)

Project-owned code is licensed under [Apache License 2.0](LICENSE). Dependency and third-party source rights are described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
