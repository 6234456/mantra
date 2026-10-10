# Contributing to Mantra

Mantra is a pre-1.0 general calculation-schema library. Domain demonstrations live in `apps/`;
tax or accounting rules do not belong in the engine or workbench.

## Build and verification

Use JDK 21, Git and Node.js 22.13+ (22.x) or 24+. Normal builds resolve the published
`com.xqiou:normein-dsl:0.3.0` kernel from Maven Central without a source checkout or private
credentials. Kernel development can explicitly select a clean checkout with `NORMEIN_BUILD_PATH`
or `-PnormeinBuildPath`; its commit must match `normein-build.lock` and it must not be patched as
part of a Mantra change. See [kernel integration](docs/normein-publication.md).

```bash
npm --prefix workbench-ui ci
./gradlew --no-daemon check
./gradlew :mantra-cli:installDist
scripts/smoke-cli.sh
python3 -m unittest discover -s scripts/tests
cd workbench-ui
npm test
npm run build
MANTRA_TEST_CHROME="/path/to/installed/chrome" npm run test:e2e
```

The browser runner uses an already installed Chrome-compatible browser in headless mode, with a
separate temporary profile. It stops its browser and Vite processes and removes its temporary files
on success, failure or interruption. Do not download browser bundles, use a personal profile or
change browser registration or defaults. Verify cleanup before reporting a browser test complete.
Prefer the Codex built-in Browser for interactive checks. Generated outputs belong under `build/`;
application papers and workbooks are written to `apps/<application>/build/out/`.

Run the checks relevant to a change and the complete verification required by CI. A new primitive
needs meaningful tests of its behavior, including independent acceptance values and formula coverage;
small documentation corrections do not need new tests.

## Local live development

On macOS or Linux (including WSL), install a full JDK 21 with `java` and `javac`, Git,
Node.js 22.13+ (22.x) or 24+, npm and Python 3.10+. Set `JAVA_HOME` to the JDK and put its
`bin` directory on `PATH`. From the repository root, start the workbench with one command:

```sh
scripts/dev.sh
```

The launcher checks the tools, installs frontend dependencies with `npm ci` when needed,
builds the CLI incrementally, starts the backend on `http://127.0.0.1:8080`, and starts live Vite
on `http://127.0.0.1:5173`. It verifies an actual calculation through Vite's API proxy before
opening your browser. Frontend changes use Vite hot reload; JVM changes require a restart.
The Vite development integration obtains the backend session metadata and preserves the backend's
Host and session-token checks. API writes carrying a foreign browser Origin are rejected.

Package schemas, parameters and layouts are captured at startup and remain read-only. The launcher
explicitly maps writable copies of demonstration cases under `build/dev/cases/`; input edits and
undo/redo affect those local copies. Existing copies survive restarts and are never overwritten.
Restart to reload changed package resources; changed manifest resources must also retain valid
package checksums. To reset a case, stop the launcher and remove only its copy under
`build/dev/cases/`, then restart. `--case-dir <directory>` selects a separate set of local case copies,
and `--workspace <directory>` selects another directory of manifest packages.
UI saves refresh the calculated documents automatically. If you edit a host-case copy with an
external text editor, reload the browser to see its changes; package mode does not subscribe to
the legacy workspace event stream.

```sh
scripts/dev.sh --no-browser        # start without opening a browser
scripts/dev.sh --check             # Gradle check, frontend tests, live HTTP checks, then stop
scripts/dev.sh --ui-port 5174      # use another Vite port; backend stays on 8080
scripts/dev.sh --reinstall         # force a fresh npm ci
scripts/dev.sh --skip-build        # reuse the installed CLI for frontend-only work
```

Press Ctrl+C to stop. On failures and cancellation, the launcher stops only its own services and
Gradle session; it preserves pre-existing processes and refuses occupied ports. Session logs and
generated configuration are kept under `build/dev/session-*/`. The session's isolated Gradle registry
reuses existing dependency caches, wrapper downloads and configured init scripts. On a fresh machine,
fallback caches persist under `build/dev/gradle-cache/`. The launcher never downloads a browser,
changes browser profiles, or exposes the servers beyond loopback.
`--check` does not run browser end-to-end tests; use the installed-browser command above when needed.
The real startup and cleanup flow is verified on Linux; physical macOS execution remains to be verified.

After a deliberate contract change, regenerate application papers and workbench fixtures with
`MANTRA_UPDATE_GOLDEN=1 ./gradlew --no-daemon test`. Regenerate the Compare fixture through the
CLI so its content revision stays authentic:

```sh
./gradlew --no-daemon :mantra-cli:installDist
mantra-cli/build/install/mantra/bin/mantra diff apps/de-est/schema.mantra \
  --case apps/de-est/case-mustermann.mantra --layout apps/de-est/layout.mantra \
  --variant-parameters apps/de-est/params-2026.mantra \
  --out mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/compare-2026.json
```

Review the changed expected values and run the checks again without `MANTRA_UPDATE_GOLDEN`.

## Design and boundaries

- Read [architecture](docs/architecture.md), the [engine/application contract](docs/engine-application-boundary.md)
  and [roadmap](docs/roadmap.md) before changing semantics.
- Read the [workbench contract](docs/workbench/contract.md) before changing workbench, server or UI behavior.
  Update the contract before implementation when interfaces change.
- Applications and output modules use public APIs. `core.api` and `core.view` expose calculation and
  result contracts; `core.engine` is internal to `mantra-core`.
- Add a kernel requirement to `docs/rfc/` and cover the adopted pinned behavior with a contract test.
  An unpinned candidate build is for assessment only and must never be released.
- New generic primitives need justification from at least two domains and must satisfy roadmap §2.3.
  New applications are separate `:apps:<name>` projects and must satisfy §2.4 before being called complete.
- Keep exact decimals and explicit rounding. Never perform business calculations in frontend code,
  data adapters or layouts. Report XLSX formula fallbacks explicitly.

## Style and documentation

The root `check` task runs Spotless/ktlint, public-API and application-boundary checks, source-size
checks and frontend format/lint/type checks alongside JVM tests. Install frontend dependencies first.
Use `./gradlew --no-daemon spotlessApply` and `npm --prefix workbench-ui run format` to format sources.
Handwritten Kotlin files are limited to 1,200 nonblank lines; TypeScript files allow 1,200
nonblank, noncomment lines. Code lines use a 120-character target (literal strings and URLs are exempt where splitting changes meaning).
Follow existing Kotlin and TypeScript conventions and the repository's format/check scripts. Prefer
small functions with clear responsibilities. Explain non-obvious constraints, source references and
intentional simplifications in comments. Use stable diagnostic codes and keep document locations useful.

Write public README, tutorial, DSL/API and application documentation in English. Design documents
may remain Chinese while translation proceeds. Update function catalogs, DSL reference, contracts and
relevant examples in the same change. Breaking changes to DSL, Kotlin APIs, JSON contracts or
`mantra.calc` need an explicit version change and a change record.

## Pull requests

Target the repository's default branch. Describe the concrete problem, resulting behavior and checks
performed. Include independent expected-value sources for calculation changes and screenshots when
presentation changes need visual review. Keep unrelated formatting and refactors separate.

Do not submit real taxpayer, customer, payroll or other confidential data. Use a minimal fictional
reproducer. Preserve third-party attribution and license files; add new sources to
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). IFRS example material is not covered by Mantra's
license merely because it is referenced by a test.

Contributions of project-owned code are under [Apache License 2.0](LICENSE).
