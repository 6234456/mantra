# Monthly energy budget showcase

This fictional demonstration uses existing public Mantra primitives: input tables,
generated monthly periods, previous-period stock, first/last stock aggregation,
summed flows, weighted ratios, checks/reconciliation and matrix/transpose paper.
It requests no new calculation primitive. Directory and JAR package acceptance, HTML/Text rendering and XLSX numeric
comparisons have passed against the independent Decimal/Fraction source.

The independent model treats each site/month as one net-energy interval. Opening
stored energy plus generation is used for demand, missing energy is imported,
surplus above storage capacity is exported, and the remainder closes storage.
Next month starts from the previous closing. All quantities are kWh. NIST's
[SI conversion table](https://www.nist.gov/pml/special-publication-811/nist-guide-si-appendix-b-conversion-factors/nist-guide-si-appendix-b9)
supports the unit terminology (1 kWh = 3.6 MJ); it supplies no fixture numbers.
All sites, meter readings, capacities and tariffs are invented here.

Purchase cost and export credit are individually rounded half-up to cents each
month. Net cost is their difference, and may be negative. Coverage is local
supply divided by demand, reduced as a ratio of summed quantities rather than
an average of percentages. Zero demand leaves the ratio undefined; it never
becomes zero or infinity. The reported closing readings are separately supplied
fictional source facts computed by the independent producer, with one deliberate
mismatch. They are not claimed to be observations from a real meter.

The main example closes at 0 kWh, imports 128 kWh, exports 40 kWh, costs EUR 31.84
and has weighted local coverage 0.663158. Cases also cover zero capacity, a single
site, all zero demand (undefined ratios), fractional measurements/currency cents,
and unreconciled reported stock (business failure, successful calculation).
References cover every domain coordinate, global reduction, per-site scope and
per-month scope, including first opening/last closing and undefined addresses.

`energy-difference` is an ordinary informational numeric line: closing storage less
the independent reported reading. Its global and partial reducers sum those fictional
reading differences (the deliberate P2 mismatch totals -1). `energy-reconciliation`
remains the BUSINESS control with per-reading diagnostics and values; controls have
no numeric reducers. Neither line changes closing storage or the cost calculation.

```sh
python3 apps/energy-budget/independent/reference.py --self-test
python3 apps/energy-budget/independent/reference.py --write-references
```

The first command checks conservation, weighted ratios, first/last stocks and
partial scopes independently. The second regenerates fictional facts, values,
expected diagnostics and the source manifest; it reads no engine/DSL/golden or
workbook output. `data/*.csv` and `import-templates/*.json` describe the same
typed demo inputs with explicit schema version and case binding.

**Showcase only.** This is not engineering, accounting or investment advice. It
does not simulate within-month chronology, battery losses, power limits, outages,
network constraints, degradation, emissions, real tariffs, tax or market prices.
Its simplified perfect-efficiency net dispatch is an application assumption,
not a claim that a physical battery can perform these monthly flows. Simplification
comments will accompany the corresponding scheme formulas. No engine changes,
special application routing or publication as a library artifact are required.

Identifier amendment: declaration names are domain-specific to keep shared library
boundary checks meaningful. Frozen source-record columns and factual amounts
remain unchanged; the independent reference producer renames output addresses only.
