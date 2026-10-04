# v1 RC performance acceptance — measured results and diagnostic JFR

The **1.0.0-rc.1 unprofiled performance and streaming-batch checks passed every unchanged
acceptance envelope** on 2026-10-04. The original and continuous-period processes retained all
390 samples; a separate Python verifier recomputed all 39 summaries. The separate Combined XLSX
JFR recording and identity checks also passed; its sampled findings are reported below. These are
candidate measurements, not a stable
v1.0 completion declaration or a claim of public Maven publication.

The measured source is `ae9af84bde3f82cc86787e908aab7be76ddecee9`. The coordinator's
[candidate CI run](https://github.com/6234456/mantra/actions/runs/37220953941) passed. The JVM runs
and their frozen raw artifacts are actual executions; this report update used only Python
standard-library inspection/statistics and did not rerun an engine, build or benchmark.

## Source, runtime and reference device

The [runtime identity](../benchmarks/baselines/v1-macos-aarch64/v1-runtime-identity.json) records
engine `1.0.0-rc.1`, a clean measured source tree, a clean Normein tree at lock commit
`0a3ae1de844c92635fbbc03406a13cb0e8920c03`, and all 25 benchmark-runtime JAR digests. The runtime
contains Normein 0.3.0, Kotlin 2.2.20 and POI 5.5.1. The batch kernel identity is
`application-code:mantra.calc:2` with content SHA-256
`1e653d9873d1c8c9620ff316a8157a464b27f585549bf3391ba3994c09822943`.

The complete [before](../benchmarks/baselines/v1-macos-aarch64/v1-source-before.json) and
[after](../benchmarks/baselines/v1-macos-aarch64/v1-source-after.json) inventories are byte-for-byte
equal: 212 files and source SHA-256
`c41f47cfe0abbe316e4460f15866814502e10c0bbffe18fda6d954ca407ed59f`.
The existing fingerprint includes sorted `.kt` sources **and tests** in mantra-core, mantra-render,
mantra-excel and benchmarks, excluding build directories, plus raw `normein-build.lock`; it hashes
each relative UTF-8 path, NUL and original bytes. It is not a main-source-only fingerprint or a hash
of every repository file. The [independent source/runtime verification](../benchmarks/baselines/v1-macos-aarch64/v1-source-runtime-verification.json)
also confirms unchanged runtime JARs, matching environment identities and the pinned clean kernel.

Both [original](../benchmarks/baselines/v1-macos-aarch64/original/environment.txt) and
[period](../benchmarks/baselines/v1-macos-aarch64/periods/environment.txt) environments record Mac OS X
27.0/aarch64, 10 available processors, Azul OpenJDK 21.0.12.1, a 2 GiB maximum heap and:

```text
-Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8
-Duser.country=DE -Duser.language=zh -Duser.variant=
```

The coordinator ran the processes sequentially after build/install, without a competing build,
browser test, server or benchmark. Original metadata was captured at 17:35:28.079 UTC and its
summary was written at 17:46:36.325 UTC; period metadata was captured at 17:46:36.480 UTC and its
summary was written at 17:55:56.517 UTC. These timestamps describe this reference-device window,
not portable CI latency or hard real-time promises. The earlier batch used a 512 MiB heap.

A later documentation commit does not change the measured source identity. The Java diagnostic
profiling consumer is separate from the measured library and `.kt` harness fingerprint; its later
privacy revision has its own recording identity below. The old compiled, unrecorded profiler hashes
in `v1-runtime-identity.json` do not identify the actual diagnostic recording.

## Scope and unchanged envelopes

There are **39 scenario/operation groups**, not 39 different input scenarios: five original fixture
sizes with six operations each (30 groups), plus the 200-series/10-generated-year-period fixture
with nine operations. Each group has five warmups and ten retained measured repetitions,
producing **300 original + 90 period = 390 samples**. The original fixtures stay 25/5/25, 250/5/25,
25/50/25, 25/5/1000 and 250/50/1000 (lines/members/table rows). Formulas, rounding, numeric
expectations, timing scope and the following bounds were not changed to fit the observations.

| Acceptance | Unchanged bound | Actual result | Verdict |
| --- | --- | --- | --- |
| Combined calculate median | ≤1,500 ms | 635.138 ms | Pass |
| Combined audit/calculate median ratio | ≤1.5 | 1.20735 | Pass |
| Combined XLSX median / nearest-rank p95 | ≤60,000 / 150,000 ms | 37,457.241 / 38,414.915 ms | Pass |
| Period calculate median | ≤250 ms | 95.639 ms | Pass |
| Period audit/calculate median ratio | ≤2 | 1.60666 | Pass |
| Period actual edit median | ≤100 ms | 12.071 ms | Pass |
| Every unchanged repeat's evaluated tasks / formulas | Exactly 0 / 0 | 0 / 0 in all ten samples | Pass |
| Period matrix XLSX median / p95 | ≤40,000 / 60,000 ms | 21,434.900 / 22,025.717 ms | Pass |
| Period transpose XLSX median / p95 | ≤20,000 / 30,000 ms | 13,021.726 / 13,100.636 ms | Pass |
| Maximum observed sum of heap-pool peaks | ≤1 GiB | 600,391,712 bytes (572.578 MiB) | Pass |
| Batch distinct IDs / independent values | 10,000 / 1,120,000 | 10,000 / 1,120,000 | Pass |
| Batch total declared timed work | ≤300 seconds | 22.777 seconds | Pass |
| Batch runtime plan compilations / VALUE_ONLY evidence materializations | Exactly 0 / 0 | 0 / 0 | Pass |
| Batch kernel session lifecycle | Opens = successful closes; attempts separately reported | 22 opens / 22 attempts / 22 successful closes | Pass |

XLSX's explicit read duration remains 300 seconds, the request ceiling already documented in M3.
It did not enlarge any performance envelope. The period peak is 497,599,736 bytes (474.548 MiB);
the batch peak is 333,767,296 bytes (318.305 MiB). All are below the same 1 GiB acceptance ceiling.

The [independent CSV report](../benchmarks/baselines/v1-macos-aarch64/v1-performance-independent.json)
verifies exact group sets, fixture dimensions, repetition IDs 1..10 and every summary. It recomputes
arithmetic medians, nearest-rank p95, extrema, sample counts, peak and increase bytes. With ten
samples, nearest-rank p95 is the largest sample. Median comparison permits 0.001 ms because raw
samples are published to 0.001 ms while the harness summary uses original nanoseconds; all other
published extrema/p95 and integer counts compare exactly. No sample or outlier was removed.

Heap-pool peaks are summed after reset, with GC requested before each sample outside measured time.
They are neither simultaneous whole-heap usage nor allocations or process RSS. Batch likewise
reports summed per-pool peaks, rather than a simultaneous peak. Sampling and allocator conclusions
require the independent JFR evidence below.

## Actual original measurements

All values below come from the frozen [30-group summary](../benchmarks/baselines/v1-macos-aarch64/original/summary.csv).
Each row has ten samples; time columns are milliseconds and the final column is summed heap-pool
peak MiB. The original [300 raw samples](../benchmarks/baselines/v1-macos-aarch64/original/samples.csv)
retain heap-before/after and increase evidence as well.

| Fixture | Operation | Median | p95 | Minimum | Maximum | Peak MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| small | plan | 59.522 | 66.238 | 53.830 | 66.238 | 265.094 |
| small | calculate | 62.942 | 66.505 | 60.654 | 66.505 | 269.120 |
| small | calculate-audit | 63.707 | 69.796 | 62.090 | 69.796 | 276.147 |
| small | explain | 57.392 | 68.447 | 55.611 | 68.447 | 269.161 |
| small | paper | 2.316 | 2.926 | 1.990 | 2.926 | 13.165 |
| small | xlsx | 47.222 | 54.214 | 45.055 | 54.214 | 47.201 |
| lines | plan | 452.579 | 463.315 | 441.159 | 463.315 | 368.221 |
| lines | calculate | 490.388 | 500.945 | 479.094 | 500.945 | 367.305 |
| lines | calculate-audit | 544.944 | 565.528 | 534.889 | 565.528 | 367.410 |
| lines | explain | 484.365 | 504.032 | 475.759 | 504.032 | 367.359 |
| lines | paper | 8.616 | 9.923 | 7.470 | 9.923 | 37.826 |
| lines | xlsx | 418.191 | 434.427 | 411.197 | 434.427 | 252.843 |
| members | plan | 49.177 | 50.589 | 48.177 | 50.589 | 269.606 |
| members | calculate | 61.477 | 62.255 | 59.950 | 62.255 | 290.610 |
| members | calculate-audit | 90.337 | 96.674 | 89.645 | 96.674 | 325.700 |
| members | explain | 59.452 | 60.088 | 59.079 | 60.088 | 290.613 |
| members | paper | 4.909 | 5.034 | 4.791 | 5.034 | 28.614 |
| members | xlsx | 389.457 | 407.934 | 379.922 | 407.934 | 219.619 |
| table-rows | plan | 50.274 | 50.917 | 48.646 | 50.917 | 269.542 |
| table-rows | calculate | 55.942 | 56.669 | 55.274 | 56.669 | 287.544 |
| table-rows | calculate-audit | 60.043 | 64.369 | 59.205 | 64.369 | 294.545 |
| table-rows | explain | 55.925 | 56.967 | 55.652 | 56.967 | 287.546 |
| table-rows | paper | 0.919 | 0.947 | 0.879 | 0.947 | 16.548 |
| table-rows | xlsx | 282.820 | 294.291 | 279.745 | 294.291 | 337.556 |
| combined | plan | 453.259 | 457.619 | 434.965 | 457.619 | 525.563 |
| combined | calculate | 635.138 | 645.028 | 622.910 | 645.028 | 494.483 |
| combined | calculate-audit | 766.831 | 774.669 | 754.390 | 774.669 | 464.162 |
| combined | explain | 635.034 | 643.527 | 621.425 | 643.527 | 504.097 |
| combined | paper | 44.570 | 45.678 | 42.979 | 45.678 | 171.705 |
| combined | xlsx | 37457.241 | 38414.915 | 31156.041 | 38414.915 | 572.578 |

Before timing, the public engine and cached XLSX verification matched **15,265 independently
calculated coordinate values** across the five fixtures. Each retained fixture verification reports
zero fallbacks and zero evaluation errors; the Combined export has 13,838 formula cells and the
independent answer 514,543.75. Verification files and addressed paper snapshots are retained under
`original/<fixture>/` in the archive.

## Actual continuous-period measurements

These are the frozen [nine-group summary](../benchmarks/baselines/v1-macos-aarch64/periods/summary.csv)
and [90 raw samples](../benchmarks/baselines/v1-macos-aarch64/periods/samples.csv). Units and
per-row sample count match the original table.

| Operation | Median | p95 | Minimum | Maximum | Peak MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| calculate | 95.639 | 102.326 | 94.082 | 102.326 | 210.834 |
| calculate-audit | 153.659 | 158.120 | 150.910 | 158.120 | 377.727 |
| explain | 93.600 | 95.062 | 90.796 | 95.062 | 210.868 |
| paper-matrix | 53.654 | 59.772 | 44.521 | 59.772 | 141.869 |
| paper-transpose | 18.042 | 18.469 | 17.729 | 18.469 | 85.871 |
| xlsx-matrix | 21434.900 | 22025.717 | 21097.457 | 22025.717 | 474.548 |
| xlsx-transpose | 13021.726 | 13100.636 | 12353.532 | 13100.636 | 455.902 |
| session-recalc | 12.071 | 12.492 | 11.597 | 12.492 | 67.661 |
| session-repeat | 11.737 | 12.946 | 10.447 | 12.946 | 67.657 |

The [verification](../benchmarks/baselines/v1-macos-aarch64/periods/verification.txt) independently
checks 6,200 scalar coordinates and 633 global/fixed reductions. First opening is 2,010,000,
last closing 2,043,000 and cumulative movement 33,000. Matrix checks cover 6,633 addressed paper
values and 6,833 XLSX values, with 6,908 formula cells; transpose checks cover 603 paper values
and 6,203 XLSX values, with 6,230 formula cells. Both exports report zero fallbacks/errors.
Standard bounded audit retains the real `MANTRA-AUDIT-TRUNCATED` warning; the warning does not
alter numeric verification.

Every actual edit sample toggles only S1 seed by +10/+11 and records 41 evaluated/invalidated tasks,
12,161 reused tasks, ten formula evaluations, no full rebuild and two retained execution sessions.
Every unchanged repeat records zero evaluated/invalidated tasks and formula evaluations, 12,202
reused tasks, no full rebuild and the same two sessions. Repeat median/p95 is 11.737/12.946 ms:
zero formula work does not imply zero snapshot/request/reader control cost. `reusedTasks` means
completed tasks retained before the request, not cache-hit calls; `executionSessions` means
retained sessions, not new session opens.

## Actual 10,000-case streaming batch

The [actual summary](../benchmarks/baselines/v1-macos-aarch64/batch/summary.json) records 10,000
requested/completed/successful distinct cases, 1,120,000 exact numeric comparisons, zero technical
or validation failures and no batch failure. Elapsed declared work is **22,777 ms** and summed
heap-pool peaks **333,767,296 bytes**. The [second independent verification](../benchmarks/baselines/v1-macos-aarch64/batch/independent-verification.json)
read the actual/reference streams without running the engine, checked all 112 numeric values per
case plus exact ID/key sets, and retained the same actual JSONL digest.

The template initially compiled 22 syntax/semantic/execution plans for 22 formulas. Across the
stream there were 560,000 row cycles, physical row preparations and logical expression evaluations,
22 frame allocations, zero runtime plan compilations and zero VALUE_ONLY evidence materializations.
All 22 opened kernel sessions closed successfully in 22 close attempts. The one-case-at-a-time
consumer retained only the current record/result; these counters describe actual work and are not
synthetically amortized per-case timing claims.

The [reference manifest](../benchmarks/baselines/v1-macos-aarch64/batch/reference-manifest.json)
pins the frozen fictional Decimal/Fraction producer and exact legacy schema `ifrs.ifrs16/lessee-schedule`
version `0.1`, no parameter overrides and currency scale two. Its 44,589,112-byte JSONL has SHA-256
`715049290ac763f1c578abf4325fefd944453fc8c0fcaba793f7ab929105961d`.
Actual output SHA-256 is
`ea7456d8e9dddecbbe7fc4c9f489fbca1337677b5b7c0222147796b2e1b5315a`;
captured package revision is
`c5cd3b249c13c1b69aca9a491f21417aaaad0dd66f4ccc574ca2db82044cf0fb`.

The actual host invocation supplied `--package-root apps/ifrs-leases`, engine version
`1.0.0-rc.1`, explicit `trusted-local` policy and `--max-seconds 300`; the Gradle JavaExec task
retained its 512 MiB maximum heap. Strict handles remain the library default. Trusted-local is a
cooperative directory policy, not protection against same-permission malicious rename races.
The timer includes template construction, typed decoding/input iteration, engine execution,
public coordinate/reduction reads, independent comparisons and streaming writes. It excludes
package capture, producer/reference generation, initial hash validation, JVM startup and Gradle
startup. This is one observed batch run, not a median/p95 distribution or core-only throughput.

## Raw-byte and source evidence

The [archive manifest](../benchmarks/baselines/v1-macos-aarch64/archive-manifest.json) pins the retained
measurement files; this report's standard-library check confirmed every declared byte length/digest.
Raw CSV line endings and historic M2/M3/M4 files were not normalized or regenerated.

| Artifact | SHA-256 |
| --- | --- |
| Original samples | `1c8bc00f592abea9716abce1ef9b812d070eb1f7433829ed26875d738253ed36` |
| Original summary | `a1e3aacb70a5cecf008b3e74b6642386a49a01a78320be4f4689818d60e9d98b` |
| Period samples | `1a07197acf0aec188634833461acfd3129a1aee01d387a4dd72b387502a27a4c` |
| Period summary | `3d8a2c000a64b980c6086cbf99cb0c6ae46825a98c1be2a881990d3b3df0cfed` |
| Entire source-before inventory | `008e267564f03f2bafa58150dba15322867897fa8ee1c7ac159f26eec0e46a3d` |
| Entire source-after inventory | `008e267564f03f2bafa58150dba15322867897fa8ee1c7ac159f26eec0e46a3d` |

For historical context, M3 Combined XLSX median/p95 were 37,640.363/38,148.429 ms; the current
37,457.241/38,414.915 ms shows a slightly lower median and slightly higher p95. M4's historical
10,000-case stream was 20,875 ms, compared with current 22,777 ms. Both current checks remain
inside unchanged bounds; no causal explanation or broad speedup claim follows from those
observations. Retained M2 98–127-second tails remain historical evidence and cannot be discarded
or explained by a later single diagnostic recording.

## Reproduction of the unprofiled runs

These are the source-aligned commands for the measured candidate. Build/install precedes the
exclusive JVM measurement window. Use fresh output directories for another run; retain historic
bytes. The following is documentation of the coordinator's real paths/options, not a command
executed during this report update.

```sh
./gradlew --no-daemon --max-workers=1 :benchmarks:installDist

MANTRA_JDK21=/Users/qiouyang/Library/Java/JavaVirtualMachines/azul-21.0.12.1/Contents/Home
"$MANTRA_JDK21/bin/java" -Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 \
  -Duser.country=DE -Duser.language=zh -Duser.variant= \
  -cp 'benchmarks/build/install/mantra-benchmark/lib/*' \
  com.xqiou.mantra.benchmarks.PerformanceBaselineKt \
  --output benchmarks/build/v1-performance --warmup 5 --repetitions 10

"$MANTRA_JDK21/bin/java" -Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 \
  -Duser.country=DE -Duser.language=zh -Duser.variant= \
  -cp 'benchmarks/build/install/mantra-benchmark/lib/*' \
  com.xqiou.mantra.benchmarks.PeriodPerformanceBaseline \
  --out benchmarks/build/v1-period-performance --warmup 5 --repetitions 10

python3 scripts/verify-performance.py \
  --original benchmarks/build/v1-performance \
  --periods benchmarks/build/v1-period-performance
```

The independent verifier reads CSV/statistics only. Its `engineExecuted: false` means it did not
rerun the engine; the separately executed harnesses and streaming batch supply actual results.

## Independent JFR recording and analysis

**The diagnostic recording and identity verification passed.** This is one Combined XLSX export,
recorded after the 390 unprofiled measurements using unchanged production code, `.kt` harness and
runtime JARs through public APIs. Its diagnostic Java consumer was revised only to suppress
private process metadata. The recorded operation took **41,029.463375 ms** and produced
**663,684 workbook bytes**; the binary JFR is **855,219 bytes**. Recording began at
2026-10-04 18:00:24 UTC and its summary reports 41 seconds. Neither this operation nor recording
startup contributes a sample to the unprofiled distributions above.

The archived [consumer source](../benchmarks/baselines/v1-macos-aarch64/profile/CombinedXlsxProfile.java)
uses public SyntheticFixture/Scenario and Mantra/Excel APIs. It verifies actual audited/workbook
values and warms one export before recording, then records a complete XLSX build, POI
recalculation, serialization to bytes and close. Its stopwatch begins after `recording.start()`;
recording dump occurs after `recording.stop()`. The original harness has no operation-only
selector: a startup JFR of `--scenarios combined` would include other operations and would not
identify an isolated XLSX profile.

The [recording identity](../benchmarks/baselines/v1-macos-aarch64/profile/identity-privacy-verification.json)
and [JDK summary](../benchmarks/baselines/v1-macos-aarch64/profile/summary.txt) identify this actual
recording. The independent [recording verifier](../scripts/verify-profile-recording.py) checks the
binary/source digests and event inventory against a JDK summary; CI checks archived evidence and
compiles the public Java consumer, without taking fresh performance timings on shared runners.

| Recorded identity | SHA-256 |
| --- | --- |
| Privacy-revised Java source | `44bce2ed8da563fa632fbee39954e12e6f3b23746c49ae8c0f2f3bcea3ea608a` |
| Actual compiled consumer class | `fac73486daaae983e3e13b1be5d905bca39e80f30409d5d120008a5d1ff749d1` |
| Binary recording | `477fdec404eec8535fdbded7f28872dddd297b9a03ce963390cd8d63dbe71b12` |

There are **2,812 ExecutionSample**, **1,774 ObjectAllocationSample**, **5 GarbageCollection** and
**5 GCPhasePause** events. Each of the six deliberately suppressed metadata event types has zero
records: InitialEnvironmentVariable, InitialSystemProperty, InitialSecurityProperty, SystemProcess,
ProcessStart and JVMInformation. This is the verified scope of metadata suppression, not a claim
that every possible JFR field has been anonymized. FileWrite also has zero records: this consumer
serializes workbook bytes in memory and dumps the recording after stopping it.

Python's standard-library streaming decoder inspected the selected events one at a time. The
409,193,793-byte JSON export used at most 1,230,518 buffered characters; it was not loaded as a
single in-memory document. The retained [analysis](../benchmarks/baselines/v1-macos-aarch64/profile/analysis.json)
records full representative method stacks, event counts, sampled weights and GC durations.
The large derived JSON is not needed in the archive: the pinned
[binary recording](../benchmarks/baselines/v1-macos-aarch64/profile/combined-xlsx.jfr) can reproduce
selected events with JDK `jfr print --json --events`.

### CPU samples: named-range resolution during formula parsing

The [hot-method view](../benchmarks/baselines/v1-macos-aarch64/profile/hot-methods.txt) reports
`XmlObjectBase.get_store` as the leaf in **2,426 samples (86.2731%)**. Examining caller stacks gives
a more specific observation: **2,768 samples (98.4353%)** include POI's
`BaseXSSFEvaluationWorkbook.getName` while parsing formula names. These stacks split as follows;
percentages use all 2,812 CPU samples as denominator.

| Named-range caller observed in stack | CPU samples | Share |
| --- | ---: | ---: |
| POI recalculation, obtaining/parsing formula tokens | 1,844 | 65.5761% |
| Formula installation through `XSSFCell.setFormula` | 924 | 32.8592% |
| Combined named-range lookup stacks | 2,768 | 98.4353% |

For example, one exact stack appears 519 times: XMLBeans `get_store` → `CTDefinedNameImpl.getName`
→ `XSSFName.getNameName` → POI `getName` → `FormulaParser` → `XSSFCell.setFormula` → Mantra's
`ExcelFormulaWriter.writeValuesAndFormulas` → `ExcelWorkbookBuilder.build`. Other dominant stacks
reach the same lookup/parser through POI's evaluator. This makes repeated named-range resolution
in formula parsing a concrete target for a later isolated optimization experiment. It does not
assign 98.4353% of wall time to one method, establish the cost of core calculation, or explain
historic M2 timing tails. Inclusive method shares overlap and must not be added together.

### Allocation samples: separate recorder startup from export stacks

The [allocation-site view](../benchmarks/baselines/v1-macos-aarch64/profile/allocation-by-site.txt)
reports total sampled allocation weight of **7,589,228,280 bytes**. This weight estimates allocation
pressure between samples; it is neither exact allocated bytes nor a heap/RSS peak. The largest
leaf site, ASM `Frame.merge`, is one event with weight 5,422,938,376 (71.4557% of all weight).
Its full stack runs through JFR `EventInstrumentation`, class retransformation,
`PlatformRecorder.start`, `Recording.start` and the diagnostic `main`. It therefore belongs to
recording startup, which precedes the export stopwatch, and cannot be presented as an engine
allocation bottleneck.

Four stacks contain JFR recorder-control/instrumentation frames, with combined weight
5,422,977,232 (71.4562%). Removing that explicitly observed category leaves sampled weight
**2,166,251,048 bytes** across 1,770 events. The remaining exclusive stack categories are below.
A category means a matching caller occurred in the captured stack; it is not precise ownership
of all allocated objects or a fully isolated timed-export allocation total.

| Remaining stack category | Events | Sampled weight (bytes) | Share of remaining weight |
| --- | ---: | ---: | ---: |
| Other stacks containing Mantra Excel export frames | 1,039 | 833,573,528 | 38.4800% |
| XMLBeans serialization | 432 | 724,195,632 | 33.4308% |
| Other or absent stack | 147 | 507,232,616 | 23.4152% |
| Other POI formula-evaluation stacks | 150 | 100,202,768 | 4.6256% |
| POI named-range resolution | 2 | 1,046,504 | 0.0483% |

The retained site evidence includes XMLBeans namespace emission/buffer resizing, regex matcher
construction and map-node allocation. These are sampled follow-up leads, not a demonstrated
memory leak. Stack truncation occurred in 439 of the 4,586 sampled CPU/allocation events, and
sampling cannot provide exact allocation attribution. The measured peak-heap acceptance above
continues to use the original independent heap-pool observations.

### GC pauses and limits of inference

The [GC-pause view](../benchmarks/baselines/v1-macos-aarch64/profile/gc-pauses.txt) and selected events
contain five G1New evacuation pauses, with durations **6.736125, 9.487917, 10.105333, 11.963125 and
15.264750 ms**. Their sum is **53.557250 ms**, median 10.105333 ms and maximum 15.264750 ms.
The sum is 0.130534% of the 41,029.463375-ms timed export. GarbageCollection and GCPhasePause
entries describe the same five collections; this sum counts only the pause entries once.
The observed pause total does not support GC as a dominant cost in this recording.

This single diagnostic export is not a timing distribution. Its sampling/recording overhead is
outside all 390 acceptance samples, its startup allocation weights require the qualification
above, and its findings cannot explain or invalidate older retained tails. Stable Maven
publication remains separate from the measured candidate and verified diagnostic evidence
reported here.
