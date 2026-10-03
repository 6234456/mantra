# IFRS impairment demonstration

Demonstration only. This simplified application is not production accounting software and does
not constitute accounting advice. It illustrates proportional allocation with conserved totals,
CGU member matrices, higher-of choices and an application-declared formula binding using only
Mantra's public API.

`schema.mantra` and `layout.mantra` define the calculation and paper. `case-ie8.mantra` uses numerical
inputs attributed to IAS 36 Illustrative Example 8 (IE69–IE79). `case-custom-weight.mantra` is a
synthetic variant with a different weighting formula; `case-no-impairment.mantra` is a synthetic
zero-loss case. `verify_expected.py` recomputes the amounts independently using Fraction arithmetic.

The engine's largest-remainder allocation deliberately assigns CGU B's loss 11 to corporate assets
and 31 to own assets. This differs from the illustrative example's printed 12/30 split. The
application uses supplied recoverable amounts; it does not implement a complete valuation or all
IAS 36 requirements. These simplifications are also commented in `schema.mantra`.

The IFRS Foundation owns rights in IFRS materials. Reference labels and numerical provenance do
not imply endorsement or grant rights to the source text. See [third-party notices](../../THIRD_PARTY_NOTICES.md)
for the outstanding provenance/permission review before the planned M0 public release.

From the repository root:

```sh
./gradlew :apps:ifrs-impairment:test
python3 apps/ifrs-impairment/verify_expected.py --verify apps/ifrs-impairment/build/out/case-ie8-values.json
./gradlew :mantra-cli:run --args='run apps/ifrs-impairment/schema.mantra --case apps/ifrs-impairment/case-ie8.mantra --layout apps/ifrs-impairment/layout.mantra --audit'
./gradlew :mantra-cli:run --args='serve apps/ifrs-impairment --port 8080'
```

All cases render HTML/Text and recalculating XLSX into `build/out/`. `data/cgus.csv` and
`import-templates/cgus.json` provide sample table data and a workbench import mapping. Existing
explicit case inputs take precedence over an imported table.
