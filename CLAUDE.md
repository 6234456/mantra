# Mantra – project guidance

Calculation-schema engine for finance and tax computations on top of the Normein DSL. Read
`docs/architecture.md` before changing engine semantics.

## Boundaries (non-negotiable)

- **The engine library contains no business logic.** `mantra-core`, `mantra-render` and `mantra-cli`
  provide generic calculation components (section, line, field, total, choice, slot, dimension,
  allocation/banding/discounting/cross-footing functions), generic table components (table, columns,
  col, column-content functions) and common presets only. Tax law, accounting-standard steps and form
  layouts belong to domain schemas.
- **`examples/` are acceptance samples, not library code.** They use only the public API and are
  candidates for separate domain applications in a later monorepo.
- **Normein is consumed unchanged** at the commit in `normein-build.lock` (composite build of
  `normein-dsl`). Kernel-level needs go into an RFC under `docs/rfc/`, never into a patched kernel.
- **Presentation is a separate layer.** Layouts never change values; the layout DSL is owned by this
  project and extended here.
- **The workbench (web UI) is a generic reader and structured editor of Mantra documents; there is no
  UI DSL.** Read `docs/workbench/contract.md` before touching workbench, server or UI code. UI code
  contains no business logic: every displayed number and validation comes from the engine, and
  nothing branches on schema or node ids. Contract changes go into `contract.md` first. Work is split
  into packages in `docs/workbench/work-packages.md`.

## Commands

- Pinned kernel checkout: `scripts/bootstrap-normein.sh` (or `NORMEIN_BUILD_PATH=<clean checkout>`).
- Full verification: `./gradlew test` (core, render and acceptance examples).
- CLI: `./gradlew :mantra-cli:installDist`, then `mantra-cli/build/install/mantra/bin/mantra run|check|catalog`.
- Rendered acceptance papers are written to `examples/build/out/`.

## Conventions

- Kotlin, JVM toolchain 21, packages `com.xqiou.mantra.*`.
- Diagnostics use stable codes `MANTRA-<AREA>-<DETAIL>`; kernel functions fail with `DSL-MANTRA-*`.
- Numbers are exact `BigDecimal`; rounding is always explicit (`:round`, `decimal/*`).
- New calculation primitives need a unit test in `mantra-core` and should be justified by at least two
  domains; new presentation primitives need a test in `mantra-render`.
- Expected values in acceptance tests must be verifiable from an independent source (statute formula,
  published example, or an independent recomputation script).
