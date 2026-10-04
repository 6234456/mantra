# Calculation schemas, explained

Mantra turns a declarative calculation schema, fictional or real host-supplied facts, parameter layers and a layout into reproducible values, working papers and editable workbooks. Domain rules belong to the schema; public engine and presentation APIs provide reusable primitives.

Start with [your first schema](site:tutorial.html), then [embed the same files in Kotlin](site:embedding.html). The [DSL reference](site:reference/dsl.html), [function directory](site:reference/functions.html), [diagnostic directory](site:reference/diagnostics.html) and [public API index](site:reference/api.html) are generated from the checkout's documentation and source. Read the [language specification](site:reference/specification.html) and [compatibility policy](site:reference/compatibility.html) for normative clauses and the independently versioned contracts.

## Separate the four documents

| Document | Owns |
| --- | --- |
| Schema | Typed inputs, calculations, dependencies, dimensions, business checks and explicit rounding |
| Case | Supplied facts, selected schema version, parameter/layout identities and permitted extensions |
| Parameters | Literal template overrides in an explicit order |
| Layout | Tables, columns, labels, number formatting and presentation styles |

Calculating and rendering remain separate. `CalculationResult.view` is a detached read-only snapshot. Ordinary evaluation uses the value path; `calculateForAudit` retains bounded evidence from actual kernel execution. A paper does not create another version of the arithmetic.

## Explore complete domain examples

The [application gallery](site:apps/index.html) covers ten repository applications: income tax, impairment, cost accounting, income-tax reconciliation, fixed assets, leases, trade tax, circular calculations, energy budget and project portfolio. Each page links its README, schema, case, package manifest, independent sources, tutorials and available actual generated outputs.

The [two neutral-domain walkthroughs](site:neutral-domains.html) show monthly energy stocks/flows and supplied project selection using existing primitives. [Independent-source guidance](site:independent-sources.html) distinguishes fictional facts, separate arithmetic and current runtime evidence. The applications are demonstrations only: their commented simplifications do not establish tax, accounting, engineering, investment or staffing suitability.

## Current delivery boundary

This is an offline documentation snapshot for the current implementation. The [roadmap](repo:docs/roadmap.md) and actual acceptance reports determine milestone status. M3 is completed; M4 capabilities are implemented and undergoing integrated acceptance, while M5/M6 documentation and stability work continue. This site does not declare those milestones complete or announce v1.0. Mantra is pre-stable; the pinned Normein kernel currently requires authorized access for a full build.

The current checkout includes public compiled calculation templates, streaming batch execution, captured directory/classpath-JAR packages, explicit validity-based parameter selection, reviewable migrations, dynamic XLSX capacity options and compatibility gates. [Package limitations](site:packages.html) describe resource authority, exact legacy versions, trusted-local capture, row-capacity bounds and publication prerequisites. [The measured batch report](repo:docs/performance-m4.md) records the actual 10,000-case evidence without treating one result as completion of all M4 exit conditions. Source/API pages describe this checkout; they do not promise publicly uploaded Maven artifacts.

[Repository README](repo:README.md) explains setup and the pinned kernel. [Architecture](repo:docs/architecture.md) and [engine/application boundary](repo:docs/engine-application-boundary.md) retain the authoritative design decisions.
