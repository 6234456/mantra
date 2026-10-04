# v1 performance acceptance — draft awaiting the final run

Status: preparation only. The final v1 source has not been measured by this review. Values below are
frozen acceptance envelopes or explicitly labelled historical results. Do not convert the draft into
an accepted result until the final reference-device runs, independent comparisons and profiling
artifacts exist.

The reference platform is the existing macOS/aarch64 machine, Azul JDK 21.0.12.1, Kotlin 2.2.20 and
Normein 0.3.0 at its pinned lock commit. Run original/period harnesses with a 2 GiB G1 heap; the
10,000-case batch retains its 512 MiB heap. Run sequentially without Gradle compilation, browser
tests, servers or another benchmark competing for this device. Record OS/JDK/CPU, JVM flags, exact
source/kernel identities, warmup/repetition counts and read ceilings. These are reference-device
engineering bounds, not portable CI latency or hard real-time promises.

## Scope and unchanged envelopes

There are **39 scenario/operation groups**, rather than 39 different input scenarios: the original
five fixture sizes each have six operations (30 groups), and the 200-series/10-period fixture has
nine operations. Five warmups and ten measured repetitions per group produce **390 retained
samples**. The five original fixtures stay 25/5/25, 250/5/25, 25/50/25, 25/5/1000 and 250/50/1000
(lines/members/table rows); fixture sizes, formulas, rounding and numeric expectations stay fixed.

| Acceptance | Existing frozen bound |
| --- | ---: |
| Combined calculate median | ≤1,500 ms |
| Combined audit/calculate median ratio | ≤1.5 |
| Combined XLSX median / nearest-rank p95 | ≤60,000 / 150,000 ms |
| Period calculate median | ≤250 ms |
| Period audit/calculate median ratio | ≤2 |
| Period actual edit median | ≤100 ms |
| Every unchanged repeat's tasks/formulas | Exactly 0 / 0 |
| Period matrix XLSX median / p95 | ≤40,000 / 60,000 ms |
| Period transpose XLSX median / p95 | ≤20,000 / 30,000 ms |
| Maximum observed sum of heap-pool peaks | ≤1 GiB |
| Batch distinct IDs / independent values | 10,000 / 1,120,000 |
| Batch total declared timed work | ≤300 seconds |
| Batch runtime plan compilations / VALUE_ONLY evidence | Exactly 0 / 0 |
| Batch kernel session lifecycle | Opens = successful closes; attempts reported separately |

XLSX's explicit read duration remains 300 seconds, as already documented in M3. It is a request
ceiling; it does not enlarge the performance envelopes. No bound is derived retrospectively from a
newly successful run. Any proposed exception must be recorded as an unresolved acceptance change.

## Source and data freeze

1. Finish functional checks and the six library ABI/clean-consumer gates. Freeze the Kotlin library
   and benchmark sources before measurement. Record revision and dirty-tree state honestly.
2. Capture the existing harness fingerprint independently: sorted `.kt` files in mantra-core,
   mantra-render, mantra-excel and benchmarks excluding every build directory, plus the raw
   normein-build.lock. Feed each relative UTF-8 path, NUL and original bytes to SHA-256. Both harness
   environment files must report the same fingerprint. Retain a per-file hash inventory as well.
   This fingerprint includes tests by the existing harness definition; it must not be relabelled a
   main-source-only hash. Capture other library/package/batch source hashes separately where relevant.
3. Record every runtime classpath JAR digest and kernel content identity when interpreting a profile
   or batch. Re-check the library/harness source inventory after both measurement processes stop.
4. Freeze output CSV/verification bytes and their SHA-256 immediately. Preserve original line endings
   in storage; parse them with a standard CSV reader. Do not normalize or regenerate historic samples.
   A docs-only edit may follow the measured source freeze, but report its different publication commit.

The independent `scripts/performance-source-fingerprint.py` independently mirrors the existing harness algorithm and emits a
file inventory. Run it before/after from the same repository root and compare the full records.

## Actual rerun commands

Build/install before entering the exclusive measurement window. The commands below are instructions
for the parent to execute; they were not run in this review. Use a new final-output directory so
historic baseline bytes remain untouched.

```sh
./gradlew --no-daemon --max-workers=1 :benchmarks:installDist

MANTRA_JDK21=/Users/qiouyang/Library/Java/JavaVirtualMachines/azul-21.0.12.1/Contents/Home
"$MANTRA_JDK21/bin/java" -Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 \
  -cp 'benchmarks/build/install/mantra-benchmark/lib/*' \
  com.xqiou.mantra.benchmarks.PerformanceBaselineKt \
  --output benchmarks/build/v1-performance --warmup 5 --repetitions 10

"$MANTRA_JDK21/bin/java" -Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 \
  -cp 'benchmarks/build/install/mantra-benchmark/lib/*' \
  com.xqiou.mantra.benchmarks.PeriodPerformanceBaseline \
  --out benchmarks/build/v1-period-performance --warmup 5 --repetitions 10

python3 scripts/verify-performance.py \
  --original benchmarks/build/v1-performance \
  --periods benchmarks/build/v1-period-performance
```

Use exactly the same locale settings as the archived M3 JVM when strict cross-run environment parity
is required (`-Duser.country=DE -Duser.language=zh -Duser.variant=`). Numeric CSV formatting explicitly
uses Locale.ROOT, but the complete environment still belongs in the report.

The original harness independently checks 15,265 coordinate values against arithmetic and the
actual cached XLSX values before timing. The period harness checks 6,200 scalar coordinates and 633
reductions plus matrix/transpose addressed paper/workbook values. Verification must retain zero
fallback/error requirements and genuine bounded-audit truncation warnings. The edit toggles only S1
seed by +10/+11. `reusedTasks` means completed tasks retained before the request, not cache-hit calls;
`executionSessions` means sessions retained, not opens. Fresh reader/request control and snapshot
work still run when formulas/tasks are zero.

The independent CSV verifier requires all expected groups, exact repetition IDs 1..10 and every
summary. It recomputes arithmetic medians, nearest-rank p95, minima/maxima, sample counts, peak and
increase bytes. At n=10 nearest-rank p95 is the largest sample; it is not an interpolated percentile.
Median comparison permits 0.001 ms because raw samples are published to 0.001 ms while the harness
summary uses original nanosecond timings. Other published extrema/p95 and integer counts are exact.
No outlier is removed. Whole-run summed heap-pool peaks are not simultaneous heap usage, allocations
or resident memory; keep the requested-GC/pre-sample/reset method in the environment evidence.

## Batch rerun

Keep the independent Decimal/Fraction producer and its source hashes. Generate/verify the 10,000
reference JSONL before the timed JVM; do not read engine output into the expected producer. Invoke
the existing `:apps:ifrs-leases:leaseBatchDemo` with its 512 MiB heap, explicit trusted-local policy
for this cooperative local directory, `--max-seconds 300`, actual current engine version and fresh
output paths. Strict handles remain the library default; the example's policy is an explicit host
choice. Verify all 112 values per distinct case again in a separate Python stream, including ID/key
sets, raw JSONL digests, successful-session counts and frozen budgets.

The declared timer includes template construction, typed decoding, input iteration, engine
execution, public coordinate/reduction reads, independent comparisons and streaming output writes.
It excludes package capture, producer/reference generation and initial hash validation, and JVM
startup. One observed 10,000-case run is one run, not a synthetic median/p95 distribution.

## XLSX profiling

The existing original harness has `--scenarios` but no `--operations` selector. Attaching a JFR
startup recording to `--scenarios combined` captures planning/calculation/explain/paper too. Do not
call such a process recording an isolated XLSX measurement.

A separate Java diagnostic consumer uses the unchanged public SyntheticFixture/Scenario and public
Mantra/Excel APIs. It verifies audited results and actual workbook values, performs one export
warmup outside recording, then starts JDK JFR only around a full Combined XLSX workbook build,
POI recalculation, serialization and close. No production/harness source is changed. Its source is
**uncompiled and unexecuted** until the parent runs:

```sh
mkdir -p /private/tmp/mantra-v1-jfr/classes
"$MANTRA_JDK21/bin/javac" -cp 'benchmarks/build/install/mantra-benchmark/lib/*' \
  -d /private/tmp/mantra-v1-jfr/classes \
  benchmarks/profile/CombinedXlsxProfile.java
"$MANTRA_JDK21/bin/java" -Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 \
  -cp '/private/tmp/mantra-v1-jfr/classes:benchmarks/build/install/mantra-benchmark/lib/*' \
  CombinedXlsxProfile /private/tmp/mantra-v1-jfr/combined-xlsx.jfr
"$MANTRA_JDK21/bin/jfr" summary /private/tmp/mantra-v1-jfr/combined-xlsx.jfr
"$MANTRA_JDK21/bin/jfr" view hot-methods /private/tmp/mantra-v1-jfr/combined-xlsx.jfr
"$MANTRA_JDK21/bin/jfr" view allocation-by-site /private/tmp/mantra-v1-jfr/combined-xlsx.jfr
"$MANTRA_JDK21/bin/jfr" view gc-pauses /private/tmp/mantra-v1-jfr/combined-xlsx.jfr
"$MANTRA_JDK21/bin/jfr" print --json --stack-depth 64 \
  --events jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.GarbageCollection,jdk.GCPhasePause,jdk.FileWrite \
  /private/tmp/mantra-v1-jfr/combined-xlsx.jfr > /private/tmp/mantra-v1-jfr/selected-events.json
```

The already-installed JDK 21 `jfr help print/view` was checked; it includes those views. Sampling and
allocation estimates are evidence, not an exact causal partition. This is a diagnostic profile with
one warmup/one recorded export, separate from the five-warmup/ten-repetition acceptance distribution.
JFR overhead must not be folded into the unprofiled 390 samples. Retain the binary recording, summary,
selected-stack/event analysis and profiler source/classpath hashes. Any new bottleneck claim must cite
actual stacks/events. M2's retained 98–127 second tails are not reproduced or explained by a current
single profile, and must not be discarded or attributed without evidence.

## Evidence to fill after actual v1 execution

| Evidence | Final result |
| --- | --- |
| Measured source revision / fingerprint / kernel identity | Pending |
| Actual environment and exclusive execution window | Pending |
| Original 300 samples / 30 recomputed summaries | Pending |
| Period 90 samples / 9 recomputed summaries | Pending |
| Raw byte digests and after-run source freeze comparison | Pending |
| Independent exact values / fallbacks / errors | Pending |
| Same-case repeat and changed-seed physical counters | Pending |
| Combined timing and allocation/JFR stacks | Pending |
| Actual 10,000-case stream / second independent comparison | Pending |
| Unchanged envelopes pass or unresolved regression | Pending |

For context only, M3's archived Combined XLSX median/p95 were 37,640.363/38,148.429 ms; M4's historical
10,000-case stream took 20,875 ms with 22 initial plans, 22 opened/successfully closed sessions,
560,000 physical evaluations and zero runtime compilations/evidence materializations. The independent
TMP verifier recomputed the existing M3 390 raw samples/39 summaries in this read-only task; that
validates historical CSV consistency and bounds, not current v1 runtime performance.
