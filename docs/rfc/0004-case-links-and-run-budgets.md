# RFC 0004: case links, bounded convergence and layered run budgets

Status: accepted host implementation contract; integration started on 2026-10-04. Tests pending.
This task proceeds with the recommended layered-budget implementation assumption; the user budget preference
has not yet been answered. Generation-time audit snapshots with visible expiry after input edits are confirmed.

References: [M3 work packages](../milestones/m3-work-packages.md), [roadmap](../roadmap.md),
[kernel preparation](../milestones/m3-kernel-preparation.md), [domain preparation](../milestones/m3-domain-preparation.md).

## Scope and compatibility

M3 extends the unchanged Normein 0.3.0 pin `0a3ae1de844c92635fbbc03406a13cb0e8920c03`.
Mantra's library becomes `mantra.calc@2`; the host release is 0.4 and workbench wire becomes `/4`.
Existing calculation semantics remain except the explicitly versioned new functions and work charging.
Keep source-compatible convenience APIs. Packages, version ranges, automatic migrations and batch repositories
are M4 work and are not prerequisites for a link between two explicit cases.

## 1. Exact schema versions

Schemas already carry `:version` metadata. Expose it as a validated nonblank opaque string.
Cases may carry `:schema-version`; when present it must exactly equal the loaded schema's version.
Links require the expected schema id and version. Do not normalize `2025.2` into SemVer or choose latest.
Source resolution by (id, version) must be unique; missing, ambiguous and mismatched bindings are hard errors.
Legacy unversioned cases continue only where their binding is unambiguous. When adding a second version,
explicitly pin the existing case to its old version rather than silently changing the original computation.

## 2. Declarative links

```clojure
(case demo-est
  {:schema "de.est/2025" :schema-version "2025.3"}
  (links
    {:path "../de-gewst/case-demo.mantra"
     :schema "de.gewst/2025"
     :schema-version "2025.1"
     :mappings
     [{:from {:node :messbetrag :coord []}
       :to {:input :gewst-messbetrag :coord [:A]}}
      {:from {:node :gewerbesteuer :coord []}
       :to {:input :gewst-due :coord [:A]}}]})
  (inputs {:gewinn-gewerbe {:A 120000}}))
```

Each declaration is an explicit record; path, expected schema id/version and mappings are mandatory.
Node/input keys and member keys have the normal host identifier rules. Coordinates are complete vectors in
the respective schema's declared dimension order; scalar coordinates are explicitly `[]`.
`gewst-due` illustrates the new version's chosen target id, not an engine-recognized tax identifier.

The first version transfers exact scalar primitive values from an explicitly addressed source node to an
editable input/field at an explicit coordinate. It has no expressions, aggregate aliases, implicit broadcasts,
member-map conversions, temporal inference, table-row writes or fallback sources. Cross-case carry-forward
uses the explicit final source coordinate and first target coordinate; ordinary M2 prev continues within the case.

Relative paths are resolved against the authoring case's origin by the host resolver. Canonical identity must
merge equivalent relative paths and aliases before cache or cycle checks. The workspace's existing directory
confinement applies. A source uses its own schema, parameters, layout and declared data sources.
The consumer's parameter overrides never become the source's parameters.

## 3. Pure binder and provenance

Proposed minimum public surface:

```kotlin
object CaseLinkBinder {
    fun bind(
        targetSchema: Schema,
        targetCase: CaseData,
        resolved: List<ResolvedLinkSource>,
    ): CaseLinkBinding
}
```

`ResolvedLinkSource` contains the original declaration, an immutable source CalculationView, canonical case key,
schema id/version from the view and source revision. `CaseLinkBinding` is either
`Success(case, provenance, targets)` or `Failure(diagnostics)`; the runner propagates source BUSINESS findings.
It must not return a partially bound success. The binder performs no I/O, formula evaluation or live cache lookup.
The shared case-graph runner owns loading, cycle checking, independent source computation and controls.

Binder Success means atomic syntax/type/presence/conflict materialization, not runtime target-domain validation
or graph Success. Validate source id/full coordinate/active value/version and target input/field id/coordinate
shape/type. After computing the real linked target, the runner verifies that each target coordinate exists and
is active in that result, and only then publishes graph Success. Invalid or inactive target coordinates fail
technically; never drop an input silently or pre-run a default/unlinked case to guess a derived member domain.

Reject two mappings to the same
target or a mapping colliding with a local/imported fact, even a local Nil. Schema defaults are not explicit facts.
Presence tests are structural; zero and false are valid supplied values and never tested through truthiness.
The binder materializes detached inputs and typed provenance, while the loader retains the raw declaration graph.
It does not write materialized link inputs back into source documents.

Source Nil, missing coordinates, inactive values and source technical failure are strict technical failures.
Use `MANTRA-LINK-UNDEFINED` for unavailable values, category EVALUATION. Do not bind Provided, choose a target
default, convert to zero, skip to another period or reuse the last good source result. Optional target inputs do not
override this first-version rule. A later nullable-link design would require per-coordinate undefined evidence,
not a node-wide change to M1's ordinary optional Nil treatment.

Source BUSINESS findings retain their original category, severity, source case/node/coord and revision.
An otherwise technically successful graph with upstream BUSINESS ERROR remains succeeded=true and
validationPassed=false. Warnings alone do not fail validation. Technical failure makes the graph fail.

`InputOrigin.LINK` and immutable typed `LinkProvenance` record source case key/id, schema id/version,
revision, node and coordinate; renderers may label it `link:<case>#<node>`. Do not encode the entire contract
only in a display string. Existing source provenance and local fact origins retain their meanings.

Proposed stable host failures, to enter diagnostics documentation and contract tests before implementation:

| Code | Category | Trigger |
| --- | --- | --- |
| MANTRA-LINK-VERSION | STRUCTURAL | Expected schema id/version missing or mismatched; ambiguous source schema binding |
| MANTRA-LINK-ADDRESS | STRUCTURAL | Unknown source node/target input, incomplete or illegal coordinate |
| MANTRA-LINK-TYPE | STRUCTURAL | Explicit scalar source type cannot bind the declared target type |
| MANTRA-LINK-CONFLICT | STRUCTURAL | Duplicate target or existing local/imported fact at that address |
| MANTRA-LINK-CYCLE | STRUCTURAL | Canonical case binding identity re-entered on the active stack |
| MANTRA-LINK-UNDEFINED | EVALUATION | Nil/inactive/missing runtime value or technically failed source |
| MANTRA-RUN-LIMIT | EVALUATION | Host counter limit exceeded; structured counter/limit/attempted accompany it |
| MANTRA-RUN-CANCELLED | EVALUATION | Shared cancellation signal observed |
| MANTRA-RUN-DEADLINE | EVALUATION | Shared effective deadline reached |

Preserve underlying source and kernel diagnostics rather than replacing every technical error with a link code.
Syntax errors retain PARSING. Missing source files get a concrete source-resolution diagnosis and never a Nil default.

## 4. Graph execution, revision and ownership

The runner performs demand DFS by canonical case binding identity, with an explicit active stack for cycles.
Different aliases to the same source do not evade cycles or duplicate execution. One source with two mappings
is one case evaluation and two mappings. A cache identity includes the actual binding and parsed content revision,
not just schema id, basename or mtime. A failed request cannot promote an old cached result to a current success.

Revision includes each recursively participating case/schema/include/parameter/layout/data file's actual bytes.
Changing an upstream file changes the downstream revision even when values remain equal. Use canonical,
length-framed digest inputs; do not let a file re-read after parsing produce a cache key for different bytes.
Business diagnostics and provenance revisions are part of the bound snapshot's invalidation needs.

Only the top-level request creates RunContext. Source loading, link binding, source calculations and target
calculation share it. Public independent session recalculate creates a fresh epoch; internal bound recalculate
accepts the existing context. Calling the public fresh-epoch method from each source is forbidden.

Normein VALUE_ONLY sessions are thread-confined. SDK open/recalculate/close must run on the opening thread,
and wrong-thread checks happen before mutating closed/broken state. Detached immutable results cross threads.
Workbench cache creation/reuse/replacement/LRU/close stays on its dedicated owner executor; close waits for
termination, is idempotent, restores interruption after cleanup and unwraps worker errors to their original type.
The initial graph runner is sequential on that owner thread. Only the cancellation signal is cross-thread;
RunContext counters are not a mutable global or shared between concurrent top-level requests.
Parallel case execution would need a separate ownership/synchronization contract rather than moving existing sessions.

## 5. Layered limits

RunLimits defaults and exact charging points are in the work package. All cumulative counters are Long,
with overflow-safe checks before allocation/execution. The single shared deadline is the earlier of the
caller deadline and start+60 seconds. Cancellation is an explicit shared signal, not a mutable library global.

Host caps bound cases, depth, mappings, coordinate products/visits, input rows, actual tasks/formulas,
host scans and participating bytes. Cache reuse is reported separately. A unified CoordSpace applies fixed
assignments before enumeration, preserves ancestor/member order, treats an empty axis as product zero,
and checks the product before allocating a Cartesian list.

Use the existing public kernel APIs:

- FULL: `DslEvaluationRequest.budgetLimits` and `DslEvaluationInput.cancellation/deadline`.
- VALUE_ONLY: `DslExecutionSessionOptions.budgetLimits` on openValueOnlySession, and
  `DslValueOnlyInput.cancellation/deadline` for each row. A new profile requires a new session.
- Domain function work: `DslFunctionRuntime.charge` and `checkpoint` at actual host work points.

Merge caller overrides into complete `DslBudgetLimits.defaults()` and constrain them to the default ceiling.
The public constructor's absent counter means unbounded, so a partial map must not silently disable other caps.
The current kernel resets per output; this RFC does not claim shared cumulative VALUE_ONLY kernel counters.
Cost metrics are not budget counters and FULL receipts are not a replacement for the VALUE_ONLY route.

Host budget/cancellation/deadline failures stop the request and carry stage, counter/limit/attempted and address.
Budget exhaustion is EVALUATION, not BUSINESS. Audit collection limits remain separate best-effort limits;
truncated trace is visibly incomplete without making an already valid numerical computation fail.

## 6. calc/converge

```clojure
(calc/converge f init max-iterations tolerance)
```

Use a PURE eager DslFunctionSpec. The callback type is `DslTypes.function` with a DslFunctionTypeSignature
whose sole parameter is Decimal and whose result is the Integer/Long/Decimal numeric union: Decimal→Number.
Other parameters are numeric; the returned value is a Decimal. Convert init to Decimal before the first call,
and convert each numeric callback result to Decimal for the next call. Invoke f strictly through
runtime.invokeCallable with one Decimal argument. A `^Decimal` callback is compatible; a `^Integer` callback
is not. Function parameters are contravariant, so Number→Number must not be used as an alleged compatible
contract for every explicitly Decimal-annotated callback.
Do not access application data, run mutable external code, interpret lambda syntax or truncate callback arity.

The M3 contract PoC must cover untyped lambda, named defn, explicit `^Decimal`, callback returning an
Integer constant, and rejection of an explicit `^Integer` parameter. No kernel patch is required.

init and callback results are non-Nil scalar numbers. max-iterations is exact integral 1..1000;
tolerance is nonnegative. BigDecimal is used without Double conversion. Starting at current=init,
each iteration computes next=f(current). If abs(next-current)<=tolerance, return next.
An iteration counts one callback invocation; init is not an iteration. A zero tolerance is numeric exactness,
not scale-sensitive BigDecimal object equality. The caller's callback owns any monetary rounding.

Charge ITERATIONS=1 before each callback and NUMERIC_OPERATIONS=3 for subtraction, absolute value and
comparison; the kernel charges invokeCallable and its body. Additional actual host conversions must be documented.
Nested convergence shares the current formula's kernel budget. Never swallow cancellation/budget/callback errors.

Exhaustion returns no approximation. The handler throws the kernel-compatible stable
`DSL-MANTRA-CALC-NOT-CONVERGED`; Mantra explicitly projects it as `MANTRA-CALC-NOT-CONVERGED`,
EVALUATION, retaining span, node and coordinate. Other callback errors keep their own causes.
Invalid bounds, callback arity/type and arithmetic errors are separate technical failures.

Adjacent delta is the stopping rule; it does not guarantee a business equation residual or global error.
The callback's rounding, initial seed, tolerance and iteration limit are part of the mathematical contract.
The host adds no automatic rounding, solution selection, oscillation repair or seed-independent guarantee.
An unrounded closed form does not prove that the rounded callback converges or has only one fixed point.

Required independent examples:

| Callback and settings | Result or failure | Independent meaning |
| --- | --- | --- |
| round_HALF_UP(0.1*(100000-B),2), init=0, tolerance=0 | 9090.91 | The unrounded equation has solution 100000/11; verify the rounded fixed-point equation separately |
| round_HALF_UP(1000+0.25*G,2), init=1000, tolerance=0, limit=60 | 1333.33 | One rounded fixed point; unrounded solution is 4000/3 |
| Same gross-up callback, init=2000 | 1333.34 | A second rounded fixed point; do not replace it with the rounded closed form or the other seed's value |
| round_HALF_UP(0.5*(0.01-B),2), init=0, tolerance=0 | Not converged: 0 and 0.01 alternate | Unrounded solution 1/300 exists but does not rescue this iteration |
| 1-B, init=0, tolerance=0 | Not converged: 0 and 1 alternate | Unrounded fixed point 0.5 exists |
| 1000+G | Not converged | No fixed point exists; limit/cancellation can end the attempt |

Both rounded gross-up values satisfy their rounded callback, while their unrounded equation residuals differ.
Independent scripts must preserve seed and rounding settings in references, verify true rounded fixed points
or cycles, and separately compare with the unrounded Fraction/Decimal closed form. They must not rewrite a
valid alternate-seed result to fit the main case. tolerance>0 can stop near, rather than at, any exact fixed point.
Tests also cover zero net/zero rate: for a rate of one and zero net the identity callback has many fixed points,
unlike the positive-net rate-one no-solution example. Do not label every rate-one case as mathematically unsolvable.

FULL retains genuine repeated callback trace nodes with original source spans and named definitions.
eventId/invocationIndex may be retained as genuine kernel metadata, but invocationIndex is not automatically
the converge iteration number. The runtime has no public custom trace-event API. No replayed history is
presented as original evidence; VALUE_ONLY produces no artificial trace. Truncation remains explicit.

## 7. Wire/4, Explain and XLSX

Every explainable address includes case in addition to node/coord/cell; aggregation addresses retain their
existing generic representation. Case identity, schema version and source revision distinguish equal node names.
Run adds immutable link graph/provenance and host usage/limits snapshots. Explain follows the actual source
address, not the consumer's coordinates or parameter binding. Stale source revisions are reported explicitly.
All strict JSON schemas, generated types, fixtures/goldens, server routing, URL encoding and UI navigation
must update together; v3 fixtures cannot be relabeled as v4 without required fields.

Input edits preserve generation-time audit snapshots and mark them outdated. A fresh FULL audit is a genuine
execution for the new bound revision; old and new steps are not spliced. SSE uses recursively participating files
to notify downstream cases of revision changes. HTML/Text papers identify linked source and revision.

XLSX link values are snapshot input cells with source case/node/coord/schema-version/revision comments.
Ordinary workbook formulas still recalculate locally; source refresh belongs to the host, not external workbook links.

Translate converge into a bounded hidden iteration table: each row evaluates the callback only if the preceding
row has not stopped, records value/stop state, and preserves the first stopped value. For literal max-iterations,
allocate its 1..1000 rows; for dynamic limits allocate within the hard 1000 ceiling, validate the live bound,
and preflight cell/work limits before allocation. No Excel global iterative calculation or static-value fallback.
Already supported scalar closures and named functions must retain captures and current coordinate references.
Unsupported structures fail explicitly. Nonconvergent edited workbooks expose an error/status, not an old approximation.

Excel binary64 does not promise arbitrary BigDecimal zero-tolerance equality. The demo uses explicit per-step
currency rounding and independently compared amounts; exact intermediate kernel evidence remains a snapshot.
All demonstration apps must export with zero unsupported/fallback nodes.

## Acceptance and unresolved capability

The work package's exit checklist is mandatory: scalar convergence/true failure, independent closed forms,
cross-case cycles/depth/version/identity, shared caps/control, exact Nil/zero/false presence, source findings,
revision invalidation/SSE, original trace, HTML/Text/XLSX, full regressions/performance/CI and resource cleanup.
No checklist item is green until the test or measurement was actually run.

The layered implementation has no additional human architecture blocker. If a later instruction requires an exact,
preemptive shared total of every VALUE_ONLY kernel counter, current public APIs are insufficient: request a
reviewed public Normein extension, a released revision and contract tests before changing the lock.
Likewise custom structured iteration events require a separate evidence contract or kernel extension.
Neither missing capability is silently claimed by this RFC's layered implementation.
