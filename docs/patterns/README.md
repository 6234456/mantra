# Shared calculation and presentation patterns

This directory is one workspace. Each pattern includes `../common/formulas.mantra`, so copy the
whole directory when reusing it and set the CLI workspace boundary to `docs/patterns`, not to a
single pattern subdirectory. Schemas, cases and layouts remain ordinary public DSL documents.

The following expectations were fixed by independent hand arithmetic before writing the schemas.
`ReusablePatternTest` checks the actual files against these numbers, including business failures
that preserve calculated values.

| Pattern | Independent arithmetic | Expected result |
| --- | --- | --- |
| `actuals-vs-baseline` | A: actual `10 × 12 = 120`, baseline `10 × 10 = 100`, allowance `150`; B: actual `8 × 10 = 80`, baseline `8 × 11.25 = 90`, allowance `100` | Actual total `200`, baseline `190`, variance `20 + (-10) = 10`, remaining `30 + 20 = 50` |
| `source-control` | One supplied row for A is `40`; one for B is `15`. Independent reports are `40` and `15` | Total `55`; counts `1` each; all controls pass |
| `source-control/case-duplicate` | Replace A's row with `25` and `15` | A remains `40`, total remains `55`; A's count is `2` and the uniqueness check fails without blocking calculation |
| `capped-allocation` | Request `8 × 10 = 80`; weights `1 : 2 : 3`, caps `10 : 30 : 100`. A binds at `10`; remaining `70` splits `2 : 3` | A/B/C allocations `10, 28, 42`; allocated `80`, unallocated `0`, unused capacity `140 - 80 = 60` |
| `capped-allocation/case-excess` | Request `20 × 10 = 200` exceeds total capacity `10 + 30 + 100 = 140` | Allocations `10, 30, 100`; allocated `140`, unallocated `60`, unused capacity `0`; conservation `140 + 60 = 200` |
| `ratio-use` | A: `1 / 3`; B: `1 / 6`; C: `0 / 0`. Combined ratio `(1 + 1 + 0) / (3 + 6 + 0)` | Member rates `0.3333`, `0.1667`, nil; combined rate `0.222222` |

## Run the workspace

Install the CLI once with `./gradlew :mantra-cli:installDist`, then run from the repository root:

```sh
mantra-cli/build/install/mantra/bin/mantra check docs/patterns/actuals-vs-baseline/schema.mantra --case docs/patterns/actuals-vs-baseline/case-demo.mantra --workspace docs/patterns
mantra-cli/build/install/mantra/bin/mantra run docs/patterns/source-control/schema.mantra --case docs/patterns/source-control/case-duplicate.mantra --workspace docs/patterns --layout docs/patterns/source-control/layout.mantra --audit
mantra-cli/build/install/mantra/bin/mantra run docs/patterns/capped-allocation/schema.mantra --case docs/patterns/capped-allocation/case-excess.mantra --workspace docs/patterns --layout docs/patterns/capped-allocation/layout.mantra --format html --out build/patterns/capped-allocation.html
mantra-cli/build/install/mantra/bin/mantra run docs/patterns/ratio-use/schema.mantra --case docs/patterns/ratio-use/case-demo.mantra --workspace docs/patterns --layout docs/patterns/ratio-use/layout.mantra --format xlsx --out build/patterns/ratio-use.xlsx
```

Select the layout explicitly for these standalone CLI runs. Cases declare matching layout IDs
for hosts that register the documents. A duplicate-source case reports a business failure; inspect
its calculated values independently of that status.

## Preview locally in the browser

From the repository root, build the live UI once and start the installed CLI:

```sh
npm --prefix workbench-ui ci
VITE_WORKBENCH_MODE=live npm --prefix workbench-ui run build
mantra-cli/build/install/mantra/bin/mantra serve docs/patterns --directory-policy trusted-local --port 8091 --ui workbench-ui/dist
```

Keep that terminal process running, then open [http://127.0.0.1:8091/](http://127.0.0.1:8091/).
Choose a pattern case in the workbench to view its calculation, paper, controls and export options.
The workspace contains all pattern folders and the shared `common` directory, so included formulas
remain inside the same loading boundary. Stop the server with Ctrl+C when finished.

Use the numeric loopback address shown above; the server's Host validation requires it. Run these
commands on your computer to preview there. If the process runs in a cloud environment, that URL
belongs to the cloud machine and requires an available forwarding connection for browser access.

Structured input edits save to these case files. Copy the whole workspace first when you want to
keep the supplied examples unchanged. The built UI connects to this server; it does not provide
frontend hot reload.

## Reuse the shared pieces

- [Common formulas](common/formulas.mantra) provide a rounded product and a rounded ratio. Both
  take an explicit integer scale and use the standard half-up decimal operations. Callers decide
  applicability and zero-denominator behavior; the helper inserts no zero substitution or guard.
  Caller schemas declare scale inputs as `:integer` with explicit defaults; ordinary numeric
  parameters have Decimal type and are not silently converted to the helper's Integer argument.
- [Actuals versus baseline](actuals-vs-baseline/schema.mantra) keeps source quantities and prices,
  computed amounts, signed variance, supplied allowance and remaining allowance as distinct nodes.
- [Source controls](source-control/schema.mantra) keeps matching, count, uniqueness and independent
  reconciliation separate. Summing records does not assert that exactly one source exists.
- [Capped allocation](capped-allocation/schema.mantra) distinguishes unallocated request from unused
  capacity and reconciles the request to the allocated and unallocated amounts.
- [Ratio reuse](ratio-use/schema.mantra) calls the shared ratio helper after an explicit caller guard.
  Member and combined-component rounding have separate scales.

All layouts opt into the `:utilities` and `:working-paper` style presets. Item classes select
presentation roles such as `:source`, `:assumption`, `:detail`, `:subtotal`, `:result`, `:note`,
`:variance` and `:control`. The first layout also defines a local `:key-result` class and reuses
utility declarations in a scoped rule. No class changes values, contribution signs, precision,
aggregation or validation.

See the [reusable pattern guide](../reusable-patterns.md) for include contracts and style precedence.
