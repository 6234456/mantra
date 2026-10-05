# Public-kernel performance — reference-device acceptance

On **2026-10-05**, the public-kernel `1.0.0-rc.1` runs passed all unchanged performance budgets:
**390 retained samples / 39 groups**, plus **10,000 distinct streaming cases / 1,120,000 exact
numeric comparisons**. These are actual measurements on the public `normein-dsl:0.3.0` artifact,
with separate standard-library verification. They do not announce a stable Mantra Maven release.

The measured source is `a1e6e65a01aa39c792681a138beda31ab66af172`. Its
[implementation CI](https://github.com/6234456/mantra/actions/runs/37275776080) passed; the archived
[receipt](../benchmarks/baselines/public-kernel-macos-aarch64/verification/implementation-ci.json)
records 103 frontend tests, 98 Python build-gate tests, 13 browser flows and browser cleanup.
That CI receipt is separate from the reference-device timings. No hosted Central workflow or
Mantra Central upload was executed.

## Runtime and source identity

The [source/runtime verification](../benchmarks/baselines/public-kernel-macos-aarch64/source-runtime-verification.json)
confirms engine `1.0.0-rc.1`, Normein `0.3.0`, equal complete before/after source inventories and
all **25 unchanged installed runtime JARs**. The actual public Normein JAR SHA-256 is
`83a101ac90ae0c2a50bdc9a4b103d1c3de015df8bcbcafa4f63bbe5aa1562cc8`. A publisher Git commit is not inferred from that artifact;
the environment's `normeinSourceBaselineCommit` is the optional development baseline, not a claim
about the runtime JAR's source commit.

The [before](../benchmarks/baselines/public-kernel-macos-aarch64/source-before.json) and
[after](../benchmarks/baselines/public-kernel-macos-aarch64/source-after.json) source records contain
**213 identical entries**, with library/harness SHA-256
`cbfd26a246033d6e016415a6eca49459801b372d04bc25038200669f6bf54661`. This established fingerprint includes sorted `.kt`
sources and tests in core, render, Excel and benchmarks, excluding build directories, plus raw
`normein-build.lock`; it hashes each relative UTF-8 path, NUL and original bytes. It is not a hash
of every repository file. The historical v1 archive has its own 212-entry identity and remains
unchanged. Installed runtime metadata also records Kotlin 2.2.20 and POI 5.5.1.

The [public artifact integration](normein-publication.md) records its identity/replay differences
from the earlier source-built kernel and the separate functional gates. Neither this report nor
the new archive replaces [the original v1 report](performance-v1.md), its `ae9af84` measurements,
the original source tag or any historic raw bytes.

## Method and execution window

Both [original](../benchmarks/baselines/public-kernel-macos-aarch64/original/environment.txt) and
[period](../benchmarks/baselines/public-kernel-macos-aarch64/periods/environment.txt) runs used
Mac OS X 27.0/aarch64, 10 available processors, Azul JDK 21.0.12.1 and:

```text
-Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8
```

There are five original fixtures, each measured for six operations, and one
200-series × 10-generated-year-period fixture measured for nine operations. Each group has five
warmups and ten measured repetitions: 300 original plus 90 period samples. Original fixture sizes
remain 25/5/25, 250/5/25, 25/50/25, 25/5/1000 and 250/50/1000 (lines/members/table rows).
The measured operations, independent numbers, rounding and timing scopes were not changed to fit
these results. The explicit XLSX read ceiling remains 300 seconds and does not widen a time budget.

The [execution log](../benchmarks/baselines/public-kernel-macos-aarch64/measurement-execution.txt)
records sequential task-owned processes in UTC:

| Process | Start | Completion |
| --- | --- | --- |
| Original | 2026-10-05 07:13:39.388121 | 07:28:36.567413 |
| Periods | 2026-10-05 07:28:36.567677 | 07:38:47.011515 |
| Streaming batch | 2026-10-05 07:38:47.011946 | 07:39:08.488190 |

No competing task-owned build, browser, server or benchmark was started. Existing unrelated user
JVMs were preserved; the [aggregate observation](../benchmarks/baselines/public-kernel-macos-aarch64/verification/process-start-observation.json)
recorded 11 Java executables. This is a reference-device run, not controlled whole-machine
isolation or a portable CI latency guarantee. Process-window timestamps include startup and are
distinct from each operation's stopwatch.

The independent [CSV verification](../benchmarks/baselines/public-kernel-macos-aarch64/performance-independent.json)
checks exact group sets, dimensions, repetition IDs 1..10, all summaries and unchanged budgets.
Median is the mean of the two central samples; p95 uses nearest rank and is the maximum of these
ten observations. Raw times have 0.001-ms precision; median validation allows 0.001 ms for the
summary's original nanoseconds, while extrema/p95 and integer counts match exactly. No sample or
outlier was removed. Heap figures sum JVM heap-pool peaks after reset, with GC requested before
each sample outside measured time. They are not simultaneous whole-heap use, allocations or RSS.

## Unchanged budgets and actual results

| Acceptance | Unchanged bound | Actual public-kernel result | Verdict |
| --- | --- | --- | --- |
| Combined calculate median | ≤1,500 ms | 755.425 ms | Pass |
| Combined audit/calculate median ratio | ≤1.5 | 1.14548 | Pass |
| Combined XLSX median / p95 | ≤60,000 / 150,000 ms | 46144.430 / 47627.847 ms | Pass |
| Period calculate median | ≤250 ms | 134.029 ms | Pass |
| Period audit/calculate median ratio | ≤2 | 1.42027 | Pass |
| Period actual edit median | ≤100 ms | 36.429 ms | Pass |
| Every unchanged repeat's evaluated tasks / formulas | Exactly 0 / 0 | 0 / 0 in all ten samples | Pass |
| Period matrix XLSX median / p95 | ≤40,000 / 60,000 ms | 23224.216 / 23671.607 ms | Pass |
| Period transpose XLSX median / p95 | ≤20,000 / 30,000 ms | 13358.734 / 14978.414 ms | Pass |
| Every group's maximum sum of heap-pool peaks | ≤1 GiB | 789,737,784 bytes (753.153 MiB), overall maximum | Pass |
| Batch distinct IDs / independent values | 10,000 / 1,120,000 | 10,000 / 1,120,000 | Pass |
| Batch declared timed work | ≤300 seconds | 21.030 seconds | Pass |
| Batch summed heap-pool peaks | ≤1 GiB | 95,573,904 bytes (91.146 MiB) | Pass |
| Batch runtime plan compilations / VALUE_ONLY evidence | Exactly 0 / 0 | 0 / 0 | Pass |
| Batch session lifecycle | Opens = successful closes; attempts explicit | 22 opens / 22 attempts / 22 successful closes | Pass |

The period maximum is 502,101,536 bytes (478.841 MiB). All 39 groups and the batch
meet the same heap ceiling. Both the archive and independent verifier retain their actual input
and source hashes; `engineExecuted: false` describes the verifier, not the separately run engine.

## Original fixtures: all 30 groups

These are the frozen [summary](../benchmarks/baselines/public-kernel-macos-aarch64/original/summary.csv)
and [300 raw samples](../benchmarks/baselines/public-kernel-macos-aarch64/original/samples.csv).
Each row has ten measured samples; times are milliseconds and the final column is peak MiB.

| Fixture | Operation | Median | p95 | Minimum | Maximum | Peak MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| small | plan | 63.418 | 68.808 | 59.694 | 68.808 | 269.138 |
| small | calculate | 70.153 | 73.334 | 66.783 | 73.334 | 273.112 |
| small | calculate-audit | 72.298 | 74.657 | 69.443 | 74.657 | 280.118 |
| small | explain | 63.105 | 64.391 | 61.685 | 64.391 | 273.142 |
| small | paper | 3.415 | 4.140 | 2.521 | 4.140 | 17.147 |
| small | xlsx | 50.869 | 61.202 | 43.712 | 61.202 | 51.193 |
| lines | plan | 512.834 | 573.721 | 492.598 | 573.721 | 571.279 |
| lines | calculate | 556.538 | 648.604 | 531.720 | 648.604 | 537.065 |
| lines | calculate-audit | 605.617 | 665.784 | 587.657 | 665.784 | 599.179 |
| lines | explain | 551.249 | 612.622 | 533.035 | 612.622 | 601.150 |
| lines | paper | 8.429 | 12.077 | 7.757 | 12.077 | 40.940 |
| lines | xlsx | 481.517 | 520.869 | 471.347 | 520.869 | 256.956 |
| members | plan | 53.119 | 54.552 | 51.700 | 54.552 | 273.653 |
| members | calculate | 67.562 | 69.484 | 66.646 | 69.484 | 294.655 |
| members | calculate-audit | 105.843 | 111.319 | 98.103 | 111.319 | 329.714 |
| members | explain | 66.612 | 68.255 | 65.340 | 68.255 | 294.658 |
| members | paper | 5.743 | 7.116 | 5.571 | 7.116 | 31.659 |
| members | xlsx | 444.875 | 448.801 | 439.656 | 448.801 | 223.662 |
| table-rows | plan | 53.829 | 57.649 | 52.460 | 57.649 | 273.578 |
| table-rows | calculate | 62.030 | 70.823 | 61.025 | 70.823 | 291.579 |
| table-rows | calculate-audit | 67.213 | 76.806 | 65.576 | 76.806 | 297.580 |
| table-rows | explain | 62.159 | 70.195 | 60.640 | 70.195 | 291.582 |
| table-rows | paper | 1.085 | 2.380 | 1.035 | 2.380 | 20.583 |
| table-rows | xlsx | 320.673 | 363.647 | 316.927 | 363.647 | 342.092 |
| combined | plan | 500.450 | 542.391 | 486.331 | 542.391 | 549.409 |
| combined | calculate | 755.425 | 795.880 | 711.146 | 795.880 | 620.659 |
| combined | calculate-audit | 865.324 | 988.731 | 826.094 | 988.731 | 543.230 |
| combined | explain | 739.621 | 798.835 | 709.610 | 798.835 | 753.153 |
| combined | paper | 50.819 | 52.030 | 50.542 | 52.030 | 170.733 |
| combined | xlsx | 46144.430 | 47627.847 | 44230.751 | 47627.847 | 574.109 |

Before timing, independent arithmetic and cached XLSX values matched **15,265 coordinates**
across the five fixtures, with zero fallbacks and zero evaluation errors. The Combined fixture's
independent answer is 514,543.75 and its workbook has 13,838 formula cells. Each fixture retains
its actual verification text and addressed paper snapshot under `original/<fixture>/`.

## Continuous periods: all nine groups

These are the frozen [summary](../benchmarks/baselines/public-kernel-macos-aarch64/periods/summary.csv)
and [90 raw samples](../benchmarks/baselines/public-kernel-macos-aarch64/periods/samples.csv).
Units, heap method and sample count match the original table.

| Operation | Median | p95 | Minimum | Maximum | Peak MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| calculate | 134.029 | 144.959 | 118.739 | 144.959 | 220.818 |
| calculate-audit | 190.358 | 213.672 | 170.548 | 213.672 | 380.842 |
| explain | 101.314 | 104.859 | 97.171 | 104.859 | 214.844 |
| paper-matrix | 44.196 | 57.751 | 42.130 | 57.751 | 145.845 |
| paper-transpose | 18.538 | 18.730 | 18.084 | 18.730 | 89.847 |
| xlsx-matrix | 23224.216 | 23671.607 | 22850.678 | 23671.607 | 478.841 |
| xlsx-transpose | 13358.734 | 14978.414 | 13018.293 | 14978.414 | 460.209 |
| session-recalc | 36.429 | 42.617 | 16.165 | 42.617 | 72.639 |
| session-repeat | 10.641 | 11.385 | 10.166 | 11.385 | 71.635 |

The [independent fixture verification](../benchmarks/baselines/public-kernel-macos-aarch64/periods/verification.txt)
checks 6,200 scalar coordinates and 633 global/fixed reductions: first opening 2,010,000, final
closing 2,043,000 and cumulative movement 33,000. Matrix checks cover 6,633 addressed paper values
and 6,833 XLSX values, with 6,908 formula cells; transpose checks cover 603 paper values and 6,203
XLSX values, with 6,230 formula cells. Both exports have zero fallbacks/errors. Standard bounded
audit retains `MANTRA-AUDIT-TRUNCATED`; it does not change the independent numeric checks.

Every edit sample changes only S1 seed by +10/+11: 41 evaluated/invalidated tasks, 12,161 reused
tasks, ten formula evaluations, no full rebuild and two retained execution sessions. Every repeat
submits unchanged facts: zero evaluated/invalidated tasks and formulas, 12,202 reused tasks, no
full rebuild and the same two sessions. Reused tasks mean already-completed retained tasks, not
cache-hit calls; execution sessions mean retained sessions, not new opens. A zero-work repeat
still pays request/snapshot/reader-control costs.

## Actual 10,000-case batch

The [actual summary](../benchmarks/baselines/public-kernel-macos-aarch64/batch/summary.json) and
[independent streamed verification](../benchmarks/baselines/public-kernel-macos-aarch64/batch/independent-verification.json)
record 10,000 distinct completed/successful cases, all 112 numeric keys per case, no technical or
business-validation failures, **21,030 ms** and **95,573,904 summed heap-pool peak bytes**.
The independent verifier reads the actual/reference pair one record at a time and compares exact
finite Decimal values and ID/key sets without running the engine or generating new expectations.

The compiled template initially created 22 syntax, semantic and execution plans for 22 formulas.
Across the stream, all 22 sessions opened, attempted close and closed successfully. Actual runtime
counts are 560,000 row cycles, physical row preparations and logical expression evaluations,
22 frame allocations, zero execution-plan compilations and zero VALUE_ONLY evidence
materializations. The synchronous consumer retains the current record/result only.

The [frozen reference manifest](../benchmarks/baselines/public-kernel-macos-aarch64/batch/reference-manifest.json)
binds fictional Decimal/Fraction facts to legacy-exact schema `ifrs.ifrs16/lessee-schedule@0.1`,
currency scale two and no parameter overrides. The
[producer digest check](../benchmarks/baselines/public-kernel-macos-aarch64/batch/reference-producer-verification.json)
confirms the original scripts and reference bytes; nothing was regenerated from engine output.
The reference stream is 44,589,112 bytes with SHA-256
`715049290ac763f1c578abf4325fefd944453fc8c0fcaba793f7ab929105961d`. Actual output SHA-256 is
`ea7456d8e9dddecbbe7fc4c9f489fbca1337677b5b7c0222147796b2e1b5315a`, equal to the old v1 run's actual stream. Captured package revision is
`c5cd3b249c13c1b69aca9a491f21417aaaad0dd66f4ccc574ca2db82044cf0fb`. Actual/reference JSONL remain identified build artifacts rather
than duplicate large archive files.

The batch invocation used `-Xms128m -Xmx512m -XX:+UseG1GC -Dfile.encoding=UTF-8`, public package
loading, explicit `--directory-policy trusted-local`, `--engine-version 1.0.0-rc.1` and
`--max-seconds 300`. Strict handles remain the library default; trusted-local assumes a cooperative
directory and does not defend against malicious same-permission rename races. The timer includes
template compilation, typed input iteration, calculation, public value/reduction reads, independent
comparisons and streaming writes. Package capture, initial reference hashing/generation, JVM
startup and the final post-run reference hash check are outside that timer. This is one observed
batch run, not a median/p95 distribution or core-only throughput.

## Comparison boundaries and profiling

The old [source-kernel archive](../benchmarks/baselines/v1-macos-aarch64/README.md) and this new
public-kernel run use the same fixtures, arithmetic and budgets. Selected actual times in ms:

| Operation | Old source-kernel median / p95 | Public-kernel median / p95 |
| --- | ---: | ---: |
| Combined calculate | 635.138 / 645.028 | 755.425 / 795.880 |
| Combined calculate-audit | 766.831 / 774.669 | 865.324 / 988.731 |
| Combined xlsx | 37457.241 / 38414.915 | 46144.430 / 47627.847 |
| Period calculate | 95.639 / 102.326 | 134.029 / 144.959 |
| Period calculate-audit | 153.659 / 158.120 | 190.358 / 213.672 |
| Period xlsx-matrix | 21434.900 / 22025.717 | 23224.216 / 23671.607 |
| Period xlsx-transpose | 13021.726 / 13100.636 | 13358.734 / 14978.414 |
| Period session-recalc | 12.071 / 12.492 | 36.429 / 42.617 |
| Period session-repeat | 11.737 / 12.946 | 10.641 / 11.385 |

Higher observed public-run times include Combined calculation/XLSX and period edit recalculation;
all remain inside the original ceilings. They are retained, not dismissed as outliers. The public
JAR has real identity/replay differences and this checkout has runtime metadata changes; no fresh
profile or controlled isolated experiment establishes why these observed timings differ.
Unrelated user JVMs were preserved. Old benchmark flags also explicitly set country/language/
variant properties, whereas the new recorded benchmark flags above do not.

The old batch recorded 22,777 ms and 333,767,296 bytes; this run records 21,030 ms and 95,573,904
bytes. The new invocation explicitly uses a 128 MiB initial heap; the earlier evidence guarantees
its 512 MiB maximum but does not establish matching startup flags. Different run context and
heap settings preclude a causal speedup or memory-reduction claim from these two observations.
The same exact output stream is stronger evidence of unchanged results than a timing comparison.

**No new JFR was recorded for the public kernel.** The separately documented
[old diagnostic recording](performance-v1.md#independent-jfr-recording-and-analysis) retains its
original source/runtime identities and sampling/privacy qualifications. Its observations cannot
be assigned to this new run or used to explain the public-run change or historical M2 tails.

## Retained bytes and reproduction

The [archive](../benchmarks/baselines/public-kernel-macos-aarch64/README.md) retains raw CSV,
environments, summaries, verification text, source/runtime inventories, method wrapper and small
batch/CI/signing receipts. Its manifest records the original evidence bytes; historic M2/M3/M4/v1
archives were not rewritten. Selected current evidence SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| Original raw samples | `760006cc376e8679b9acb481d680b42070b6e6ffa676d84e97ddced5a01e3b33` |
| Original summary | `ea67cbb91df1b0239fbfe7ca810f8a1583260cca68e0e17fe68351497ddd1b35` |
| Period raw samples | `2a7d5eaa9618c0a3b2718c0434efc53b8f96418f8e46fdf2a8075d5d2613fb01` |
| Period summary | `4f5c563b6f5d50535b0190e9afebc9a9a46c92e8451aa4ff9b684fd3615c1b34` |
| Source before and after inventories | `a9cc4d2b6046ab99c01e04a72292033d768754faf2c12662093b611429c41a63` |
| Runtime before and after inventories | `73f42d16aaf028df697fd0091944ce71f038f6df44752bd729b93a043c0521d8` |

Recompute CSV summaries/budgets without an engine:

```sh
python3 scripts/verify-performance.py \
  --original benchmarks/baselines/public-kernel-macos-aarch64/original \
  --periods benchmarks/baselines/public-kernel-macos-aarch64/periods
```

The [archived wrapper](../benchmarks/baselines/public-kernel-macos-aarch64/verifiers/run-public-kernel-measure.py)
records exact launch commands and pre/post captures. It targets the coordinator's checkout and
requires clean source and new output directories; it is evidence of the executed method, not a
portable benchmark installer. Reproduction needs a public-default build/install for the chosen
revision and fresh output directories, retaining all old bytes. The
[archive verifier notes](../benchmarks/baselines/public-kernel-macos-aarch64/verifiers/README.md)
explain its full source/runtime and 1,120,000-value checks separately from JVM measurement.

Stable Mantra publication and production secrets remain explicitly deferred. Canonical local
TEST-key preparation has its [own evidence](central-publication.md), not a public release receipt.
