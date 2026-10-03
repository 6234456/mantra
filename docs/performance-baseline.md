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
expectations. The workbook report records all formula fallbacks and evaluation errors. A failed
calculation or numeric comparison aborts the run; a fallback is listed explicitly in the output.

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

Every operation warms up separately, then runs ten measured repetitions. Wall time comes from
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

## Recorded M0 run

Measurements will be inserted here after running the verified harness on the finalized M0 source.
No budget or regression threshold is set until repeat runs establish the machine's variability.
