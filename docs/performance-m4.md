# M4 compilation reuse and streaming batch performance

Status: independent 10,000-case numeric and budget acceptance verified. This report does
not mark M4 complete. The unchanged [M3 budgets](performance-m3.md) remain in force for established
scenarios. The batch budget below is frozen **before** the final run, using the same macOS/aarch64
Azul JDK 21.0.12.1 platform and explicit 512 MiB Java heap.

| Final batch acceptance | Frozen bound |
| --- | --- |
| Distinct typed cases | 10,000 different case and member IDs |
| Independent numeric comparisons | 112 per case, 1,120,000 total |
| Measured batch duration | At most 300 seconds |
| Peak heap pool sum | At most 1 GiB; actual process heap is capped at 512 MiB |
| Runtime plan compilation | Exactly zero after immutable template construction |
| VALUE_ONLY evidence materialization | Exactly zero |
| Worker session lifecycle | Every opened session closes successfully |
| Current technical/business failures | Zero for this frozen positive corpus |

The timer includes template construction, typed decoding, iteration, actual engine evaluation,
public coordinate/reduction reads, independent comparisons and streaming output writes. It excludes
initial package capture, independent producer generation, reference digest validation and JVM/Gradle
startup. These exclusions are declared rather than hidden inside an engine-only timing.
Heap pool peaks may occur at different times; their sum is a conservative observation, not resident
memory or a cumulative allocation counter.

The 100-case probe completed 11,200 exact numeric comparisons in **1,374 ms**, with heap pool peaks
totalling **322,419,720 bytes**. Template construction made 22 actual syntax/semantic compiler calls
and 22 execution-plan compilations, one for each authored expression. One worker opened/closed 22
kernel sessions and allocated 22 frames. The 100 cases executed 5,600 row cycles with no later plan
compilation and no VALUE_ONLY evidence materialization. A template is compiled once; that does not
mean the kernel exposes one plan for the entire schema.

The independent 10,000-case JSONL has 44,589,112 bytes and SHA-256
`715049290ac763f1c578abf4325fefd944453fc8c0fcaba793f7ab929105961d`. Its Decimal/Fraction producer
derives amounts from fictional facts and never reads engine output. Large generated reference and
actual JSONL files remain build artifacts; summary, source digests and verification commands are
retained. No deadline/heap budget is inferred from a successful six-case smoke test.

The explicit trusted-local directory policy is used for this cooperative local package capture;
strict handles remain the library default. Source revision and actual numerical result digest are
reported with the run. This workload demonstrates reusable compilation and streaming, not arbitrary
input table sizes beyond the pinned kernel's value limits.

## Final batch evidence

The actual 10,000-case run completed **1,120,000 exact comparisons in 20,875 ms**, with heap pool
peaks summing to **333,947,640 bytes** (318.48 MiB). All cases had distinct IDs and all current
technical/business checks passed. A second independent Python stream compared every frozen decimal
again, checked the two JSONL key sets and IDs, recomputed digests and enforced the frozen bounds.

Construction remained 22 actual plans; worker activity was 22 opened/closed sessions, 22 frames,
560,000 physical row preparations/evaluations, zero later compilations and zero VALUE_ONLY evidence
materializations. The actual JSONL SHA-256 is
`ea7456d8e9dddecbbe7fc4c9f489fbca1337677b5b7c0222147796b2e1b5315a`.
Raw probe and final summaries are in [the batch baseline](../benchmarks/baselines/m4-batch-macos-aarch64/).
The full generated JSONL remains a reproducible build artifact; it is not committed as a library
resource. This is one measured final batch, not a fabricated median/p95 distribution.
