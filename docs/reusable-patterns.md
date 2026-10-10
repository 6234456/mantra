# Reusable calculation and presentation patterns

Reuse a formula when its inputs and policy can be stated explicitly. Reuse a calculation pattern
when its named rows, checks and dimensions should remain visible. Reuse presentation declarations
through layout classes. These mechanisms keep numeric meaning separate from appearance.

The runnable [pattern workspace](patterns/README.md) supplies four schema/case/layout sets and one
shared formula fragment. Its numeric expectations are independently fixed in the workspace README.
The existing [standalone templates](templates/README.md) remain complete three-file examples.

## Share pure formulas with a fragment

The workspace's [common fragment](patterns/common/formulas.mantra) declares typed helpers:

```clojure
(fragment
  (defn pattern-rounded-product [^Decimal amount ^Decimal multiplier ^Integer scale]
    (decimal/round (* amount multiplier) scale))
  (defn pattern-rounded-ratio [^Decimal numerator ^Decimal denominator ^Integer scale]
    (decimal/divide numerator denominator scale)))
```

A schema imports the fragment at its top level:

```clojure
(include "../common/formulas.mantra")
```

The two helpers take an explicit scale and use the existing half-up decimal operations. They do
not infer currency units, insert default values or decide applicability. A ratio caller states
the zero-denominator policy explicitly:

```clojure
(info rate "Rounded ratio"
  (if (zero? denominator) nil
    (pattern-rounded-ratio numerator denominator member-scale))
  {:aggregate {:ratio [numerator denominator] :round [6 :half-up]}})
```

The member formula's scale and the aggregate's rounding are independent. The aggregate divides
component sums rather than adding rounded member rates. Keep other rounding modes explicit at
their call sites instead of treating these helpers as a universal financial policy.

The example schemas declare configurable scales with typed inputs such as
`(input member-scale :integer {:default 4})`. Ordinary numeric `param` roots have Decimal type;
the helper's Integer scale argument requires an integer-typed input or expression rather than an
implicit conversion. Defaults are explicit in the schema and can be supplied by each case.

An include inserts declarations in authored order; it is not a parameterized macro or an import
with a private namespace. Include the common fragment once in each root schema and keep function
and node IDs unique. Original fragment source locations remain available to diagnostics and Explain.
Nested paths must stay inside the host's authorized workspace. For these examples, use
`--workspace docs/patterns`; choosing a pattern subdirectory would exclude the shared fragment.

## Reuse named calculation patterns

| Pattern | Rows to retain | Why they remain separate |
| --- | --- | --- |
| [Actuals versus baseline](patterns/actuals-vs-baseline/schema.mantra) | Source quantity/prices, actual, baseline, signed variance, allowance, remaining | A variance's sign has no implicit business interpretation; allowance is a supplied assumption |
| [Source control](patterns/source-control/schema.mantra) | Matching count, matching sum, independent report, uniqueness check, reconciliation | Multiple rows may sum to the right value while violating the source-count requirement |
| [Capped allocation](patterns/capped-allocation/schema.mantra) | Request, weights, capacities, allocations, unallocated request, unused capacity, conservation | Unallocated request and unused capacity describe different sides of the allocation |
| [Ratio use](patterns/ratio-use/schema.mantra) | Numerator, denominator, guarded member formula, combined ratio | Undefined member ratios and aggregate-component division have explicit meanings |

Copy these patterns, adapt IDs and declared inputs, then verify independently expected values.
The shared helper fragment removes formula duplication; the visible row structure is intentionally
retained. Period stock/flow and simple allocation examples are also available in the
[standalone templates](templates/README.md).

## Select a style vocabulary

Layout style presets are opt-in and independent of the paper's existing `:preset`:

```clojure
(layout pattern/working-paper
  {:preset :ifrs-schedule
   :style-preset [:utilities :working-paper]}
  (style-class :key-result {:weight :bold :tone :accent :fill :subtle})
  (style {:class :result :column :cross-total}
    {:use [:strong :accent] :fill :subtle}))
```

`:utilities` supplies presentation declarations such as `:strong` and `:accent`.
`:working-paper` supplies semantic row roles:

| Role | Appropriate use |
| --- | --- |
| `:source` | Supplied measurements and source facts |
| `:assumption` | Explicit supplied assumptions, limits and capacities |
| `:detail` | Supporting calculated amounts |
| `:subtotal` | An intermediate sum or control amount |
| `:result` | A principal calculated result |
| `:note` | Explanatory text |
| `:variance` | A signed difference between named amounts |
| `:control` | A named validation or reconciliation row |

Assign roles to individual calculation items, for example:

```clojure
(info remaining "Allowance less actual" (- allowance actual)
  {:class [:result :key-result]})
```

`style-class` defines a local named declaration and emits a class-selector rule at that position
in the layout. Definitions may appear before or after a `:use` reference. Local names are unique,
simple lowercase keywords; they may override a class supplied by a selected preset. Definitions contain only
`weight`, `tone` and `fill`, without inheritance or recursive `:use`.

An ordinary style rule can reuse a single class or an ordered vector of selected preset/local
classes. Reused declarations merge left to right, then explicit properties override them. A local
override merges with its preset declaration for reuse. Preset rules precede authored rules;
matching authored rules override earlier properties in layout order. The order of an item's
class tags does not set precedence, and section tags do not propagate to child items.

Unknown explicit `:use` references and invalid definitions are errors at authored source locations.
An item may still carry an application-specific tag without a matching layout rule. Empty preset
or reuse vectors are allowed. There are at most 256 local definitions and 64 references per rule.

## Keep presentation and calculation distinct

Styles use the existing bounded properties: `:weight :normal|:bold`,
`:tone :default|:muted|:accent` and `:fill :none|:subtle|:accent`. They do not introduce browser CSS,
selector specificity, automatic inheritance or layout-dependent calculation behavior.

Number formats, precision, contribution signs, aggregation, applicability and validation remain
explicit DSL settings. A `:control` tag does not assert that a check passed. HTML, XLSX, PDF and
Workbench consume the same resolved cell styles; Text retains its amounts and ignores visual
styling. Existing layouts that omit `:style-preset` retain their preset/default rules. PDF now
honors previously authored styles, and Workbench honors explicit normal/default/no-fill resets.

See [language specification S7.4](language-specification-v1.md#s7-diagnostics-limits-and-outputs)
and the [DSL reference](dsl-reference.md).
