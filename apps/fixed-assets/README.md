# Fixed-assets roll-forward demonstration

Demonstration only. These fictional fixtures are not production accounting software and do
not constitute accounting advice. The application uses cost-model fixed assets with
asset × year coordinates, period-by-period carry-forward and explicit stock/flow aggregation.
The schema, papers and tests depend only on Mantra public APIs.

The [IFRS Foundation's IAS 16 overview](https://www.ifrs.org/issued-standards/list-of-standards/ias-16-property-plant-and-equipment/)
provides the recognition and depreciation context. The primary adopted text is the
[EU's 2023/1803 annex](https://eur-lex.europa.eu/eli/reg/2023/1803/oj/eng/pdf),
IAS 16.50–55, 62 and 73(e): depreciable amount considers residual value, depreciation follows
the useful life and availability for use, and carrying amounts reconcile between periods.
[HGB §284(3)](https://www.gesetze-im-internet.de/hgb/__284.html) and
[HGB §253(3)](https://www.gesetze-im-internet.de/hgb/HGB.pdf) support separate cost and accumulated
depreciation schedules. Sources were reviewed on 2026-10-04 only for those concepts; this is
not a claim of complete IAS 16 or HGB compliance. No standard wording or illustrative-example
data is bundled. All entities, dates and amounts below are project-authored and fictional.

The default case covers calendar years 2026–2030. The machine starts at cost 120,000,
accumulated depreciation 22,000 and carrying amount 98,000, with residual value 10,000 and
48 remaining service months. Equipment costing 60,000 is added at the start of 2027, then
disposed of at the end of 2028 after that year's depreciation. Land costing 50,000 has no
depreciation. The separately supplied closing amounts are:

| Year | Opening carrying amount | Additions | Depreciation | Carrying amount disposed | Closing |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2026 | 148,000 | 0 | 22,000 | 0 | 126,000 |
| 2027 | 126,000 | 60,000 | 42,000 | 0 | 144,000 |
| 2028 | 144,000 | 0 | 42,000 | 20,000 | 82,000 |
| 2029 | 82,000 | 0 | 22,000 | 0 | 60,000 |
| 2030 | 60,000 | 0 | 0 | 0 | 60,000 |

At the end of the schedule, gross cost is 170,000 and accumulated depreciation is 110,000.
The disposal removes gross cost 60,000 and accumulated depreciation 40,000, giving a net
carrying amount removed of 20,000. Total depreciation is 128,000. The gross, accumulated and
net bridges all reconcile independently. The 60,000 closing balance is a stock at the last
period, not the sum of annual closing balances.

`verify_expected.py` reads only these fictional JSON facts and recomputes each asset and period.
It does not read a Mantra case, invoke a JVM library or extract values from a workbook.
Opening gross and accumulated balances are carried from the preceding closing balances.
Additions precede depreciation; disposal removes the entire remaining gross and accumulated
balances after depreciation. Asset coordinates remain present with zero closing balances
after disposal. The monthly coverage uses `[start,end)` boundaries, with service dates at the
start of a month. This monthly proportion is a declared demonstration convention, not a tax rule.
Depreciation is rounded half-up at the selected currency scale. The final service period
absorbs the currency residue down to the supplied residual value; a residual-floor clamp is
explicit and does not alter gross cost or accumulated balances silently.

Each `case-*.mantra` has an independently authored JSON source in `fixtures/`:

- `demo`: additions, full disposal, land and the final residual floor.
- `partial-year`: availability on 2026-07-01; charges 10,000 / 20,000 / 20,000 / 10,000 / 0.
- `rounding-residue`: a 100-unit tool over 36 months; annual charges 33.33 / 33.33 / 33.34.
  The four-place variant gives 33.3333 / 33.3333 / 33.3334.
- `fully-depreciated`: gross cost and accumulated depreciation both 48,000; zero future charges.
- `residual-floor`: a 100-unit tool with accumulated depreciation 80 and residual value 20;
  carrying amount remains 20 and depreciation remains zero.
- `unreconciled`: reported 2027 closing amount 144,000.02; computed amount stays 144,000,
  with a −0.02 business reconciliation difference against the explicit inclusive 0.01 tolerance.

`schema.mantra` declares asset × generated-period coordinates `P1`–`P5`, corresponding to
calendar years 2026–2030. The source JSON retains each original `FY2026`-style label as
`source_id`; only coordinate names were mapped during migration. `layout.mantra` groups asset
rows with annual member columns and stock/flow cross-totals. `layout-transpose.mantra` shows
assets as rows and the gross, accumulated and net balances of `P3` as columns, using the same
calculation.

`data/assets.csv`, `periods.csv` and `reported-closing.csv`, with JSON mappings in
`import-templates/`, provide typed public CSV imports. `params-precision.mantra` sets four-place
currency calculations; the default uses two places. `parameters.json` documents source policy,
while the executable schema fixes the explicit inclusive reconciliation tolerance at 0.01.
`references/` contains default and four-place independent numeric sources. Keys distinguish
asset/period values, per-period asset sums, first opening stocks (`*-opening@*`), last closing
stocks (`*-closing@*`) and cumulative flows. Engine-owned reductions select a period boundary
within every remaining asset group and sum other axes. They never select the last asset.
The independent sources also cover every asset-fixed and year-fixed monetary reduction,
presentation aliases, period-summary boundaries and reconciliation differences. `@*/P3`
fixes the third year and sums assets; `@Machine/*` fixes the machine and applies the declared
stock boundary or flow sum. Service-month counts follow the independently calculated date overlap.
`source-manifest.json` fingerprints the current facts, scripts, references and application documents;
the original preparation manifest remains separately archived.

Simplifications: whole assets only; cost model only; constant supplied residual values and
remaining lives; acquisitions without component replacement; no impairment, reversals,
revaluation, FX, tax depreciation, estimate revisions, construction interest or full disclosures.
No proceeds or gain/loss on disposal is calculated. The remaining-life input starts at the
model's supplied availability date, including when an existing asset has opening accumulated
depreciation. These choices are commented in the schema.

```sh
python3 verify_expected.py --self-test
python3 verify_expected.py --case demo --summary
python3 verify_expected.py --case rounding-residue --scale 4
python3 verify_expected.py --verify references/demo-scale2-values.json
python3 verify_expected.py --write-references
```

`--verify` checks independent source serialization; `--write-references` regenerates
the independent references and the current source manifest. From the repository root:

```sh
./gradlew :apps:fixed-assets:test
python3 apps/fixed-assets/verify_expected.py --verify apps/fixed-assets/build/out/case-demo-values.json
./gradlew :mantra-cli:run --args='serve apps/fixed-assets --port 8080'
```

The shared application acceptance suite runs every case at both precision settings and renders
both matrix and transpose layouts to HTML, Text and XLSX. It checks every recalculated workbook
scalar against public engine values, including independently checked boundary and flow
reductions, and opens all cases in the generic workbench without application adapters.
