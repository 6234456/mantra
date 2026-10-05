# Public-kernel reference-device performance archive

Measured source: `a1e6e65a01aa39c792681a138beda31ab66af172`, engine `1.0.0-rc.1`,
2026-10-05. Runtime kernel: public `com.xqiou:normein-dsl:0.3.0`, JAR SHA-256
`83a101ac90ae0c2a50bdc9a4b103d1c3de015df8bcbcafa4f63bbe5aa1562cc8`. The optional source lock is a development baseline;
it is not claimed as the source commit of this public artifact.

All **390 samples / 39 groups** and the separate **10,000-case / 1,120,000-value batch** passed
unchanged budgets and independent verification. The original and period fixture processes used
five warmups and ten measured repetitions per operation, sequentially, on macOS/aarch64 with
Azul JDK 21.0.12.1. No competing task-owned build/browser/server ran; unrelated user JVMs were
preserved. This is not whole-machine isolation or portable CI latency evidence.

`original/` and `periods/` preserve actual CSV, summaries, environment flags, numeric verification
text and paper snapshots. The maximum summed heap-pool peaks are 789,737,784 and
502,101,536 bytes. Every unchanged repeat has zero evaluated tasks/formula evaluations;
matrix and transpose XLSX verification has no fallback/evaluation errors.

Recompute all CSV summaries and original budgets without running an engine:

```sh
python3 scripts/verify-performance.py \
  --original benchmarks/baselines/public-kernel-macos-aarch64/original \
  --periods benchmarks/baselines/public-kernel-macos-aarch64/periods
```

`source-before.json` and `source-after.json` are identical complete 213-entry inventories, SHA-256
`cbfd26a246033d6e016415a6eca49459801b372d04bc25038200669f6bf54661`. `runtime-before.json` and `runtime-after.json`
record the same 25 JARs. This source hash follows the established library/harness `.kt`+tests+lock
scope; it is not a complete repository digest. `source-runtime-verification.json` and
`performance-independent.json` retain the independent identity and numerical gate outcomes.

`batch/` preserves the actual summary, frozen reference manifest, producer digest proof and
independent streamed comparison. The actual run took 21,030 ms with 95,573,904 summed
heap-pool peak bytes; all 22 sessions closed, with no runtime plan compilation or VALUE_ONLY
evidence materialization. It explicitly used `-Xms128m -Xmx512m -XX:+UseG1GC`, so comparison to the
earlier batch does not establish causal time/heap improvement. The actual stream hash is
`ea7456d8e9dddecbbe7fc4c9f489fbca1337677b5b7c0222147796b2e1b5315a`, equal to the old v1 result stream. Large streams remain identified
build artifacts and are not duplicated here; no expected values were regenerated.

`verification/` contains the exact-source implementation CI receipt, aggregate process
observation, public-kernel functional/static review, metadata-only fixture proof and local
canonical TEST signing receipt. They retain their own scope; TEST signatures are not production
signer authorization or a Maven upload. `measurement-execution.txt` records actual UTC process
start/completion times. `verifiers/run-public-kernel-measure.py` preserves the exact executed
launch wrapper; its paths are task-specific and it requires clean source/new output directories.

The frozen Python verifiers check source/runtime/CSV identities, exact batch ID/key/value sets and
all original time/heap/lifecycle limits. `verifiers/README.md` documents their original build-input
paths and bounded archival method. The archive manifest pins retained bytes; do not rewrite prior
measurements, samples or reference expectations when regenerating documentation.

**No fresh JFR belongs to this public-kernel run.** The [older v1 archive](../v1-macos-aarch64/README.md)
keeps its 212-entry source identity, source-built kernel and separate diagnostic JFR unchanged.
See [the full public-kernel report](../../../docs/performance-public-kernel.md) for every measured
group, unchanged budgets, comparisons, evidence hashes and limitations. Mantra Central publication
and production secret setup remain deferred.
