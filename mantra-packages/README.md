# Captured calculation packages

M4 P3–P5 implementation preparation. This module has **not been compiled or executed**. It is
held outside the repository while M3 is verified. The source and tests require the root's normal
Gradle integration and formatting before they can be treated as accepted implementation.

The module depends on the public `mantra-core` API and pinned Jackson 2.18.3. It does not depend
on workbench, render, Excel, application projects, or internal Normein classes. A package contains
data and Mantra documents, never executable JVM plug-ins. Loading is read-only.

## Manifest and containers

`manifest.json` uses `format: "mantra.package/1"`, a package `id`, a strict SemVer `version`, an
`engine` comparator intersection, and explicit `resources`, `schemas`, `parameters`, `layouts`,
`cases`, and `dependencies` arrays. All arrays are required, including empty arrays. Schema
bindings include `id`, `version` (explicit null for unversioned legacy), and `versionMode`
(`semver` or `legacy-exact`). Resources include canonical relative POSIX `path`, `role`,
`byteLength`, and lowercase hexadecimal `sha256`. The exact JSON shape is exercised by
`PackageFixture`; unknown and duplicate JSON fields are errors.

`PackageLoader.directory(root, engineVersion, limits)` captures only declared resources using
directory-relative secure reads. Nested symlinks are rejected. `SecureDirectoryStream` is
required: unsupported filesystem providers fail closed with `MANTRA-PACKAGE-SECURE-READ`;
classpath JAR loading remains available. Explicit roots are resolved to a real directory before
opening the capability. No subsequent resource access reopens a path.

`PackageLoader.classpath(root, classLoader, engineVersion, limits)` requires exactly one manifest
URL and pins its **container**, including its exact JAR and resource prefix. It never looks up each
resource independently through the class loader. Only local file/JAR URLs are supported. JARs
are not extracted. This is not a standalone ZIP import API; the optional ZIP decision remains a
separate user choice. All JAR handles are closed after capture.

Host-supplied `PackageLimits` separately bound manifest bytes, each resource, total captured bytes,
resource count, JSON nesting, and JAR entry scanning. These are capabilities, not a claimed M4
performance budget. Integrity is checked against the exact captured bytes subsequently parsed.
Snapshots expose defensive byte copies; source mutation cannot change a retained snapshot.

Schema and case reads assert exact manifest identities. SemVer cases require an explicit case pin.
Explicit legacy bindings can preserve an authored absent case version without rewriting history.
Parameter IDs and `:for` bindings are checked. Authored parameter/layout bindings cannot disagree
with the manifest. Includes must resolve to listed schema/fragment resources. Imports must resolve
to listed DATA resources; the host receives `CapturedPackageData` rather than permission to reread
the filesystem. CSV/JSON/XLSX parsing remains the existing public importer's responsibility.

`PackageCatalog.register(mount, snapshot)` is the explicit cross-package access capability.
Overlapping mounts are rejected. A cross-package relative link needs both an authorized host
mount and an exact manifest dependency. A dependency alone grants no filesystem, classpath or
network read access. `MountedPackageCase` returns the logical canonical path used by the existing
`CaseGraphRunner` resolver; it does not evaluate or alter source-owned facts.

## Versions and parameter selection

`SemanticVersion` follows [SemVer 2.0.0](https://semver.org/): comparison ignores build metadata,
while exact package/schema identity retains its full spelling. Arbitrarily large numeric components
cannot overflow. `VersionRange` permits explicit whitespace-separated `>`, `>=`, `=`, `<`, `<=`
comparators only. Prereleases use normal mathematical SemVer precedence; there is no implicit npm
prerelease exclusion. `0.4.0-SNAPSHOT` therefore needs a compatible bound such as `>=0.4.0-0`.
Opaque historic `2025.2`, `0.1`, `1`, and null versions stay `LEGACY_EXACT`; they never enter ranges.

`ParameterSelector.effectiveDate(snapshot, schema, date, candidateIds, requiredKeys)` requires an
explicit date and candidate universe. It selects only the keys actually requested by the host.
Intervals are `[valid-from, valid-until)`; absent endpoints remain unbounded. It rejects overlaps
and gaps at the requested date rather than guessing a successor, default, latest set or today.
Multiple independent keys may come from different eligible sets.

`whatIf` accepts ordered explicit IDs and retains precedence. Its winning-key provenance records
whether the chosen value is eligible at the stated date. It never labels an out-of-interval choice
as valid. Both modes preserve literal values, schema eligibility, source references, package/resource
hashes and a revision that includes the date, requested keys and policy. Actual typed compilation
remains core's responsibility; this selector performs no coercion or domain arithmetic.

## Migration integration ports

`MigrationPlan` records immutable intent. `MigrationCoordinator.preview(plan)` (or the expanded
case/revision/source/target/operations overload) returns
exact original/candidate text, before/after genuine calculation results and a review token.
`CaseMigrationEditor` uses public form spans to pin schema/version, parameter/layout bindings or
apply a precisely matched explicit text patch. Unrelated facts, links and comments remain authored.
The old schema/package is retained; it is never rewritten by a migration.

`MigrationRuntime.current` and `evaluate` are host adapters over the public `CaseGraphRunner`.
The host must prepare exact source-owned bindings and return the actual target resource/context
revision. Evaluation must not overwrite source diagnostics or replace failed values with zero.
`commitIfCurrent` must hold the host's mutation/epoch lock across fresh graph/source revision checks
and the write callback. Merely checking a revision and releasing that lock is not conforming.

`FileMigrationStore(writableRoot, caseBytes)` is an **explicit** host write capability. It reads and
writes confined paths with secure directory handles, a JVM lock, an OS file lock and a source-byte
CAS. Same-directory temporary writes are forced and atomically moved through
[`SecureDirectoryStream.move`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html), with no non-atomic fallback;
temporary files are deleted on failure. The reusable hidden lock file is intentional store state.
This does not claim a multi-file transaction against arbitrary non-cooperating external writers.
The host epoch lock plus immediate graph revision check prevents cooperative source/target races,
and the store CAS independently prevents stale root-source replacement.

`apply(preview, reviewedToken)` re-evaluates under the host lock, rejects changed target/linked
source revisions, and commits once. BUSINESS errors remain reviewable and committable under R6;
technical errors and absent failed results cannot commit. No package/JAR is automatically writable.
The runtime/store ports avoid a packages→workbench dependency cycle; root workbench integration
maps these operations to its existing history/undo and typed graph response.

## Verification still required

The source-level JUnit tests cover strict versions/ranges, actual directory/JAR captures,
classpath shadowing, duplicate JSON, integrity and budgets, symlinks, captured includes/imports,
exact legacy pins, date boundaries/gaps/overlaps/what-if, explicit cross-package capabilities,
and genuine linked graph migration with stale root/source/target races, BUSINESS and technical
failures. They use public core calculation/graph APIs and actual temporary filesystem/JAR operations.
They have not been executed. No Gradle, formatter, browser, git, network download, or real repository
mutation was performed for this preparation.

Root integration must add the module to publication/API gates, catalog its diagnostic codes, bridge
package snapshots to `PreparedCasePackage` and importers with each epoch's byte charge ownership,
include captured manifest and parameter selection context in graph revisions, attach rendering
layout parsing, implement the workspace runtime/history bridge, then compile and run all tests.
Eight app manifests and the actual 10,000-case compiled lease demonstration follow this module
integration; neither is claimed complete here.
