# IFRS impairment demonstration

Demonstration only. This simplified application is not production accounting software and does
not constitute accounting advice. It illustrates proportional allocation with conserved totals,
CGU member matrices, higher-of choices and an application-declared formula binding using only
Mantra's public API.

`schema.mantra` and `layout.mantra` define the calculation and paper. All three cases contain
independently authored fictional facts for Demo Facilities Ltd. `case-demo.mantra` allocates 211
units of shared facilities across North, Central and South using weighted carrying values
120/360/780. Largest-remainder allocation produces 20/60/131; the resulting impairment losses
are 0/30/91 and total 121. `case-custom-weight.mantra` uses squared useful lives and totals 133;
`case-no-impairment.mantra` covers zero loss. `verify_expected.py` recomputes these amounts using
Fraction arithmetic, independently of Mantra and its XLSX formulas.

The application supplies recoverable amounts and simplifies the loss-allocation rules. It does
not implement a complete valuation or all IAS 36 requirements. These simplifications are also
commented in `schema.mantra`. Paragraph identifiers refer to the standard; no standard text or
published illustrative-example dataset is bundled in the current demonstration.

IFRS materials retain the IFRS Foundation's rights. This project is not endorsed by the Foundation.
See [third-party notices](../../THIRD_PARTY_NOTICES.md) for the source review and rights inventory.

From the repository root:

```sh
./gradlew :apps:ifrs-impairment:test
python3 apps/ifrs-impairment/verify_expected.py --verify apps/ifrs-impairment/build/out/case-demo-values.json
./gradlew :mantra-cli:run --args='run apps/ifrs-impairment/schema.mantra --case apps/ifrs-impairment/case-demo.mantra --layout apps/ifrs-impairment/layout.mantra --audit'
./gradlew :mantra-cli:run --args='serve apps/ifrs-impairment --port 8080'
```

All cases render HTML/Text and recalculating XLSX into `build/out/`. `data/cgus.csv` and
`import-templates/cgus.json` provide sample table data and a workbench import mapping. Existing
explicit case inputs take precedence over an imported table.
