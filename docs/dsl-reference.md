# Mantra DSL reference (v0.2)

Mantra documents use the Normein reader syntax: Clojure-style lists, vectors, maps, keywords, strings,
numbers and `;` comments. Formulas are Normein expressions; see the pinned kernel's
[expression-language reference](https://github.com/6234456/normein/blob/0a3ae1de844c92635fbbc03406a13cb0e8920c03/docs/architecture/dsl-clojure-compatibility.md).
This document describes Mantra's host forms and calculation library.

For a short overview of calculation paradigms and copyable examples, see the
[syntax pattern guide](syntax-patterns.md) and [runnable templates](templates/README.md).

The root form determines the document type:

| Root form | Purpose | Typical filename |
| --- | --- | --- |
| `(schema <id> {meta} …)` | A domain calculation schema | `schema.mantra` |
| `(fragment …)` | Schema declarations/items inserted by `include` | `*.mantra` |
| `(case <id> {meta} …)` | Case inputs, overrides and allowed extensions | `case-*.mantra` |
| `(parameters <id> {meta} (values {…}))` | A reusable parameter set | `params-*.mantra` |
| `(layout <id> {opts} …)` | Working-paper layout | `layout*.mantra` |

Domain applications under `apps/` use these public forms and APIs. They are demonstrations, not
production tax or accounting software, and are not published as library artifacts.

## 1. Schemas

```clojure
(schema de.est/2025
  {:title "Einkommensteuer 2025" :subtitle "…" :version "2025.1"
   :period "VZ 2025" :preset :de-staffel-4}
  <declaration>*)
```

### 1.1 Declarations

| Form | Meaning |
| --- | --- |
| `(include "fragment.mantra")` | Insert a fragment's declarations and items here; schema top level only |
| `(param id <literal> {opts})` | Schema parameter; parameter sets and case `params` can override it. Numbers, keywords, strings, booleans, vectors and maps are accepted |
| `(input id :type {opts})` | Input not displayed as a paper row. Types: `:decimal` (default), `:integer`, `:boolean`, `:keyword`, `:text`, `:date`, `:table` |
| `(dimension id {opts})` | Static `:members [{:key :A :label "…" :when <expression>} …]`, or table-backed `:from <input> :key :column :title :column`; `:total-label` names the total column |
| `(defn name [^Type arg …] body)` | Named Normein helper function. Arithmetic arguments need annotations such as `^Decimal`, `^Integer` or `^Boolean` |

Input options include `:label`, `:per dim|[dims]`, `:default`, `:optional true`, `:required true`,
`:required-when <boolean-expression>`, `:min-rows n` (tables only), `:min`, `:max`,
`:options {:kw "Label"}|[:kw …]`, `:help`, `:unit` and `:columns {:column :type …}`.
A table column type ending in `?` is nullable, for example `:decimal?`.

Column options can also be maps: `:columns {:mode :keyword :order-id {:type :keyword?
:required-when (= row.mode :direct)}}`. The condition sees the converted record as `row`.
Conditional requirements are evaluated after their calculation dependencies, separately from input
binding. An explicit zero or false is a supplied fact; nil, blank text, defaults and implicit values
do not satisfy a requirement. A nil condition does not trigger a requirement. `:min-rows` counts
records, including records with blank optional cells. Required, range and row-count failures are
business findings and permit calculation and saving. Type and foreign-key errors remain technical
failures. Table findings carry a zero-based `rowIndex`, column name and source-cell location.

Table-backed dimensions can declare `:parent <dimension> :parent-key :column`. The engine checks
that each child references an existing active parent member. A table input can declare foreign keys
with `:references {:column <dimension>}`; every non-null value must identify an active member.
For example, a cost table may use `:references {:order-id order}`, while the case provides
`:order-id :O100`. Member keys must be unique.

### 1.2 Calculation items

| Form | Meaning |
| --- | --- |
| `(section id "Label" {opts} item*)` | Group items. A section containing a `total` is opaque: its result is its last total |
| `(field id "Label" {opts})` | Display an input as a row and declare it; inherits the section's dimensions by default |
| `(line id "Label" <formula> {opts})` | Calculated row |
| `(subtract id "Label" <formula> {opts})` | `line` with `:op :minus`; retains the row value and reverses its contribution |
| `(info id "Label" <formula> {opts})` | `line` with `:op :info`; retains the value and contributes zero |
| `(formula-slot id "Label" <default-formula> {opts})` | Application-controlled formula extension point. The schema fixes type, dimensions, rounding and position; `:uses [root …]` limits accessible roots. A case replaces the formula with `bind` |
| `(total id "Label" {opts})` | Running-total checkpoint: previous total plus signed contributions since it |
| `(choice id "Label" {:rule :min\|:max} (option :key "Label" <formula> {:when …})+)` | Evaluate available options and select the minimum or maximum |
| `(choose-min id "Label" {opts} (option …)+)`, `(choose-max id "Label" {opts} (option …)+)` | `choice` with a fixed `:rule :min` or `:rule :max` |
| `(check id "Label" <boolean-formula> {opts})` | Report a failed business condition; nil also fails |
| `(reconcile id "Label" <left-formula> <right-formula> {:tolerance 0.01})` | Retain both numeric sides and their difference; pass when `abs(left-right) <= tolerance` |
| `(slot id "Label" {opts})` | Application-controlled insertion point, populated by a case's `extend` |
| `(note "Text")` | Explanatory paper row |

`subtract`, `info`, `choose-min` and `choose-max` keep the ordinary row or choice model, including
the authored ID, formula, source location, applicability, dimensions, rounding and option trace.
Their option maps are optional. `subtract` fixes `:op :minus` and `info` fixes `:op :info`;
`choose-min` and `choose-max` fix only `:rule` and accept ordinary contribution options such as
`:op :info` or `:op :minus`. Repeating a fixed option must agree with the alias; a conflicting
setting is rejected at the authored value. Use these forms wherever their canonical
`line` or `choice` form is allowed, including fragments and licensed case extensions.

Checks and reconciliations contribute zero to totals and cannot be referenced as ordinary
calculation roots. They support inherited dimensions/section conditions and their own `:when`;
inactive decisions have nil value, no business finding and blank status. Tolerance is a nonnegative
literal and its boundary is inclusive. A failed check retains its boolean result; reconciliation
retains both sides, difference and tolerance in the public validation view.

Ratio aggregation does not sum member rates. It sums numerator and denominator values at exactly
the rate's active coordinates, including dimension/section/own conditions. Components must be
numeric nodes with the same dimensions. Explicit aggregate rounding is separate from the member
formula's rounding; omitting it requires finite exact division. A zero denominator gives nil and
`MANTRA-AGGREGATE-ZERO-DENOMINATOR` as a business warning. No active members gives nil without that
warning. A nil rate contribution also makes enclosing totals nil.

Each weighted aggregate retains engine-owned evidence: aligned coordinates and active mask, exact
numerator/denominator totals, explicit rounding, result and undefined reason. Member detail is
limited to 64 entries; truncation is visible while totals cover every active member. Explain the
aggregate through `aggregate.<node>`; `all.<node>` remains the member-value map. Aggregate evidence
is separate from kernel expression steps and does not approximate the unrounded rational value.

Common options:

| Option | Applies to | Meaning |
| --- | --- | --- |
| `:op :plus\|:minus\|:info` | line, field, choice, section | Contribution to the surrounding Staffel; default `:plus` |
| `:per dim`, `:per [d1 d2]`, `:per []` | Items | Dimensions; an explicit declaration replaces inherited dimensions |
| `:when <expression>` | line, total, choice, section, option, check, reconcile | Applicability. False marks the item inactive; decisions have nil value and blank status |
| `:round n`, `:round [n :floor]` | line, choice | Result rounding. Modes: `:half-up`, `:half-even`, `:half-down`, `:floor`, `:ceiling`, `:down`, `:up` |
| `:type` | line | Result type; default `:decimal` |
| `:spread true` | line | Evaluate once without the last dimension; distribute the returned member map |
| `:aggregate true\|false`, `:aggregate :sum\|:none` | line | Permit or suppress cross-member sums |
| `:aggregate {:ratio [numerator denominator] :round [8 :half-up]}` | numeric line | Cross-member rate from the sums of its aligned components, filtered by the rate's active coordinates |
| `:severity :error\|:warning` | check, reconcile | Business finding severity; default `:error` |
| `:display :inline\|:schedule\|:hidden` | section | Default presentation: inline, separate schedule or hidden |
| `:layout :tiered\|:matrix` | section | Default table style |
| `:reference`, `:note`, `:source` | Items | Source/reference metadata; core names are English |
| `:format :amount\|:percent\|:number\|:integer`, `:precision n` | Items | Number display |
| `:hidden true` | Items | Calculate without displaying |
| `:class :name`, `:class [:name :other]` | Items, sections, notes | Reusable style tags; affect presentation only |
| `:sign-labels {:positive "…" :negative "…" :zero "…"}` | Calculation items | Choose a label using the result's sign before taking absolute values; omitted branches retain the original label |
| `:group :key` | input, field | Input group; schema `:group-titles {:key "Label"}` supplies the title, otherwise the key is displayed |
| `:headline node-id` | schema metadata | Main result; defaults to the final mainline section's result |
| Other keywords | Items | Application metadata passed to presentation, such as `:kz` or `:zeile` |

Sections inherit their applicability conditions. The engine aligns conditions across dimensions;
authors do not configure evaluation order or cross-dimension condition propagation.

### 1.3 Names inside formulas

| Expression | Meaning |
| --- | --- |
| `bruttoarbeitslohn` | Another row, input or parameter. Matching dimensions give the current member's scalar; additional dimensions give a member map such as `{:A … :B …}` |
| `all.weighted-amount` | The complete member map of a dimensioned row |
| `relation_order` | Engine-generated child-to-parent map for a dimension `order` with `:parent`; usable with `dim/rollup` |
| `cgu`, `person` | Current member record, e.g. `cgu.carrying-amount`, `person.label`, `person.key`, `person.index` |
| `(my-fn …)` | A helper declared with `defn` |

### 1.4 Calculation functions (`mantra.calc@2`)

| Function | Meaning |
| --- | --- |
| `(alloc/pro-rata amount weights scale)` | Allocate using a weight map and largest remainders. Rounded parts add up to the rounded amount |
| `(alloc/capped amount weights caps scale)` | Pro-rata allocation with per-key caps and redistribution to uncapped keys; excess beyond all caps is unallocated |
| `(alloc/waterfall amount capacities)` | Allocate in capacity-map key order. Each key receives the smaller of its nonnegative capacity and the remaining amount; nil capacity absorbs the remainder |
| `(table/band x rows)`, `(table/band x rows default)` | Given ascending `[[threshold value] …]`, return the last value whose threshold is at most x. Below the first threshold, return default or nil |
| `(table/sum-where records criteria value-key)` | Exact sum of numeric `value-key` cells in record maps matching every scalar keyword-keyed equality criterion. No matches return zero; matched missing, nil or nonnumeric values are technical failures |
| `(table/count-where records criteria)` | Integer count of record maps matching every scalar keyword-keyed equality criterion; no matches return zero |
| `(fin/pmt rate n pv scale)` | Positive end-of-period annuity payment for present value pv over n positive periods, rounded half-up to scale. At rate zero, return pv/n |
| `(calc/stepwise amount [[upper rate] … [nil rate]])` | Apply each rate only to the amount within its band; nil is the open upper limit of the final band |
| `(calc/converge f init max-iterations tolerance)` | Return the first `next=f(current)` with `abs(next-current) <= tolerance`; the callback receives Decimal, the bound is an exact integer 1..1000 and tolerance is nonnegative. Exhaustion is a technical failure with no result. |
| `(dim/sum values)` | Sum a member map; nil values count as zero |
| `(dim/rollup values relation target)` | Sum source-member values assigned to target by a declared child-to-parent relation, e.g. `(dim/rollup all.actual-order-cost relation_order product.key)` |
| `(dim/min values)` | Smallest non-nil member value, or nil if none |
| `(dim/max values)` | Largest non-nil member value, or nil if none |
| `(fin/df rate t scale)` | Discount factor 1/(1+rate)^t |
| `(fin/npv rate [cf1 cf2 …] scale)` | Present value of end-of-period cash flows |

Run `mantra catalog` for the executable function directory. Normein standard functions are also
available, including `min`, `max`, `if`, `cond`, `let`, `decimal/round`, `decimal/floor` and
`decimal/divide`. Division with `/` rejects non-terminating decimals; request explicit rounding
with `decimal/divide` or `:round`.

Table equality matching preserves scalar types, except that exact numbers compare independently
of scale (`1` equals `1.00`). An absent key does not match a nil criterion. An empty criteria map
matches all records; duplicate matching records are all counted or summed. Criteria values may
be nil, Boolean, text, keyword or exact numeric values; date and collection criteria are rejected.
The functions accept concrete ordered vectors or sequences of keyword-keyed record maps, including
typed input tables, and perform no joins, uniqueness checks, defaulting or rounding. See
[table matching examples](syntax-patterns.md#match-table-records).

Matching operates on bound records after ordinary input conversion. Typed `:table` inputs retain
their existing rules: omitted nullable columns become nil; omitted or nil ordinary numeric columns
become implicit zero. These helpers cannot reconstruct authored presence from those values. Raw
literal/parameter record maps preserve absent keys. Use input completeness checks for supplied facts.

`calc/converge` executes its pure callback through the pinned kernel; the trace records the actual
invocations and selected branches. Apply monetary rounding inside the callback when the recurrence
requires it. The seed can select a different rounded fixed point, and a cent cycle fails even when
an unrounded algebraic solution exists. There is no implicit rounding, approximation or retry.
XLSX uses bounded helper formulas and preserves eager `let` errors, including unused bindings.

## 2. Cases and parameter sets

```clojure
(case mustermann-2025
  {:schema "de.est/2025" :title "…" :subject "…" :period "VZ 2025"
   :prepared-by "…" :reviewed-by "…" :date "2026-03-15" :reference "…"}
  (inputs {:veranlagungsart :zusammen
           :bruttoarbeitslohn {:A 68500 :B 31200}
           :vermietungsobjekte [{:id :leipzig :mieten 9600}]})
  (params {:kirchensteuersatz 0.08})
  (extend weitere-sonderausgaben
    (line schulgeld "Schulgeld (30 %, max. 5.000 €)" (min 5000 (* 0.3 1800))))
  (defn …))
```

Dimensioned inputs use nested maps keyed by member. A declared `:schema` must match the loaded
schema id; application cases should always declare it. Omission is permitted for temporary calculations.

Rectangular table inputs can use a compact literal directly in the `inputs` map:

```clojure
(inputs {:rates (rows [:period :rate]
                 [:P1 0.25]
                 [:P2 0.30])})
```

This expands to `[{:period :P1 :rate 0.25} {:period :P2 :rate 0.30}]` without evaluating cells.
The nonempty header contains unique keyword columns; each row must be a vector of matching width.
Zero rows are allowed. Authored cell locations are retained for diagnostics and editing, and the
schema still checks column types. Use nil for an explicit nil cell, or record-map literals when a
key must be omitted. `rows` is case-input syntax only, not a formula function or parameter literal.

Cases may supply only declared inputs and parameters. `extend` targets declared `slot` items;
`bind` targets declared `formula-slot` items. A binding is compiled in the original row's dimension
context and must satisfy its result type, allowed `:uses` roots and dependency checks. It cannot
change the row's operator, rounding or layout. Cyclic bindings are rejected.

The impairment application exposes `weighting`; a case can use
`(bind weighting (* remaining-life remaining-life))`.
See the [custom-weight case](../apps/ifrs-impairment/case-custom-weight.mantra).

A parameter set contains typed literal overrides:

```clojure
(parameters demo/annual-parameters {:for "demo/schema" :label "Annual parameters"}
  (values {:rate 0.2 :allowance 1000}))
```

Precedence is schema defaults, then parameter sets in supplied order, then the case's `params`.
The Kotlin API accepts loaded parameter sets; the workbench resolves their ids from the case's
`:parameters ["id" …]` metadata. A case's `:layout "layout-id"` selects its layout in the workbench.
Data-source bindings use `(sources (csv {…}) (json {…}) (xlsx {…}))`; later sources override earlier
ones and case inputs override sources. Paths are relative to the case and must remain inside the
workspace. See the [workbench binding and import contract](workbench/contract.md#44-案例绑定d1-已定)
for source options and structured editing.

## 3. Layouts

```clojure
(layout de.est/steuerberechnung
  {:preset :de-staffel-4 :title "…" :subtitle "…" :locale "de-DE" :language :de
   :precision 2 :percent-precision 2 :negative :minus :zero "–" :grouping true
   :hide-zero true :show-inactive false :expand-members false :signed false
   :row-numbers :global :explain :appendix
   :header [:subject :period :schema :prepared-by :reviewed-by :date :reference]}
  (operators {:plus "+" :minus "./." :total "=" :info ""})
  (columns :tiered :operator :label (col :pre {:header "Detail"}) :main)
  (columns :matrix :label (members person) :cross-total :status)
  (style {:all true} {:weight :normal :tone :default :fill :none})
  (style {:class :variance} {:weight :bold :tone :accent})
  (style {:section component-costs :depth 1} {:weight :bold})
  (style {:height 0 :kind :value} {:tone :muted})
  (style {:nth-child :even :kind :value} {:fill :subtle})
  (style {:has-row-number true :column :row-number} {:tone :muted})
  (table <section-id> {:title "…" :style :matrix :expand-members true} (col …)*)
  (schedule <section-id> …) (inline <section-id> …) (hide <item-id> …))
```

- Without explicit `table` declarations, render the schema root and each `:display :schedule` section.
  With explicit declarations, render only those tables. Separate child schedules appear as reference
  rows in a parent table; references to their results identify the source table.
- `:hide-zero` hides all-zero rows and sections. A row that turns a nonzero input into zero remains
  visible to explain the transformation.
- `:signed true` displays subtraction contributions as negative values, useful without an operator column.
- `:negative` accepts `:minus` or `:parentheses`; `:explain` accepts `:appendix` or `:none`.
- `:row-numbers :global` continues numbering across tables; `:table` restarts at 1. Omission uses
  the preset or column configuration; an explicitly configured row-number column restarts per table.
- Built-in columns are keywords such as `:label`, `:status` and `:cross-total`. Use `col` only to
  override header, width, alignment or the column name. When name equals content, `:content` is optional.
  Dimension columns use `members` or `member`.
- Each concept has one name. Document options govern the whole paper; `columns` govern order and
  necessary overrides; `style` governs appearance. Do not repeat defaults already supplied by a preset.

### 3.1 Column contents

| Content | Meaning |
| --- | --- |
| `:label` | Label with hierarchy indentation |
| `:operator` | Contribution marker, e.g. +, ./. or = |
| `:row-number` | Row number; usually inserted by `:row-numbers`. In HTML it links to the audit appendix |
| `:reference`, `:note`, `:source` | Core item metadata |
| `(attribute :name)` | Application metadata column, e.g. `(attribute :kz {:header "Kz."})` |
| `:status` | Σ aggregated, ✓ selected, ▲ user-defined, – inactive |
| `:value` | Row value; cross-member total for a dimensioned row |
| `:pre`, `:main` | Detail and main amount columns (Vorspalte / Hauptspalte) |
| `:cross-total` | Cross-member total in a matrix |
| `(members dim)`, `(member dim :key)` | One column per member or one specified member |
| `:formula`, `:explain` | Formula source or an expression with root values substituted |

### 3.2 Presets

| Preset | Purpose |
| --- | --- |
| `:de-staffel-4` | German four-column paper: Zeile, Bezeichnung, Vorspalte, Hauptspalte, with Rechtsgrundlage; member matrix with total |
| `:de-staffel-3` | Three columns: Bezeichnung, Vorspalte, Hauptspalte |
| `:ifrs-schedule` | Member schedule plus total; parenthesized negatives, zero decimal places, signed subtraction |

### 3.3 Style rules and selectors

Rules use `(style {selector} {declarations})`; both arguments must be maps.
`{:all true}` is a default rule and must appear alone. `:class` matches schema style tags; unmatched
tags remain available without changing appearance.

Selectors can combine `:section`, `:depth`, `:height`, `:indent`, `:nth-child :odd|:even`,
`:kind`, `:has-row-number true|false`, `:column` and `:class`. Section matches any section in
the row's ancestor path. Column matches a column id or content role such as `:member`.

Depth starts at 0 for the table root; height is the distance to the farthest visible leaf and is 0
for leaves, following [D3 hierarchy](https://d3js.org/d3-hierarchy/hierarchy). The table root is a
container rather than a rendered row. Hidden items do not count towards height; a separate schedule
is a reference leaf in its parent. Indentation changes appearance, not hierarchy.

For a table rooted at `costs`, direct `material` and `overhead` children have depth 1; `power` and
`maintenance` inside overhead have depth 2; `repair` inside maintenance has depth 3.
Material, power and repair have height 0; maintenance has height 1; overhead has height 2; costs
has height 3. `{:section overhead}` matches overhead and its subtree.

`:nth-child` counts visible body rows from 1 within each table, including headings and notes.
Adding `:kind :value` restricts matching without changing this count. `:has-row-number` tests the
final table configuration; `:column :row-number` matches only row-number cells.

Styles apply over built-in output styles in declaration order; later matches override only the
properties they specify. Supported properties are `:weight :normal|:bold`,
`:tone :default|:muted|:accent` and `:fill :none|:subtle|:accent`. HTML, XLSX, PDF and Workbench
share these meanings; Text ignores visual styling. Styles cannot alter values, rounding,
aggregation, applicability, validation or dependencies.

### 3.4 Reusable style classes

Choose style classes independently of the table/number preset:

```clojure
(layout example/paper
  {:preset :ifrs-schedule :style-preset [:utilities :working-paper]}
  (style-class :key-result {:weight :bold :tone :accent :fill :subtle})
  (style {:class :key-result :column :value}
    {:use [:strong :accent] :fill :accent})
  (table calculation :label :value))
```

Attach the named class to a calculation item using the existing presentation option:

```clojure
(total net "Net amount" {:class :key-result})
(info rate "Declared rate" rate-input {:class [:assumption :muted]})
```

| Style preset | Class | Declaration |
| --- | --- | --- |
| `:utilities` | `:normal`, `:strong` | Normal or bold weight |
| `:utilities` | `:muted`, `:accent` | Muted or accent tone |
| `:utilities` | `:subtle`, `:highlight` | Subtle or accent fill |
| `:working-paper` | `:source` | Muted tone, no fill |
| `:working-paper` | `:assumption` | Muted tone, subtle fill |
| `:working-paper` | `:detail` | Normal weight, default tone |
| `:working-paper` | `:subtotal` | Bold weight, subtle fill |
| `:working-paper` | `:result` | Bold weight, accent tone and fill |
| `:working-paper` | `:note` | Muted tone |
| `:working-paper` | `:variance` | Bold weight, accent tone, subtle fill |
| `:working-paper` | `:control` | Bold weight, accent tone |

`:style-preset` accepts one keyword or an ordered keyword vector. It is opt-in; omitting it
preserves existing layout defaults. Empty vectors are valid. Unknown or repeated presets fail.
Preset rules come before authored rules, in the selected preset order.

`style-class` accepts a simple lowercase keyword (`[a-z][a-z0-9-]*`) and one declaration map. A local name
can occur once and may override a preset class. Its rule appears at the definition's authored
position and contains only the authored properties. Section tags belong to the section itself;
use `{:section section-id}` when styling its descendants. Unknown item tags remain valid.

A style rule's `:use` accepts one class keyword or a vector of up to 64 class keywords. Referenced
declarations merge left to right; explicit properties apply last. Local definitions can be
referenced before their declaration. Reusing a locally overridden preset class merges its preset
properties with its local declaration. There can be at most 256 local definitions; explicit
unknown references and duplicate local definitions fail at their authored locations. Definitions
contain only weight, tone and fill, so they cannot recursively inherit other classes.

An item's class-vector order does not establish precedence: matching rules still follow layout
declaration order, property by property. These are controlled presentation classes; they do not
execute CSS, add selector specificity or imply that a `control` passed validation.
See the [shared pattern guide](reusable-patterns.md) for runnable examples.

## 4. Diagnostic codes (selection)

| Code | Meaning |
| --- | --- |
| `MANTRA-READ-*` | Reader, literal or option error |
| `MANTRA-SCHEMA-*`, `MANTRA-LINE-*`, `MANTRA-CHOICE-*` | Invalid schema forms |
| `MANTRA-ID-RESERVED`, `MANTRA-ID-DUPLICATE` | Identifier conflicts with a Normein function or another declaration |
| `MANTRA-FORMULA` | Normein compile diagnostic, anchored to the document location |
| `MANTRA-CYCLE` | Cyclic dependency, with the complete path |
| `MANTRA-TOTAL-DIMS` | A total component has insufficient dimensions |
| `MANTRA-CASE-*`, `MANTRA-INPUT-*` | Unknown case inputs, invalid types, missing or invalid values |
| `MANTRA-EVALUATION` | Runtime failure with member coordinate; other calculations continue |
| `MANTRA-VALUE-LIMIT` | Kernel value-construction limit exceeded |
| `MANTRA-LAYOUT-*` | Invalid layout |

Business diagnostics have category `business` and never make `succeeded` false. The separate
`validationPassed` flag is false for error-level business findings. The other categories are
`parsing`, `structural` and `evaluation`; callers choose whether a finding should block their own
workflow. See the complete [diagnostic directory](diagnostics.md).

Continuous periods and previous-period references are introduced by M2 below; linked cases remain planned for M3;
they are not v0.2 DSL forms.
See the [roadmap](roadmap.md) for accepted decisions and completion criteria.

## 5. Audit capture and workbook editing

`Mantra.calculateForAudit(schema, case, parameterSets, options)` captures source-indexed kernel
steps and branch decisions for every requested formula within `AuditOptions` budgets. Ordinary
`Mantra.calculate` does not request expression audits; a paper produced from it explicitly reports
missing audit capture. `calculateForExplain` collects one selected formula's bounded trace.
Paper/Text/HTML/XLSX consume that same evidence. Hidden or omitted kernel values remain nullable;
rendered kernel text is retained, and budget truncation is visible with `MANTRA-AUDIT-TRUNCATED`.

The CLI and workbench use audit capture for papers and exports. A workbook's audit page stores the
original snapshot. Formula comparisons automatically show `current` or `outdated` after changes to
input cells, effective parameters or the editable `Provided` fact flags. Restore all original values
to return to `current`, or re-export to capture a new audit. Page protection prevents accidental
edits; it is not a security boundary. Values, checks, reconciliations and weighted rates remain
live Excel formulas. A fixed exported table retains its records when cells are cleared; add/remove
records in the workbench and regenerate the workbook.

`mantra check` compiles and inspects structure; it does not run business validations. `run`/export
report business findings while retaining technical success. The workbench wire version is
`mantra.workbench/2`; upgrade strict v1 clients together with the server.


## 6. Continuous periods and multidimensional papers (M2 / v0.3)

Implementation and verification are tracked in [M2 work packages](milestones/m2-work-packages.md).

```clojure
(dimension year {:periods {:start "2026-01-01" :unit :year :count 5}})
(dimension fiscal-year
  {:periods [{:key :FY2026 :start "2026-07-01" :end "2027-07-01"}
             {:key :FY2027 :start "2027-07-01" :end "2028-07-01"}]})
(section balance "Balance" {:per [asset year]}
  (line opening "Opening" (prev closing first-opening) {:aggregate {:first year}})
  (line change "Change" period-change)
  (total closing "Closing" {:aggregate {:last year}}))
```

Periods are ordered half-open intervals: end is exclusive, and adjacent boundaries must match.
Generated units are `:month`, `:quarter` and `:year`, with keys `P1` through `Pn`. Each boundary is
computed from the original start, avoiding iterative month-end drift. The current period record
provides key, zero-based index, start, end-exclusive and previous-key. Node applicability may vary;
period membership cannot filter away intermediate periods. Non-calendar fiscal years use the same rules.

The global `(prev node first-expression)` selects the same other coordinates in the preceding
period. Only the first period executes the explicit fallback; later nil stays nil. Local or named
functions called prev keep normal lexical semantics. Ordinary current-period and domain cycles
remain errors, with coordinate paths in diagnostics. Each closing balance is independently addressable.

Numeric inputs, fields, lines, totals and choices support `:aggregate {:first year}` or `{:last year}`.
Removing the period axis selects its declared boundary in each other-coordinate group; other axes
sum. The rule never searches for the last nonzero or active value. `CalculationView.reduce(nodeId,
fixed)` exposes the immutable value and bounded ratio/boundary/sum evidence used by consumers.

A period `:parent` relation uses full interval containment. The original three-argument dim/rollup
remains additive. Its five-argument overload supplies an explicit first/last rule and ordered source
keys; a function never guesses stock semantics from the referenced node's name.

```clojure
(table bridge {:style :matrix :row-dimension asset} :label (members year) :cross-total)
(table bridge {:style :transpose :row-dimension asset} :label (node opening) (node change) (node closing))
```

Use separate layouts for these alternative views. A fixed slice such as `:fixed {:year :P3}` can select
a particular period. Every numeric cell retains its exact node/coordinate or aggregate fixed slice;
layouts do not recalculate amounts. XLSX previous references and stock reductions retain formulas.
The generation-time audit snapshot and automatic outdated status continue to follow section 5.
