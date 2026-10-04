# Embed the same calculation in Kotlin

Use the four files from [the first-schema tutorial](site:tutorial.html). The following source uses current public `Mantra`, `Render` and `ExcelExport` entry points. It does not import execution plans or evaluators.

Download [InvoiceEmbedding.kt](repo:docs/site/examples/InvoiceEmbedding.kt).

{{file examples/InvoiceEmbedding.kt kotlin}}

The expected charge is `300.00`. Decimal comparisons use `compareTo`, so `300` and `300.00` are the same numeric value. The result retains the original immutable inputs, parameter layers, diagnostics and bounded trace evidence after the call returns.

## Own binding deliberately

Low-level `Mantra.calculate` and `calculateForAudit` accept a loaded schema, case and parameter list. They do not search files for case-authored parameter or layout IDs. This example explicitly supplies the parameter set and layout that the case declares.

For file-backed linked cases, use the public `CaseGraphRunner` with a `CasePackageResolver`; `mantra-workbench` supplies the confined `CasePackageLoader`. One graph run keeps exact source versions, participating revisions, typed source addresses, source-owned findings and shared top-level controls. A failed current graph must not be replaced with a previous successful result.

## Choose evidence and controls

`Mantra.calculate` avoids full audit capture. `calculateForAudit` receives `AuditOptions` separately from `CalculationOptions`; truncation is visible and does not alter arithmetic. `calculateForExplain` captures a requested value. A renderer consumes `CalculationResult.view`, not executable plans.

Calculation sessions and graph runners have owner-thread and lifetime requirements. Use `use { ... }` to close them; detached snapshots can be handed to another thread. Sessions serve repeated edits of a case, and do not promise M4's generic batch interface.

Each read or export has its own bounded request. `view.openReader(options)` can share a read epoch across compatible projections. `ExcelOptions.reading` controls the entire export's read work. Cancellation, deadlines and exceeded limits are technical failures, not zero results. Formula limits may lower complete pinned kernel ceilings; host usage is not a fabricated cumulative kernel usage counter.

## Inspect workbook coverage

`ExcelWorkbook.report` lists formula fallbacks and evaluation errors. The example requires both lists to be empty before writing its workbook. Inputs and parameters remain editable. Audit evidence belongs to the generation-time snapshot, whose status is available through `auditSnapshotStatusAddress()`.

The current exporter does not support arbitrary formulas or dynamic insertion of imported table rows. Re-export for a changed row shape; inspect explicit limits and fallback reports for other formulas. Convergence uses bounded helper formulas rather than global Excel iterative-calculation settings.

## M4 update point

Compiled schema handles, batch execution, distributable scheme packages, parameter validity selection and version-migration tooling belong to M4. Replace this section only when their public APIs and compatibility policy are delivered. This tutorial intentionally makes no dependency coordinates, package manifest or batch method up.

See the [public declaration index](site:reference/api.html) for current types and the [roadmap](repo:docs/roadmap.md) for planned interfaces.
