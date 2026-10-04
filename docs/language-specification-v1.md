# Mantra language specification, version 1

This document specifies the portable calculation semantics of Mantra DSL 1. It is normative together
with the host-form and option tables in the [DSL reference](dsl-reference.md), the exact code names
in the [diagnostic reference](diagnostics.md), and `mantra.calc@2`'s function contracts. Examples,
application laws and paper formatting do not add calculation rules. The independent corpus in
[`conformance/`](../conformance/README.md) identifies clauses below; passing that finite corpus is
evidence of conformance, not a proof for every possible document. The 24 frozen corpus vectors have
passed the external-adapter runner. That result does not close graph, lifecycle, output, security
or performance integration gates by itself. The joint `1.0.0-rc.1` candidate implementation and
functional acceptance have now passed those recorded integration gates. Measured source
`ae9af84bde3f82cc86787e908aab7be76ddecee9` has
[green candidate CI](https://github.com/6234456/mantra/actions/runs/37220953941) and independently
verified frozen performance. The final source-publication revision and CI are identified by the
`v1.0.0-rc.1` tag and the [candidate report](release-candidate.md) release receipt; this document
does not declare a stable `1.0.0` or Maven Central release.

## S1. Documents and literals

**S1.1** A document contains one root schema, fragment, case, parameters or layout form. Lists,
vectors, ordered maps, namespace-free keywords, strings, decimal/integer numbers, Boolean values,
nil and semicolon comments use the pinned Normein reader grammar. The expression baseline is
[Normein 0.3.0](https://github.com/6234456/normein/tree/0a3ae1de844c92635fbbc03406a13cb0e8920c03).
Readers reject malformed root/declaration forms; they do not silently discard unknown declarations.

**S1.2** IDs bind declarations, independently of labels. Duplicate, reserved or invalid IDs are
structural errors. Case input/parameter keys must be declared. Includes insert fragment declarations
at their authored position; include cycles fail. Loading a document does not confer filesystem or
network permission. The embedding host defines source resolution and limits.

**S1.3** Inputs are typed. Decimal values use exact base-ten arithmetic. Integers must be exact;
Booleans are not numeric coercions. Keywords, text and ISO dates remain distinct values. Nil is
distinct from zero, false and blank text. Table records retain their authored order and cell identity.
Nullable column kinds admit nil; incorrect kinds and unknown foreign keys are technical errors.

## S2. Binding and calculation items

**S2.1** Parameter precedence is schema defaults, explicitly supplied parameter sets in their order,
then case overrides. Source facts precede case facts. Omission and explicit nil remain distinguishable
for completeness checks. An explicit zero or false is a supplied fact; a default/implicit zero is not.

**S2.2** Each active line evaluates its formula in its bound coordinate. A field reads its typed input.
The default contribution is plus; minus reverses its contribution and info contributes nothing.
A total checkpoints the preceding total plus subsequent signed contributions. A section with a
total contributes its last total as one opaque result to its parent. Checks/reconciliations never
become numeric contributions or ordinary numeric dependency roots.

**S2.3** Cases may extend declared slots and bind declared formula slots. A formula binding preserves
the schema's position, dimensions, type and rounding, and can access only its licensed `:uses` roots.
Unknown slots, forbidden roots and dependency cycles are structural errors. Function and lexical
bindings retain normal kernel identity; a spelling match does not override a local function.

## S3. Dimensions and reductions

**S3.1** Static or table-backed dimensions have ordered, unique, nonblank member keys. Table-backed
keys and titles come from declared columns. Parent relations require existing active parent keys.
`member.key`, label, index and record attributes identify the current actual member. Reordering
records changes ordering, not the identity of facts addressed by member key.

**S3.2** A node's explicit `:per` replaces inherited dimensions. Matching axes select current scalars;
remaining axes produce ordered member maps. `all.node` selects the complete member map.
Conditions inherited from sections, dimensions and the node are aligned at each coordinate.
False applicability excludes a contribution; inactive business decisions produce nil and no finding.
Failure to determine applicability is a technical failure, not an inactive contribution.

**S3.3** Ordinary numeric reductions sum eligible coordinates. Ratio reductions sum their aligned
numerator/denominator at the rate's active coordinates, then divide with the declared aggregate
rounding. They do not sum member rates. A zero denominator yields nil and one business warning;
no active coordinates yields nil without that warning. Member rounding and aggregate rounding
are separate. Optional ordinary input nil is additive zero under the declared numeric reduction
contract; an active failed calculation or an explicitly undefined total, ratio or selected stock
propagates nil. Available partial sums in undefined trace evidence are not a successful amount.
Inapplicable coordinates retain their neutral contribution. Nil is never reported as an observed zero.

## S4. Continuous periods

**S4.1** A period dimension contains at least one ordered interval `[start,end)`. Consecutive intervals
are adjacent, nonoverlapping and gapless. Generated month/quarter/year boundaries are calculated from
the original start, preserving month-end behavior. Period conditions cannot remove intermediate
members. Parent period intervals must contain their children completely.

**S4.2** Global `(prev node fallback)` selects the preceding period at the same other coordinates.
Only the first period evaluates fallback. A later nil remains nil. Current-period and domain cycles
still fail; previous-period dependency lowering does not license arbitrary recursion.

**S4.3** `:aggregate {:first period}` or `{:last period}` selects the declared boundary for each group
of other coordinates; other removed axes sum. It never searches for the last nonzero/active period.
Every closing balance remains independently addressable. Layout transposition changes presentation,
not these amounts or their node/coordinate identities.

## S5. Conditions, decisions and exact rounding

**S5.1** Kernel truthiness treats only false and nil as falsey. Conditional evaluation follows the
kernel's executed-branch rules. A choice evaluates applicable options and selects the specified
minimum/maximum. Eager forms retain errors in evaluated bindings even when the value is unused.

**S5.2** Required, minimum/maximum, minimum-row and authored check/reconciliation failures are
business findings. They preserve technical success and permit saving well-typed inputs. A check
passes only for its true Boolean result. Reconciliation passes exactly when
`abs(left-right) <= tolerance`; the nonnegative tolerance boundary is inclusive.

**S5.3** Decimal arithmetic is exact until an explicit calculation rounding operation. `/` must not
approximate a nonterminating decimal. `decimal/divide` and declared rounding use their explicit
scale/mode; half-up, half-even, half-down, floor, ceiling, down and up retain their usual signed
decimal meanings. Display precision cannot change calculation values. A technical error has no
previous-success fallback.

**S5.4** `calc/converge` calls its pure decimal callback at most the explicit integer bound 1..1000,
and returns the first next value with `abs(next-current) <= tolerance`. Initial value and tolerance
are numeric, tolerance is nonnegative, and each callback receives Decimal. A numeric callback result
is converted to Decimal for the next call. The callback type is Decimal→Number; an Integer-only
argument does not accept that contract. Exhaustion is technical failure, not an approximate result.
There is no implicit monetary rounding or retry. Explicit callback rounding, seed and tolerance are
part of the result: multiple rounded fixed points or a two-cycle need not converge to an initial-value
independent closed form.

## S6. Case links, packages and provenance

**S6.1** A linked case runs under its own exact schema/version/parameters. Mappings name the source
node/coordinate and destination input. Cross-case cycles, depth limits and invalid mappings fail.
The receiving revision incorporates all participating captured sources and linked revisions.
Linked amounts retain source case, revision and typed address; no cross-workbook formula is implied.
Explicit zero and false are supplied linked values; source nil is a technical link error, never a
local default. Each destination must exist and be active in the actual bound domain. Source business
findings remain source-owned and may coexist with technical success; a source technical failure
prevents publishing a successful graph. Revision changes update provenance even at the same amount.

**S6.2** New package versions use strict SemVer and explicit compatible engine ranges. Historical
schema versions remain exact opaque bindings when marked legacy; implementations must not rewrite
`2025.2`, `0.1`, `1` or unversioned historical cases into inferred SemVer. Migration is explicit,
reviewable and conditional on the reviewed source revision. Old versions may coexist.

**S6.3** Parameter validity uses an explicit host-supplied effective date and half-open validity
intervals. Overlap and gaps for required keys fail; what-if selections are explicit and retain their
true provenance. Adapters may select/decode keyed parameters, but may not secretly compute domain
results. Directory loading defaults to `STRICT_HANDLES` and fails if secure handles are unavailable;
`TRUSTED_LOCAL` requires explicit host selection. Directory access policies describe their actual
security guarantee, without claiming malicious concurrent rename isolation for that cooperative policy.

## S7. Diagnostics, limits and outputs

**S7.1** Findings preserve stable code, severity, category, optional authored location and calculation
address. For a published calculation result, `succeeded` is false for nonbusiness error-level
findings; a BUSINESS error by itself leaves technical success intact. Its `validationPassed` is
false for BUSINESS errors only. A technical error alone does not change that independent business
flag. A rejected request/graph without a result does not claim validation success. UI localization
uses code-based explanations and retains the original English message as detail. Missing exact
source ranges must not be replaced by invented ranges.

**S7.2** Kernel and host resource ceilings, cancellation and deadlines remain technical limits.
Truncated audit evidence is explicitly incomplete and does not change successful numerical values.
Calculation and read requests have separate bounded epochs. A paper/export shares one read epoch
across its cells and source views; it cannot reset controls per cell or retain controls in the returned
artifact. Owner-thread sessions must be opened, used and closed on one thread; immutable result
snapshots may cross threads. A final cancellation/deadline failure prevents publishing the new
snapshot, even if formula evaluation completed.

**S7.3** HTML, text, PDF and XLSX consume public read-only views. Unsupported editable formulas,
capacity limits and evaluation errors are explicit. Dynamic workbook facts remain keyed by actual
members. Generation-time audit is a snapshot; changed input values or structure make it stale,
and restoring the original facts restores its current status. Dynamic edits are limited to the
explicit capacity and supported formula/type shapes. An OOXML/POI round-trip does not establish
native Excel GUI certification. Rendering must not compute new
domain amounts or claim that cached audit was rerun by Excel.

## Version correspondence

DSL 1 uses `mantra.calc@2` and the locked Normein 0.3.0 commit above. Package manifest version,
schema version, library artifact version and wire contract version are distinct identities.
The `1.0.0-rc.1` artifact label does not change DSL 1, calc@2 or the wire majors.
See the [compatibility policy](compatibility.md). Future language changes must identify affected
clauses and add independent expectations before implementation.

## Acceptance scope

The independent corpus binds source bytes, clause IDs and typed expected values before execution;
its runner checks those hashes and never records implementation outputs as new expectations.
Attempt-opaque audit event IDs identify real execution events within one trace and are not stable
across runs. Semantic trace comparisons may canonicalize event identity consistently while retaining
step/branch associations and invocation indices; production events must not be fabricated for goldens.
The [M6 work packages](milestones/m6-work-packages.md) record actual candidate checks and publication
boundaries; the source tag/report receipt records the final revision and CI. The [performance report](performance-v1.md) covers all 390 independently checked
samples and the actual 10k/1,120,000-value batch (22,777 ms; 333,767,296 summed heap-pool peak bytes;
22 successful session closes). The separate Combined export JFR record passed privacy/scope checks
and does not alter those unprofiled samples or the language semantics. Namespace/signing and the
matching public Normein artifact are still unavailable; no public Maven release is implied.
