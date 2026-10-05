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

Calculation sessions and graph runners have owner-thread and lifetime requirements. Use `use { ... }` to close them; detached snapshots can be handed to another thread. A calculation session serves repeated edits of one case. For independently identified cases, compile a template and open a worker with `CompiledCalculation.openSession`, or consume a streaming batch with `forEach`.

Each read or export has its own bounded request. `view.openReader(options)` can share a read epoch across compatible projections. `ExcelOptions.reading` controls the entire export's read work. Cancellation, deadlines and exceeded limits are technical failures, not zero results. Formula limits may lower complete pinned kernel ceilings; host usage is not a fabricated cumulative kernel usage counter.

## Inspect workbook coverage

`ExcelWorkbook.report` lists formula fallbacks and evaluation errors. The example requires both lists to be empty before writing its workbook. Inputs and parameters remain editable. Audit evidence belongs to the generation-time snapshot, whose status is available through `auditSnapshotStatusAddress()`.

For supported structured tables, opt into live member insertion, deletion and reorder with `ExcelOptions(dynamicTableCapacities = mapOf("table-id" to 8))`. Capacity includes authored rows and reserved slots; member keys and keyed scalar facts determine identity. Blank reserved rows are inactive. Duplicate/missing keys, invalid typed facts and interior holes are visible errors. Supported dynamic allocators allow at most twelve physical slots. Unsupported dynamic formula shapes fail explicitly. Inspect the export report for other limitations. The default remains a workbook shaped to the captured case. Convergence uses bounded helper formulas rather than global Excel iterative-calculation settings.

The IFRS 16 and fixed-asset demonstrations save and reopen inserted, deleted and reordered member states, then compare every meaningful number to independent expectations. These are actual OOXML/POI checks; they do not claim a manual Excel GUI certification.

## Compile once and stream typed cases

`Mantra.compile(schema, bindingCase, parameters)` freezes compatible formula/function/extension and parameter-type shape. The handle is immutable and shareable; each worker opens and closes its own kernel sessions on its owner thread. Vary typed `CaseData` facts and IDs without generating DSL text. Incompatible shapes are rejected explicitly. Every case receives fresh controls, domains, provenance and diagnostics.

`compiled.forEach(cases, parameters, BatchOptions()) { item -> ... }` consumes one iterator and calls back with detached current results. The first implementation is sequential. Defaults are 10,000 cases and five minutes for the batch, plus separate per-case limits. Callback exceptions escape after cleanup. Check `BatchSummary.failure` as well as technical and business outcomes; cancellation does not deliver an unfinished case.

The [lease batch example](repo:apps/ifrs-leases/src/main/kotlin/com/xqiou/mantra/apps/leases/LeaseBatchDemo.kt) is a complete public-API host. Its [independent producer](repo:apps/ifrs-leases/batch/independent_batch.py) supplies mathematical expectations. The measured 10,000 distinct cases compare 1,120,000 numbers and retain actual compilation/session counts; see [performance evidence](repo:docs/performance-v1.md).

## Capture a distributable package

`mantra-packages` loads a bounded `manifest.json` and the exact declared resource bytes from a directory or a pinned classpath/JAR container. It verifies byte lengths, SHA-256 digests, strict package SemVer and the declared engine range. It does not extract arbitrary ZIPs. A manifest dependency grants no file or network authority: the host explicitly mounts every package with `PackageCatalog` and uses `PackageGraphResolver`. `PackageImports` decodes captured CSV/JSON/XLSX resources without reopening the filesystem or calculating domain results.

Directory loading defaults to `DirectoryPolicy.STRICT_HANDLES`, requiring secure directory handles. On platforms without that facility, an explicitly cooperative local host can select `TRUSTED_LOCAL`. This mode checks no-follow components, file identity and captured digests but does not promise isolation from adversarial concurrent directory replacement. JAR resources remain read-only.

All applications include manifests. `mantra serve apps --directory-policy trusted-local` opens their captured packages in the generic workbench on a cooperative local machine. Plain schema workspaces remain supported. Editable cases require separately granted host storage in `mantra.package-workspace/1`; manifest discovery never grants write access.

## Select valid parameters and review migrations

Parameter selection receives an explicit effective date and named candidate sets/required keys. Validity is `[valid-from, valid-until)`; overlaps and gaps fail. What-if mode is explicit and retains source provenance. Selection decodes only requested keys before evaluation; it never silently aggregates domain data.

New scheme versions use strict three-component SemVer. Historic strings such as `2025.2` remain exact and coexist. A migration records byte and graph revisions, source/target bindings and concrete edit operations. Preview displays current before/after evidence; apply requires the exact reviewed token and compare-and-swap checks. Host storage writes atomically without changing the captured package. The [income-tax migration example](repo:apps/de-est/src/main/kotlin/com/xqiou/mantra/apps/deest/EStMigrationDemo.kt) demonstrates the whole flow with independently derived values.

See the [public declaration index](site:reference/api.html), [compatibility policy](repo:docs/compatibility.md) and [language specification](repo:docs/language-specification-v1.md). Local staging and clean-consumer tests validate packaging; they do not establish a Maven Central upload. The kernel `com.xqiou:normein-dsl:0.3.0` is already available from Maven Central. Mantra library coordinates will be announced after their actual publication.
