# Changelog

## Unreleased — v0.4 / M3 (implementation in progress)

- Establish exact schema-version links, layered run controls and scalar bounded convergence contracts.
  Workbench wire advances to 4 and mantra.calc to 2; implementation acceptance is pending.

## v0.3 / M2 (not published as library artifacts)

- Cache scoped Excel reduction expressions and restore live range sums for complete unconditional scopes;
  preserve editable guards, parent relations and declared period boundaries. Nested date predicates retain
  Boolean metadata and propagate upstream errors during workbook recalculation.
- Add continuous static/generated periods, member-level previous-period dependencies and explicit
  first/last stock aggregation. Flow reductions remain additive; public evidence comes from the core.
- Add grouped two-dimensional and transposed working papers with exact per-cell addresses.
- Advance the workbench contract to `mantra.workbench/3` with discriminated ratio/boundary/sum
  evidence. Kotlin APIs and host DSL advance to 0.3; clients and strict schemas migrate together.
- Add fictional fixed-asset and lease demonstrations and extend impairment with cash-flow value in
  use and capped allocation. Independent source checks precede application/export acceptance.

- Record 390 final performance samples and initial reference-device envelopes, preserving unresolved
  XLSX tail latency and the first M2 measurements. Dynamic insertion/deletion of workbook table records
  remains pending M4; the current workbook supports recalculation within its captured record set.

## v0.2 / M1 (not published as library artifacts)

- Add nonblocking `check` and `reconcile` items, conditional required inputs/columns and minimum
  table row counts. Business findings retain severity, category and precise table-cell locations;
  `succeeded` reports technical success and `validationPassed` reports business error findings.
- Add weighted ratio aggregation from aligned numerator/denominator sums with explicit rounding,
  active-coordinate filtering and undefined zero-denominator results. Undefined rate contributions
  propagate through running totals.
- Capture bounded kernel audit traces through `Mantra.calculateForAudit` and `AuditOptions`.
  Text, HTML, XLSX and Explain share steps and branch decisions, with visible truncation.
  XLSX keeps the original protected audit snapshot and automatically marks it outdated after input,
  effective-parameter or supplied-fact changes. Main values and business decisions remain formulas.
- Upgrade the strict workbench wire contract to `mantra.workbench/2`; clients must update together.
  Add category filters and table-cell diagnostics while permitting saves with business failures.
  Kotlin APIs and host DSL advance to 0.2; `mantra.calc@1` and the pinned Normein kernel are unchanged.
- Update the three existing applications to the new primitives and add `apps/ifrs-income-taxes`
  with fictional IAS 12 cases, independent Decimal verification and generic workbench support.
  Rename the cost schedule section `reconciliation` to `cost-reconciliation`.

## v0.1 / M0 foundation (not published as library artifacts)

- Split demonstrations into `apps/de-est`, `apps/ifrs-impairment` and `apps/cost-accounting`, each
  with its own Gradle project, English README, cases, import sample/template and format checks.
- Rename the cost schema/layout namespace from `sap.co` to `cost.accounting`. Update persisted
  schema/layout references in cases when moving an existing demonstration workspace.
- Move result and formula-authoring interfaces to `core.api` and trace/coordinate/member interfaces
  to `core.view`. Planner/vertex implementations are internal. Consumers use `Mantra.inspect` and
  `FunctionCatalog` for structural inspection and function discovery. These are breaking Kotlin
  package/API changes before the first library release; host DSL forms and JSON v1 remain unchanged.
- Eagerly detach calculation snapshots from caller-owned schema, inputs and provenance collections.
- Fix XLSX export of empty dimensions and inactive boolean values, including dependent boolean
  expressions, so the workbook agrees with the calculation result.
- Add architecture gates, golden papers for every case/parameter variant, full workbook value
  comparisons, independent numeric verification, CLI smoke checks and a GitHub Actions workflow.
- Add pinned Kotlin/TypeScript formatters and static checks, file/line limits, and executed-test
  floors for all ten modules. Split workbook, workspace and HTTP responsibilities into smaller files.
- Replace the impairment demonstration's former `case-ie8.mantra` dataset with independently
  authored fictional `case-demo.mantra` facts, including custom-weight and zero-loss variants.
- Keep large table sums as formulas by compacting adjacent cell references and bounding SUM
  argument counts. Report Excel formula-length and nesting limits explicitly as export fallbacks.
- Reject auxiliary-formula fallbacks when no verified value exists for the exact cell, preserving
  downstream calculation fidelity.
- Add a public-API synthetic benchmark harness and raw timing/heap records for planning,
  calculation, Explain, working papers and XLSX export.
- Add Apache-2.0 licensing, dependency notices and community documentation. Normein stays unchanged
  at the locked commit. Record the demonstration source review and coordinated Maven publication plan.
