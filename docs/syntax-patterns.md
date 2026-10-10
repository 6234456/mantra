# Syntax patterns and shortcuts

Mantra combines typed facts, declarative calculation rows and ordinary Normein expressions.
Use the following patterns to choose a structure before writing a formula.

| Pattern | Forms to use | Useful shortcut or template |
| --- | --- | --- |
| Facts and parameters | `input`, `field`, `param`, case `inputs` | `rows` for rectangular case table literals |
| Reusable formulas | `defn`, `let`, `line` | [Named rules](templates/rules/schema.mantra) |
| Signed contributions and checkpoints | `section`, `line`, `total`, `:op` | `subtract` and `info` |
| Conditional and banded rules | `if`, `cond`, `:when`, `calc/stepwise` | Keep applicability separate from a zero-valued calculation |
| Select among alternatives | `choice`, named `option` forms | `choose-min` and `choose-max` |
| Dimensions and matrices | `dimension`, `:per`, `all.<node>`, `dim/rollup` | A section passes its dimensions to child rows |
| Table matching and totals | Typed `:table` inputs | `table/sum-where` and `table/count-where` |
| Ratios and allocations | Ratio aggregation, `alloc/*` | [Ratio](templates/ratio/schema.mantra), [allocation](templates/allocation/schema.mantra) |
| Period stocks and flows | Period dimensions, `prev`, first/last aggregation | [Roll-forward](templates/roll-forward/schema.mantra) |
| Validation and evidence | `check`, `reconcile`, `:reference`, `:source` | Keep named checks and tolerances explicit |
| Case extensions and presentation | `slot`/`extend`, `formula-slot`/`bind`, `layout` | Extension points stay schema-controlled; layouts only change presentation |

## Row and choice shortcuts

These pairs have the same calculation meaning:

```clojure
(line deduction "Deduction" requested {:op :minus})
(subtract deduction "Deduction" requested)

(line supplied "Supplied fact" basis {:op :info})
(info supplied "Supplied fact" basis)

(choice selected "Selected amount" {:rule :min :op :info}
  (option :first "First" first-amount)
  (option :second "Second" second-amount {:when second-available}))
(choose-min selected "Selected amount" {:op :info}
  (option :first "First" first-amount)
  (option :second "Second" second-amount {:when second-available}))
```

Use only one member of each pair for a given node ID. `choose-max` works the same way with
`:rule :max`. All aliases keep the original ID, label, formula, dimensions, applicability, rounding
and audit identity. `subtract` changes the contribution to a total; it does not negate the row's
own value. `info` contributes zero while retaining its value for references and presentation.
`choose-min` and `choose-max` still retain option labels and the selected option in the trace.

Ordinary item options remain available. `subtract` fixes only `:op :minus`, `info` fixes only
`:op :info`, and the choice aliases fix only `:rule`; their contribution can still be `:op :info`
or `:op :minus`. An explicitly repeated fixed option must agree with the alias; a conflicting
setting is a syntax error at the authored option. The aliases also work
in schema fragments and allowed case extensions. They do not create new calculation node kinds.

## Compact case table data

Inside a case's `inputs`, `rows` spells an ordered vector of record maps:

```clojure
(inputs {:rates (rows [:period :rate]
                 [:P1 0.25]
                 [:P2 0.30])})

; Equivalent literal:
(inputs {:rates [{:period :P1 :rate 0.25}
                 {:period :P2 :rate 0.30}]})
```

The header is a nonempty vector of unique keyword columns. Every subsequent argument is a vector
with exactly the same number of cells. Cells are literals; table types are still checked against
the schema declaration. `(rows [:period :rate])` is an empty table with that declared header.
The reader retains each cell's authored location for input diagnostics and structured editing.

A rectangular row supplies every header column. Use an explicit nil cell for nil; use the original
record-map spelling when a key must be omitted. Zero and false remain supplied values. `rows` is
case input syntax, not a formula function, and cannot run expressions or be used in parameter sets.

## Match table records

For exact, conjunctive equality matching, write:

```clojure
(table/sum-where movements {:entity entity.key :period period.key} :amount)
(table/count-where movements {:entity entity.key :period period.key})
```

The first call sums `:amount` from matching record maps. The second counts matching records as an
integer. Criteria keys are keywords; values may be nil, Boolean, text, keyword or exact numbers.
Dates and collection criteria are rejected. Numeric equality is exact and
ignores decimal scale, so `1` matches `1.00`. Other values retain their types: a keyword does not
match a string, and false does not match zero. An absent key does not match an explicit nil
criterion. An empty criteria map matches every record.

No matches produce zero. Every matched sum value must be present and numeric; a missing, nil or
nonnumeric value is a technical error rather than an implicit zero. Repeated matching records
are all counted or summed. Inputs must be concrete ordered vectors or sequences of keyword-keyed
record maps, including typed input tables. These functions do not assert uniqueness, join tables, choose a
default, round amounts or perform inequality matching. Use a separate named `check` for source
completeness, and ordinary `filter`/`map` expressions for other predicates.

Helpers inspect the bound records after existing input conversion. In a typed input table, omitted
nullable columns have already become nil, and omitted or nil ordinary numeric columns have become
implicit zero. Raw literal/parameter maps keep their absent keys. Matching does not recover the
original supplied-fact presence; input completeness checks continue to serve that purpose.

Ordinary table queries retain live XLSX formulas, including supported dynamic tables. The export
report identifies unsupported conditional collection shapes, computed value-column selectors and
comparisons that cannot distinguish live nil from empty text. Static export may retain the captured
value with a reported fallback; dynamic export rejects an unsupported formula. There is no silent
substitution of Excel's case-insensitive or coercing `SUMIF` semantics.

## Keep financial structure visible

The [copyable templates](templates/README.md) are runnable schema/case/layout sets with independently
fixed arithmetic expectations. They use these shortcuts without hiding their calculation policy:

- Ratio member formulas and aggregate-component division have separate, explicit rounding.
- Allocation shows weights, the declared algorithm and a control reconciliation.
- Period opening and closing select the first and last declared periods; flow sums all periods.
  `prev` uses its fallback only in the first period, including when a later closing value is zero.
- Named rules retain explicit alternatives, applicability and signed contributions.

Further shortcuts do not imply a new expression grammar. Formulas still use the pinned Normein
reader and prefix expressions. Percent presentation remains `:format :percent` for a ratio such
as `0.25`; it does not introduce a `25%` literal. See the normative
[language specification](language-specification-v1.md) and complete [DSL reference](dsl-reference.md).

## Reuse formulas and presentation roles

The [reusable pattern guide](reusable-patterns.md) and [shared workspace](patterns/README.md) extend
the standalone templates with a common typed formula fragment, actual/baseline comparisons,
source-count controls and capped allocation. Include paths remain inside one explicit workspace;
the common fragment does not add a macro or a new calculation primitive.

Layouts can opt into `:style-preset [:utilities :working-paper]`, define local declarations with
`style-class`, and reuse declarations through a style rule's `:use`. Classes such as `:source`,
`:variance`, `:result` and `:control` choose presentation only. They do not inherit automatically
from sections or change rounding, calculation, applicability or validation.
