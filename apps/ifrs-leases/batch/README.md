# Typed lease batch embedding

**The Kotlin host has been compiled and the actual 10,000-case batch has passed.** Its 1,120,000 numbers match the independently produced reference. The recorded reference-device run took 20.875 seconds, with 22 initial expression plans, 22 sessions and no plans compiled during case evaluation. See [measurement and budget](../../../docs/performance-m4.md).

The independent Python source has also been run: it produced 10,000 different case/lease IDs and different contractual
payments, with 112 exact expected numeric coordinates/global/partial reductions per case.

This fictional full-year lessee demonstration uses the existing public package snapshot and
`Mantra.compile` / `CompiledCalculation.forEach` APIs. No formulas or DSL case text are generated
per item. Each row becomes a typed `CaseData` with one dynamic lease member and three periods;
the iterator keeps only the current record, and completed results are consumed and released.
Actual compiler, session, row, expression and frame counters are written without deriving them
from the requested batch length. The exact schema/version and kernel content digest are recorded.

The source fixture varies 1/2/3-year terms, zero/2.5%/5% rates, contractual cash flows, commencement
payments and direct costs. Payments occur in arrears. Payments after the term are explicitly zero
inputs. Initial liability is cent-rounded present value; interest is cent-rounded each year;
terminal lease-liability residue is carried without a clamp. ROU depreciation uses the final
service period to absorb currency residue. Modification, reassessment, options, taxes, FX and
professional judgments remain outside this demonstration. This is a showcase, not accounting advice.

`independent_batch.py` uses only Python Decimal and the already independent M2 lease reference
algorithm. It reads no engine, workbook or golden values. Source-reported balances are explicitly
derived independently from the fictional contractual facts; this is not external reporting data.
Its self-tests cover zero rates, first/last stock, total flow and partial scopes.

```sh
python3 apps/ifrs-leases/batch/independent_batch.py --self-test
python3 apps/ifrs-leases/batch/independent_batch.py --cases 10000 --out build/out/lease-batch-reference.jsonl
```

The preparation generated 44,589,112 reference bytes with SHA-256
`715049290ac763f1c578abf4325fefd944453fc8c0fcaba793f7ab929105961d`.
Keep this generated JSONL in build/output storage, not source control. Commit its producer and
small manifest/provenance instead. The existing frozen M2 source facts and historic hashes remain
unchanged. Python output does not establish Kotlin compilation, SDK acceptance or performance.

After root integration, run the actual Java main with explicit host controls:

```text
com.xqiou.mantra.apps.leases.LeaseBatchDemo
  --package-root apps/ifrs-leases
  --reference build/out/lease-batch-reference.jsonl
  --reference-manifest build/out/lease-batch-reference.manifest.json
  --out build/out/lease-batch-actual
  --engine-version 1.0.0-rc.1
  --directory-policy strict-handles|trusted-local
  --max-seconds <host-selected-positive-limit>
  [--limit 6]
```

The example hashes the frozen source before/after streaming and checks every supplied expected
coordinate through public typed values and configured reductions. Undefined/error values cannot
stand in for zero. A batch cancellation/limit/deadline never claims completion. Exact observed
actual JSONL, SHA, environment and counters accompany the summary. Time includes compilation,
reference streaming, actual execution, reductions and comparisons; it is not a core-only throughput
measurement. `peakHeapPoolSumBytes` is the sum of observed per-pool peaks, an upper bound rather than
a simultaneous whole-heap peak. No timing or heap budget is represented as user-confirmed.

The directory policy must be supplied explicitly. Strict handles never silently fall back;
trusted-local checks assume cooperative host writers and cannot defeat same-permission malicious
rename races. The batch uses the exact schema default currency scale of two and no parameter
overrides; this is recorded in the independent source manifest. `reference-small.jsonl` supplies
six fixed contracts (672 independent numeric checks) for the meaningful JVM smoke test.
