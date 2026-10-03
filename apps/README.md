# Demonstration applications

Each directory is a separate Gradle project with its own schema, cases, layouts, independent checks
and format acceptance tests. These are demonstrations only, not production tax or accounting
software or professional advice. Applications are provided with the repository and are never
published as Maven library artifacts.

| Application | Gradle project | Demonstrated capabilities |
| --- | --- | --- |
| [German income tax](de-est/README.md) | `:apps:de-est` | Staffel, conditional members, choices, parameter layers, explicit rounding |
| [IFRS impairment](ifrs-impairment/README.md) | `:apps:ifrs-impairment` | Allocation, member matrices, choices, formula bindings |
| [Cost accounting](cost-accounting/README.md) | `:apps:cost-accounting` | Table inputs, parent relations, allocation, weighted costs and reconciliation |

From the repository root run `./gradlew check`. Papers and workbooks are generated under each
application's `build/out/`. Every supplied case and parameter variant gets HTML/Text golden checks
and scalar-by-scalar XLSX recalculation checks. The generic workbench opens each case without domain
adapters. See [M0 work packages](../docs/milestones/m0-work-packages.md) for completion evidence and
remaining work.

To review an intentional rendering change, run `MANTRA_UPDATE_GOLDEN=1 ./gradlew test` and inspect
the changes under `apps/*/src/test/resources/golden/` and the workbench golden directory. Never use
this flag in CI. New M1–M3 applications belong directly under `apps/`.
