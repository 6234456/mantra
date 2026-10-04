# Captured packages and explicit host boundaries

Repository applications provide `manifest.json` packages containing schemas, exact
bindings, parameter sets, layouts, cases, imports and documentation. They remain
showcases distributed with the repository; they are not published as library artifacts.
Use [public Kotlin embedding](site:embedding.html) for the current API and
[the gallery](site:apps/index.html) for all ten applications. Current acceptance reports,
not this documentation page, determine milestone completion and release status.

## Capture declared bytes

The public package loader supports an explicit directory and a fixed local classpath/JAR
container. The manifest declares a strict SemVer package identity, engine comparator
intersection, relative resource paths, roles, byte lengths and SHA-256 hashes. Loading
captures immutable resource bytes; subsequent parsing and revision evidence refer to
that capture. Undeclared include/import resources, duplicates, path escapes, mismatched
hashes and resource-budget violations are errors.

The supported JAR is a classpath container. It does not imply arbitrary ZIP upload,
network downloading or extraction. Dependency declarations identify packages; a host
must explicitly mount a resolver for permitted cross-package links. Imports read the
captured data through an explicit host port rather than falling back to unrelated files.
Classpath packages are read-only.

## Choose directory authority explicitly

`DirectoryPolicy.STRICT_HANDLES` is the default and never silently falls back when a
filesystem lacks `SecureDirectoryStream`. The explicit `TRUSTED_LOCAL` policy checks
canonical path components, rejects resource symlinks, compares file identity/size/time
before and after capture and verifies hashes. It assumes a cooperative trusted local
directory; Java before/after checks do not defend against a malicious process with the
same permissions performing a rename-and-restore race. A host may inject its own
`DirectoryAccess` capability with stronger native guarantees.

Keep source packages read-only. A writable case or migration destination must live in
an explicitly supplied host directory. Package read authority does not grant write
authority or access to other local/network sources. Check the library defaults and
current CLI policy flags before running on the intended platform.

## Preserve exact versions and dates

New package/schema versions use strict SemVer; supported compatibility ranges are
explicit comparator intersections, with no implicit latest selection. Historical
schema spellings such as `2025.2`, `2025.3`, `0.1` and unversioned identities keep exact
legacy matching. Loading does not rewrite their strings or pad missing components.

A host explicitly supplies the effective date for validity-based parameter selection.
Intervals are `[valid-from, valid-until)`; overlaps and gaps are diagnostics rather
than an arbitrary first match. Explicit what-if layers preserve their date eligibility
and source provenance. Merely loading a case's authored parameter IDs does not infer
today's date or claim that those parameters are legally current.

## Review a migration before writing

The [income-tax migration example](repo:apps/de-est/migration/README.md) prepares and
previews exact schema/parameter/layout changes before an explicit apply. The plan
retains the original revision and concrete edits. Revision changes reject stale apply;
source and target versions coexist, and old facts can still run with their old schema.
The writable host root, lock/CAS behavior and atomic file replacement are explicit.

The [lease streaming example](repo:apps/ifrs-leases/batch/README.md) uses distinct typed
cases and a public compiled template. Its independent JSONL compares every declared
numeric value. See [measured batch evidence](repo:docs/performance-m4.md) for the actual
10,000-case run, timer scope, memory observations and physical compilation/session
counts; these measurements do not enlarge the pinned kernel's input limits.

## Workbook and publication limits

Ordinary XLSX exports retain the initial row shape. Dynamic tables require explicit
`ExcelOptions.dynamicTableCapacities`; capacity bounds calculation slots and is not an
unlimited table-size promise. Supported structured-table edits must recalculate
members, relations, period balances, aggregates and snapshot status. Exceeding capacity
is visible. Inspect fallback/evaluation reports; native Excel interaction and POI row
operations have distinct validation evidence, and one must not be presented as the other.

An editable workbook's audit appendix belongs to the generation-time snapshot. Value
or record-shape edits mark that evidence stale; it is not a new audited engine run.
Re-export to capture current evidence. Supported bounded convergence helpers do not
enable unrestricted Excel iterative calculation.

Public source code, local staging and a clean consumer test are separate from actual
Maven publication. Namespace, signing, repository access and the pinned Normein
artifact remain publication prerequisites. No external artifact release, v1.0 label
or production certification is announced by this offline site.
