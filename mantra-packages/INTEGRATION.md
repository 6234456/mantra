# Root integration handoff (uncompiled preparation)

All new files are inside `/private/tmp/mantra-m4-packages-preparation/mantra-packages`.
Only the copied `settings.gradle.kts` additionally includes `:mantra-packages`. No existing core,
render or workbench source has been changed. The actual repository was not edited.

## P3 public entry points

```kotlin
PackageLoader.directory(root: Path, engineVersion: SemanticVersion, limits: PackageLimits): PackageSnapshot
PackageLoader.classpath(root: String, loader: ClassLoader, engineVersion: SemanticVersion, limits: PackageLimits): PackageSnapshot
snapshot.schema(binding: PackageSchemaBinding): Schema
snapshot.case(id: String): CaseData
snapshot.parameters(id: String): ParameterSet
snapshot.layout(id: String): SourceText
snapshot.dataSources(caseId: String): List<CapturedPackageData>
snapshot.source(path: String, role: PackageResourceRole? = null): SourceText
snapshot.bytes(path: String, role: PackageResourceRole? = null): ByteArray
snapshot.manifestBytes(): ByteArray
PackageCatalog.register(mount: String, snapshot: PackageSnapshot)
PackageCatalog.resolveCase(reference: String, from: String? = null): MountedPackageCase
```

`PackageSnapshot.manifest` describes exact identities/bindings; `manifestSha256`,
`manifestByteLength`, `totalByteLength` and `revision` describe the captured immutable container.
Every resource's size/digest is available through `descriptor(path)` or manifest.resources.
The snapshot remains usable after directory changes or closing its source class loader.

The root's generic package `CasePackageResolver` adapter must identify canonical mounted cases,
load the source's own schema/parameters/layout/data, and return `PreparedCasePackage`. Retain
the authored links. Run them through the existing public `CaseGraphRunner`; do not calculate
links or source diagnostics in a consumer. A manifest dependency alone cannot authorize a mount.
No virtual filesystem path may trigger an importer's default filesystem read: pass the captured
CSV/JSON text or XLSX bytes into the public importer/host import port.

Every graph resolver epoch owns its byte-charge set. Include the captured manifest as an
`INCLUDED` participating source (until a reviewed core role extension exists), with its actual
digest and byte length. Include actual selected document/data descriptors. Canonical identities
must be transport-independent, e.g. package ID + full version + logical path. Cached package
bytes remain part of the participating-input budget; do not retain a prior epoch's charged set.
Package capture limits are separate explicit host limits, not a fabricated M4 timing budget.

P3 currently requires `SecureDirectoryStream` for directory loading and writable migration.
The host can use classpath JAR containers on unsupported providers. Do not add a silent unsafe
filesystem fallback merely to make a Windows test pass; support requires a separately reviewed
provider strategy. Java's atomic secure-directory move may reject replacing an existing target
on some providers; rejection retains the original and cleans the temporary file.

## P4 public entry points

```kotlin
SemanticVersion.parse(text: String): SemanticVersion
VersionRange.parse(text: String): VersionRange
range.contains(version: SemanticVersion): Boolean
ParameterSelector.effectiveDate(snapshot, schemaBinding, explicitDate, candidateIds, requiredKeys): ParameterSelection
ParameterSelector.whatIf(snapshot, schemaBinding, explicitDate, orderedIds, requiredKeys): ParameterSelection
```

`ParameterSelection.parameters` contains only requested keys; its immutable provenance identifies
the winning set/key, effective date, half-open interval, eligibility, exact schema/package/resource
hash and reference. Core remains responsible for typed compilation, with no selection coercion.
`selection.revision` must enter the prepared case revision even when another date selects the same
values. Do not infer dates or intervals from a filename/year, display label or neighboring set.

Each host call explicitly names its candidate universe and keys; unrelated precision variants
are not accidentally included in automatic eligibility. Existing unbounded `valid-from` files
genuinely overlap if included together. The package app manifests/migration preparation must
either use an explicit reviewed interval update or preserve that ambiguity as a diagnostic.
The source selector never rewrites frozen historic parameter facts.

## P5 runtime and writer ports

```kotlin
interface MigrationRuntime {
    fun current(casePath: String): MigrationState
    fun evaluate(casePath: String, candidate: SourceText, target: MigrationTarget): MigrationEvaluation
    fun commitIfCurrent(casePath: String, graphRevision: String, sourceSha256: String, commit: () -> Unit)
}
interface MigrationStore {
    fun read(casePath: String): SourceText
    fun commit(casePath: String, sourceSha256: String, candidate: SourceText, authorize: (write: () -> Unit) -> Unit)
}
fun interface MigrationEditor {
    fun apply(source: SourceText, operations: List<MigrationOperation>): SourceText
}
```

`MigrationState` includes the actual resolved source schema, original graph revision, original case
byte hash, detached result (possibly absent for technical failure) and typed original diagnostics.
`MigrationEvaluation` includes the observed target identity/resource/selection context, actual
candidate graph revision, detached result and typed graph diagnostics. Returning the requested
target unchanged without checking its live resource revision would violate the port.

`MigrationPlan(casePath, baseRevision, sourceBinding, target, operations)` records immutable intent.
`MigrationCoordinator.preview(plan)` (or the expanded overload with these arguments) does
no write. `apply(preview, reviewedToken)` requires the exact review token, original byte CAS,
host-held graph/epoch mutation lock, a fresh technical-successful target evaluation and unchanged
candidate graph revision. Its result schema is checked against the target exact identity. It
retains BUSINESS diagnostics and refuses technical errors/results. The current `CaseMigrationEditor`
supports source-preserving schema pin, parameter/layout binds and explicit expected-text patches.
The host may inject a source-preserving editor; there is no packages→workbench dependency.

`FileMigrationStore(explicitWritableRoot, caseBytes)` is one genuine confined filesystem writer.
Workbench's existing atomic write/history mechanism can implement `MigrationStore` instead, so
migration shares history/undo and does not establish conflicting lock order. Whichever store is
used, the host's `commitIfCurrent` adapter must hold its mutation lock through the write callback.
A check-before-lock/check-then-unlock adapter is incorrect. Multi-file races from external programs
are not claimed to be full filesystem transactions.

## Integration checklist

1. Copy only the new module, add its settings include, and run the pinned formatter/compiler/tests.
2. Add it to ABI publication, public-surface/build/test-count/diagnostic catalogs and CI gates.
3. Connect the generic package graph/import/render adapter without domain IDs or a dependency cycle.
4. Connect strict schema-version/date/provenance and migration to the workbench revision/history API.
5. Build eight reviewed manifests from actual frozen resources. Include all schema versions and
   explicit legacy modes; do not mutate historic hashes merely to satisfy a SemVer parser.
6. Move public application acceptance through both directory and classpath-JAR package loading,
   then test original version coexistence, ESt migration and source-owned linked versions/parameters.
7. Connect the real compiled lease 10,000-case stream once the P1/P2 API is integrated and tested.

Only static source inspection has occurred here. The 22 JUnit test declarations are uncompiled and
unexecuted. No successful Maven publication, workbench wire integration, Windows provider support,
application package acceptance, migration browser flow or batch performance result is claimed.
