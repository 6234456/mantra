# All-open-PR integration, 2026-10-28

Requested scope: every open PR in `6234456/mantra`, including the authoring draft and dependency updates.

## Included PRs

`#2 #3 #4 #5 #6 #7 #8 #9 #10 #11 #12 #15 #16 #17 #18 #19 #20` — 17 PRs.
Original PR heads are retained as merge parents. #15 was merged through GitHub first; the remaining
heads are integrated together before advancing main, so the npm lock can be validated as one graph.
The refreshed Dependabot head for #17 is also included. #16 retains both the main branch's rendered
function-page assertions and the PR's additional catalog mismatch/internal-helper regressions.

## Compatibility work

- TypeScript 7.0.2 is the actual compiler, under the pinned `typescript-compiler` npm alias. Build and
  typecheck invoke its explicit `bin/tsc` entry point. TypeScript 5.9.3 remains solely as the supported
  ESLint parser dependency; no forced or legacy peer resolution is used. The unified npm lock includes
  Vite 8.3.1 and all three CodeMirror upgrades. See [frontend tooling](../workbench-ui/README.md).
- Gradle 9.8.1 task registration uses `tasks.register` instead of the removed delegate DSL. The release
  preparation helper resolves the matching installed 9.8.1 distribution; its regression fixture follows it.
- Kotlin 2.4.20 enables ABI validation through its current extension and task providers. Module names are
  pinned to their previous values, and language/API metadata remains 2.2 for existing Kotlin consumer
  compatibility. This does not disable any ABI or binary consumer check.
- Dokka 2.2.0 uses `dokkaGenerateHtml`, with the existing `build/dokka/html` publication path pinned for
  docs and Javadoc jars. Source generation still precedes documentation; active instructions are updated.
- The new ABI dumper omits five synthetic private-constructor bridges that are present in both old and
  new binaries. Only these tool-format entries were migrated in two baselines. Separate `javap -public -s`
  verification covers 431 public classes across all six libraries, and their member-signature sets match.
  Some compiler-generated method order changed; order is not a signature difference. Evidence:
  [signature sets](pr-integration-evidence-20261028/abi-signature-verification.json) and
  [the five bridges](pr-integration-evidence-20261028/abi-dump-migration.json).

## Verification

- Clean `npm ci` succeeds; frontend format/lint/type checks, 569 tests, fixture build and fresh live isolation pass.
- Live isolation's nine counterexamples pass. The original workbench and 17 authoring browser scenarios pass;
  the real CLI/template preview suite passes all 44 scenarios, including strict process/profile cleanup.
- The merged documentation catalog tests pass all 28 cases; release-preparation tests pass all 10 cases.
- The full integration gate is `./gradlew check :mantra-cli:installDist :mantra-cli:verifyDocumentationExamples checkAbiCounterexample checkCleanConsumer`, including all application tests, six library ABI gates, the real binary-break counterexample and fresh staged consumers. The integration PR carries the definitive CI result before main advances.

The first local full-check attempt exposed this container's existing orphan-process reaping limitation.
The full run uses an external task-owned subreaper wrapper, with cleanup assertions unchanged. Tool wrappers
are environment setup under `/tmp`, not product dependencies. No release signing or remote artifact publication
is part of this integration. Repository CI remains the authority for the final main-branch run.
