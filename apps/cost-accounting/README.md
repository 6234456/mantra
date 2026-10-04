# Cost-accounting demonstration

Demonstration only. This synthetic application is not production accounting software and does
not constitute accounting advice. Its flow is inspired by SAP CO-style cost collection, using a
neutral schema ID `cost.accounting/product-cost`; it has no SAP integration or endorsement.

`schema.mantra` collects direct primary costs, allocates primary/secondary pools to orders and
rolls those orders up to products. Unit costs divide total cost by completed quantity. Cross-member
totals use the ratio of total cost to total quantity: the main actual/standard totals are
65.6522/60.6957, rather than the sum of product rates. The fictional products use a comparable unit.
`layout.mantra` supplies order/product matrices and reconciliation.
`case-demo.mantra` is the main scenario; `case-zero-pools.mantra` exercises zero allocations and a
favourable variance. All data are fictional. `params-unit-precision.mantra` increases member unit-cost
precision from four to six places; weighted cross-totals retain their explicit four-place rounding.

Declarative checks require nonnegative order allocation bases and a positive total basis for a
nonzero cost pool. The orders and products tables require at least one row, and a direct posting
requires an explicitly supplied order ID. Source-to-order and order-to-product checks retain both
amounts, the difference and an explicit 0.01 tolerance. Business findings do not stop calculation;
invalid foreign keys and unusable allocation formulas still report execution errors. The schema
section formerly called `reconciliation` is now `cost-reconciliation`.

Independent Python Decimal arithmetic in `verify_expected.py` checks these amounts and the
weighted actual/standard cross-totals. Generated `*-values.json` records ratio aggregates with
an `@*` suffix, independently checked from total costs and quantities:

| Main scenario | Amount |
| --- | ---: |
| Direct primary / pooled primary / secondary | 12,400 / 900 / 1,800 |
| Orders O100 / O101 / O200 | 5,450 / 4,350 / 5,300 |
| Product A: actual / standard | 9,800 / 9,000 |
| Product B: actual / standard | 5,300 / 4,960 |
| Total actual / standard / variance | 15,100 / 13,960 / 1,140 |
| Source-to-order / order-to-product differences | 0 / 0 |

Simplifications are commented in the schema: pools do not overlap direct costs, secondary costs
are not counted again upstream, standards are supplied inputs, and quantities are completed
output. There is no posting, settlement, work-in-progress or Material Ledger calculation.

From the repository root:

```sh
./gradlew :apps:cost-accounting:test
python3 apps/cost-accounting/verify_expected.py --verify apps/cost-accounting/build/out/case-demo-values.json
./gradlew :mantra-cli:run --args='run apps/cost-accounting/schema.mantra --case apps/cost-accounting/case-demo.mantra --layout apps/cost-accounting/layout.mantra --audit'
./gradlew :mantra-cli:run --args='serve apps/cost-accounting --port 8080'
```

Acceptance tests render HTML/Text/XLSX to `build/out/`, compare every scalar workbook value, and
check recalculation after amount and parent-product edits. `data/primary-postings.csv` and
`import-templates/primary-postings.json` show generic table import; explicit inputs in an existing
case take precedence over the imported table. Foreign-key checks run in Mantra; XLSX does not
provide an order-number validation dropdown.
