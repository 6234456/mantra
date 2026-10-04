# Independent expectations

These answers were chosen before running any adapter. None were copied from existing engine test
results. The named clauses refer to specification 1. The runner never regenerates an expectation.
`test_derivations.py` verifies selected arithmetic with standard-library Fraction/Decimal, separately
from the DSL and with no import of Mantra. Its methods do not reproduce a planner or evaluator.

| Vector | Independent reasoning |
| --- | --- |
| 01 typed facts | Explicit numeric 0 and Boolean false retain distinct kinds. Keyword `alpha` is not text `"alpha"`. February 28 is an ISO date. Omitted optional text is nil. |
| 02 checkpoints | First checkpoint is `10 - 3 = 7`, information 99 contributes nothing. Second is `7 + 5 = 12`. Nested branch checkpoints 2 and contributes it once, so the final value is 14. |
| 03 parameters | The second parameter set changes offset from 20 to 30. Case multiplier 0 and flag false supersede earlier defaults/sets; the sum is 30. |
| 04 required | Defaulted 0 is computable but does not prove fact presence. The explicit other 0 and false are supplied. Exactly one BUSINESS ERROR leaves computation successful with validation failed. |
| 05 typed error | Decimal 1 is not Boolean. Input conversion emits one STRUCTURAL type error and publishes nil for the invalid input, with no coerced true. |
| 06 coordinates | Static A/B ordering gives indices 0/1. Values 4 and 6 remain addressed by keys and sum to 10. |
| 07 parent table | X/Y belong to G1 and contribute 2+3=5; Z belongs to G2 and contributes 7. Global sum is 12. The original table vector has three ordered records with distinct keyword/text/numeric wire kinds. |
| 08 applicability | Positive A=4 is active. Negative B=-6 makes the node/check inapplicable: numeric neutral is 0, decision is nil, no finding. Only A contributes to 4. |
| 09 anchored months | Start Jan 31 plus original-anchor one/two/three months yields Feb 28/Mar 31/Apr 30. A recurrence from 0 increments to 1,2,3; last-boundary reduction is 3. |
| 10 period gap | `[Jan 1, Feb 1)` and `[Feb 2, Mar 1)` leave Feb 1 uncovered. One continuity error rejects the domain. |
| 11 two-axis stocks | A starts 100 and moves +5,+7,-2, closing 110. B starts 40 and moves +3,-1,+4, closing 46. First openings sum to 140; final closings sum to 156. Fixed B final is 46. |
| 12 lazy previous | First closing is a legitimate nil from decimal zero division, later closings are 5. First prior uses fallback 9; second selects the actual earlier nil; third selects 5. The unreachable later fallback division error must not execute. |
| 13 rounding | At -1.25, half-up/floor/up give -1.3; half-even/half-down/ceiling/down give -1.2. Rounded third at scale 3 is .333. Display precision 0 leaves actual 2.345 intact. |
| 14 nontermination | Exact rational 1/3 has denominator factor 3 and no finite base-ten expansion. `/` cannot publish an approximation. Its nil and one EVALUATION error coexist with independent 7. |
| 15 business boundary | `abs(1.005-1)=.005`, exactly the tolerance, so reconciliation passes and publishes difference .005. False warning and false error checks produce two distinct severity/effect records; only the error fails validation. |
| 16 convergence | Exact unrounded fixed point of `b=.1(100000-b)` is `100000/11`. The explicit cents/seed iteration is 0→10000→9000→9100→9090→9091→9090.90→9090.91. The first adjacent delta ≤.01 is .01, so next is 9090.91 and remaining 90909.09. |
| 17 two-cycle | Starting at 0, `1-current` gives 1,0,1,0. Each adjacent delta is 1>0; four calls exhaust without an answer. Last 0 is not a successful approximation. |
| 18 opaque version | String `2025.2` equals that exact schema version and requires no SemVer rewriting. The literal result is supplied zero. |
| 19 version mismatch | Strings `2025.2` and `2025.02` are unequal regardless of any human year/month interpretation; binding fails structurally. |
| 20 host ceiling | Max formula executions 0 permits no formula call; the first literal formula is rejected with one EVALUATION run-limit error and no published result. |
| 21 formula replacement | Replacement only reads licensed basis=3; `3×1.235=3.705`. Declared scale 2 half-up returns 3.71. |
| 22 current cycle | A needs current B and B needs current A. This is an ordinary structural dependency cycle, with no previous-period exception. |
| 23 weighted rate | Member rates are 10/100=.1 and 40/200=.2. Aggregate is `(10+40)/(100+200)=1/6`, scale 6 half-up .166667, not .3 or .15. |
| 24 zero denominator | Aligned aggregate numerator and denominator are both 0. Member decimal divisions and aggregate remain nil. Exactly one aggregate BUSINESS WARNING preserves both technical success and validation. |

This corpus deliberately distinguishes a valid nil from a technical failure. A nonterminating `/`
or exhausted convergence has an error and nil; `(decimal/divide 1 0)` is a valid nil. The finite set
also distinguishes unsupported reduction (an adapter protocol error) from a defined nil reduction.
The rounded bonus is seed/tolerance dependent; no claim of universal convergence or unique rounded
fixed points follows from its unrounded closed form.
