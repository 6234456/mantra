# Independent Mantra DSL 1 conformance corpus

This finite corpus contains 24 small, complete schema/case programs and two parameter documents.
It exercises clauses S1–S7 of [language specification 1](../docs/language-specification-v1.md), with
exact independently authored answers. It does not prove the entire specification. In particular,
case graph resolution, package validity/migration, asynchronous cancellation, owner-thread cleanup,
and HTML/PDF/XLSX lifecycle guarantees require their separate integration and consumer tests.

The expected files were written from the specification and hand, Fraction or Decimal arithmetic.
They were frozen before executing these vectors against Mantra. No expected answer was captured
from Mantra, its existing fixtures, an adapter response, or XLSX. See [derivations.md](derivations.md).
An implementation failure must be investigated; running an adapter never rewrites this corpus.

`manifest.json` records the specification clauses, query addresses, exact byte length and SHA-256
of every source and expected document. The corpus digest hashes the sorted `path<TAB>bytes<TAB>sha256`
records with a trailing newline. The runner validates the entire inventory before invoking any
adapter; even whitespace changes invalidate the frozen document. Reviewed language changes require
an explicit corpus change with updated derivations and inventory, not a recording mode.

## Running an external implementation

The runner uses only Python's standard library and knows no Mantra implementation:

```sh
python3 conformance/run.py --verify-only
python3 conformance/test_runner.py
python3 conformance/test_derivations.py
python3 conformance/run.py --adapter 'conformance-adapter/build/install/mantra-conformance-adapter/bin/mantra-conformance-adapter'
```

Build the separate, nonpublished `conformance-adapter` application with the repository's normal
Gradle gate first. The adapter depends only on public Mantra and WorkbenchJson APIs. It has no
access to expected documents or private planner/evaluator classes. A different implementation can
supply its own executable with the same protocol. `--vector ID` selects a frozen vector and
`--timeout SECONDS` bounds each foreground adapter invocation. The runner uses a fresh temporary
directory per vector, passes copies of source documents, invokes no shell and always removes those
copies. It starts no browser, daemon or global service.

Protocol arguments are repeated `--schema PATH`, `--case PATH`, `--parameter PATH`, `--query QUERY`
pairs (one schema/case, zero or more parameters/queries). A query `node@A/B` addresses a complete
coordinate in the node's declared dimension order. `node@*` requests the complete public reduction;
`node@A/*` fixes the first dimension and reduces the remaining axes. `node` addresses a scalar.
The one host-ceiling vector additionally passes `--max-formulas 0`.

The adapter prints exactly one JSON object to stdout with Boolean `succeeded`, Boolean
`validationPassed`, a `values` object keyed by the requested query strings, and sorted `diagnostics`.
Unexpected exceptions and invalid argument/protocol shapes are adapter failures, not semantic
answers. Structural exceptions have no calculated query values. A technically failed partial
calculation retains queryable nil/independent values from its immutable public result.

Typed values use the public wire shapes: `{"n":"0.1"}` for finite exact decimals, JSON Boolean
for Boolean, JSON string for text, `{"kw":"A"}` for keyword, `{"date":"2026-02-28"}` for date,
JSON null for nil, JSON array for vector, and `{"map":[[key,value],...]}` for an ordered typed map.
Decimal comparison permits only base-ten value equivalence (e.g. `1.0` and `1.00`); it never coerces
false, text or nil into numbers. Map entry order and key kinds remain significant.

Each diagnostic contains exactly `code`, `category`, `severity`, `effect`. Sort by those fields in
that order, preserving duplicate findings. Effects are `technical-failure` for a nonbusiness error,
`validation-failure` for a business error, `warning` for a warning and `information` for info.
Messages, source offsets, elapsed times and attempt-opaque event IDs are intentionally outside this
portable protocol. Stable codes, severity/category/effects and all calculated values are compared.
