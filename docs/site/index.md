# Calculation schemas, explained

Mantra turns a declarative calculation schema, fictional or real host-supplied facts, parameter layers and a layout into reproducible values, working papers and editable workbooks. Domain rules belong to the schema; public engine and presentation APIs provide reusable primitives.

Start with [your first schema](site:tutorial.html), then [embed the same files in Kotlin](site:embedding.html). The [DSL reference](site:reference/dsl.html), [function directory](site:reference/functions.html), [diagnostic directory](site:reference/diagnostics.html) and [public API index](site:reference/api.html) are generated from the checkout's documentation and source.

## Separate the four documents

| Document | Owns |
| --- | --- |
| Schema | Typed inputs, calculations, dependencies, dimensions, business checks and explicit rounding |
| Case | Supplied facts, selected schema version, parameter/layout identities and permitted extensions |
| Parameters | Literal template overrides in an explicit order |
| Layout | Tables, columns, labels, number formatting and presentation styles |

Calculating and rendering remain separate. `CalculationResult.view` is a detached read-only snapshot. Ordinary evaluation uses the value path; `calculateForAudit` retains bounded evidence from actual kernel execution. A paper does not create another version of the arithmetic.

## Explore complete domain examples

The [application gallery](site:apps/index.html) covers eight repository applications: income tax, impairment, cost accounting, income-tax reconciliation, fixed assets, leases, trade tax and circular calculations. Each page links its README, schema, case, independent verification and available generated outputs.

The applications are demonstrations only. Their fictional inputs, commented simplifications and independent reference calculations illustrate engine capabilities; they are not production tax or accounting software.

## Current delivery boundary

This is an offline M5 documentation preparation. The [roadmap](repo:docs/roadmap.md) records completed milestones; the current acceptance report defines the delivery status of M3's graph and convergence capabilities. Source-derived APIs here describe the checkout rather than published library artifacts. Mantra is pre-1.0 and the pinned Normein kernel currently requires authorized access for a full build.

The current code contains public calculation sessions and graph execution. M4's package format, batch API, validity-based parameter selection, migration workflow and binary compatibility gates remain planned. This site does not announce those interfaces or treat this preparation as M5 completion.

[Repository README](repo:README.md) explains setup and the pinned kernel. [Architecture](repo:docs/architecture.md) and [engine/application boundary](repo:docs/engine-application-boundary.md) retain the authoritative design decisions.
