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
checks Explain, writes Text and HTML, and checks every cached calculated XLSX cell against the same
expectations. The workbook report records formula fallbacks and evaluation errors. A failed
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
| explain | `Mantra.calculateForExplain(...)`: planning and full calculation, plus bounded source-level trace for the final member line |
| paper | `Render.paper(result, layout)`: paper construction and full audit appendix from an existing result; HTML/Text encoding and disk I/O are excluded |
| xlsx | `ExcelExport.workbook(...)` plus `bytes()`: complete paper, formulas, POI recalculation, XLSX encoding and workbook close; calculation and disk I/O are excluded |

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

## Recorded M0 runs

The first clean implementation run used revision
`c62a2fb6742a653d8093f42a7ded1c3f0203e319` and started at
`2026-10-03T22:17:17.798269Z` (2026-10-04 in Europe/Berlin). It used Azul OpenJDK 21.0.12.1,
Kotlin 2.2.20, Mac OS X 27.0 on aarch64, ten available processors and the JVM settings above.
The kernel was `normein-dsl` 0.3.0 at
`0a3ae1de844c92635fbbc03406a13cb0e8920c03`.

This run discovered a large-table export limit: `table-rows` and `combined` each reported one
explicit fallback for `table-sum`. The generated `SUM` had 1,000 arguments; POI rejected it with a
255-argument limit. All cached calculated values still matched the independent arithmetic and
there were zero evaluation errors, but changing a source table cell would not recalculate that
fallback. The run is retained as evidence for the generic export fix, rather than the final baseline:
[environment](../benchmarks/baselines/m0-before-large-sum-fix/environment.txt),
[all 250 samples](../benchmarks/baselines/m0-before-large-sum-fix/samples.csv),
[summary](../benchmarks/baselines/m0-before-large-sum-fix/summary.csv) and per-scenario verification
reports in the same directory.

Its median wall times in milliseconds were:

| Scenario | plan | calculate | explain | paper | xlsx |
| --- | ---: | ---: | ---: | ---: | ---: |
| small | 60.580 | 62.713 | 59.370 | 2.289 | 24.096 |
| lines | 460.423 | 538.013 | 537.099 | 6.495 | 325.839 |
| members | 49.711 | 77.616 | 77.282 | 5.377 | 248.259 |
| table-rows | 48.955 | 59.057 | 58.337 | 2.018 | 30.474 |
| combined | 457.301 | 1,285.139 | 1,281.207 | 52.347 | 27,021.441 |

The combined XLSX p95 was 27,192.072 ms. The complete run took 9 minutes 20 seconds, dominated
by repeatedly exporting the largest workbook. All five schemas, cases, HTML, Text and XLSX outputs
were generated and checked. Source fingerprint:
`54390c531c4d62eb91aabff00c6800ea8e790e95dcecc0f803416d7e767f9d53`.

The final baseline will be recorded after the large-table formula fix, using the same workload,
five warmups and ten samples per operation, with zero formula fallbacks required for all scenarios.
No budget or regression threshold is set until repeat runs establish the machine's variability.
