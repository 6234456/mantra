# Two domains, the same public primitives

Start with [your first schema](site:tutorial.html) and [public Kotlin embedding](site:embedding.html).
These two applications then exercise the existing calculation language in new domains.
Their reference amounts come from separately authored Decimal/Fraction scripts. Check
the current acceptance report for actual engine and export status; a site page does not
declare M4, M5, M6 or v1.0 complete.

## Monthly energy

The [energy-budget showcase](site:apps/energy-budget.html) models fictional sites over
three generated months. Opening storage plus generated energy serves demand; imports
cover a deficit, exports remove surplus above capacity, and the remaining energy closes
storage. `prev` carries closing into the next opening. First opening and last closing are
stocks; generation, demand, imports and exports are summed flows.

The independent main case starts with 12 kWh, imports 128 kWh, exports 40 kWh and closes
at zero. Individually rounded monthly import costs and export credits yield EUR 31.84.
Weighted local coverage is `252 / 380`, rounded to `0.663158`; it is a ratio of quantities,
not an average of site/month percentages. Zero demand leaves coverage undefined.

Read the [English scope and assumptions](repo:apps/energy-budget/README.md),
[schema](repo:apps/energy-budget/schema.mantra),
[matrix layout](repo:apps/energy-budget/layout.mantra),
[transpose layout](repo:apps/energy-budget/layout-transpose.mantra),
[fictional facts](repo:apps/energy-budget/independent/fixtures/demo.json),
[independent arithmetic](repo:apps/energy-budget/independent/reference.py) and
[source manifest](repo:apps/energy-budget/independent/manifest.json).
The reported-closing mismatch is an intentional BUSINESS error: calculation values
remain available while `validationPassed` is false. Fractional readings, zero storage,
a single site and zero demand have separate cases.

```sh
python3 apps/energy-budget/independent/reference.py --self-test
./gradlew :apps:energy-budget:test
python3 apps/energy-budget/verify_expected.py --fixture demo --verify-values apps/energy-budget/build/out/case-demo-values.json
```

The Python self-test uses original fictional facts and reads no engine output. The last
command compares a real generated serialization against that independent source;
run the coordinated application acceptance first. Declaration names such as
`energy-generation`, `energy-export` and `budget-month` keep shared library boundary
checks meaningful. Factual input column names remain as authored.

**Showcase only.** Monthly net dispatch assumes perfect efficiency and omits chronology,
power limits, battery losses, outages, networks, degradation and real tariffs. These
facts cannot support engineering, accounting or investment decisions.

## Supplied project selection

The [project-portfolio showcase](site:apps/project-portfolio.html) accepts a supplied
Boolean selection of fictional proposals. It rounds each proposal's cost and benefit
to cents, sums selected amounts, rolls them up to departments and checks supplied
cost/hour limits. It does not optimize which proposals to choose.

The independent main selection A+C costs EUR 120, has fictional expected benefit
EUR 208 and requires 130 hours. Net benefit is EUR 88; the weighted benefit/cost ratio
is `208 / 120`, rounded to `1.733333`. Unselected proposals contribute explicit zero;
their supplied false remains Boolean false. A zero-cost portfolio has an undefined
ratio. Budget/hour overruns retain negative remaining capacity and BUSINESS findings.

Read the [English scope and assumptions](repo:apps/project-portfolio/README.md),
[schema](repo:apps/project-portfolio/schema.mantra),
[layout](repo:apps/project-portfolio/layout.mantra),
[fictional facts](repo:apps/project-portfolio/independent/fixtures/demo.json),
[independent arithmetic](repo:apps/project-portfolio/independent/reference.py) and
[source manifest](repo:apps/project-portfolio/independent/manifest.json).
The optional approval-note column becomes required when the supplied selection is true.
The missing-note case checks an actual zero-based row index and column name.

```sh
python3 apps/project-portfolio/independent/reference.py --self-test
./gradlew :apps:project-portfolio:test
python3 apps/project-portfolio/verify_expected.py --fixture demo --verify-values apps/project-portfolio/build/out/case-demo-values.json
```

The `proposal` dimension and generated `relation_proposal` use existing parent/child
rollup primitives. Ratio aggregation, conditional-required fields, checks and
reconciliation are shared language features, with no application handler in the engine.

**Showcase only.** This one-period additive model omits uncertainty, discounting,
financing, dependencies, resource scheduling and real approvals. Supplied fictional
benefits are assumptions, not investment or staffing recommendations.

## Open, import and inspect

From the repository root, `mantra-cli/build/install/mantra/bin/mantra serve apps` opens
the generic reference workbench. Select each domain's descriptive case title. Each app's
`import-templates/*.json` declares its actual table input, exact schema version, demo
case and CSV path. CSV columns are typed; decimal and grouping conventions are explicit.

Use [independent-source guidance](site:independent-sources.html) to distinguish fictional
facts, independently derived amounts and current runtime evidence. The
[package boundary](site:packages.html) explains captured resources, exact version pins,
explicit host capabilities and output limitations.
