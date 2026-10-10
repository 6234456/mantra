# Your first schema

Build a fictional charge of 25% on a supplied basis. This neutral example demonstrates data binding and audit/export behavior; it is not a tax model. The expected charge and total are exactly `1200 × 0.25 = 300.00`.

## Prepare the checkout

Use JDK 21 and the authorized pinned Normein checkout described in the [repository README](repo:README.md). From the repository root, install the local CLI with `./gradlew :mantra-cli:installDist`. Libraries are not yet published, so no Maven dependency coordinates are promised here.

The downloadable example directory is `docs/site/examples/invoice/`. To create your own workspace, copy its four files into a directory such as `out/tutorial/`. Keep that directory as the workspace boundary for all loading, source data and case links.

## Declare the schema

Download [schema.mantra](repo:docs/site/examples/invoice/schema.mantra). The schema declares a decimal fact, a template parameter, an in-place input row, a rounded calculation, a Boolean business check and a running total. The basis row is informational and does not add to the total.

{{file examples/invoice/schema.mantra clojure}}

This tutorial retains its original `1.0` exact version identity. New distributable schemes can use strict three-component SemVer; historic spellings remain exact. Node IDs are references, so choose names that do not collide with callable names. `:round [2 :half-up]` is part of calculation semantics; the layout's precision changes display only.

## Supply a parameter layer

Download [parameters.mantra](repo:docs/site/examples/invoice/parameters.mantra).

{{file examples/invoice/parameters.mantra clojure}}

Parameter precedence is schema defaults, supplied parameter sets in order, then the case's own `(params ...)`. The case pins this parameter set by ID. The low-level Kotlin evaluator accepts the loaded sets explicitly; a case graph loader resolves the authored IDs.

## Pin the case and presentation

Download [case.mantra](repo:docs/site/examples/invoice/case.mantra) and [layout.mantra](repo:docs/site/examples/invoice/layout.mantra).

{{file examples/invoice/case.mantra clojure}}

{{file examples/invoice/layout.mantra clojure}}

The case selects the exact schema version, parameter identity and layout identity. The check's status is displayed beside the values. All four files can be opened with the generic reference workbench; no application-specific handler is needed.

## Compile, run and export

Run these commands from the repository root against the provided files. They select the explicit workspace and root schema without extending permission to unrelated cases.

```sh
mantra-cli/build/install/mantra/bin/mantra check docs/site/examples/invoice/schema.mantra --case docs/site/examples/invoice/case.mantra --workspace docs/site/examples/invoice
mantra-cli/build/install/mantra/bin/mantra run docs/site/examples/invoice/schema.mantra --case docs/site/examples/invoice/case.mantra --workspace docs/site/examples/invoice --audit
mantra-cli/build/install/mantra/bin/mantra run docs/site/examples/invoice/schema.mantra --case docs/site/examples/invoice/case.mantra --workspace docs/site/examples/invoice --format html --out build/tutorial/paper.html
mantra-cli/build/install/mantra/bin/mantra run docs/site/examples/invoice/schema.mantra --case docs/site/examples/invoice/case.mantra --workspace docs/site/examples/invoice --format xlsx --out build/tutorial/workbook.xlsx
```

The run should show basis `1200`, the active parameter layer `0.25`, charge `300.00`, a passing check, and total `300.00`. The exporter reports any unsupported formula fallback explicitly. Treat a fallback or evaluation error as a reviewable export limitation rather than assuming every formula is editable.

PDF is also available with `--format pdf --audit --out build/tutorial/paper.pdf`. It includes bounded A4 pagination, page numbers and the generation-time audit appendix. The bundled font supports Latin/Greek/Cyrillic text; unsupported glyphs fail explicitly.

## Explain the result

```sh
mantra-cli/build/install/mantra/bin/mantra explain docs/site/examples/invoice/schema.mantra --case docs/site/examples/invoice/case.mantra --workspace docs/site/examples/invoice --address charge --format json --out build/tutorial/explain.json
```

Explain records actual expression evaluation, source locations and executed branches. Event IDs identify a particular kernel attempt; independent runs need not have identical IDs. Trace limits can truncate evidence without changing the result, and truncation is explicit.

## Edit and diagnose

In the exported workbook, change the basis input from `1200` to `2000`. The charge recalculates to `500.00`; the audit appendix remains the original snapshot and shows that it is outdated. Re-export after changing the case to capture current evidence.

In the case, supply a negative basis to exercise its declared minimum and the nonnegative check. Business findings preserve calculation values: inspect `validationPassed` separately from technical `succeeded`. `/` by zero is a technical runtime error; `decimal/divide` by zero returns nil. Do not infer error behavior from the display of an empty cell.

Use the [diagnostic directory](site:reference/diagnostics.html) to interpret codes, and the [embedding tutorial](site:embedding.html) to calculate and export through public APIs.

## Reuse other calculation patterns

The [syntax pattern guide](repo:docs/syntax-patterns.md) groups the DSL by facts, signed totals,
choices, dimensions, table matching and period calculations. It explains `info`, `subtract`,
`choose-min`, `choose-max` and compact case `rows` literals with their canonical equivalents.
The [copyable templates](repo:docs/templates/README.md) provide runnable schema/case/layout sets
for separately rounded ratios, exact rounded allocation, stock/flow roll-forwards and named rules.
The [reusable pattern guide](repo:docs/reusable-patterns.md) adds shared typed formula fragments,
source controls, actual/baseline comparisons and capped allocation. Its
[pattern workspace](repo:docs/patterns/README.md) includes browser preview instructions and reusable
presentation class presets.
