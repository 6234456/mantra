# Verify a template against multiple cases

Use `template-verify` to check one candidate schema/layout against several saved cases and raw
scalar input scenarios. Each result comes from the same real preview service as `/template-preview`.
The command checks exact values, formatted text, active state and business findings, and produces
a JSON report suitable for CI. Workspace sources remain unchanged.
Use [Paper presentation acceptance](template-paper-acceptance.md) for version 2 checks of rendered
titles, ordered columns, cell text, final styles and visibility.

## Run the included example

From the repository root with JDK 21:

```sh
./gradlew --no-daemon :mantra-cli:installDist
mantra-cli/build/install/mantra/bin/mantra template-verify \
  docs/examples/template-acceptance/suite.json \
  --workspace docs/examples/template-acceptance \
  --out /tmp/mantra-template-acceptance-report.json
```

The example proposes changing `base-amount * 2` to `base-amount * 3`, without saving that change to the
schema. Four independent checks cover two saved cases, German decimal input and a decimal beyond
binary64 precision. Their expectations are simple multiplication by three:

Example sources: [suite](repo:docs/examples/template-acceptance/suite.json),
[schema](repo:docs/examples/template-acceptance/schema.mantra),
[layout](repo:docs/examples/template-acceptance/layout.mantra),
[positive case](repo:docs/examples/template-acceptance/positive.mantra) and
[negative case](repo:docs/examples/template-acceptance/negative.mantra).

| Scenario | Input | Expected exact answer | Business check |
| --- | --- | --- | --- |
| Positive saved case | `10` | `30` | passes |
| Negative saved case | `-2` | `-6` | fails as expected |
| German raw text | `1,25` | `3.75` | passes |
| Large exact input | `9007199254740993,0000000001` | `27021597764222979.0000000003` | passes |

Expected findings can pass acceptance: the negative scenario explicitly requires
`validationPassed: false` and a failed `nonnegative` check. This does not turn that calculation's
business status into success. A negative runtime test must explicitly expect `succeeded: false`;
syntax errors, missing inputs, unsupported targets and revision conflicts still fail verification.

## Capture your own suite

Use workspace-relative case paths that share the same root schema and optional layout:

```sh
mantra-cli/build/install/mantra/bin/mantra template-capture \
  positive.mantra negative.mantra \
  --workspace docs/examples/template-acceptance \
  --out /tmp/my-template-suite.json
```

Capture writes root candidate text and each case's complete source revisions. Every `expect` is
initially `null`; verification returns exit code 2 until you provide expectations. Capture never
copies current calculated outputs into a passing baseline.

Edit `documents[].text` to propose the shared schema/layout change. Keep `document`, `role` and
`sha256` unchanged. Replace each `expect` with independently checked values, for example:

```json
{
  "succeeded": true,
  "validationPassed": true,
  "values": [
    {"node": "answer", "present": true, "value": {"n": "30"}, "display": "30,00"},
    {"node": "nonnegative", "present": true, "value": true, "validationPassed": true}
  ],
  "diagnostics": []
}
```

Add scenarios by copying a case entry, assigning a unique `id`, and changing `inputs` and `expect`.
Inputs use `{ "node": "base-amount", "text": "1,25" }`: text is parsed by the candidate engine and
locale. An empty input list retains saved case values. `panel` and `includeZero` are optional
preview contexts. Output assertions in this first version address scalar nodes only.

An explicit `{ "n": "1.00" }` compares numerically equal to `{ "n": "1" }` using exact decimal
arithmetic; `display` compares the engine's string exactly. Boolean `false`, Decimal zero, null
and a missing scalar result remain distinct. Use `{ "node": "removed-node", "present": false }`
for an absent result. A present assertion must include `value`, even when that value is null.

`validationPassed` on a value can be true, false or null. Null means there is no applicable
business verdict. Omit it to leave that property unchecked. If `diagnostics` is present, its
`{code,severity,count}` entries must match the complete diagnostic multiset; `[]` requires none.

## Read the report

The report includes the suite SHA-256, actual engine versions, each pinned source graph and
candidate revision, expected/actual assertions, diagnostics and the engine's Difference from the
saved case. The saved difference uses that case's saved input values, not another scenario's
temporary overrides. There is no frontend calculation or expected-value regeneration.

| Exit code | Meaning |
| --- | --- |
| `0` | Every scenario passed, or capture succeeded |
| `1` | Invalid command options |
| `2` | Invalid suite, technical failure, missing expectation, IO error or source conflict |
| `3` | Valid calculations did not match the independent expectations |

Independent scenarios continue after a failure. Technical failures take precedence over value
mismatches in the exit code. A report with any error, conflict or failed assertion cannot pass.
Malformed JSON can fail before a report is available; read stderr as well as the exit code.

## Revisions and limits

The suite locks each complete source graph, including cases, parameters, includes and linked
cases. Every scenario gets fresh case-specific source handles. After all calculations, the runner
checks all captured graphs again: a later dependency change invalidates earlier results. These
are repeated revision checks, not a cross-process atomic snapshot or a lock on external editors.

If a source changes, review that change, capture a new suite, then deliberately transfer your
candidate and independent expectations. Updating a hash alone is not a reviewed rebase. This
command does not save, migrate, publish or merge template files.

Suites use `mantra.template-acceptance/1`; reports use `mantra.template-acceptance-report/1`.
The suite reader rejects duplicate keys, unknown fields, invalid UTF-8 and trailing JSON. Limits
are 2 MiB UTF-8, nesting depth 16, 32 scenarios, 100 input overrides per scenario, 256 value
assertions per scenario and 2048 value assertions overall. Text fields have a 65536 UTF-16-unit
limit; the real preview service also enforces its existing request and engine budgets.

`--out` cannot overwrite the suite or a participating source, including aliases and hard links.
If a source graph cannot be fully captured or its final check fails, use stdout or a new output
file outside the workspace. An output file is replaced atomically, and temporary output files
are removed on failure. Reports may contain case values; choose their destination accordingly.

Table/dimensioned input overrides, multi-case visual editing, formal source Save, publication
and Template Engine compatibility certification remain separate work. This CLI does not enable
those operations or change their current status.
