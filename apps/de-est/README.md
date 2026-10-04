# German income-tax demonstration

Demonstration only. This simplified schema is not production tax software and does not constitute
tax advice. The engine has no tax-specific logic; the application combines Staffel totals,
conditional person/property dimensions, progressive formulas, choices, parameter overrides and
extension slots through the public API.

`schema.mantra` includes `einkuenfte.mantra` and `abzuege.mantra`. `layout.mantra` supplies the German
working paper; `params-2026.mantra` demonstrates another parameter set. `case-mustermann.mantra`
uses fictional joint-assessment data. `case-single-tax-free.mantra` exercises a single active
person, no children and a zero tax result. Tariff tests also cover statutory zone boundaries.

Expected amounts are independently recomputed by `verify_expected.py` using Python Decimal and
the tariff formulas attributed in the schema and parameter set. This is a demonstration of those
specified formulas, without a guarantee of current or complete legislation. Simplifications are
marked with `;; Vereinfachung` in the scheme files, including pensions, loss carryforwards and tax
credits.

From the repository root:

```sh
./gradlew :apps:de-est:test
python3 apps/de-est/verify_expected.py
./gradlew :mantra-cli:run --args='run apps/de-est/schema.mantra --case apps/de-est/case-mustermann.mantra --layout apps/de-est/layout.mantra --audit'
./gradlew :mantra-cli:run --args='serve apps/de-est --port 8080'
```

Acceptance tests produce HTML, Text, XLSX and numeric verification JSON under `build/out/`, including
2026 parameter variants. `data/payroll.csv` and `import-templates/payroll.json` show a generic wide
CSV import into per-person input fields; apply the template in the workbench after selecting that
file. Explicit inputs in an existing case take precedence over imported values.

M1 business validation checks conflicting single-parent/joint-assessment flags and requires a
child when the single-parent flag is enabled. This application's data-completeness rule requests
explicit wage withholding when positive wages are supplied; a legal zero amount must be entered
as zero. These are scheme rules, rather than a claim to complete legal validation. The final
payment reconciliation compares assessed taxes with supplied withholding and advance payments
using an explicit 0.01 tolerance. Findings remain visible while calculations and saving continue.
The average income-tax rate is shown with explicit six-place calculation rounding.
