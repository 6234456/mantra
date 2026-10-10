# Copyable calculation patterns

Each directory contains a complete generic schema, fictional case and presentation-only layout.
Copy the three files together, rename the IDs, and replace the supplied facts. These are ordinary
DSL documents rather than new calculation primitives or application packages.

The following expectations were fixed by hand arithmetic before the templates were written.
`SyntaxTemplateTest` reads the actual files and checks these values through the public evaluator.

| Template | Independent arithmetic | Expected result |
| --- | --- | --- |
| `ratio` | A: `1 / 3`; B: `1 / 6`; combined: `(1 + 1) / (3 + 6)` | Member rates `0.3333`, `0.1667` at four places; combined rate `0.222222` at six places |
| `allocation` | Split `100.01` with weights `1 : 2 : 3`. Cent floors are `16.66`, `33.33`, `50.00`; the two remaining cents go to A and B, which have the largest fractional remainders | `16.67`, `33.34`, `50.00`; total `100.01` |
| `roll-forward` | A starts at `100` and moves `+20, -5, +10`; B starts at `50` and moves `-10, +5, -45` | A closes `120, 115, 125`; B closes `40, 45, 0`. First opening `150`, total flow `-25`, last closing `125` |
| `rules` | Rounded charge `1000 × 0.125 = 125.00`; eligible deductions are `30` and `45`, while the alternative `20` is disabled | Minimum deduction `30`; net `125.00 - 30 = 95.00`; maximum reference `125.00` |

Run any template from the repository root after installing the CLI. For example:

```sh
mantra-cli/build/install/mantra/bin/mantra check docs/templates/roll-forward/schema.mantra --case docs/templates/roll-forward/case-demo.mantra --workspace docs/templates/roll-forward
mantra-cli/build/install/mantra/bin/mantra run docs/templates/roll-forward/schema.mantra --case docs/templates/roll-forward/case-demo.mantra --workspace docs/templates/roll-forward --layout docs/templates/roll-forward/layout.mantra --audit
mantra-cli/build/install/mantra/bin/mantra run docs/templates/ratio/schema.mantra --case docs/templates/ratio/case-demo.mantra --workspace docs/templates/ratio --layout docs/templates/ratio/layout.mantra --format xlsx --out build/templates/ratio.xlsx
```

Explicitly select a layout when using these standalone files. A case's layout identity also works
when a workspace host has registered the matching layout.

- [Ratio](ratio/schema.mantra): distinguish member division from division of aggregate components.
  Member and aggregate rounding are separately declared. Rates use `info` because adding rates is
  usually not meaningful.
- [Allocation](allocation/schema.mantra): expose the weights, distribute a member map with
  `:spread true`, and reconcile the rounded total. Input/member order breaks equal-remainder ties.
- [Roll-forward](roll-forward/schema.mantra): make opening, movement and closing separate named
  nodes. Opening uses the first period, closing uses the last, and movement sums all periods. The
  final zero for B is a supplied result and remains the selected closing boundary.
- [Named rules](rules/schema.mantra): put a reusable formula in `defn`, keep choice options named,
  declare applicability, and use `subtract` for the total's contribution.

The examples use `rows` only for case input literals; it is not an expression constructor. Table
matching uses scalar equality criteria, and a sum requires numeric values in every matched record.
No templates insert default nil handling, implicit financial rounding or domain-specific rules.

See the [syntax pattern guide](../syntax-patterns.md) and [DSL reference](../dsl-reference.md).
