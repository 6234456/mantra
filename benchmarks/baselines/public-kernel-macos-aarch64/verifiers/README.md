# Public-kernel measurement verification and archival

Task-only Python standard-library tool. It does not run JVMs, Gradle, npm, git, signing tools,
an engine, a producer or a measurement. Its only subprocess is the repository's existing
`scripts/verify-performance.py`, launched with Python `-E -B` so optimization cannot remove that
verifier's assertions and no Python bytecode is written in the repository.

Run after the coordinator's measure wrapper has completed all three processes and both final
identity captures exist:

```sh
python3 /private/tmp/mantra-public-kernel-evidence/archive_public_kernel.py \
  --root /Users/qiouyang/Documents/Claude/Codes/mantra
```

To verify and publish the new archive in one operation, add `--archive`. The destination is exactly
`benchmarks/baselines/public-kernel-macos-aarch64`. It must not exist. There is no overwrite,
historical-output update, budget override, sample deletion or expected-value regeneration option.
After archival, rerun without `--archive` if another independent check is needed.

Inputs use the exact paths from `/private/tmp/mantra-public-kernel-measure.py`:

- `build/release-checks/public-normein/public-source-{before,after}.json` and
  `public-runtime-{before,after}.json`;
- `benchmarks/build/public-kernel-performance` and `public-kernel-period-performance`;
- `build/out/lease-batch-public-kernel-10000/{summary.json,actual.jsonl}`;
- existing `build/out/lease-batch-reference.jsonl` and `lease-batch-reference.manifest.json`.

Checks require exact before/after source records and fingerprints, matching current source bytes,
all 25 unchanged installed benchmark JARs, public Normein version `0.3.0` with JAR SHA-256
`83a101ac90ae0c2a50bdc9a4b103d1c3de015df8bcbcafa4f63bbe5aa1562cc8`, and both environments'
actual commit/source hash/JAR hash/heap/repetition settings. The historical source lock is accepted
only under its explicitly named baseline key; it cannot be reported as a runtime commit.

The unchanged CSV verifier must accept all 300 original and 90 period samples/39 groups. Batch
verification reads one actual/reference JSONL pair at a time under a 65,536-byte line limit,
rejects duplicate JSON keys/non-finite values, checks exact distinct IDs and all 112 numeric keys,
and compares all 1,120,000 numbers with exact finite Decimal equality. It independently checks
stream hashes, recorded counters, technical/business success, fully closed session lifecycle,
zero runtime compilations/VALUE_ONLY evidence, ≤300,000 ms and ≤1 GiB summed heap-pool peaks.
The initial 22 compiled formulas remain distinct from zero compilations during row execution.

PASS reports are emitted only after every check succeeds. The optional archive is staged and
renamed as a complete new directory, with copied-byte hashes checked against the earlier input
inventory. It contains raw CSV, summaries, environment files, verification text, small paper
snapshots, source/runtime inventories, verifier scripts and small batch/reference metadata.
The explicit `verification/` allowlist additionally preserves the measured-commit implementation
CI receipt, aggregate-only process
observation, canonical local TEST-only signing receipt, public-kernel functional/static review
receipts and metadata-only fixture change proof. These existing reports retain their original
scope; no receipt is promoted to a Maven publication or whole-machine isolation claim. Unrelated
user JVMs were preserved during the coordinator's sequential task-owned measurements.
Each file is capped at 4 MiB; aggregate input is capped at 32 MiB. XLSX/JAR/JFR/JSONL/signed bundles
are omitted. Actual/reference streams stay at their original build paths and are identified by
their preserved hashes. Historical archives are never a mutation target.

Preparation validation: 16 small synthetic stdlib tests passed, covering exact Decimal values,
key/ID/count mismatch, non-finite values, duplicate JSON keys, UTF-8, bounded lines, trailing
records, source path/byte changes, archive refusal and temporary staging cleanup. They use
temporary fixtures only and do not read the actual measurement outputs.

```sh
python3 -B /private/tmp/mantra-public-kernel-evidence/test_archive_public_kernel.py
```

Fresh public measurements and archive publication are performed by the coordinator. No fresh
measurement acceptance is inferred from preparation or these synthetic tests.
