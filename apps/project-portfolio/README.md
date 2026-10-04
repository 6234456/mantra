# Supplied project portfolio showcase

This fictional demonstration uses existing public Mantra primitives: input tables,
project-to-department relations and rollups, selected Boolean facts, explicit
currency rounding, weighted benefit/cost ratios, conditional required fields,
budget/hour checks and reconciliation. It requests no new calculation primitive.
Directory and JAR package acceptance, HTML/Text rendering and XLSX numeric
comparisons have passed against the independent Decimal/Fraction source.

Project selection is an input fact. The application totals the chosen proposals,
groups them by department, and reports whether costs and hours fit the supplied
limits. It neither finds an optimal set nor supplies a financial recommendation.
The [GAO cost guide](https://www.gao.gov/products/gao-20-195g) supplies conceptual
context for recording cost assumptions and alternatives. No GAO example numbers,
requirements text or certification claim are reproduced. All proposals, costs,
expected benefits, hours, limits and selection notes are invented.

Cost and benefit amounts are individually rounded half-up to cents per proposal
before selection and totals. Hours remain exact supplied decimals. The weighted
benefit/cost measure is the ratio of summed selected benefits and costs. A free
portfolio has an undefined ratio, preserved as nil. Unselected projects contribute
explicit numeric zero to selected totals and false remains a Boolean fact.
Selected proposals require a supplied nonblank selection note; this is a showcase
data-completeness rule and does not represent an approval workflow in the engine.

The main selection A+C totals cost EUR 120, expected benefit EUR 208 and 130 hours,
with weighted ratio 1.733333 and net benefit EUR 88. Cases cover no selection,
budget/hour overrun (business failures), half-cent rounding, a missing selected
proposal note (row/column diagnostic), and zero cost (undefined ratios). Independent
references cover all numeric project coordinates and department/global reductions,
remaining budget/hours, reconciliation and undefined addresses.

```sh
python3 apps/project-portfolio/independent/reference.py --self-test
python3 apps/project-portfolio/independent/reference.py --write-references
```

The first command checks parent/child crossfoot, exact selection totals, weighted
ratios, cent boundaries and expected diagnostics. The second regenerates fictional
facts, references and their manifest. It reads no engine/DSL/golden or workbook
output. The LF CSV data and typed import templates bind the same demo case and
exact schema version explicitly.

**Showcase only.** This is not investment, accounting, staffing or project-management
advice. It assumes supplied deterministic amounts, additive independent projects
and one period. It omits uncertainty, discounting, financing, tax, dependencies,
resource scheduling and real approvals. Those simplifications belong in scheme
comments. The application uses normal public APIs and generic Workbench behavior;
it is not published as a library artifact.

Identifier amendment: declaration names are domain-specific to keep shared library
boundary checks meaningful. Frozen source-record columns and factual amounts
remain unchanged; the independent reference producer renames output addresses only.
