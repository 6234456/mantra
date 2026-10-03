# Changelog

## Unreleased — M0 foundation

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
- Add Apache-2.0 licensing, dependency notices and community documentation. Normein stays unchanged
  at the locked commit; IFRS material provenance review and the rest of M0 remain open.
