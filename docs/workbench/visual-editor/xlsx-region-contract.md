# XLSX rectangle imports

This adds an explicit region mode to the existing `xlsx` case source. Named-input imports remain
unchanged when none of the region options is supplied. A region imports facts into one declared
table input; it does not copy workbook layout, define business calculations or infer a schema.

```clojure
(sources
  (xlsx {:path "imports/facts.xlsx"
         :input "facts"
         :sheet "Facts"
         :range "A1:E20"
         :columns {"Item" "id"
                   "Amount" "amount"
                   "Enabled" "enabled"
                   "Effective date" "effective-date"}}))
```

## Selection and mapping

- Supplying any of `input`, `sheet`, `range` or `columns` selects region mode. `input`, `sheet` and
  `range` are all required; the input must exist and have type `table`.
- `sheet` is an exact local sheet name. `range` is one inclusive A1 rectangle, for example
  `A1:E20` or `$A$1:$E$20`. Sheet prefixes, external references, whole rows/columns, unions and
  reversed endpoints are rejected. The rectangle may contain only its header row.
- The first row contains literal text headers. Headers are trimmed and must be nonempty and unique.
  A formula is not a header. Merged cells intersecting the selected rectangle are rejected rather
  than expanded into invented facts.
- `columns` maps an exact trimmed source header to a declared table column. Explicit mappings may
  omit source columns, but each named source must exist, every target must exist, and targets must be
  unique. With no nonempty mapping, headers are normalized as CSV headers (lowercase; spaces and
  underscores become hyphens); unknown columns are skipped with a warning.
- Rows keep their physical worksheet order. A row whose mapped cells are all absent, blank or
  empty strings is skipped. Other rows preserve zero, `false` and empty fields distinctly: blank
  mapped cells are omitted from the row map, and the normal engine table validation decides what
  missing fields mean. The adapter never fills blanks with zero, `false` or an inferred value.

## Cell values and diagnostics

- Numeric cells produce exact decimal model values from Excel's stored binary64 value, using the
  same `BigDecimal.valueOf` convention as named imports. This cannot restore precision Excel did
  not retain. Integer validation remains in the engine.
- Boolean cells remain booleans. Text remains text; text for a keyword column becomes a keyword.
  Numeric and boolean text is not locale-guessed. Date text must be an ISO date for normal engine
  conversion.
- Numeric cells targeting a date column use the workbook's 1900/1904 date system. Serial values
  must be finite, whole valid dates; the fictitious 1900-02-29 (serial 60) is rejected.
- Formula cells consume only the result stored in the workbook. The adapter never evaluates a
  formula or follows an external workbook. A formula without a stored result is rejected with a
  technical diagnostic and must be recalculated and saved by its source application. Stored results
  are snapshots and may be stale; importing a result does not certify formula correctness.
- Literal or cached Excel errors are technical failures, with the input id, zero-based imported row
  index, target column and worksheet cell in the diagnostic. Invalid regions, headers, mapping,
  sheet names and ambiguous merged cells are technical failures as well. A failing import returns
  no partial table.
- Type mismatches in otherwise readable cells follow the existing engine input diagnostics. Business
  validation findings keep their existing non-blocking behavior.

## Bounds, snapshots and existing import flow

The source consumes the captured bytes passed by the workspace/package loader, with the existing
XLSX ZIP preflight (10 MiB compressed, 1000 entries, 16 MiB per entry and 64 MiB expanded). A region
contains at most 50,000 cells. Checkpoints cover workbook/cell/merge scans; each retained input row
is charged before retention through the existing callback. No extra filesystem or network read is
performed when captured bytes are supplied.

The existing `imports/apply` request accepts these ordinary source options; existing mapping
templates retain them without a new template format. Applying still captures the uploaded file,
writes its content-addressed name, appends the source declaration and uses the existing revision
check/rollback behavior. File ordering and manual-input precedence do not change.

The workbench source-import page preserves `Named inputs` as its default and also offers
`Worksheet range`. Region mode selects a worksheet, an editable range, one table input and header-to-
column mappings. Older servers without `xlsxSheets` metadata continue to support named imports;
region selection is disabled there. A changed range is validated by the backend, and the page states
that inspection headers describe the worksheet's original first stored row.

### Additive inspection metadata

The existing named-cell `columns` and `rowCount` inspection fields remain unchanged. XLSX inspection
also returns optional `xlsxSheets`, with at most 16 worksheet entries:

```json
{"xlsxSheets":[{"name":"Facts","rows":20,"columns":5,
  "headers":[{"column":0,"title":"Item"},{"column":1,"title":"Amount"}],
  "suggestedRange":"A1:E20"}]}
```

`rows` is the last physically stored worksheet row plus one, and `columns` is the last cell in its
first physically stored row plus one; these are extents, not imported record counts. `headers`
contains at most 64 cells starting at that row's first cell, with absolute zero-based worksheet
column numbers and trimmed literal text titles of at most 256 characters. Inspection does not scan
all worksheet cells. `suggestedRange` is omitted if the candidate is empty, exceeds 50,000 cells or
does not have usable literal headers. Header metadata belongs to that original row; changing the
selected range's starting row does not make those titles describe another row. The actual adapter
validates the selected rectangle and its mapping on apply.

## Acceptance

Use a workbook with no defined names, an equivalent CSV and JSON, and one independent literal case.
Compare normalized table facts, selected computed values and stable diagnostic identities. Include
zero/false, missing optional fields, dates, physical record order, header/mapping errors, stored
formula results, absent formula caches, cached errors, merged geometry, region bounds and callback
cancellation. Preserve all existing named-XLSX round-trip checks.
