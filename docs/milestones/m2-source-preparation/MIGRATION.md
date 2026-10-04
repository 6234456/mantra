# M2 implementation handoff

The preparation scripts are arithmetic sources, not adapters to a locked DSL. Preserve the
fictional facts and derived amounts when choosing the final node names and input tables.
Numeric reference key spelling may be mapped; monetary expectations must not be edited merely
to accommodate a discrepancy in the engine, renderer or Excel translator.

The two domains require the following generic semantics to be locked first:

- Ordered contiguous period coordinates with explicit start/end boundaries and first-period seed.
  These fixtures use `[start,end)` intervals. Fixed-assets service dates start on month boundaries;
  lease preparation deliberately supports full annual periods only, including noncalendar years.
- `prev` binds all other dimensions to the current coordinate and references the immediate prior
  period. Disposal/extinguishment produces zero closing values at later coordinates, rather than
  deleting a period or allowing `prev` to jump over inactive periods.
- The asset or lease dimension sums; the period dimension selects the last closing stock or first
  opening stock, and sums movements. Partial aggregates must preserve any dimensions not collapsed.
- First-period seeds are supplied facts or independently calculated initial measurements. They
  must not be double-counted as recurring additions or recurring commencement payments.
- Node values, paper, Explain, workbench and XLSX must preserve the same period-specific balances.
  Every period must remain addressable. A nonzero liability residue must remain visible; rounding
  is explicit and business findings must not replace amounts with zero.
- Business findings for each fixture/precision pair are recorded in its summary. Both variants
  currently have the same findings for a given case. No expected implementation diagnostics for
  ill-typed facts or malformed period declarations are invented here.

Convert the default sample CSV files into generic import templates only after the input shapes
are agreed. JSON facts are templates, not runnable Mantra cases. Use the public APIs for tests;
render HTML/Text and compare all visible XLSX period/member values and stock/flow aggregate cells.
Capture both successful and intentionally failed reconciliation cases through the same generic
workbench, without adding domain adapters.

This preparation does not request a new financial kernel primitive. Existing discounting,
rounding, arithmetic and business checks can combine with the planned continuous-period,
previous-period, dimension-specific aggregation and two-dimensional presentation capabilities.
