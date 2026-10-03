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
| [Apache POI](https://poi.apache.org/) (`poi`, `poi-ooxml`, `poi-ooxml-lite`) | 5.4.1 | Apache-2.0 | XLSX export, descriptions and import |
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

Test/build tooling has additional transitive packages recorded in `package-lock.json`. Before
shipping a frontend or CLI distribution, inventory the actual bundled dependencies and include their
complete license and copyright notices, including transitive dependencies. Update this file when
declared dependencies or resolved runtime versions change.

## Domain source material and trademarks

### IAS 36 impairment demonstration

`apps/ifrs-impairment/case-ie8.mantra` uses numerical facts attributed to IAS 36 Illustrative
Example 8, paragraphs IE69–IE79. The schema, case labels, tests and generated working papers refer
to the same example. The original standard and illustrative material are owned by the IFRS Foundation.

The repository contains a Mantra implementation and case data, rather than a bundled IFRS standard
or PDF. This does not establish permission to publish every adapted label, example selection or
rendered output. The Foundation's [intellectual-property policy](https://www.ifrs.org/legal/intellectual-property/)
describes permission and licensing routes; its [website terms](https://www.ifrs.org/legal/terms-and-conditions/)
reserve the rights in its content and include Illustrative Examples. Mantra's Apache license grants
no rights in that underlying source material.

**Publication review remains open:** record the exact source edition and provenance of the IE8
figures and wording, determine the applicable reproduction basis, and retain permission where needed
or replace restricted example material with independently authored fictional facts and wording before
a public distribution. No written reproduction permission is recorded in this repository.

The independently authored custom-weight case and recomputation scripts verify Mantra's calculations;
they do not themselves resolve source-material rights. The application is a demonstration and is not
endorsed by the IFRS Foundation.

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
- Resolve the IAS 36 publication review before treating M0's open-source exit criteria as complete.
