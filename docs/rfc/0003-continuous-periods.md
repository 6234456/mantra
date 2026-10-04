# RFC 0003: continuous periods and execution sessions

Status: accepted host design; implementation and contract tests complete (2026-10-04).

M2 consumes the unchanged `normein-dsl` 0.3.0 commit in `normein-build.lock`. Period membership,
coordinate dependencies, first/last stock aggregation and presentation belong to Mantra. The
implementation contract is in [M2 work packages](../milestones/m2-work-packages.md).

## Previous-period references

The global host form `(prev closing first-expression)` is lowered through the public syntax AST
and checked-draft compiler. It receives a typed previous-value root, an explicit first-period flag
and a zero-argument closure. The private pure helper invokes the closure only in the first period.
Its roots are supplied by the host coordinate scheduler; it does not read mutable runtime context.

Lexical shadowing remains intact. Source spans and selected named definitions are retained in a
host projection of the final source index; kernel identities and artifacts remain unmodified.
The implementation must verify generic type inference, lexical captures, named calls, nested
fallback dependencies, unused failing fallbacks, later nil and authentic source trace positions.

## Execution modes and invalidation

Ordinary calculation uses the public execution plan/session API in VALUE_ONLY mode. Audit and
Explain consume FULL source trace and retain their existing collection budgets. Session lifetime
and dependencies stay internal; public results are immutable. Reuse after editing requires
dependency invalidation of affected coordinates and derived member domains, with fresh audit
evidence for recalculated values. Schema/function changes invalidate compilation.

No new kernel extension is assumed by this design. If a contract test exposes missing behavior,
record its reproducer and affected domains here and request a reviewed released kernel revision.
Never patch the pinned checkout or silently fabricate source trace, rounding or dependency edges.

## Host function addition

The existing three-argument `dim/rollup` remains additive. The five-argument overload supplies an
explicit first/last rule and ordered source keys, so it can select a stock boundary without guessing
metadata from the author's expression. Date operations use the existing public `date/*` library.
New host function semantics require catalog, exact numeric, trace and XLSX contract tests.
