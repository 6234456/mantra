# Mantra DSL reference (v0.1)

Mantra documents use the Normein reader syntax: Clojure-style lists, vectors, maps, keywords, strings,
numbers and `;` comments. Formulas are Normein expressions; see the pinned kernel's
[expression-language reference](https://github.com/6234456/normein/blob/0a3ae1de844c92635fbbc03406a13cb0e8920c03/docs/architecture/dsl-clojure-compatibility.md).
This document describes Mantra's host forms and calculation library.

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

Input options include `:label`, `:per dim|[dims]`, `:default`, `:optional true`, `:min`, `:max`,
`:options {:kw "Label"}|[:kw …]`, `:help`, `:unit` and `:columns {:column :type …}`.
A table column type ending in `?` is nullable, for example `:decimal?`.

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
| `(formula-slot id "Label" <default-formula> {opts})` | Application-controlled formula extension point. The schema fixes type, dimensions, rounding and position; `:uses [root …]` limits accessible roots. A case replaces the formula with `bind` |
| `(total id "Label" {opts})` | Running-total checkpoint: previous total plus signed contributions since it |
| `(choice id "Label" {:rule :min\|:max} (option :key "Label" <formula> {:when …})+)` | Evaluate available options and select the minimum or maximum |
| `(slot id "Label" {opts})` | Application-controlled insertion point, populated by a case's `extend` |
| `(note "Text")` | Explanatory paper row |

Common options:

| Option | Applies to | Meaning |
| --- | --- | --- |
| `:op :plus\|:minus\|:info` | line, field, choice, section | Contribution to the surrounding Staffel; default `:plus` |
| `:per dim`, `:per [d1 d2]`, `:per []` | Items | Dimensions; an explicit declaration replaces inherited dimensions |
| `:when <expression>` | line, total, choice, section, option | Applicability. False gives numeric zero or nonnumeric nil and marks the item inactive |
| `:round n`, `:round [n :floor]` | line, choice | Result rounding. Modes: `:half-up`, `:half-even`, `:half-down`, `:floor`, `:ceiling`, `:down`, `:up` |
| `:type` | line | Result type; default `:decimal` |
| `:spread true` | line | Evaluate once without the last dimension; distribute the returned member map |
| `:aggregate true\|false`, `:aggregate :sum\|:none` | line | Permit or suppress cross-member totals. Ratios such as unit costs use `false`; compute a cross-member ratio in a separate formula |
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

### 1.4 Calculation functions (`mantra.calc@1`)

| Function | Meaning |
| --- | --- |
| `(alloc/pro-rata amount weights scale)` | Allocate using a weight map and largest remainders. Rounded parts add up to the rounded amount |
| `(alloc/capped amount weights caps scale)` | Pro-rata allocation with per-key caps and redistribution to uncapped keys; excess beyond all caps is unallocated |
| `(alloc/waterfall amount capacities)` | Allocate in capacity-map key order. Each key receives the smaller of its nonnegative capacity and the remaining amount; nil capacity absorbs the remainder |
| `(table/band x rows)`, `(table/band x rows default)` | Given ascending `[[threshold value] …]`, return the last value whose threshold is at most x. Below the first threshold, return default or nil |
| `(fin/pmt rate n pv scale)` | Positive end-of-period annuity payment for present value pv over n positive periods, rounded half-up to scale. At rate zero, return pv/n |
| `(calc/stepwise amount [[upper rate] … [nil rate]])` | Apply each rate only to the amount within its band; nil is the open upper limit of the final band |
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

The only syntax is `(style {selector} {declarations})`; both arguments must be maps.
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
`:tone :default|:muted|:accent` and `:fill :none|:subtle|:accent`. HTML and XLSX share these
meanings; Text ignores visual styling. Styles cannot alter values, rounding, aggregation or dependencies.

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

Business checks and reconciliation primitives are planned for M1. Continuous periods, previous-period
references and linked cases are planned for later milestones; they are not v0.1 DSL forms.
See the [roadmap](roadmap.md) for accepted decisions and completion criteria.
