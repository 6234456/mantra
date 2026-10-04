# Compatibility and deprecation policy

Mantra versions independent contracts explicitly. A version bump in one contract does not silently
reinterpret a stored case under another. Applications in `apps/` are demonstration packages and
are never Maven library artifacts.

| Surface | Baseline | Compatible change | Breaking change |
| --- | --- | --- | --- |
| Host DSL | Language specification 1 | Additive opt-in forms/options with unchanged existing meanings | New language major; retain an explicit old reader or documented migration |
| Kotlin/Java API | Full public JVM ABI of the six library modules | Additive overloads/types, preserving descriptors and source behavior | Artifact major after 1.0; reviewed ABI change plus migration notes |
| Workbench JSON | `mantra.workbench/4` | Additions accepted by the supported client/schema, with defined defaults | New wire major and negotiated/correctly rejected clients |
| Package host JSON | `mantra.packages/1`, containing real workbench/4 payloads | Additions accepted by the supported envelope schema/client | New envelope major; do not relabel incompatible wire payloads |
| Calculation functions | `mantra.calc@2` | Additive callable signatures retaining existing arithmetic | Function-library major; exact selection remains in metadata |
| Package manifest | Manifest format 1; strict SemVer package identity | Explicit compatible engine comparator intersection | New manifest format; no implicit latest-package selection |
| Schema identity | Exact authored ID/version, including legacy versions | Coexisting new versions | Explicit reviewed migration; never automatic case rewriting |
| Normein kernel | 0.3.0, commit `0a3ae1de844c92635fbbc03406a13cb0e8920c03` | Only after RFC/contract/conformance review and lock update | Coordinated adapter/library release and mapping table update |

The authoritative baseline for a release is its tag, lock file, ABI dumps, conformance corpus and
wire schemas. `0.x` and `1.0.0-rc.1` artifacts remain pre-stable; changes still require review and
release notes. The RC candidate does not establish a published stable 1.0 baseline. The ABI gate
must not be bypassed by filtering out reachable model or view types. Java and Kotlin
consumers are compiled and executed against a real local Maven staging repository with no composite
substitution in the consumer build. Staging the pinned dependency may use the producer's composite;
the consumer uses a separate temporary fixture, fresh Gradle home and POM-only artifact resolution.
Local staging does not mean artifacts have been uploaded to Maven Central.

All six libraries are in the owned ABI gate: `mantra-core`, `mantra-render`, `mantra-excel`,
`mantra-workbench`, `mantra-server` and `mantra-packages`. Public model/read, layout, package and
external POI signatures are included, without an `api`/`view` package filter. The dump comparison
protects owned public descriptors; it does not analyze every method of an external dependency.
The pinned dependencies and real downstream consumers remain additional compatibility evidence.

Deprecate before removing an established public API: annotate it with a replacement and release
note, retain the JVM descriptor for at least two minor releases and six months, and remove only in
the next major release. Emergency security removals identify the concrete vulnerability and impact.
If no safe replacement exists, state that directly. Do not hide incompatible behavior behind an
unchanged method descriptor or schema version.

An ABI baseline update requires human-readable review of every added/removed public declaration,
a genuine breaking-change counterexample, source and already-compiled consumer checks, and the
normal complete CI gates. Changes to numerical semantics additionally update the normative clause
and independently authored expected values; regenerating expectations from the engine is prohibited.

Wire consumers reject unsupported major versions before editing. Adding an optional field is not
inherently compatible with an older strict JSON schema: supported clients and schema revisions must
accept the change, or the incompatibility must be versioned. Do not call a required client upgrade
an unchanged-wire compatibility guarantee. Preserve stable diagnostic codes,
typed addresses, original messages and explicitly bounded evidence. LSP uses its advertised 3.17
capability subset, UTF-16 positions and versioned edits; clients cannot apply an unversioned rename
to stale open buffers. Snapshot revisions are identities, not semantic artifact versions.

Directory loading defaults to `STRICT_HANDLES` and fails when secure handles are unavailable.
`TRUSTED_LOCAL` requires explicit host selection; its cooperative identity/hash checks do not
claim isolation from a malicious same-permission process racing filesystem renames.

New releases update this table and document the supported kernel correspondence. Publishing requires
the authorized Maven namespace/signing setup and the corresponding real Normein artifact. A GitHub
source release is labelled as such and does not claim unavailable Maven coordinates.

## Public execution and statistics contracts

`CompiledCalculation` is an immutable checked template that may be shared across threads.
Its `openSession` creates a distinct `CompiledCalculationSession`; a worker's open/calculate/close
belong to one thread. `CalculationSession` is the same-case incremental runtime, while the compiled
worker accepts compatible independently identified cases. `CaseGraphRunner` and `CalculationReader`
also have owner-thread/lifetime requirements. Synchronization or a volatile result does not make a
session transferable. Acquire the completed immutable result on its owner, then pass that snapshot.
A rejected current call must be handled as a failure; a retained last result is historical evidence.

Each public calculation starts fresh controls, with kernel sessions rebuilt when the complete formula
budget profile changes. Session caches retain no old cancellation token, deadline or RunContext.
`view.openReader(options)` starts a separate read epoch; one reader can cover a whole paper/export,
including cross-view reads. Its usage does not amend the published calculation usage. Rendering
closes readers before returning detached artifacts; later workbook edits do not call expired controls.

`CompilationStatistics.syntaxCompilerCalls` and `semanticCompilerCalls` count actual public compiler
invocations, not private kernel cache misses. `executionPlanCompilations` is per authored expression,
not one plan per schema. `CompiledExecutionStatistics` reports actual session opens, close attempts,
successful closes and public kernel row/frame/evidence counters. A batch summary counts callbacks
actually delivered and distinguishes technical failures from business validation failures.
`RecalculationStats.reusedTasks` counts completed tasks retained before a request, not cache-hit events;
`executionSessions` counts retained sessions. Zero formula evaluations does not mean zero snapshot,
control, dependency or projection work. Changes to these meanings require compatibility review.

## Recorded acceptance and release boundary

The joint `1.0.0-rc.1` implementation and functional acceptance are complete. Full local checks
include six-library ABI comparisons, the temporary public-model Int→Long counterexample and
unchanged Java/Kotlin binaries rejected with `NoSuchMethodError`, five isolated POM-only executable
consumers, and all six local sources/KDoc/POM artifacts. Conformance, current security/import and
protocol regressions, application values, UI, browser/live-package flows and cleanup passed.
The measured source is `ae9af84bde3f82cc86787e908aab7be76ddecee9`; its
[candidate CI run 37220953941](https://github.com/6234456/mantra/actions/runs/37220953941) passed.

The [candidate performance record](performance-v1.md) retained all 390 samples and independently
recomputed 39 summaries against unchanged bounds. The actual 10,000-case stream checked 1,120,000
exact values in 22,777 ms with 333,767,296 bytes of summed heap-pool peaks; all 22 sessions closed.
A separate post-measurement Combined XLSX JFR recording passed scope/privacy verification:
41.029 seconds, 2,812 CPU samples, 1,774 allocation samples, five GC events and zero events in all
six disabled private-metadata categories. It leaves the measured production/harness unchanged
and is not inserted into the unprofiled distribution or used to explain historic latency tails.

The final source-publication revision and CI are identified by the `v1.0.0-rc.1` tag and the release
receipt in the [source-candidate report](release-candidate.md). Maven Central upload has not been
performed; namespace, signing and a publicly available matching
Normein 0.3.0 artifact remain pending the maintainer's decision/setup. Current local acceptance and
staging are not a stable `1.0.0` release or public repository availability.
