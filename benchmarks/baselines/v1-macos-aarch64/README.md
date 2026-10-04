# v1 source-candidate reference-device acceptance

Measured implementation: `ae9af84bde3f82cc86787e908aab7be76ddecee9`, `1.0.0-rc.1`.
The original and period processes ran sequentially on macOS/aarch64, Azul JDK 21.0.12.1,
Kotlin 2.2.20 and the unchanged pinned Normein 0.3.0 kernel. Each group has five warmups and
ten retained measured repetitions; 30 original and nine period groups provide 390 samples.
Environment files retain all actual JVM flags and source identities.

`original/` and `periods/` are byte-preserved copies of actual raw samples, summaries and value
verification reports. The independent verifier recomputes all summaries and unchanged budgets:

```sh
python3 scripts/verify-performance.py \
  --original benchmarks/baselines/v1-macos-aarch64/original \
  --periods benchmarks/baselines/v1-macos-aarch64/periods
```

`v1-source-before.json` and `v1-source-after.json` contain the identical 212-entry source inventory.
`v1-runtime-identity.json` records the frozen implementation's 25 runtime JAR digests and the
original Java profiling consumer's **pre-recording** source/class hashes. The actual recording
uses the later diagnostic-only metadata-suppression revision, whose separate source/class/recording
identities are in `profile/identity-privacy-verification.json`. Production Kotlin and the measured
Kotlin harnesses did not change between measurement and profiling.

`batch/` retains the actual 10,000-case/1,120,000-value summary, producer provenance and second
independent streamed comparison. Its 22,777 ms result is one observed run, not a median/p95.
The large actual JSONL remains a build artifact, identified by its SHA-256 in the summary.

`profile/` retains the binary JFR, summaries, selected-stack analysis and privacy verification.
The six environment/system/process metadata event counts are zero. Workbook serialization is to
memory bytes; no FileWrite event was observed, and the recording dump occurs after capture stops.
The selected-event JSON can be regenerated from the retained JFR with the documented JDK command;
it is not duplicated here. The separately profiled 41.029-second export includes JFR overhead and
does not alter the unprofiled acceptance samples or explain historical M2 latency tails.

The exact standard-library streaming analyzer used for this recording is retained as
`profile/analyze-selected-events.py`. It targets the documented `build/v1-profile/` paths and
the recorded 41,029,463,375-nanosecond export, rather than claiming to be a general profiler.
To reproduce its analysis, copy the archived identity to that build directory and regenerate
`selected-events.json` from the archived JFR with the report's JDK command. Its recorder-start
allocation category is explicitly separated from export-related stacks.

CI checks the archived CSV statistics and a fresh JDK summary of the archived binary:

```sh
jfr summary benchmarks/baselines/v1-macos-aarch64/profile/combined-xlsx.jfr \
  > build/v1-profile/summary.txt
python3 scripts/verify-profile-recording.py \
  --recording benchmarks/baselines/v1-macos-aarch64/profile/combined-xlsx.jfr \
  --summary build/v1-profile/summary.txt \
  --identity benchmarks/baselines/v1-macos-aarch64/profile/identity-privacy-verification.json \
  --source benchmarks/baselines/v1-macos-aarch64/profile/CombinedXlsxProfile.java
```

`archive-manifest.json` provides byte sizes and SHA-256 for the retained evidence files.
See [the full report](../../../docs/performance-v1.md) for method, unchanged budgets, observations
and limits. These results establish reference-device engineering acceptance, not portable CI
latency guarantees or completion of the coordinated public Maven release.
