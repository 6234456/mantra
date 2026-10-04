# IFRS impairment demonstration

Demonstration only. This simplified application is not production accounting software and does
not constitute accounting advice. It illustrates proportional allocation with conserved totals,
CGU member matrices, higher-of choices and an application-declared formula binding using only
Mantra's public API.

`schema.mantra` and `layout.mantra` define the calculation and paper. The original three cases contain
independently authored fictional facts for Demo Facilities Ltd. `case-demo.mantra` allocates 211
units of shared facilities across North, Central and South using weighted carrying values
120/360/780. Largest-remainder allocation produces 20/60/131; the resulting impairment losses
are 0/30/91 and total 121. `case-custom-weight.mantra` uses squared useful lives and totals 133;
`case-no-impairment.mantra` covers zero loss. `params-cent-precision.mantra` repeats every case with
cent allocations: the main case shares 20.09/60.29/130.62 and total impairment 120.91.
`verify_expected.py --scale 2` recomputes this variant, while the default verifies whole units.
The same two settings apply to all six cases. New JSON facts in `fixtures/` document forecasts
and capacities; the independently expressed Fraction water-filling calculation retains cap excess.
The script uses Fraction arithmetic independently of Mantra and its XLSX formulas.
The three M2 cases additionally have numeric `references/` at both allocation precisions.
They cover all global reductions, every CGU-fixed and year-fixed forecast sum, and CGU-fixed
or asset-fixed capped allocations and carrying balances. Inactive branches contribute zero
to sum reductions while their exact nil coordinates remain absent from numeric sources.
Aliases, scalar summaries, discount-factor precision and parameter values are independently
derived from the facts and declared policies. `source-manifest.json` fingerprints the current
facts, script, references and application documents. New emitted reduction keys must have an
independent source; verification rejects uncovered keys.

The CGU table requires at least one record. Declarative checks require nonnegative CGU carrying
amounts and positive remaining useful lives. Every CGU's recognised loss is reconciled to its
allocation with an explicit 0.01 tolerance. App acceptance tests also demonstrate nonblocking
negative-amount and missing-table findings. These checks supplement the simplified scheme; they
do not implement the complete recognition and measurement requirements of IAS 36.

`case-discounted-viu.mantra` adds three fictional end-of-year pre-tax cash flows at 10%:
North 66 / 72.6 / 79.86, Central 77 / 84.7 / 93.17 and South 110 / 121 / 133.1. Each year's
present values are respectively 60, 70 and 100; their sums independently reproduce the legacy
180, 210 and 300 values in use. An 18-place discount factor and explicit allocation-currency
rounding are visible. The matrix uses CGU × generated year coordinates `P1`–`P3`.

`case-capped.mantra` starts with a 390-unit CGU, including 30 goodwill, and a 300 recoverable
amount. The 90 loss first absorbs goodwill; the remaining 60 goes to assets carrying
120 / 180 / 60. Individual floors 110 / 130 / 42 cap their losses at 10 / 50 / 18. The machine's
initial 20 share exceeds its 10 cap; redistribution produces 10 / 37.50 / 12.50 at cent precision,
or 10 / 38 / 12 in whole units with deterministic remainder order. Individual closing assets
sum to 300. A transpose paper shows the assets and their cap/closing columns.

`case-insufficient-capacity.mantra` limits each asset's loss to 5. Its 15 allocation leaves 45
unassigned, visible in the paper, and both the capacity check and allocation reconciliation fail.
The resulting individual closing sum is 345. This deliberate failure demonstrates the allocator's
unallocated excess; it is not a permissible finished accounting entry. Business failure retains
arithmetic and rendering for review.

The [IFRS Foundation's IAS 36 overview](https://www.ifrs.org/issued-standards/list-of-standards/ias-36-impairment-of-assets/)
and the [primary EU-adopted text](https://eur-lex.europa.eu/eli/reg/2023/1803/oj/eng/pdf),
IAS 36.33–55 and 104–105, support the cash-flow, goodwill-first and individual-floor concepts.
Sources were checked on 2026-10-04. The application uses fictional current-condition pre-tax
forecasts without terminal growth, financing, tax or future restructuring and simplifies other
loss-allocation rules. It does not implement a complete valuation or all IAS 36 requirements. These simplifications are also
commented in `schema.mantra`. Paragraph identifiers refer to the standard; no standard text or
published illustrative-example dataset is bundled in the current demonstration.

IFRS materials retain the IFRS Foundation's rights. This project is not endorsed by the Foundation.
See [third-party notices](../../THIRD_PARTY_NOTICES.md) for the source review and rights inventory.

From the repository root:

```sh
./gradlew :apps:ifrs-impairment:test
python3 apps/ifrs-impairment/verify_expected.py --verify apps/ifrs-impairment/build/out/case-demo-values.json
python3 apps/ifrs-impairment/verify_expected.py --self-test
python3 apps/ifrs-impairment/verify_expected.py --write-references
./gradlew :mantra-cli:run --args='run apps/ifrs-impairment/schema.mantra --case apps/ifrs-impairment/case-demo.mantra --layout apps/ifrs-impairment/layout.mantra --audit'
./gradlew :mantra-cli:run --args='serve apps/ifrs-impairment --port 8080'
```

All cases render HTML/Text and recalculating XLSX into `build/out/`. `data/cgus.csv` and
`import-templates/cgus.json` provide sample table data and a workbench import mapping.
`forecast-cashflows.csv` and `cgu-assets.csv` have matching public import templates. Existing
explicit case inputs take precedence over an imported table.
