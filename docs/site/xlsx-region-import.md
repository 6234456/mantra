# Import a table from an ordinary Excel worksheet

An XLSX source can import a rectangular table even when the workbook has no named cells. Declare
the target table's types in the schema, then select the worksheet, rectangle and column mapping in
the case. The first row of the rectangle is its header; the remaining rows are input records.

In the workbench's Data sources page, choose and inspect the XLSX file, then select `Worksheet range`
instead of the default `Named inputs`. Select the worksheet and target table input, check the range,
map its headers to table columns and apply. The same selection can be saved as a mapping template.

```clojure
(schema example/worksheet
  (input facts :table
    {:columns {:id :keyword
               :amount :decimal
               :enabled :boolean
               :effective-date :date
               :note :text?}})
  (section summary "Summary"
    (line amount-total "Amount total"
      (sum (map (fn [row] row.amount) facts)))))
```

For a worksheet named `Facts` containing headers `Item`, `Amount`, `Enabled`, `Effective date` and
`Note` in cells A1:E1, bind the region as follows:

```clojure
(case sample {:schema "example/worksheet"}
  (sources
    (xlsx {:path "imports/facts.xlsx"
           :input "facts"
           :sheet "Facts"
           :range "A1:E20"
           :columns {"Item" "id"
                     "Amount" "amount"
                     "Enabled" "enabled"
                     "Effective date" "effective-date"
                     "Note" "note"}})))
```

Paths resolve relative to the case and must remain inside its workspace. `:range` includes its
header row and may use absolute references such as `$A$1:$E$20`. A source can target one table
input. Explicit mappings may omit unwanted source columns. If `:columns` is omitted, source
headers are normalized as CSV headers: lowercase, with spaces and underscores changed to hyphens.
For example, `Effective Date` maps to `effective-date`.

Missing or blank mapped cells stay absent in the imported record. The schema's existing table
validation determines their meaning; the adapter does not fill them with zero or `false`. Genuine
numeric zero and boolean `false` remain facts. Rows whose mapped cells are all blank are skipped,
and all other rows keep their worksheet order. Optional columns use nullable types such as `:text?`.

Use actual Excel numeric and boolean cells. Text is not guessed into a number or boolean. Date
columns accept whole Excel date serials using the workbook's date system, or ISO date text. Excel
numbers already have binary64 precision; import cannot recover decimal digits that Excel discarded.

Formula cells import the result stored in the XLSX file. Recalculate and save the workbook in its
source application before importing it. The adapter does not evaluate formulas or retrieve external
workbooks, and a missing stored result or an Excel error rejects the import. A stored result may be
stale; it is an input snapshot, not a certification of the source formula.

The rectangle must contain at most 50,000 cells. Duplicate or empty headers, merged cells inside the
rectangle, missing worksheets, unknown mapping targets and repeated target columns are errors.
The existing XLSX container and run limits also apply. No partial table is used after an import error.

To import through the existing workbench API, use `format: "xlsx"` with these source options:

```json
{
  "input": "facts",
  "sheet": "Facts",
  "range": "A1:E20",
  "columns": {"Item": "id", "Amount": "amount", "Enabled": "enabled",
              "Effective date": "effective-date", "Note": "note"}
}
```

Pass this object as `options` to the existing `imports/apply` request along with its file contents
and current `baseRevision`. It also works in an XLSX mapping template. Inspection includes bounded
worksheet/header candidates in `xlsxSheets`; the existing `columns` field continues to describe
named inputs. Selecting another starting header row requires mapping that actual row's titles.

Existing `(xlsx {:path "inputs.xlsx"})` declarations continue to import named input cells. Neither
mode imports workbook styling into the Mantra layout. Sources still apply in order, and explicit
case inputs take precedence over imported facts.

See the [technical region contract](../workbench/visual-editor/xlsx-region-contract.md) and the
[workbench import contract](../workbench/contract.md#81-导入) for diagnostics and HTTP behavior.
