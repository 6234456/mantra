package com.xqiou.mantra.packages

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.SourceBinding
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText

/** Captures are immutable, transport-independent and detached from the directory/JAR lifetime. */
class PackageSnapshot internal constructor(
    val manifest: PackageManifest,
    manifestBytes: ByteArray,
    resources: Map<String, ByteArray>,
) {
    private val manifestCapture = manifestBytes.copyOf()
    private val bytes: Map<String, ByteArray> = resources.mapValues { it.value.copyOf() }
    val manifestSha256: String = digest(manifestCapture)
    val manifestByteLength: Long = manifestCapture.size.toLong()
    val totalByteLength: Long = manifestByteLength + bytes.values.sumOf { it.size.toLong() }

    /** Resource hashes are already covered by the captured manifest; bytes were checked against them. */
    val revision: String = manifestSha256

    fun manifestBytes(): ByteArray = manifestCapture.copyOf()

    fun bytes(path: String, role: PackageResourceRole? = null): ByteArray {
        val resource = descriptor(path)
        if (role != null &&
            resource.role != role
        ) {
            fail("MANTRA-PACKAGE-ROLE", "Resource $path is ${resource.role}, not $role")
        }
        return checkNotNull(bytes[path]).copyOf()
    }

    fun source(path: String, role: PackageResourceRole? = null): SourceText =
        SourceText("${manifest.identity.id}@${manifest.identity.version}/$path", utf8(bytes(path, role)), path)

    fun descriptor(path: String): PackageResource = manifest.resources.firstOrNull { it.path == logicalPath(path) }
        ?: fail("MANTRA-PACKAGE-UNLISTED", "Resource is not declared by manifest: $path")

    /** Only listed fragments/schema documents are authorized include targets. */
    fun schema(binding: PackageSchemaBinding): Schema {
        val entry = manifest.schemas.singleOrNull { it.binding == binding }
            ?: fail("MANTRA-PACKAGE-BINDING", "No exact schema binding: ${binding.identity}")
        val resolver = SourceResolver { reference, from ->
            val path = relativePath(reference, from?.base)
            val role = descriptor(path).role
            if (role !in setOf(PackageResourceRole.FRAGMENT, PackageResourceRole.SCHEMA)) {
                fail("MANTRA-PACKAGE-ROLE", "Include is not a schema or fragment: $path")
            }
            source(path)
        }
        val schema = Mantra.loadSchema(source(entry.path, PackageResourceRole.SCHEMA), resolver)
        if (schema.identity !=
            binding.identity
        ) {
            fail("MANTRA-PACKAGE-BINDING", "Schema bytes do not match manifest identity: ${binding.identity}")
        }
        return schema
    }

    fun case(id: String): CaseData {
        val entry = manifest.cases.singleOrNull { it.id == id } ?: fail("MANTRA-PACKAGE-BINDING", "No case: $id")
        val case = Mantra.loadCase(source(entry.path, PackageResourceRole.CASE))
        if (case.id != id || case.schemaId != entry.schema.identity.id ||
            (
                case.schemaVersion != entry.schema.identity.version &&
                    !(entry.schema.mode == SchemaVersionMode.LEGACY_EXACT && case.schemaVersion == null)
                )
        ) {
            fail("MANTRA-PACKAGE-BINDING", "Case bytes do not match their exact manifest binding: $id")
        }
        val authoredParameters = case.meta["parameters"]?.let { value ->
            (value as? Value.Vec)?.items?.map {
                (it as? Value.Text)?.value
                    ?: fail("MANTRA-PACKAGE-BINDING", "Case parameter IDs must be text")
            }
                ?: fail("MANTRA-PACKAGE-BINDING", "Case parameter bindings must be a vector")
        }
        if (authoredParameters != null &&
            authoredParameters != entry.parameters
        ) {
            fail("MANTRA-PACKAGE-BINDING", "Case parameter bindings disagree with manifest")
        }
        val authoredLayout = case.meta["layout"]?.let {
            (it as? Value.Text)?.value
                ?: fail("MANTRA-PACKAGE-BINDING", "Case layout must be text")
        }
        if (authoredLayout != null &&
            authoredLayout != entry.layout
        ) {
            fail("MANTRA-PACKAGE-BINDING", "Case layout disagrees with manifest")
        }
        val imported = case.sources.map { binding -> importPath(binding, entry.path) }
        if (imported.toSet().size !=
            imported.size
        ) {
            fail("MANTRA-PACKAGE-DUPLICATE", "Case imports the same resource twice")
        }
        return case
    }

    /** Host importers receive captured bytes. No fallback filesystem read is authorized. */
    fun dataSources(caseId: String): List<CapturedPackageData> {
        val entry =
            manifest.cases.singleOrNull { it.id == caseId } ?: fail("MANTRA-PACKAGE-BINDING", "No case: $caseId")
        return case(caseId).sources.map { binding ->
            val path = importPath(binding, entry.path)
            CapturedPackageData(binding, descriptor(path), bytes(path, PackageResourceRole.DATA))
        }
    }

    private fun importPath(binding: SourceBinding, from: String): String {
        val reference = (binding.options["path"] as? Value.Text)?.value
            ?: fail("MANTRA-PACKAGE-ROLE", "A package data source requires an explicit text :path")
        val path = relativePath(reference, from)
        if (descriptor(path).role !=
            PackageResourceRole.DATA
        ) {
            fail("MANTRA-PACKAGE-ROLE", "Import target is not declared DATA: $path")
        }
        return path
    }

    fun parameters(id: String): ParameterSet {
        val entry =
            manifest.parameters.singleOrNull { it.id == id } ?: fail("MANTRA-PACKAGE-BINDING", "No parameter set: $id")
        val set = Mantra.loadParameters(source(entry.path, PackageResourceRole.PARAMETERS))
        if (set.id != id || set.forSchema != entry.schema.identity.id) {
            fail("MANTRA-PACKAGE-BINDING", "Parameter bytes do not match their manifest binding: $id")
        }
        return set
    }

    fun layout(id: String): SourceText {
        val entry = manifest.layouts.singleOrNull { it.id == id } ?: fail("MANTRA-PACKAGE-BINDING", "No layout: $id")
        return source(entry.path, PackageResourceRole.LAYOUT)
    }
}

/** Registration is an explicit host capability. Manifest dependencies never grant read authority. */
class PackageCatalog {
    private val mounted = linkedMapOf<String, PackageSnapshot>()

    @Synchronized
    fun register(mount: String, snapshot: PackageSnapshot) {
        logicalPath(mount)
        if (mounted.keys.any { it == mount || it.startsWith("$mount/") || mount.startsWith("$it/") }) {
            fail("MANTRA-PACKAGE-DUPLICATE", "Package mounts overlap: $mount")
        }
        if (mounted.values.any { it.manifest.identity == snapshot.manifest.identity }) {
            fail("MANTRA-PACKAGE-DUPLICATE", "One package identity must have exactly one canonical mount")
        }
        mounted[mount] = snapshot
    }

    @Synchronized
    fun resolveCase(reference: String, from: String? = null): MountedPackageCase {
        val path = relativePath(reference, from)
        val mount = mounted.keys.singleOrNull { path.startsWith("$it/") }
            ?: fail("MANTRA-PACKAGE-UNREGISTERED", "Case is outside explicitly registered package mounts: $path")
        val snapshot = checkNotNull(mounted[mount])
        val local = path.removePrefix("$mount/")
        val case = snapshot.manifest.cases.singleOrNull { it.path == local }
            ?: fail("MANTRA-PACKAGE-BINDING", "Not a declared case: $path")
        if (from != null) {
            logicalPath(from)
            val source = mounted.entries.singleOrNull { from.startsWith("${it.key}/") }?.value
                ?: fail("MANTRA-PACKAGE-UNREGISTERED", "Source case is outside registered package mounts")
            val sourceMount = mounted.entries.single { it.value === source }.key
            if (source.manifest.cases.none {
                    it.path == from.removePrefix("$sourceMount/")
                }
            ) {
                fail("MANTRA-PACKAGE-BINDING", "Source path is not a declared case")
            }
            if (source !== snapshot && snapshot.manifest.identity !in source.manifest.dependencies) {
                fail("MANTRA-PACKAGE-DEPENDENCY", "Cross-package case requires an exact declared dependency")
            }
        }
        return MountedPackageCase(path, snapshot, case)
    }
}

data class MountedPackageCase(val canonicalPath: String, val snapshot: PackageSnapshot, val entry: PackageCase)

class CapturedPackageData internal constructor(
    val binding: SourceBinding,
    val resource: PackageResource,
    bytes: ByteArray,
) {
    private val captured = bytes.copyOf()
    fun bytes(): ByteArray = captured.copyOf()
    fun text(): String = utf8(captured)
}
