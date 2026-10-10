# IFRS income-tax demonstrations

Demonstration only. This simplified application is not production accounting or tax software and
does not constitute accounting or tax advice. It demonstrates single-period tax-expense
reconciliation, declarative checks, conditional table-cell requirements and weighted ratio totals
through Mantra's public API. The separate `roll-forward/` schema extends this application with
recognized deferred-tax stocks and movements over three periods.

The conceptual references are IAS 12.81(c), 84–86. The [IFRS Foundation's IAS 12 overview](https://www.ifrs.org/issued-standards/list-of-standards/ias-12-income-taxes/)
and its [2022 issued standard](https://www.ifrs.org/content/dam/ifrs/publications/pdf-standards/english/2022/issued/part-a/ias-12-income-taxes.pdf?bypass=on)
were reviewed on 2026-10-04 for the relationship between accounting profit, expected tax and
tax expense, and for the definition of an effective rate. All facts, labels and amounts in this
application are independently authored and fictional. No standard text or illustrative-example
dataset is bundled. IFRS materials retain the IFRS Foundation's rights; this project is not endorsed
by the Foundation.

`case-demo.mantra` has North/South accounting profits of 240,000/80,000 and supplied applicable
rates of 25%/20%. Expected tax of 60,000/16,000 plus supplied tax effects of 5,400/−1,600 reconciles
to booked expense of 65,400/14,400. Entity effective rates are 27.25%/18%; the combined rate is
79,800 ÷ 320,000 = 24.9375%, rather than the arithmetic average of the entity rates.
`verify_expected.py` recomputes every expected amount using Python Decimal arithmetic independently
of Mantra and the generated workbook. Its checks include the combined rate under the
`effective-tax-rate@*` key in generated `*-values.json`; the zero-profit case must omit this
undefined aggregate.

The other cases cover a loss with a supplied tax benefit, zero accounting profit (undefined rate),
and an unreconciled expense plus a missing explanation. Findings remain visible while calculation,
paper generation and export continue. `params-precision.mantra` demonstrates a parameter layer that increases expected-tax rounding
precision from two to four places. These whole-unit fixture profits have the same exact amounts
under both settings. Reconciliations use an explicit 0.01 tolerance. Table imports and sample data are supplied for both inputs.

Simplifications of the single-period schema, also commented in `schema.mantra`: booked tax and tax
effects are supplied facts;
this does not calculate tax liabilities, deferred-tax balances, recoverability of tax assets,
Pillar Two taxes, intercompany eliminations, or a complete set of IAS 12 disclosures. Different
fictional rates demonstrate weighted aggregation and do not assert any jurisdiction's law. A loss
case's tax benefit is supplied, without assessing recognition criteria.

From the repository root:

```sh
./gradlew :apps:ifrs-income-taxes:test
python3 apps/ifrs-income-taxes/verify_expected.py --verify apps/ifrs-income-taxes/build/out/case-demo-values.json
./gradlew :mantra-cli:run --args='run apps/ifrs-income-taxes/schema.mantra --case apps/ifrs-income-taxes/case-demo.mantra --layout apps/ifrs-income-taxes/layout.mantra --audit'
./gradlew :mantra-cli:run --args='serve apps/ifrs-income-taxes --port 8080'
```

Every case and parameter variant renders HTML/Text golden files and a recalculating XLSX workbook
under `build/out/`. The generic workbench opens and edits the application without a domain adapter.

## Deferred-tax roll-forward

`roll-forward/` adds an independent, three-period demonstration alongside the unchanged
single-period rate reconciliation. Its conceptual references are IAS 12.47 (measurement rates),
58 and 61A (profit or loss and amounts outside profit or loss), and 79–80 (tax-expense components),
using the IFRS Foundation sources cited above. The sources establish accounting concepts only;
this project does not reproduce IFRS text or illustrative-example data.

All temporary differences are **supplied already-recognized amounts**, with a supplied opening rate
and closing rate for each year. Positive signed balances represent liabilities and negative balances
represent assets. One item represents one signed temporary difference, classified from its combined closing tax;
distinct asset and liability differences must be separate items. Every item carries separate supplied
profit-or-loss (PL) and other-comprehensive-income
(OCI) provenance buckets. The calculation carries each closing difference and tax amount into the
next opening stock, applies movements at the current supplied rate, and displays the rate-change
effect on the opening difference separately in the same provenance bucket. Each bucket is explicitly
rounded before its change is calculated, so fractional currency residues remain reconciled.

For each PL/OCI bucket, the independent formulas are:

```text
closing difference = opening difference + supplied movement
measured closing tax = round(closing difference × supplied closing rate, currency scale)
movement tax effect = measured closing tax − round(opening difference × closing rate, scale)
rate-change effect = round(opening difference × closing rate, scale) − opening recognized tax
opening tax + movement tax effect + rate-change effect = closing measured tax
```

The main case starts with a 2,500 recognized liability and a 1,000 recognized asset. Signed control
balances close at 1,500, 2,700 and 2,400 across 2026–2028, including a 25% to 30% supplied rate change
and OCI difference movements. Other cases cover zero balances, fractional rate changes with PL/OCI
provenance, equal gross assets and liabilities with a zero signed control balance, and an independently
reported closing balance that differs by 0.02. This mismatch is a visible nonblocking business finding;
computed balances and all export formats remain available. The precision case binds the four-place
parameter set explicitly. Opening and closing cross totals select the first and last period, while
movement cross totals sum flows. Gross asset/liability totals are descriptive; the signed control sum
does not assert that legal offsetting is permitted.

`verify_roll_forward.py` uses only Python Decimal and independently authored JSON facts. Its fixtures,
formulas and full-coordinate/partial-reduction references were hashed in
`independent/roll-forward/source-manifest.json` before the schema was implemented. The application
tests compare every numeric expectation at two and four places, render HTML/Text/PDF for every
scenario, and compare workbook formulas with the same independent amounts. They also change a real
workbook input to verify later-period recalculation, exercise the 0.01 reconciliation boundary,
and use the unmodified generic workbench for discovery, Explain, editing, undo and all four exports.
CSV samples and import templates are supplied for all four input tables.

From the repository root:

```sh
python3 apps/ifrs-income-taxes/verify_roll_forward.py --check
./gradlew :apps:ifrs-income-taxes:test
./gradlew :mantra-cli:run --args='run apps/ifrs-income-taxes/roll-forward/schema.mantra --case apps/ifrs-income-taxes/roll-forward/case-demo.mantra --layout apps/ifrs-income-taxes/roll-forward/layout.mantra --audit'
./gradlew :mantra-cli:run --args='serve apps/ifrs-income-taxes --port 8090'
# Compare an acceptance-generated values file to the independent oracle:
python3 apps/ifrs-income-taxes/verify_roll_forward.py --verify apps/ifrs-income-taxes/build/out/roll-forward/case-demo-values.json
```

Open the generic workbench at `http://127.0.0.1:8090` when running locally and select
**Deferred tax: demo**. Running through the shared development launcher can use the live frontend
address instead. No schema-specific UI or new calculation primitive is required.

The scope remains a fictional measurement and movement demonstration: it does not decide whether
temporary differences exist, qualify for recognition, are recoverable, use enacted or substantively
enacted rates, reverse in a particular jurisdiction, can be offset, or require additional disclosures.
It does not implement current tax, deferred-tax discounting, tax losses/credits, acquisitions,
foreign-exchange movements, transfers, or production recognition/allocation rules. Rates and provenance
are supplied facts; their validation checks only the numeric range and source completeness.
