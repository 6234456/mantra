# Third-party notices

Mantra's [Apache License 2.0](LICENSE) covers project-owned code. Dependencies keep their upstream
licenses and notices. References to standards or third-party examples do not relicense their source
material.

This inventory was checked on 2026-10-03 against `normein-build.lock`, Gradle build declarations,
the installed CLI runtime libraries and cached Maven POM/license metadata, and
`workbench-ui/package-lock.json`. It records the dependency families relevant to this repository;
it is not a replacement for the full notices required in a binary distribution.

## JVM dependencies

| Component | Version at this baseline | Upstream license | Use |
| --- | --- | --- | --- |
| [Normein DSL](https://github.com/6234456/normein) | 0.3.0, commit `0a3ae1de844c92635fbbc03406a13cb0e8920c03` | Apache-2.0 | Unmodified calculation kernel, included through a pinned composite build |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | 2.2.20 | Apache-2.0 | JVM runtime |
| [JetBrains annotations](https://github.com/JetBrains/java-annotations) | 13.0 | Apache-2.0 | Kotlin runtime dependency |
| [Apache POI](https://poi.apache.org/) (`poi`, `poi-ooxml`, `poi-ooxml-lite`) | 5.5.1 | Apache-2.0 | XLSX export, descriptions and import |
| [Apache XMLBeans](https://xmlbeans.apache.org/) | 5.3.0 | Apache-2.0 | POI OOXML dependency |
| [Apache Commons](https://commons.apache.org/) | Codec 1.18.0; Collections4 4.4; Compress 1.27.1; IO 2.18.0; Lang3 3.16.0; Math3 3.6.1 | Apache-2.0 | POI and XMLBeans runtime dependencies |
| [Apache Log4j API](https://logging.apache.org/log4j/2.x/) | 2.24.3 | Apache-2.0 | POI logging API |
| [SparseBitSet](https://github.com/brettwooldridge/SparseBitSet) | 1.3 | Apache-2.0 | POI runtime dependency |
| [Curves API](https://github.com/virtuald/curvesapi) | 1.08 | BSD-3-Clause (Maven POM: BSD License) | POI runtime dependency |
| [Jackson](https://github.com/FasterXML/jackson) (`jackson-annotations`, `jackson-core`, `jackson-databind`) | 2.18.3 | Apache-2.0 | HTTP JSON parsing and serialization |
| [RE2/J](https://github.com/google/re2j) | 1.8 | BSD-3-Clause, upstream describes it as the Go license | Regex implementation used by Normein |
| [JUnit 5](https://junit.org/junit5/) | 5.10.0 declared by Mantra | EPL-2.0 | Test-only |
| [NetworkNT JSON Schema Validator](https://github.com/networknt/json-schema-validator) | 2.0.1 | Apache-2.0 | Contract-schema tests only |

The RE2/J license credits the Go Authors (2009). Preserve its
[upstream license](https://github.com/google/re2j/blob/re2j-1.8/LICENSE) with bundled runtime
distributions. Apache dependency archives contain their own `META-INF/LICENSE` and, where supplied,
`META-INF/NOTICE`; retain these files rather than replacing them with Mantra's license.
Gradle is distributed under Apache-2.0; the repository's Gradle wrapper is build tooling.

Mantra consumes only `normein-dsl`. Normein's invoice examples, XML corpora, Schematron files and
other non-kernel modules are not Mantra runtime resources. If they are bundled in a future release,
apply their separate upstream notices rather than assuming the kernel license covers them.

## Workbench frontend and tooling

The npm lockfile pins the resolved versions below. License labels were checked against its package
metadata; the installed packages retain their full upstream license texts.

| Component | Resolved version | License | Use |
| --- | --- | --- | --- |
| [React and React DOM](https://github.com/facebook/react) | 19.2.8 | MIT | Workbench runtime |
| [CodeMirror 6](https://github.com/codemirror) | autocomplete 6.18.6; commands 6.8.1; lint 6.8.5; state 6.5.2; view 6.38.0 | MIT | Formula editor |
| [Lezer](https://github.com/lezer-parser) | common 1.2.3; highlight 1.2.1; lr 1.4.2 | MIT | CodeMirror dependencies |
| [style-mod](https://github.com/marijnh/style-mod), [w3c-keyname](https://github.com/marijnh/w3c-keyname), [crelt](https://github.com/marijnh/crelt) | 4.1.2; 2.2.8; 1.0.6 | MIT | Editor runtime dependencies |
| [TypeScript](https://github.com/microsoft/TypeScript) | 5.9.3 | Apache-2.0 | Build tooling |
| [Vite](https://github.com/vitejs/vite) | 7.3.6 | MIT | Frontend build/dev server |
| [Vitest](https://github.com/vitest-dev/vitest) | 3.2.7 | MIT | Component tests |
| [Testing Library React](https://github.com/testing-library/react-testing-library) | 16.3.2 | MIT | Component tests |
| [jsdom](https://github.com/jsdom/jsdom) | 26.1.0 | MIT | Test DOM |
| [DefinitelyTyped React / React DOM types](https://github.com/DefinitelyTyped/DefinitelyTyped) | 19.2.18 / 19.2.4 | MIT | Type checking |
| [ESLint](https://github.com/eslint/eslint), [typescript-eslint](https://github.com/typescript-eslint/typescript-eslint) | 10.12.0; 8.71.0 | MIT | Static checks |
| [React Hooks ESLint plugin](https://github.com/facebook/react), [eslint-config-prettier](https://github.com/prettier/eslint-config-prettier) | 7.1.1; 10.1.8 | MIT | Hook correctness and formatter compatibility |
| [Prettier](https://github.com/prettier/prettier) | 3.9.9 | MIT | Source formatting |

Test/build tooling has additional transitive packages recorded in `package-lock.json`. Before
shipping a frontend or CLI distribution, inventory the actual bundled dependencies and include their
complete license and copyright notices, including transitive dependencies. Update this file when
declared dependencies or resolved runtime versions change.

## Domain source material and trademarks

### IAS 36 impairment demonstration

The current `apps/ifrs-impairment` application uses independently authored fictional facts and
project-authored formulas, comments and presentation. The six cases, CSV input samples, expected
values, generated golden papers and browser fixtures share those facts. They do not reproduce
IAS 36 Illustrative Example 8. The pre-M0 dataset was replaced during the 2026-10-04 source review;
historical commits and design notes may identify that former comparison.

The schema retains paragraph identifiers as references to IAS 36. The standard, its illustrative
material and IFRS names retain their owners' rights; Mantra's license does not grant rights in them.
No standard PDF, paragraph text, official logo or Foundation publication is included as an application
resource. No written reproduction permission is recorded or claimed. Future copied source material
requires a separate source and permission review under the Foundation's
[intellectual-property policy](https://www.ifrs.org/legal/intellectual-property/) and
[website terms](https://www.ifrs.org/legal/terms-and-conditions/).

The independent Fraction-arithmetic script verifies the fictional calculations and rounding.
The application supplies recoverable amounts or independently discounts fictional cash flows,
and implements only a simplified subset of the
referenced accounting concepts. It is a demonstration, not an IFRS compliance product, and is
not endorsed by the IFRS Foundation.

### IAS 12 income-tax demonstration

`apps/ifrs-income-taxes` uses independently authored fictional entity facts, rate adjustments,
formulas and expected values. Its independent Decimal script verifies every
numeric output and the weighted effective tax rate. Concept references were checked on 2026-10-04
against the Foundation's [IAS 12 overview](https://www.ifrs.org/issued-standards/list-of-standards/ias-12-income-taxes/)
and [2022 issued standard](https://www.ifrs.org/content/dam/ifrs/publications/pdf-standards/english/2022/issued/part-a/ias-12-income-taxes.pdf?bypass=on).
Paragraph identifiers, including 81(c) and 84–86, identify concepts; the application reproduces no
standard text, official example facts, PDF, logo or publication. The same Foundation rights and
future reproduction review described above apply. The application deliberately simplifies
single-period tax-expense reconciliation and is neither an IFRS compliance product nor Foundation-endorsed.

### Fixed-assets and lease demonstrations

`apps/fixed-assets` and `apps/ifrs-leases` use project-authored fictional facts, schemas, layouts and
independent Decimal verification. Their IAS 16 and IFRS 16 paragraph references identify the
depreciation, reconciliation and lease-measurement concepts documented in their READMEs.
Concept sources were reviewed on 2026-10-04 against the Foundation's
[IAS 16 overview](https://www.ifrs.org/issued-standards/list-of-standards/ias-16-property-plant-and-equipment/),
[IFRS 16 overview](https://www.ifrs.org/issued-standards/list-of-standards/ifrs-16-leases/) and the
[EU adopted text](https://eur-lex.europa.eu/eli/reg/2023/1803/oj/eng/pdf).
The applications reproduce no standard wording, official illustrative-example dataset, PDF or logo.
The same Foundation rights and future reproduction review described above apply. Each application
documents its simplifications and is neither an IFRS compliance product nor Foundation-endorsed.

### German income-tax demonstration

`apps/de-est` refers to German statutes and official tariff formulas. It uses project-authored
schemas, fictional cases and an independent recomputation script. Keep statute and official-source
references attached to the relevant formulas; do not add copied commentary or third-party tables
without recording their source and applicable rights.

### Cost-accounting demonstration

`apps/cost-accounting` uses fictional manufacturing cost data and project-authored reconciliation.
“SAP CO style” describes the accounting pattern; SAP names and marks belong to their respective
owners. Mantra is not affiliated with or endorsed by SAP. The neutral directory and schema names
do not imply that third-party trademarks are licensed by Mantra.

## Maintenance

- Record source, version, license and local use when adding a dependency or copying a resource.
- Keep fictional/generated fixtures distinct from adapted published examples.
- Preserve upstream copyright, license and NOTICE files in distributions.
- Recheck this inventory against actual resolved dependencies for each release.
- Keep the current impairment demonstration fictional; re-review provenance before adding published examples.

## PDF rendering and fonts

`mantra-render` uses Apache PDFBox 3.0.8 under Apache-2.0.
The unmodified embedded DejaVu Sans regular/bold fonts are distributed with their complete
Bitstream Vera/Arev notices in `mantra-render/src/main/resources/com/xqiou/mantra/render/pdf/fonts/LICENSE.txt`.
Their source and SHA-256 values are recorded alongside the font files in `provenance.json`.
Official font license: https://dejavu-fonts.github.io/License.html.
