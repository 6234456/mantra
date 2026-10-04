# M2 performance and initial reference budgets

The final M2 measurement uses clean source `ab82017fd52a58335253918c77451ae0789070cd`,
Normein 0.3.0 at the unchanged lock, Azul Java 21.0.12.1, Kotlin 2.2.20, Mac OS X 27 aarch64,
ten processors and `-Xms512m -Xmx2g -XX:+UseG1GC`. Both harnesses use five warmups and ten
measured repetitions per operation. The independently recomputed 133-file source fingerprint is
`0f647019926e511fecd61ff3cb335010bbed2c458f5b56905ed298c2f31725cc`.

All 300 original-scenario samples and 90 continuous-period samples are retained, including slow
observations. Raw CSV medians, nearest-rank p95, extrema and heap statistics were independently
recomputed. With ten repetitions, p95 is the largest observation. Heap figures sum reset JVM
heap-pool peaks; they are neither process RSS nor allocations. The measurement contract and
earlier baselines are described in [the baseline report](performance-baseline.md).

## Original scenarios

All 15,265 calculated coordinates agree with independent arithmetic and cached XLSX values.
Every workbook has zero formula fallbacks and zero evaluation errors. Median wall times, in ms:

| Scenario | plan | calculate | audit | Explain | paper | XLSX |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| small | 56.311 | 63.728 | 65.227 | 58.024 | 1.973 | 45.273 |
| lines | 457.600 | 487.902 | 532.328 | 476.763 | 5.365 | 405.047 |
| members | 46.931 | 56.319 | 86.047 | 55.489 | 4.143 | 395.251 |
| table-rows | 47.574 | 53.277 | 58.422 | 53.771 | 0.789 | 270.185 |
| combined | 449.414 | 622.965 | 762.680 | 643.855 | 38.245 | 46,047.663 |

Compared with M1, the combined ordinary calculation median is 49.84% lower. Audit adds 22.43%
to ordinary calculation within this run. Paper is 25.42% slower and XLSX is 15.36% slower than
M1. M2 emits additional aggregation evidence and live validation helpers. These end-to-end
measurements do not isolate their individual cost or establish a profiler attribution.

The first M2 source, `fe5bf8e`, produced a combined XLSX median of 66,716.924 ms. Review found
repeated scoped reduction construction and unconditional per-term guards. The generic optimization
caches reduction expressions within one workbook and uses live range SUM where the complete scope
is unconditional. Editable guards and parent relationships retain dynamic formulas. The final
median is 30.98% lower than the first M2 result; the first result is preserved in
[its archive](../benchmarks/baselines/m2-before-reduction-optimization/README.md).

The final combined XLSX p95 is **127,299.156 ms**, versus 46,957.530 ms in M1. Repetitions 5–7
took 98,535.726, 127,299.156 and 102,120.412 ms; all remain in the archive. The harness records
wall time and heap observations, but no GC/JIT or concurrent process CPU attribution. The slow
tail is unresolved; it is not discarded as noise or claimed as an explained regression. Maximum
XLSX heap-pool peak is 593.43 MiB; ordinary calculation peaks at 494.93 MiB.

The complete [environment](../benchmarks/baselines/m2-macos-aarch64/environment.txt),
[300 samples](../benchmarks/baselines/m2-macos-aarch64/samples.csv),
[30 summaries](../benchmarks/baselines/m2-macos-aarch64/summary.csv) and all five independent
verification reports are archived. The run took 16 minutes 24 seconds.

## 200 series × 10 continuous years

The period fixture independently checks 6,200 scalar coordinates and 633 global/fixed reductions.
Its global first opening is 2,010,000, final closing 2,043,000 and cumulative movement 33,000.
The ordinary matrix has 6,633 addressed paper values and 6,833 XLSX values; the transpose has
603 addressed paper values and 6,203 XLSX values. Both have zero fallbacks and evaluation errors.
Audit truncation is reported explicitly and does not alter values.

| Operation | Final median ms | Final p95 ms | First M2 median ms |
| --- | ---: | ---: | ---: |
| calculate | 76.602 | 85.437 | 74.320 |
| audit | 138.697 | 145.060 | 127.839 |
| Explain | 76.541 | 80.367 | 70.416 |
| paper matrix | 28.169 | 32.588 | 45.490 |
| paper transpose | 14.842 | 15.023 | 15.601 |
| XLSX matrix | 23,647.098 | 42,043.899 | 21,372.639 |
| XLSX transpose | 10,993.835 | 11,391.069 | 12,496.673 |
| edited session | 11.450 | 19.792 | 16.728 |
| unchanged session | 6.655 | 7.036 | 14.830 |

Changing only S1's opening evaluates 41 tasks, retains 12,161 completed tasks, invalidates 41,
executes ten formulas in two retained execution sessions and avoids a full rebuild. Repeating
unchanged facts executes zero tasks and formulas and retains 12,202 tasks. These are observed
runtime counters, not timings used to infer reuse. Matrix XLSX median is 10.64% higher than
the first M2 run; its tail remains variable. No isolated causal attribution is claimed.

The complete [environment](../benchmarks/baselines/m2-periods-macos-aarch64/environment.txt),
[90 samples](../benchmarks/baselines/m2-periods-macos-aarch64/samples.csv),
[nine summaries](../benchmarks/baselines/m2-periods-macos-aarch64/summary.csv) and
[independent verification](../benchmarks/baselines/m2-periods-macos-aarch64/verification.txt)
are archived. This run took 10 minutes 48 seconds; the first period run is retained in
[its separate archive](../benchmarks/baselines/m2-periods-before-reduction-optimization/README.md).

## Initial budgets for the reference device

M0 had no hard timing budget. The following explicit envelopes are established from the recorded
M0–M2 observations with headroom for subsequent work; they are initial reference-device budgets,
not a claim that M0 passed pre-existing limits or a portable CI promise. M4 must add a distinct
compiled-plan/batch budget. M6 must review export variability and the unresolved tail before
turning reference envelopes into a stable release performance contract.

| Workload and metric | Budget | Final M2 observation |
| --- | ---: | ---: |
| original combined calculate median | ≤ 1,500 ms | 622.965 ms |
| original combined audit / ordinary median | ≤ 1.5 × | 1.2243 × |
| original combined XLSX median / observed p95 | ≤ 60 s / 150 s | 46.048 s / 127.299 s |
| period calculate median / audit-to-ordinary ratio | ≤ 250 ms / 2 × | 76.602 ms / 1.8106 × |
| period edited session median | ≤ 100 ms | 11.450 ms |
| period unchanged session formula executions | 0 | 0 |
| period matrix XLSX median / observed p95 | ≤ 40 s / 60 s | 23.647 s / 42.044 s |
| period transpose XLSX median / observed p95 | ≤ 20 s / 30 s | 10.994 s / 11.391 s |
| maximum JVM heap-pool peak, either harness | ≤ 1 GiB | 593.43 MiB |
| all baseline XLSX fallbacks / evaluation errors | 0 / 0 | 0 / 0 |

The broad export envelope preserves the observed tail honestly; meeting it does not resolve the
latency issue. Re-run the same clean-source fixture and settings when changing execution or export
behavior. Compilation and other test jobs must complete before collecting measurements. The
benchmark JVMs and single-use Gradle daemons were verified stopped after both runs.

```sh
./gradlew --no-daemon --max-workers=1 :benchmarks:run --args='--output benchmarks/build/m2-optimized-performance --warmup 5 --repetitions 10'
./gradlew --no-daemon --max-workers=1 :benchmarks:runPeriods --args='--out benchmarks/build/m2-optimized-period-performance --warmup 5 --repetitions 10'
```
