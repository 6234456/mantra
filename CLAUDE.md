# Mantra – project guidance

General calculation-schema library on top of the Normein DSL. Read `docs/architecture.md` before
changing engine semantics and `docs/roadmap.md` for the accepted R1–R10 decisions and milestone scope.

## Boundaries (non-negotiable)

- **The engine library contains no business logic.** `mantra-core`, `mantra-render`, `mantra-excel`,
  `mantra-cli`, `mantra-workbench` and `mantra-server` provide generic calculation, presentation and
  tooling capabilities only. Tax law, accounting-standard steps and form layouts belong to schemas.
- **This is a monorepo.** `apps/<application>` contains domain demonstration applications, each a
  Gradle project `:apps:<application>` depending only on public library APIs. Applications are not
  published as library artifacts. New domains go directly under `apps/`.
- **Applications are demonstrations only**, not production tax or accounting software. Their English
  READMEs document verification sources, running instructions and simplifications. Mark schema
  simplifications with comments. Use fictional case data and check copied source rights.
- **Public calculation and result contracts belong in `core.api` and `core.view`.** Consumers may also
  use public document models, diagnostics, structure and data adapters. No module outside
  `mantra-core` imports `core.engine`; planners, compiled expressions and execution caches are internal.
- **Normein is consumed unchanged** as `com.xqiou:normein-dsl:0.3.0` from Maven Central by default.
  Explicit source builds enforce the commit in `normein-build.lock`. Kernel-level needs go into
  an RFC under `docs/rfc/`, never into a patched kernel.
  Coordinate kernel requirements and releases by milestone (R4); never release an unpinned candidate.
- **Presentation is a separate layer.** Layouts never change values; the layout DSL is owned by this
  project and extended here. Core, Text/HTML, XLSX and Explain must agree on values; report formula
  fallbacks explicitly.
- **The workbench is a generic reader and structured editor; there is no UI DSL.** Read
  `docs/workbench/contract.md` before touching workbench, server or UI code. Every displayed number
  and validation comes from the engine; nothing branches on schema or node ids. Contract changes go
  into `contract.md` first. Workbench v1 is implemented; milestone work is split under
  `docs/milestones/`, following `docs/workbench/work-packages.md`.
- **Validation reports rather than blocks calculation or saving** when it is a business check (R6).
  Invalid document syntax, input types, references or formula bindings retain their existing rejection
  behavior. Old schema versions coexist; case migration must be explicit and reviewable (R7).

## Commands

- Default kernel: Maven Central; no checkout or private credentials required.
- Optional pinned checkout: `scripts/bootstrap-normein.sh`; `NORMEIN_SOURCE` selects a remote or local clone.
  Enable it explicitly with `NORMEIN_BUILD_PATH` or `-PnormeinBuildPath=<dir>`.
- Verification: `npm --prefix workbench-ui ci` then `./gradlew --no-daemon check` (JVM,
  Kotlin format/style, source size, boundaries and frontend format/lint/type checks).
- CLI: `./gradlew :mantra-cli:installDist`, then
  `mantra-cli/build/install/mantra/bin/mantra run|check|catalog|fixtures|diff|explain|serve`.
- Application outputs: `apps/<application>/build/out/`.
- Frontend: in `workbench-ui`, `npm ci`, `npm test`, `npm run build` and `npm run test:e2e`.
  Build the live frontend with `VITE_WORKBENCH_MODE=live npm run build`; start `mantra serve apps`.
- Browser checks: prefer the Codex built-in Browser. E2E uses an installed executable selected by
  `MANTRA_TEST_CHROME` and a temporary task profile. Never install browser bundles, modify normal
  profiles or leave task-owned browser processes, profiles, caches or logs behind.

## Conventions

- Kotlin, JVM toolchain 21, packages `com.xqiou.mantra.*`.
- Diagnostics use stable codes `MANTRA-<AREA>-<DETAIL>`; kernel functions fail with `DSL-MANTRA-*`.
- Numbers are exact `BigDecimal`; rounding is always explicit (`:round`, `decimal/*`).
- New calculation primitives require `mantra-core` tests and justification from at least two domains;
  presentation primitives require `mantra-render` tests. Follow the primitive definition of done in
  `docs/roadmap.md` §2.3, including trace, export coverage, catalog and workbench behavior.
- Expected values must be verifiable from an independent source: statute formula, published example
  or independent recomputation script. Lock the source before implementation.
- Follow the application completeness standard in `docs/roadmap.md` §2.4. Keep library work generic
  and avoid expanding a demonstration into a production domain application.
- Public README, tutorial, DSL/API and application documentation is English; design contracts may
  remain Chinese while translation proceeds (R8).
- Version DSL, Kotlin APIs, JSON contracts and `mantra.calc` independently; document breaking changes
  and update the relevant version (C8). M0 prepares the repository for publication; library artifact
  publication is coordinated with `normein-dsl`, no earlier than M1 (R10).
