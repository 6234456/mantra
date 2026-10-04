# Explicit historical ESt migration demonstration

The public Kotlin migration demonstration has compiled and passed its end-to-end
verification: 50 numeric values and one Boolean match the independent reference,
and the original exact `2025.2` case remains available alongside the migrated
`2025.3` case. The Python reference producer and its independent self-tests also
pass. These checks verify this demonstration; they do not claim completion of the
whole milestone or establish production tax suitability.

This showcase migrates an unchanged fictional Mustermann case from exact legacy
schema version `2025.2` to `2025.3`, binds the declared 2025.3 parameters and
layout, and checks 50 numeric values plus one Boolean against independent
Decimal arithmetic. Both versions still use the historical 2025 tariff. The
original case has no opening/new loss or trade-tax assessment/payment, so the
new forward loss ledger and § 35 computation have zero deductions. The 2025.3
controls are exercised without inventing financial inputs. The independently
computed assessed income tax remains EUR 17,996 and the balance EUR 1,462.24.
This is a demonstration, not tax advice or production tax software.

The producer reuses `verify_expected.py`, which independently implements the
original fictional facts. It adds the zero stock ledger, positive-income
attribution and whole-person reductions from those same facts. It reads no
engine output, golden value or workbook. `reference.manifest.json` records the
producer, original case, independent algorithm and relevant schema/parameter
source hashes. The original facts and historic manifests remain untouched.

```sh
python3 apps/de-est/migration/independent_migration.py --self-test
python3 apps/de-est/migration/independent_migration.py
```

`EStMigrationDemo` uses public package capture, `MigrationCoordinator` and the
shared public `CasePackageLoader`/`CaseGraphRunner`. The application adapter
constructs a fresh loader per graph read, binds each exact schema from captured
package declarations, explicitly authorizes only its root schema/parameter/
layout files, and preserves typed diagnostic case/revision identity. It does not
implement any tax rule or replace failed/undefined values with zero. It recaptures
the package around evaluations to check resource integrity and target revisions.

The writable host root must be a separate explicitly supplied directory,
disjoint from the read-only package. `prepare` uses CREATE_NEW to make a copy;
`preview` writes original/candidate/report files to the explicit output directory
and leaves the host case unchanged. `apply` requires the reviewed token, checks
the live graph/source while holding a host epoch lock, and uses the store's byte
CAS/atomic commit. After application, it reruns both migrated and original exact
versions and verifies the source package remains unchanged.

Pass these common arguments to the Java main:

```text
com.xqiou.mantra.apps.deest.EStMigrationDemo
  --package-root apps/de-est
  --host-root <separate-explicit-host-directory>
  --case-path cases/mustermann.mantra
  --out <explicit-review-output-directory>
  --engine-version 1.0.0-rc.1
  --directory-policy strict-handles|trusted-local
```

First add `--mode prepare`, then `--mode preview`. Inspect `original.mantra`,
`candidate.mantra` and `preview.json`. Finally add `--mode apply --review-token
<exact-reviewed-token>`. The host mode is explicit: strict handles never silently
fall back, and trusted-local Java path checks are for cooperating local writers;
they do not guarantee protection from malicious same-permission rename races.
No history is automatically rewritten to SemVer, and no old schema is deleted.
