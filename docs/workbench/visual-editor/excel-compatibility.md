# Generated Excel artifact audit and Template Engine execution proof

The pinned Template Engine **imports both complete generated workbooks and recalculates the tested numeric input subsets correctly**, including a business finding. **Neither complete workbook is compatible or ready for publication.** The audit retains the unsupported functions, conversion diagnostics and masked semantic failures instead of removing audit sheets or changing formulas to obtain a passing result.

This is an actual runtime proof against Template Engine [`6443c68875a8592e79ea2099637545fe96a18ec0`](https://github.com/6234456/paramita-v2/commit/6443c68875a8592e79ea2099637545fe96a18ec0), not an inference from function metadata. It runs the application `createUploadedXlsxPackage` conversion path, captures the import IR and import-plan diagnostics, edits source-bound input cells, and runs `createWorkbookCalculationRuntime` twice. The original Template Engine checkout is read-only: the tool uses `git archive` to freeze this commit in a temporary directory and never checks out, resets or changes its files.

## Observed results

| Artifact | Full inventory | Source numeric bindings checked before and after editing | Scoped recalculation | Complete workbook |
| --- | --- | --- | --- | --- |
| Scalar proof | 6 sheets, 89 cells, 5 formulas, 4 defined names | 3 | Pass | **Fail** |
| Actuals versus baseline | 7 sheets, 317 cells, 44 formulas, 41 defined names | 31, plus 2 Boolean checks | Pass | **Fail** |

For the scalar proof, the source declares `doubled = quantity × 2` and `increased = quantity + 5`. Changing the imported input from `10` to `13` produces `20 → 26` and `15 → 18`. These are real Template Engine results and match a separate Mantra run with the changed case. The values also follow directly from the two elementary arithmetic expressions.

For the existing actuals pattern, Member A's quantity changes from `10` to `13`. Its actual price remains `12`, baseline price `10` and allowance `150`; Member B is unchanged. The independent reference is:

| Measure | Initial | Changed |
| --- | --- | --- |
| Member A actual amount | 120 | 156 |
| Member A baseline amount | 100 | 130 |
| Member A variance | 20 | 26 |
| Member A remaining allowance | 30 | −6 |
| Total actual amount | 200 | 236 |
| Total remaining allowance | 50 | 14 |
| Member A allowance check | `true` | `false` |

Both the real Office recalculation and independent Mantra execution produce these values. The changed Mantra case succeeds technically, reports `MANTRA-CHECK-FAILED` as a business finding, and has `validationPassed: false`. The Office Boolean check changes to `false`; its rendered status formulas produce `✗`. The finding does not prevent export. Complete initial and changed formula results are retained in the runtime evidence.

## Why complete compatibility fails

1. **Four functions are absent.** `EXACT`, `ISBLANK`, `ISLOGICAL` and `ISTEXT` each return `#NAME?` in direct calls to the pinned real evaluator. The exporter uses all four in its live input-basis checks. Even the scalar workbook contains these audit checks.
2. **An error count of zero hides a semantic failure.** The generated checks wrap unsupported calls in `IFERROR`. Template Engine therefore returns normal values while incorrectly marking the untouched initial snapshot as `outdated`. Comparing every initial formula against the unmodified workbook's POI-generated cache detects **3 of 5** mismatches in the scalar workbook and **15 of 44** in the actuals workbook. Complete formula results, not just thrown errors, determine compatibility.
3. **Conversion drops a name.** Both import plans report `unsupported.v2.definedName` for `_xlnm.Print_Titles`. The application wrapper discards the plan's diagnostics; this auditor retains them explicitly. The complete XML inventory and import IR retain the original defined names and scopes.
4. **Rounding and precision differ.** Actual evaluator probes return `ROUND(-1.5,0) = -1`, whereas the independently expected HALF_UP/Excel result is `-2`. `ROUND(1.005,2)` returns `1`, rather than `1.01`. `0.1 + 0.2` returns `0.30000000000000004`, and `9007199254740992 + 1` returns `9007199254740992`. No tolerance or conversion silently turns these failures into matches.

The scope that passed uses small quantities, integer results and exact binary fractions in the unchanged comparison data. This establishes those recorded cases only. Mantra keeps exact decimal strings; the Office runtime uses JavaScript binary64. It does not establish general decimal, date, missing-value, dynamic-shape or negative-rounding parity. Microsoft Excel execution was not tested.

Only the audit snapshot sheet is protected in each generated workbook. Most generated calculation formulas reside on unprotected sheets. The sidecar identifies inputs through the source API rather than cell color; it does not pretend that all formula cells are protected. The inventory retains the complete styles, cell protection and worksheet protection, and the runtime evidence retains the imported styles and edit policies. Visual style parity was not verified.

The existing `ExcelOptions` has no option to omit or replace the live audit checks. This tool uses its unchanged defaults, preserves every sheet and formula, and supplies no target-specific static substitution. A future compatible target profile needs an explicit semantic contract and its own verification. No Template Engine source changes or catalog/publishing integrations are included.

## Evidence and identities

The committed [machine compatibility report](excel-compatibility-evidence/compatibility.json) binds the results to:

- The Template Engine commit, Git tree, archive hash and source `pnpm-lock.yaml` hash.
- SHA-256 for **231 actual source modules** used by the runtime bundle, plus the bundle hash.
- The isolated dependency lock and actual versions of ExcelJS `4.4.0`, JSZip `3.10.1`, Saxes `6.0.0` and esbuild `0.25.12`.
- Hashes of every installed JVM library actually used for generation. These identify the executable binaries; they do not assert an unrecorded source commit for those binaries.
- All included DSL documents, their exact text/hash, source node/coordinate/input type, and `ExcelWorkbook.address`, `aggregateAddress` or `tableAddress` mappings.
- The complete generated XLSX SHA-256. Rebuilding may change ZIP timestamps and therefore the binary hash; each new report is bound to the new bytes.

Files are retained without trimming engine/import responses:

- [Template Engine runtime evidence](excel-compatibility-evidence/template-engine-runtime.json): complete import IR, conversion plan, imported workbook, initial/edited formula results, input conversions and runtime snapshots.
- [Scalar artifact](excel-compatibility-evidence/simple/base/artifact.xlsx), [source sidecar](excel-compatibility-evidence/simple/base/provenance.json), [complete inventory](excel-compatibility-evidence/simple/base/inventory.json), and the separately generated changed-case equivalents under `simple/edited/`.
- [Actuals artifact](excel-compatibility-evidence/actuals-vs-baseline/base/artifact.xlsx), [source sidecar](excel-compatibility-evidence/actuals-vs-baseline/base/provenance.json), [complete inventory](excel-compatibility-evidence/actuals-vs-baseline/base/inventory.json), and the changed-case equivalents under `actuals-vs-baseline/edited/`.
- [Audit dependency lock](excel-compatibility-evidence/audit-dependencies-lock.json): isolated tooling dependencies, separate from both application's dependency manifests. Template Engine's own lockfile remains unchanged; its hash is recorded independently.

Inventory covers every actual OOXML cell and defined-name expression, including cells outside a bounded 50-row/20-column preview. It records formulas and function calls, raw cached types/values, all ZIP parts, styles and custom formats, tables, merge ranges, data validations, conditional formats, relationships, protection and unmapped source entries. It does not fetch external workbook links or execute macros.

## Reproduce

Use JDK 21, Python 3.11 or later and Node 24. The Template Engine repository must contain the pinned commit; its current working tree may have unrelated modifications because only the Git object is read. Build Mantra's CLI first:

```sh
./gradlew :mantra-cli:installDist
```

Install the audit dependencies in a separate temporary prefix from their committed lock. This leaves the application dependency manifests and Template Engine checkout unchanged:

```sh
mkdir -p /tmp/mantra-te-audit-deps
cp scripts/excel-template-audit-dependencies/package.json /tmp/mantra-te-audit-deps/package.json
cp scripts/excel-template-audit-dependencies/package-lock.json /tmp/mantra-te-audit-deps/package-lock.json
npm ci --prefix /tmp/mantra-te-audit-deps --ignore-scripts --no-audit --no-fund
python3 scripts/excel-template-audit.py proof \
  --template-engine /path/to/paramita-v2 \
  --dependencies /tmp/mantra-te-audit-deps \
  --output build/excel-template-audit
```

`--cli-home /path/to/mantra-cli/build/install/mantra` selects another installed CLI. The run freezes copies of its libraries before generation, so every case uses the same binary identities. It writes `compatibility.json`, both complete base/changed workbooks, source mappings, inventories and actual runtime evidence. The successful audit command means evidence collection completed; it does **not** mean compatibility passed. Add `--require-compatible` to exit with status `2` after collecting an incompatible result. Bootstrap, export or runtime-bundling failures exit unsuccessfully and do not count as a completed proof.

Inspect another workbook without JVM or Template Engine dependencies:

```sh
python3 scripts/excel-template-audit.py inventory artifact.xlsx \
  --provenance provenance.json --output inventory.json
python3 -m unittest scripts/tests/test_excel_template_audit.py
```

The sidecar is optional for a standalone inventory; when supplied, its workbook hash and exact source text hashes must match. The tests cover cells beyond the preview bound, defined-name formulas, typed/rich-text cells, protection, source-coordinate binding, tamper rejection, call lexing, exact decimal failures, masked semantic failures and package relationship boundaries.

This evidence advances [the first publication proof](../../template-directions.md#first-publication-proof) by proving the actual import/recalculation path and identifying concrete blockers. It does not enable publishing or the `Open in Template Engine` action.
