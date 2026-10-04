# M3 performance verification

The final library and harness were measured from clean commit
`db036d4627e834deb215110bdb750d891f848a1a` on 2026-10-04, before M4 integration.
The independently recomputed 180-file fingerprint is
`db5909910a4db6891d4d4326a38969bebe96d1e94c4652bc23aa7826fa7acc1a`.
Normein stays at `0.3.0` / `0a3ae1de844c92635fbbc03406a13cb0e8920c03`.

Both harnesses ran sequentially, without other local build, browser-test or server jobs.
Each operation used five warmups and ten measured repetitions. All 390 samples are
retained. A separate Python calculation checked the 39 summaries against raw CSV:
median, nearest-rank p95, extrema, sample counts, heap peaks and heap increases.
Later commits preserve the original frozen CSV bytes across Git checkouts; they do
not change the measured Kotlin source fingerprint.

This is the same device, scenario sizes, Java 21, Kotlin 2.2.20, pinned kernel and
2 GiB G1 heap used for [M2](performance-m2.md). XLSX reading now explicitly allows
300 seconds, because retained M2 tail samples exceeded the new M3 default request
limit of 60 seconds. This changes the request ceiling, not numeric expectations or
the reference-device performance envelopes. These measurements do not promise
portable CI timings or hard real-time deadlines.

## Original scenarios

The five scenarios retain 300 samples and 30 summaries. All 15,265 calculated
coordinates matched independent arithmetic and actual cached XLSX values, with
zero fallbacks and zero evaluation errors.

| Combined scenario operation | Median ms | p95 ms |
| --- | ---: | ---: |
| plan | 445.439 | 454.418 |
| calculate | 636.591 | 646.875 |
| calculate-audit | 770.466 | 776.687 |
| explain | 637.293 | 646.021 |
| paper | 44.973 | 52.981 |
| xlsx | 37640.363 | 38148.429 |

Combined XLSX median improved from M2's 46,047.663 ms to 37,640.363 ms (18.26%).
The ten M3 samples range from 37,404.324 to 38,148.429 ms. The run does not
reproduce M2's three retained long tails; it does not establish their cause or
remove the need for M6 investigation. Combined calculation median is 2.19% above
M2; its audit-to-value-only ratio is 1.21, within the 1.5 reference envelope.

The complete [environment](../benchmarks/baselines/m3-macos-aarch64/environment.txt),
[300 raw samples](../benchmarks/baselines/m3-macos-aarch64/samples.csv),
[30 summaries](../benchmarks/baselines/m3-macos-aarch64/summary.csv) and five
verification reports are archived.

## Continuous periods and incremental requests

The unchanged fixture has 200 series and ten generated years. Independent
closed-form checks cover 6,200 scalar coordinates and 633 global/fixed reductions:
first opening 2,010,000; last closing 2,043,000; cumulative movement 33,000.
Matrix and transpose papers/workbooks contain the same 6,633/6,833 and 603/6,203
addressed values as M2. Both exports have zero fallbacks and zero evaluation errors.
Standard bounded audit options retain the real `MANTRA-AUDIT-TRUNCATED` warning.

| Period operation | Median ms | p95 ms |
| --- | ---: | ---: |
| calculate | 96.075 | 98.370 |
| calculate-audit | 154.043 | 157.854 |
| explain | 93.505 | 97.684 |
| paper-matrix | 42.433 | 43.835 |
| paper-transpose | 17.950 | 18.598 |
| xlsx-matrix | 21454.960 | 22346.406 |
| xlsx-transpose | 12332.433 | 12656.896 |
| session-recalc | 36.632 | 39.893 |
| session-repeat | 11.498 | 12.573 |

The actual edit executes 41 tasks and ten formulas, reuses 12,161 tasks,
invalidates 41 tasks, keeps two execution sessions and avoids full rebuilding.
Every unchanged repeat executes zero tasks and zero formulas, reuses 12,202 tasks
and keeps the same two sessions. This preserves physical reuse while M3 still
charges actual input/domain/projection work; zero formula executions do not mean
zero host work.

Period calculation is 25.42% slower than M2; editing is 36.632 ms versus 11.450 ms,
and repeating is 11.498 ms versus 6.655 ms. These are visible regressions, although
all remain within the fixed envelopes. Cumulative runtime/read controls and the
expanded evidence projection add work; no profiling attribution was performed,
so this is an implementation-context explanation, not a measured causal breakdown.
M6 must retain these comparisons when reviewing performance.

The complete [environment](../benchmarks/baselines/m3-periods-macos-aarch64/environment.txt),
[90 raw samples](../benchmarks/baselines/m3-periods-macos-aarch64/samples.csv),
[nine summaries](../benchmarks/baselines/m3-periods-macos-aarch64/summary.csv) and
[independent values and physical counters](../benchmarks/baselines/m3-periods-macos-aarch64/verification.txt)
are archived.

## Existing envelopes

All M2 reference-device envelopes pass without widening them: combined calculation
median ≤1,500 ms, audit ratio ≤1.5, XLSX median ≤60,000 ms and p95 ≤150,000 ms;
period calculation median ≤250 ms, audit ratio ≤2, edit median ≤100 ms and repeat
formula executions zero; matrix XLSX median/p95 ≤40,000/60,000 ms and transpose
≤20,000/30,000 ms. Maximum summed heap-pool peak is 570.54 MiB, below 1 GiB.
Heap-pool peaks are neither allocations nor process RSS.
