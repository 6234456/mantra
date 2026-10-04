# IFRS 16 lease roll-forward demonstration

Demonstration only. These fictional fixtures are not production accounting software and do
not constitute accounting advice. The application supplies lease × year schedules for lease liability,
interest, payments, principal reduction and right-of-use asset depreciation. The schema, papers and tests depend only on Mantra public APIs.

The [IFRS Foundation's IFRS 16 overview](https://www.ifrs.org/issued-standards/list-of-standards/ifrs-16-leases/)
provides the lessee-accounting context. The readable primary adopted text is the
[EU's 2023/1803 annex](https://eur-lex.europa.eu/eli/reg/2023/1803/oj/eng/pdf),
IFRS 16.24, 26, 31–32 and 36–37: initial liability discounts unpaid payments; later interest
increases the liability and payments reduce it; the right-of-use asset follows depreciation
requirements. Sources were reviewed on 2026-10-04 for these concepts only. This is not a claim
of complete IFRS 16 compliance. No standard wording or illustrative-example dataset is bundled.
All entities, dates, rates and amounts below are independently authored and fictional.

The default Office lease has three end-of-year payments of 10,000 and a supplied annual rate
of 5%. Its initial liability and right-of-use cost are both 27,232.48:

| Year | Liability opening | Interest | Payment | Liability closing | ROU depreciation | ROU closing |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 2026 | 27,232.48 | 1,361.62 | 10,000 | 18,594.10 | 9,077.49 | 18,154.99 |
| 2027 | 18,594.10 | 929.71 | 10,000 | 9,523.81 | 9,077.49 | 9,077.50 |
| 2028 | 9,523.81 | 476.19 | 10,000 | 0 | 9,077.50 | 0 |

The Storage lease has zero interest and three payments of 6,000. Its initial liability is
18,000 and its period closing balances are 12,000 / 6,000 / 0; annual ROU depreciation is
6,000. Combined initial liability is 45,232.48. The final liability and ROU stocks are zero;
payment flow is 48,000, interest flow is 2,767.52 and depreciation flow is 45,232.48.
Annual closing balances must not be summed to produce a final stock.

`verify_expected.py` discounts every supplied future payment using an 80-digit Decimal
working context, then rounds the initial liability half-up to the declared currency scale.
It independently carries the previous liability closing balance into each opening balance,
rounds periodic interest half-up, deducts the actual supplied payment and records the exact
resulting closing balance. It never clamps a liability to zero or silently adjusts a payment.
The right-of-use cost adds a separately supplied commencement payment and initial direct
costs and subtracts a separately supplied commencement incentive. Ordinary depreciation is
rounded at the same currency scale; the last service-period charge explicitly absorbs its
currency residue. All lease and ROU bridges are checked exactly.

Each `case-*.mantra` has an independently authored JSON source in `fixtures/`:

- `demo`: a positive-rate lease and a zero-rate lease, with a one-cent terminal depreciation residue.
- `single-period`: one end payment of 8,400 at 5%; initial liability 8,000 and interest 400.
- `commencement-payment`: a 10,000 commencement payment plus two later payments of 10,000;
  initial future-payment liability 18,594.10 and ROU cost 28,594.10. Liability closes at
  9,523.81 / 0 / 0; depreciation is 9,531.37 / 9,531.37 / 9,531.36.
- `unreconciled`: Office's supplied second-period closing balance is 9,523.83 while the
  calculation remains 9,523.81, giving a −0.02 business reconciliation difference.
- `negative-payment`: Office payments 10,000 / −1,000 / 10,000; the arithmetic remains
  available while the demonstration's nonnegative-payment rule gives a business finding.
  At two-place precision, the one-cent final liability is preserved and supplied explicitly.
  At four-place precision the computed closing liability is zero; the supplied one-cent
  balance remains within the inclusive reconciliation tolerance.
  At two-place precision the second-period calculated closing 9,523.82 differs from the supplied
  9,523.81 by exactly the inclusive 0.01 tolerance. At four places it is 9,523.8095, a −0.0005
  difference. Both reconciliations pass without modifying the supplied balance.
- `negative-rate-outside-demo`: the supplied rate −1% is mathematically evaluated, including
  negative interest, while the demonstration's declared nonnegative-rate support rule fails.
  Negative rates are not asserted to be prohibited by IFRS 16. Rates at or below −100% are
  mathematically undefined for this discount formula and are rejected by the source script.

Reconciliation uses an inclusive 0.01 tolerance. The four-place precision variants use the
same fixture facts and separately supplied two-place balances; small rounding differences
within tolerance remain visible. Business node identifiers and numeric JSON keys match the public calculation coordinates.

`schema.mantra` declares lease × generated-period coordinates `P1`–`P3`, corresponding to
2026–2028. JSON sources retain the original `FY2026`-style labels as `source_id`. The one-year
case adds explicit zero coordinates in the remaining two reporting years; its original
first-year payment, interest and balances are unchanged. `layout.mantra` groups lease rows with
annual columns. `layout-transpose.mantra` presents lease members as rows and the `P2` liability
and right-of-use balances as columns, using the same calculation.

`data/leases.csv`, `payments.csv`, `periods.csv` and `reported-closing.csv`, with JSON mappings in
`import-templates/`, provide typed public CSV imports. `params-precision.mantra` selects four-place
currency calculations; the default uses two places. `parameters.json` records the independent
source policy; the executable schema fixes its explicit inclusive reconciliation tolerance at
0.01. `references/` holds independently computed sources. Per-lease/period amounts, annual
lease sums and reductions are separate: `@*` sums leases while choosing first opening stocks,
last closing stocks and total movements across periods. `@Office/*` fixes the Office lease and
collapses only the period axis. Every period remains addressable after a lease expires.
The independent sources cover all monetary global and partial reductions, including principal
and reported balances, initial measurement and every period-summary boundary. `@*/P2` fixes
the second year and sums leases. `source-manifest.json` fingerprints current facts, scripts,
references and application documents; the original preparation manifest remains separately archived.

Simplifications: annual regular periods and payments already known at commencement; supplied
rates and terms; straight-line ROU depreciation over the supplied term, with no transfer of
ownership or purchase option; no reassessment, lease modifications, index-linked rent,
restoration obligations, tax, FX, impairment, exemptions, lessor accounting or full disclosures.
Commencement payment and incentive fields are separate from the future unpaid-payment schedule.
These choices are commented in the schema.

```sh
python3 verify_expected.py --self-test
python3 verify_expected.py --case demo --summary
python3 verify_expected.py --case commencement-payment --scale 4 --summary
python3 verify_expected.py --verify references/demo-scale2-values.json
python3 verify_expected.py --write-references
```

`--verify` checks independent source serialization; `--write-references` regenerates
the independent references and the current source manifest. From the repository root:

```sh
./gradlew :apps:ifrs-leases:test
python3 apps/ifrs-leases/verify_expected.py --verify apps/ifrs-leases/build/out/case-demo-values.json
./gradlew :mantra-cli:run --args='serve apps/ifrs-leases --port 8080'
```

The shared application acceptance suite runs every case at both precision settings and renders
both matrix and transpose layouts to HTML, Text and XLSX. It checks every recalculated workbook
scalar against public engine values, including independently checked boundary and flow
reductions, and opens all cases in the generic workbench without application adapters.
