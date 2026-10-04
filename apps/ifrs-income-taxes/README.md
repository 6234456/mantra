# IFRS income-tax reconciliation demonstration

Demonstration only. This simplified application is not production accounting or tax software and
does not constitute accounting or tax advice. It demonstrates single-period tax-expense
reconciliation, declarative checks, conditional table-cell requirements and weighted ratio totals
through Mantra's public API.

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

Simplifications, also commented in `schema.mantra`: booked tax and tax effects are supplied facts;
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
