# 1.0.0-rc.1 source candidate evidence

The M4–M6 implementation and engineering acceptance are delivered in this source candidate.
The coordinated stable Maven release remains open. This report records executed checks and their
scope; local staging is not a public Maven upload.

## Source and runtime identity

The measured implementation is commit `ae9af84bde3f82cc86787e908aab7be76ddecee9`, engine
`1.0.0-rc.1`, with Normein `0.3.0` locked to
`0a3ae1de844c92635fbbc03406a13cb0e8920c03`. Both working trees were clean at the start of
measurement. All 212 library/harness inventory entries and 25 installed runtime JARs matched
after the two sequential performance processes finished. The harness fingerprint is
`c41f47cfe0abbe316e4460f15866814502e10c0bbffe18fda6d954ca407ed59f`.

[The candidate CI](https://github.com/6234456/mantra/actions/runs/37220953941) passed on that
exact implementation revision. The archived RC finalization changes retained evidence, updated
documentation/CI and disabled private metadata events in the separate Java profiler. Those changes
left the measured production Kotlin, Kotlin harnesses, input data, independent expectations and
numerical goldens unchanged. The source tag identifies that source-publication revision; its CI
receipt accompanies the release.

The separate 2026-10-05 integration changed default kernel resolution to the public Maven artifact
and corrected embedded runtime identity reporting. Its functional and preparation checks are
recorded separately below. They do not relabel the measurements or original tag receipts above,
and they do not establish current-source CI or performance results without their own receipts.
The separate [public-kernel measurements](performance-public-kernel.md) now record the actual
`a1e6e65` CI, 390 samples / 39 groups and 10,000-case / 1,120,000-value batch inside unchanged budgets.

## Executed acceptance at the original RC source publication

| Area | Actual evidence |
| --- | --- |
| JVM implementation | 790 test records across 19 root modules, zero failures/errors/skips; full quality gates pass. Counts include retained up-to-date suites and are not presented as 790 newly executed tests in one invocation. |
| Scheme packages | All ten applications exercised as captured directories and classpath JARs using the public package API; independent values and actual HTML/Text/XLSX outputs checked. |
| API distribution | Six complete ABI baselines, a binary-break counterexample, binary/source/KDoc local staging and five isolated Java/Kotlin POM consumers pass. |
| Independent applications | 71 workflow commands pass, using actual output files and separate Decimal/Fraction arithmetic; narrow technical cases have JVM acceptance coverage. |
| CLI/tutorial/site | Seven exact tutorial CLI commands pass; strict offline site build/check and 26 site tests pass, with real compiled API HTML and all ten showcases. |
| Language tools | Both installed LSP launchers pass real stdio navigation, hover, references, UTF-16 rename and shutdown. VS Code has five passing client tests; IntelliJ has 13 separate protocol/document tests without installing an IDE. Earlier full platform compilation/plugin packaging is recorded in M5 evidence. |
| Workbench UI | 103 tests across 20 files, type/format/lint checks and 13 system-Chrome flows pass. Generic package editing, parameter validity, migration preview/apply/undo/redo and stale-source recovery were also inspected in the built-in Browser. |
| PDF | Tutorial's two latest pages rendered and visually inspected; the lease and long-paper fixtures cover 28 pages with zero characters outside page bounds. Earlier all-page visual QA is distinguished from the latest bounds check. |
| Security controls | Focused source review and executed import, package, HTTP, LSP and policy regressions pass; 36 stalled request bodies close and recover. A fresh installed CLI closes incomplete headers in 2,044 ms under an explicit two-second test property, then returns 200. |
| Performance | All 390 original/period samples and 39 summaries independently recomputed; unchanged budgets pass. No tail is dropped. |
| Batch embedding | 10,000 distinct IDs / 1,120,000 exact comparisons in 22,777 ms with a 512 MiB heap; 22 sessions opened and successfully closed, zero runtime plan compilations and VALUE_ONLY evidence materializations. |
| XLSX profile | One separately recorded full public export, 41.029 seconds with JFR overhead; CPU/allocation/GC evidence retained. Six environment/system/process metadata event counts are exactly zero. |

Detailed evidence lives in [M4](milestones/m4-work-packages.md), [M5](milestones/m5-work-packages.md),
[M6](milestones/m6-work-packages.md), [performance](performance-v1.md) and the
[scoped internal security review](security-review-v1.md). Runtime logs and rendered outputs are
retained in build directories and CI artifacts; the byte-preserved performance records are checked
into [the v1 archive](../benchmarks/baselines/v1-macos-aarch64/README.md).

The original RC byte audit found unchanged application data: 792 original files and all 19 CSV files
match the M3 revision byte for byte; eight application build files have the intended dependency/
packaging updates. The 431 workbench golden JSON changes only align `engine.mantra` to the RC
version; parsed comparison found no changed numeric or other fields.

Browser runs used the installed system browser with a task-specific temporary profile or the
built-in Browser. Task-owned browser/server processes, profiles and temporary live-package fixtures
were stopped and removed; existing user browser profiles and services were preserved.

## Stable publication prerequisites

No production Mantra signer has been configured and no Mantra Maven Central upload has occurred.
On 2026-10-05 the maintainer explicitly deferred formal Mantra Central publication and its
independent environment secrets. The main-only `maven-central` environment and prepare-only
workflow are present; its hosted signing configuration remains deferred.

The canonical preparer passed an actual disposable TEST-key run on
`a1e6e65a01aa39c792681a138beda31ab66af172`: six signed local publications produced a 144-entry
bundle with all detached signatures verified by GPGv. Five fresh POM-only consumers and five fresh
ordinary Gradle consumers passed. The receipt confirms temporary agent shutdown, private key-home
removal and removal of the outer TEST key/bundle. The 98-test Python suite passed, including seven
preparer boundary regressions. This proves the local preparation path; it does not verify production
namespace/signer/token configuration, run the hosted workflow or upload a Mantra artifact. See
[preparation evidence](central-publication.md).

The subsequent cleanup hardening passed 114 Python tests, an actual offline Gradle timeout
exercise and a new canonical TEST-key preparation on `75d0258a377d27bff828a3e7217bb75c61a391a5`.
All 144 bundle entries and both five-consumer modes passed; task-owned daemon/key/staging cleanup
was verified. The POSIX preparer uses dedicated Gradle homes and handles catchable cancellation;
unverified cleanup retains a failure receipt. See [the new local receipts](evidence/release-process-cleanup/README.md)
and [cleanup controls](central-publication.md#task-owned-process-cleanup). Production publication
remains deferred.

Compiled identity now has an additional guard: RC → local stable → RC public-JAR probes and
fresh signed consumers assert the actual selected version, after a compiler-mode switch exposed
stale inline constants. The guarded `d052f735` TEST run passed. An exact-hash static proof confirms
the original public-kernel benchmark JAR reported RC correctly; the historical archive is unchanged.
See [version-identity evidence](evidence/release-process-cleanup/README.md#compiled-version-identity-guard).

The original `normein-dsl:0.3.0` POM probe returned HTTP 404 on 2026-10-04 at 16:22 UTC.
A new official probe on 2026-10-05 successfully fetched its POM, JAR, sources and Gradle metadata,
following the maintainer's notice that `normein-api:0.3.0` had been published. The API POM
references the now-public DSL. That public dependency prerequisite is resolved.

Default builds and CI now consume the public DSL directly. Its identity/replay implementation
has differences from the source-built kernel used for the measurements above. New functional
and isolated-consumer validation accompanies the dependency change; the archived measurements
retain their original source and runtime identities. See [kernel integration](normein-publication.md).

The source candidate does not announce stable `1.0.0` or a public Mantra Maven release. Signing
secrets belong in secure release configuration, never in this repository or chat.

Strict secure-directory handles remain the package default. On a filesystem/provider without them,
the host may explicitly choose `TRUSTED_LOCAL` for a cooperative directory; the library never
silently falls back. A native macOS secure-handle adapter remains an architectural option, not an
implemented or approved exception. Current macOS demonstrations explicitly choose the local policy.
