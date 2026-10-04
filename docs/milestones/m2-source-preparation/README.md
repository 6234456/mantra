# M2 application preparation

These are independently authored fictional data and arithmetic references for the planned
`apps/fixed-assets` and `apps/ifrs-leases` applications. They are demonstration material only,
not production accounting software or accounting advice. They contain no Mantra DSL schema,
Gradle or Kotlin code, rendered golden files, downloaded standards or browser artifacts.

The M2 period and aggregation contracts have not yet been locked. Proposed numeric keys in
`references/` are an explicit mapping for the future application, not a published API. When
the DSL is settled, map those keys to public result addresses without changing the fixture
facts or monetary expectations to accommodate an implementation discrepancy.

Both `verify_expected.py` scripts use only the Python standard library, independently of Mantra,
its JVM implementation and XLSX formulas. Monetary input fields are strings and calculations
use Decimal with an 80-digit working context, explicit half-up currency rounding and no binary
floating point. Default currency precision is two places; a four-place variant is prepared.
`references/*-values.json` and `*-summary.json` are independent arithmetic sources, not rendering
goldens or evidence that the future engine implementation passes.

Each domain has six JSON case templates, a default sample CSV dataset, parameter preparation
values, an English README, two precision variants of the numeric references and a self-test
against separately stated fixture amounts. `reference-manifest.json` records the source and
reference SHA-256 hashes and the scope of the preparation checks.

From this directory:

```sh
python3 fixed-assets/verify_expected.py --self-test
python3 ifrs-leases/verify_expected.py --self-test
python3 fixed-assets/verify_expected.py --summary
python3 ifrs-leases/verify_expected.py --case commencement-payment --summary
```

The files remain outside the repository while M1 is being validated. The parent task will
migrate the selected preparation material after locking the M2 work packages and remove this
task-owned temporary directory afterwards.
