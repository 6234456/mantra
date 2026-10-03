# Contributing to Mantra

Mantra is a pre-1.0 general calculation-schema library. Domain demonstrations live in `apps/`;
tax or accounting rules do not belong in the engine or workbench.

## Build and verification

Use JDK 21, Git and Node.js 22.13 or newer. Bootstrap a clean Normein checkout at the commit in
`normein-build.lock`; do not patch it as part of a Mantra change.

```bash
NORMEIN_SOURCE=https://github.com/6234456/normein.git scripts/bootstrap-normein.sh
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
