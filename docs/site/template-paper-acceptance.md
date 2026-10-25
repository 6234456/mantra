# Check the rendered template Paper

Version 2 acceptance suites can check the real candidate Paper's title, table columns, displayed
cell text, editability and final styles. Use these checks alongside exact calculation assertions
to catch layout and formatting regressions. The report includes the full Paper from the same
candidate calculation; there is no second renderer or copied baseline table.

## Run the example

From the repository root with JDK 21:

```sh
./gradlew --no-daemon :mantra-cli:installDist
mantra-cli/build/install/mantra/bin/mantra template-verify \
  docs/examples/template-paper-acceptance/suite.json \
  --workspace docs/examples/template-paper-acceptance \
  --out /tmp/mantra-paper-acceptance-report.json
```

The candidate changes the answer from multiplication by two to multiplication by three, displays
three decimal places and applies a reusable bold/accent/subtle class. Independent expectations
check `10 * 3 = 30`, raw German input `1,25 * 3 = 3.75`, a two-dimensional matrix with each cell
equal to seven, and the visibility of a declared zero row. Cases also select different panels and
zero-row preview contexts.

Sources: [suite](repo:docs/examples/template-paper-acceptance/suite.json),
[schema](repo:docs/examples/template-paper-acceptance/schema.mantra),
[layout](repo:docs/examples/template-paper-acceptance/layout.mantra) and
[case](repo:docs/examples/template-paper-acceptance/case.mantra).

## Create a version 2 suite

```sh
mantra-cli/build/install/mantra/bin/mantra template-capture \
  case.mantra --workspace docs/examples/template-paper-acceptance \
  --format 2 --out /tmp/my-paper-suite.json
```

Capture leaves `expect: null`. Fill independent calculation expectations as described in
[multi-case acceptance](template-acceptance.md), then add an optional `expect.paper`:

```json
{
  "title": "Candidate paper",
  "tables": [{
    "id": "main", "present": true, "style": "tiered",
    "columns": [{"id": "label", "header": "Item"}, {"id": "value", "header": "Amount"}]
  }],
  "cells": [{
    "table": "main", "column": "value",
    "address": {"node": "answer", "coord": []},
    "present": true, "text": "30,000", "editable": false,
    "style": {"weight": "bold", "tone": "accent", "fill": "subtle"}
  }, {
    "table": "main", "column": "value",
    "address": {"node": "zero-row", "coord": []}, "present": false
  }]
}
```

Title, tables and cells are optional, with at least one field required. A table assertion can check
presence, title and style. A `columns` list checks the complete ordered list of column IDs and
headers, including added or removed columns. `present: false` cannot also assert content.

## Select cells precisely

Every cell selector names a table ID and column ID. It then uses a structured `address`, a `row`,
or both as an intersection. A scalar address uses `coord: []`; a dimensioned address retains the
full ordered coordinate, for example `{"node":"element","coord":["A","Y1"]}`. Coordinates
are compared directly to the engine's cell address, without joining or splitting member names.
This also preserves members containing slashes or empty strings when present in the real Paper.

For an addressless label, select its row:

```json
{
  "table": "main", "column": "label",
  "row": {"node": "answer", "kind": "value"},
  "present": true, "text": "Answer"
}
```

`row` requires `node`, which may explicitly be null. `kind` and `optionKey` are optional filters;
an omitted option key differs from explicitly requiring null. An address filters the selected
cell itself, so an address on the value column does not select an addressless label column.

More than one matching table, column or cell is a technical error. Missing parent tables or
columns are also technical errors, including for an absent-cell expectation. Only a missing
target row/cell can satisfy that expectation. This prevents a misspelled column from passing a
hidden-row test. Matrix and expanded-member rows may require addresses because their node alone
does not identify a unique row. Row anchors and row numbers are not stable template owner IDs.

A present cell must check at least text, editable or style. Style checks only the specified final
fields: weight `normal/bold`, tone `default/muted/accent`, fill `none/subtle/accent`. The result
comes from the effective Paper cell, including class rules and overrides. It is not inferred from
a class name or its contribution to the layout.

## Results and compatibility

Content mismatches return exit code 3. Unsupported, missing or ambiguous parent selectors return
2. Reports retain actual Paper evidence even after assertion errors or a final source conflict;
check each scenario's status before using that evidence. Expected assertions are never generated
from the captured Paper. Report paths identify logical targets and retain canonical cell selectors;
they are not always directly dereferenceable JSON Pointers into the raw Paper.

Version 1 remains supported. Default capture and `--format 1` still emit
`mantra.template-acceptance/1`; its report has no `paper` field. Only explicit `--format 2`
captures the new suite. Verify reads the suite's version automatically and does not accept a
`--format` override. New suites and reports use their own `/2` versions and JSON schemas.

Version 2 allows at most 256 value/table/cell/title targets per scenario and 2048 per suite, with
at most 64 columns in an ordered-column check. The existing JSON, text, source and engine budgets
still apply. Inputs and Run value assertions remain scalar; dimensioned cells can be checked in
the Paper. PDF/XLSX rendering, Template Engine recalculation, visual editing, Save and publication
remain separate capabilities.
