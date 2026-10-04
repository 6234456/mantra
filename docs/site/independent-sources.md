# Read the evidence in order

Each [application page](site:apps/index.html) links its English README, schema, case,
independent arithmetic and available actual HTML/Text/XLSX artifacts. Read the scope
first. A conceptual primary source explains a standard, law or unit; it does not turn
fictional demonstration numbers into real financial or engineering advice.

## Facts, independent references and runtime output

Fictional source records are written before the application calculation. A separate
Decimal/Fraction producer or verifier derives expected amounts without reading engine
values. Manifests preserve source and reference byte hashes. Runtime acceptance then
compares public values and reductions, real XLSX cells and expected BUSINESS findings.
Generated output never supplies its own independent expected result.

| Application | Independent source entry | Source inventory or scope |
| --- | --- | --- |
| German income tax | [Legacy arithmetic](repo:apps/de-est/verify_expected.py), [cross-year arithmetic](repo:apps/de-est/independent/_reference.py) | [M3 source manifest](repo:apps/de-est/independent/manifest.json), [migration example](repo:apps/de-est/migration/README.md) |
| IFRS impairment | [Fraction/Decimal arithmetic](repo:apps/ifrs-impairment/verify_expected.py) | [Current source manifest](repo:apps/ifrs-impairment/source-manifest.json) |
| Cost accounting | [Decimal cost flow](repo:apps/cost-accounting/verify_expected.py) | [Fictional source and rounding scope](repo:apps/cost-accounting/README.md) |
| IFRS income taxes | [Decimal reconciliation](repo:apps/ifrs-income-taxes/verify_expected.py) | [Fictional facts and conceptual IAS 12 sources](repo:apps/ifrs-income-taxes/README.md) |
| Fixed assets | [Independent depreciation](repo:apps/fixed-assets/verify_expected.py) | [Current source manifest](repo:apps/fixed-assets/source-manifest.json) |
| IFRS leases | [Independent lease schedule](repo:apps/ifrs-leases/verify_expected.py) | [Current source manifest](repo:apps/ifrs-leases/source-manifest.json), [streaming batch source](repo:apps/ifrs-leases/batch/README.md) |
| German trade tax | [Independent trade arithmetic](repo:apps/de-gewst/independent/_reference.py) | [Source manifest](repo:apps/de-gewst/independent/manifest.json), [primary sources](repo:apps/de-gewst/independent/primary-sources.json) |
| Circular calculations | [Independent rational certificates](repo:apps/circular-calculation/independent/_reference.py) | [Source manifest](repo:apps/circular-calculation/independent/manifest.json), [simplification scope](repo:apps/circular-calculation/README.md) |
| Energy budget | [Decimal/Fraction stock and flows](repo:apps/energy-budget/independent/reference.py) | [Source manifest](repo:apps/energy-budget/independent/manifest.json), [primary-source purpose](repo:apps/energy-budget/independent/primary-sources.json) |
| Project portfolio | [Decimal/Fraction supplied selection](repo:apps/project-portfolio/independent/reference.py) | [Source manifest](repo:apps/project-portfolio/independent/manifest.json), [primary-source purpose](repo:apps/project-portfolio/independent/primary-sources.json) |

Legacy scripts may contain the independently authored source facts directly. Later
applications retain separate JSON/CSV facts and reference files. Follow the commands
and precision conventions in the corresponding README; command flags differ by source
generation. Historical source manifests remain separate from current inventories.

## Stocks, flows, ratios and undefined values

Continuous-period applications distinguish first opening, last closing and summed
flows. Partial scopes fix an explicit axis before reduction. A weighted ratio divides
reduced numerator by reduced denominator; it does not add or average displayed rates.
Independent references cover these semantic addresses as well as individual values.

Undefined numeric results stay undefined and absent from numeric serialization.
Boolean false and supplied zero remain real facts. BUSINESS errors may preserve values
with `succeeded = true` and `validationPassed = false`. Technical failures have genuine
diagnostics; no zero, stale result or approximate amount substitutes for failed output.

## Actual acceptance and downloadable artifacts

Application tests generate the working papers and workbooks shown in the gallery.
The site generator copies existing files byte-for-byte and records hashes. It does
not run calculations, certify a workbook's financial accuracy or infer that a current
CI job passed from an old output file. Strict artifact mode rejects missing files,
invalid XLSX containers and broken paper links.

Directory/classpath-JAR package acceptance uses captured resources and the same public
calculation path. A matching manifest establishes byte identity and declared binding;
independent reference checks establish the chosen fictional numerical expectations.
See [package limitations](site:packages.html), [the first-schema tutorial](site:tutorial.html)
and [the two neutral-domain walkthroughs](site:neutral-domains.html).

All applications are demonstrations only. Their simplified schemas and invented
inputs do not establish legal, accounting, engineering, investment or staffing suitability.
