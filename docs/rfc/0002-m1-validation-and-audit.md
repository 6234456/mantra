# RFC 0002: M1 validation and audit integration

Status: accepted host integration; implementation in progress (2026-10-04).

M1 uses the unchanged `normein-dsl` 0.3.0 commit in `normein-build.lock`. This RFC records the
milestone coordination required by roadmap R4; it does not authorize patching the kernel checkout.

## Host-owned capabilities

Named checks and reconciliations, conditional input requirements, minimum table cardinality,
coordinate/row/column diagnostics and ratio aggregation belong to Mantra's schema, plan and view.
They compile ordinary Boolean or numeric expressions using existing kernel types and evaluation.
Business findings do not stop calculation or saving; malformed documents and bindings remain errors.
The detailed host contract is in [M1 work packages](../milestones/m1-work-packages.md).

## Kernel capabilities consumed

- Public form reader and typed expression compilation with host source positions.
- Table-row binding using the host-declared record type, with no domain-specific kernel functions.
- Exact decimal arithmetic; finite division or explicitly requested decimal rounding.
- Existing RFC 0001-F source-level evaluation steps and branch decisions, including truncation and
  rendered values when raw values are unavailable.

Mantra adds a bounded multi-coordinate audit capture and projects each captured trace through the
same public adapter as Explain. It must not recreate steps by replacing root symbols. Both individual
coordinate limits and total collection limits are visible in the result and renderers.

No new kernel extension is required by the currently accepted M1 design. Contract tests will verify
this against the pinned commit. If implementation reveals a missing kernel behavior, record the
specific input, expected API/trace and affected domains here, and adopt only a reviewed released
kernel revision; never silently emulate or patch it in `.deps/normein`.
