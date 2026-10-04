# Performance baseline

M0 establishes a repeatable measurement, not a performance promise. The `:benchmarks` project is a
separate consumer of the public calculation, view, paper and spreadsheet APIs. It is repository
tooling and is not a published library or a domain application.

Run the public API integration fixture and then the measurements from the repository root:

```sh
scripts/bootstrap-normein.sh
./gradlew --no-daemon :benchmarks:test
./gradlew --no-daemon :benchmarks:run --args='--warmup 5 --repetitions 10'
```

The JVM toolchain is Java 21. The measurement process uses `-Xms512m -Xmx2g -XX:+UseG1GC`.
The pinned Normein checkout must be clean and match `normein-build.lock`; the benchmark never
changes the kernel or selects an unpinned candidate. A private Normein checkout needs the same
read-only access as the normal build. See [contributing](../CONTRIBUTING.md) for checkout options.

Use an idle machine, complete compilation before collecting comparison runs, and avoid concurrent
test or build jobs. Do not compare results from different machines, JVMs or heap settings as if they
were an engine regression. The baseline is intentionally excluded from timing-sensitive CI gates.
The small integration fixture runs in the ordinary JVM test suite without timing assertions.

## Workload and independent verification

The generator creates a matrix with one dimension, a table input, a chain of decimal expressions per
member and a scalar summary. Every line uses the preceding line, so this includes dependency
planning and member alignment rather than isolated constant expressions. The first line is
`basis × 1.25`; each following line adds one. Member `m` supplies basis `m + 1`, and table row `r`
supplies amount `r + 1`. Independently computed expectations are:

```text
step(i, m) = (m + 1) × 1.25 + i
tableSum = rows × (rows + 1) / 2
memberSum = Σ step(lines - 1, m)
answer = tableSum + memberSum
```

Before measuring a scenario, the harness checks every calculated coordinate against that arithmetic,
checks Explain, writes Text and HTML, and checks the cached XLSX cell for every calculated coordinate
against the same expectations. The exporter also creates presentation cross-total formulas; the
coordinate counts below refer to canonical calculation values. The workbook report records formula
fallbacks and evaluation errors. A failed
calculation, numeric comparison, export evaluation error or formula fallback aborts the current
harness run: this baseline requires every calculated value to remain recalculable in XLSX. The
initial run below predates that requirement and retains its explicit fallback reports.

| Scenario | Lines per member | Members | Input table rows | Calculated coordinates |
| --- | ---: | ---: | ---: | ---: |
| small | 25 | 5 | 25 | 128 |
| lines | 250 | 5 | 25 | 1,253 |
| members | 25 | 50 | 25 | 1,253 |
| table-rows | 25 | 5 | 1,000 | 128 |
| combined | 250 | 50 | 1,000 | 12,503 |

Each axis changes independently before the combined scenario. The generator deliberately stays
within the pinned syntax and workbook limits: 1–400 lines, 1–100 members and 1–1,000 table rows.
Exports are capped at 16 sheets, 250,000 cells and 64 MiB of encoded XLSX. This is not a large-table
or batch benchmark: those belong to M4, where compiled plans can be reused across cases.

## Measurement contract

| Operation | Timed public API and included work |
| --- | --- |
| plan | `Mantra.inspect(schema, case)`: compilation, dependency order and detached public view; parsing is excluded |
| calculate | `Mantra.calculate(schema, case)`: planning, evaluation and detached result/view; parsing is excluded |
| calculate-audit (M1) | `Mantra.calculateForAudit(schema, case)`: the same calculation plus bounded source-level trace capture for all coordinates |
| explain | `Mantra.calculateForExplain(...)`: planning and full calculation, plus bounded source-level trace for the final member line |
| paper | `Render.paper(result, layout)`: paper construction and full audit appendix from an existing result; HTML/Text encoding and disk I/O are excluded |
| xlsx | `ExcelExport.workbook(...)` plus `bytes()`: complete paper, formulas, POI recalculation, XLSX encoding and workbook close; calculation and disk I/O are excluded |

Starting in M1, paper and XLSX use an existing audit result. Their construction includes the captured
source-level evidence and XLSX snapshot-status formulas. M0 exporters used an ordinary calculation
result, so an export-time difference includes this additional output. The ordinary `calculate`,
`plan` and single-target `explain` contracts remain comparable. Audit capture uses the default
`AuditOptions`: at most 2,000 formulas, 100,000 events, 16,384 projected steps and 1,000,000
characters. Large scenarios show explicit truncation; this does not change calculated values.

There is no public reusable compiled-plan API in M0, so `calculate` includes planning and `explain`
does not measure only the cost of tracing one expression. `plan` also includes making the immutable
public structure snapshot. This contract prevents misleading comparisons with future M4 APIs.

Scenarios run in the listed order in one JVM. Every operation warms up separately, then runs ten
measured repetitions. Later scenarios can benefit from earlier JIT compilation; compare the same
scenario order, or run an individual scenario separately when investigating a change. Wall time comes from
`System.nanoTime()`. CSV summaries contain median, nearest-rank p95, minimum and maximum;
with ten samples p95 is the slowest observation, not a precise tail-latency estimate.

Before each measured sample the previous output reference is cleared, GC is requested outside
the timed interval and heap-pool peaks are reset. The result stays reachable until the end of the
sample. Reported memory is the sum of JVM heap-pool peaks and its increase over the before-sample
heap. Pool peaks can occur at different instants. These numbers include retained source fixtures and
the existing result used by exporters; they are neither total allocations, retained heap after a full
GC, nor process RSS. GC activity during an operation is part of its wall time.

## Output and comparison

The default output directory is `benchmarks/build/performance/`:

- `environment.txt`: UTC timestamp, JVM/OS/CPU count, heap settings, Kotlin version, Mantra revision,
  dirty-checkout flag, pinned kernel version/commit and a source fingerprint.
- `samples.csv`: all measured wall times and before/peak/after heap observations.
- `summary.csv`: one row per scenario and operation.
- `<scenario>/`: generated `schema.mantra` and `case.mantra`, `paper.txt`, `paper.html`, `paper.xlsx`
  and `verification.txt` with expectations and export fallbacks.

Use `--output <directory>` to keep successive runs separate and
`--scenarios small,lines,members,table-rows,combined` to choose a subset. Warmup and repetition
counts can be changed with `--warmup` and `--repetitions`; keep those counts and JVM settings fixed
when comparing a milestone to this baseline. Build artifacts and generated papers are ignored by
Git; the recorded M0 CSV and environment metadata are retained under `benchmarks/baselines/`.

## Recorded M0 baseline

The final baseline used clean revision `fc268811642f40682502cd0f6cc2c20cebd0d536`, starting at
`2026-10-03T22:50:43.292222Z` (2026-10-04 in Europe/Berlin). It used Azul OpenJDK 21.0.12.1,
Kotlin 2.2.20, Mac OS X 27.0 on aarch64, ten available processors and the JVM settings above.
The kernel was `normein-dsl` 0.3.0 at
`0a3ae1de844c92635fbbc03406a13cb0e8920c03`.

All five scenarios passed independent numeric verification with zero formula fallbacks and zero
evaluation errors. That covers 15,265 calculated coordinates across the five scenarios; the largest
scenario contains 12,503 coordinates and the workbook contains 12,753 formula cells, including
presentation cross-totals. Source fingerprint independently recomputed from the 68 committed
Kotlin/lock files:
`b382105c98240a7117258c4dc617f0b3d5420760fc755069288f32e85d838f30`.

The complete records are retained as [environment metadata](../benchmarks/baselines/m0-macos-aarch64/environment.txt),
[all 250 samples](../benchmarks/baselines/m0-macos-aarch64/samples.csv) and
[25 summaries](../benchmarks/baselines/m0-macos-aarch64/summary.csv). Each scenario also has its
`verification.txt` in the same directory. Medians and nearest-rank p95 were independently checked
against the raw samples after the run.

Median wall times in milliseconds:

| Scenario | plan | calculate | explain | paper | xlsx |
| --- | ---: | ---: | ---: | ---: | ---: |
| small | 61.250 | 64.477 | 59.372 | 2.150 | 24.567 |
| lines | 462.369 | 536.730 | 536.300 | 6.660 | 323.189 |
| members | 50.383 | 76.040 | 76.650 | 5.381 | 250.609 |
| table-rows | 50.801 | 58.794 | 58.333 | 1.954 | 30.802 |
| combined | 456.914 | 1,268.880 | 1,255.582 | 52.650 | 26,825.940 |

Maximum heap-pool peak increase over the before-sample heap, in MiB, using the method above:

| Scenario | plan | calculate | explain | paper | xlsx |
| --- | ---: | ---: | ---: | ---: | ---: |
| small | 250.00 | 257.00 | 257.00 | 1.00 | 11.00 |
| lines | 343.26 | 307.17 | 343.22 | 12.00 | 68.00 |
| members | 250.00 | 306.77 | 306.76 | 11.00 | 54.00 |
| table-rows | 250.00 | 271.00 | 271.00 | 4.00 | 35.00 |
| combined | 366.03 | 312.91 | 312.81 | 117.00 | 353.68 |

The combined XLSX p95 was 27,629.119 ms, with a maximum heap-pool peak of 398.01 MiB. The complete
run took 9 minutes 11 seconds. XLSX construction, recalculation and encoding for 12,503 calculation
coordinates remain the dominant cost at this scale; M4 can use this workload to assess export scaling.
No budget or regression threshold is set until repeat runs establish the machine's variability.

The generated schemas, cases, Text, HTML and XLSX files remain under
`benchmarks/build/performance-final/`. The task-owned benchmark JVM and single-use Gradle daemon
were both verified stopped after completion. Reproduce this run from the recorded revision with:

```sh
./gradlew --no-daemon :benchmarks:run --args='--warmup 5 --repetitions 10 --output benchmarks/build/performance-final'
```

## Export limit discovered during measurement

The preceding clean run at `c62a2fb6742a653d8093f42a7ded1c3f0203e319` started at
`2026-10-03T22:17:17.798269Z` with the same workload and machine settings. `table-rows` and
`combined` each reported one explicit fallback for `table-sum`: the generated `SUM` had 1,000
arguments, and POI rejected it with a 255-argument limit. Cached values matched the independent
arithmetic and there were zero evaluation errors, but changing a source table cell would not
recalculate that fallback. This exposed the generic export issue fixed before the final run.

The original [environment](../benchmarks/baselines/m0-before-large-sum-fix/environment.txt),
[250 samples](../benchmarks/baselines/m0-before-large-sum-fix/samples.csv),
[25 summaries](../benchmarks/baselines/m0-before-large-sum-fix/summary.csv) and five verification
reports are retained for comparison. That run took 9 minutes 20 seconds; its combined XLSX median
was 27,021.441 ms and p95 was 27,192.072 ms. Its source fingerprint independently matched the
67 committed Kotlin/lock files:
`54390c531c4d62eb91aabff00c6800ea8e790e95dcecc0f803416d7e767f9d53`.

## Recorded M1 comparison

M1 used clean calculation-source revision `45eaa0097881b504afcf90470954eca389aa6e7a`, starting at
`2026-10-04T05:56:49.344125Z`. Machine, Java, Kotlin, heap settings, scenario order, five warmups
and ten measured repetitions match M0. The additional `calculate-audit` operation brings the run to
300 samples and 30 summaries. The source fingerprint was independently recomputed from 87
Kotlin/lock files:
`17dfb588123baa07c54fdfb9d0e7ea2f10bdcbe4b193c86c44b2d8639d9d4c15`.

All 15,265 calculated coordinates passed independent arithmetic and cached XLSX comparisons;
all five workbooks had zero formula fallbacks and zero evaluation errors. The combined workbook
contains 13,806 formula cells, including live fact flags and the audit snapshot-status formula.
Audit capture remains bounded, and omitted source steps are visibly marked as truncated.

Median wall times in milliseconds:

| Scenario | plan | calculate | calculate-audit | explain | paper | xlsx |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| small | 56.245 | 59.884 | 59.022 | 58.399 | 1.770 | 39.680 |
| lines | 455.460 | 533.287 | 546.857 | 521.121 | 4.411 | 386.680 |
| members | 47.906 | 74.917 | 83.649 | 73.650 | 3.414 | 349.253 |
| table-rows | 47.775 | 56.012 | 57.475 | 56.098 | 0.653 | 248.330 |
| combined | 437.928 | 1,241.992 | 1,303.466 | 1,245.524 | 30.493 | 39,916.599 |

In the combined case, bounded audit capture adds 4.95% to the same-run ordinary calculation median.
Ordinary calculation is 2.12% faster than the recorded M0 median; a single comparison does not
establish a performance improvement. XLSX is 48.80% slower than M0, with p95 46,957.530 ms and
maximum heap-pool peak 579.92 MiB. Its maximum peak increase over the before-sample heap is
518.50 MiB. The M1 export includes source-level audit evidence, live provided-fact flags and
type-sensitive snapshot comparisons, so the measured change includes new functionality. The
1,000-row scenario also exposes that cost: XLSX rises from 30.802 to 248.330 ms. No profiler
attribution is claimed from these end-to-end timings; export scaling remains a measured M4 target.

The complete [environment](../benchmarks/baselines/m1-macos-aarch64/environment.txt),
[300 samples](../benchmarks/baselines/m1-macos-aarch64/samples.csv),
[30 summaries](../benchmarks/baselines/m1-macos-aarch64/summary.csv) and five numeric verification
reports are archived. Medians, p95, extrema and heap statistics were independently checked against
the raw samples. The run took 12 minutes 56 seconds. Generated papers and workbooks remain in
`benchmarks/build/performance-m1/`; the benchmark JVM and its single-use Gradle daemon stopped.

```sh
./gradlew --no-daemon :benchmarks:run --args='--warmup 5 --repetitions 10 --output benchmarks/build/performance-m1'
```

## Recorded M2 comparison

The [M2 performance report](performance-m2.md) preserves the first and final measurements,
390 final timing/heap samples, continuous-period reuse counters, unresolved export tails and the
initial explicit reference-device budgets.
