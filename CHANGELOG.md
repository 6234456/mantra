# Changelog

## Unreleased — M0

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
