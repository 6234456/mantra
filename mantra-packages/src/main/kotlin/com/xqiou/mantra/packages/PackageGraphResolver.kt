package com.xqiou.mantra.packages

import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.ParticipatingSource
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import java.time.LocalDate

/** Imports receive captured package bytes; a filesystem fallback is never authorized. */
fun interface PackageDataImporter {
    fun import(schema: Schema, case: CaseData, sources: List<CapturedPackageData>, control: CaseLoadControl): CaseData
}

/** The host supplies an explicit date and selection policy for each participating case. */
data class PackageParameterChoice(
    val effectiveDate: LocalDate,
    val mode: ParameterSelectionMode,
    val candidateIds: List<String>,
    val requiredKeys: Set<String>,
)

fun interface PackageParameterPolicy {
    fun choice(case: MountedPackageCase): PackageParameterChoice?
}

data class PackageGraphBinding(
    val case: MountedPackageCase,
    val prepared: PreparedCasePackage,
    val selection: ParameterSelection?,
)

/**
 * A source-owned adapter to the ordinary core graph runner, including cross-package links.
 * Mounts grant read authority explicitly; manifest dependencies only constrain that authority.
 * Construct on the graph owner's thread. Captured packages remain read-only throughout a run.
 */
class PackageGraphResolver(
    private val catalog: PackageCatalog,
    private val importer: PackageDataImporter? = null,
    private val parameters: PackageParameterPolicy = PackageParameterPolicy { null },
) : CasePackageResolver {
    private val owner = Thread.currentThread()
    private val bindings = linkedMapOf<CanonicalCaseKey, PackageGraphBinding>()

    fun binding(key: CanonicalCaseKey): PackageGraphBinding {
        checkOwner()
        return bindings.getValue(key)
    }

    override fun identify(reference: CaseReference, control: CaseLoadControl): CanonicalCaseKey {
        checkOwner()
        control.checkpoint()
        return graphBoundary {
            CanonicalCaseKey(catalog.resolveCase(reference.path, reference.fromCase?.value).canonicalPath)
        }
    }

    override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage = graphBoundary {
        loadCaptured(key, control)
    }

    private fun loadCaptured(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage {
        checkOwner()
        control.checkpoint()
        val mounted = catalog.resolveCase(key.value)
        val snapshot = mounted.snapshot
        // Parsing and importing use fresh copies of these captured bytes. Charge each owned copy.
        val sources = mutableListOf<ParticipatingSource>()
        fun participate(path: String, role: SourceRole) {
            val resource = snapshot.descriptor(path)
            control.chargeParticipatingBytes(resource.byteLength)
            sources += ParticipatingSource(sourceIdentity(snapshot, path), role, resource.sha256, resource.byteLength)
        }
        control.chargeParticipatingBytes(snapshot.manifestByteLength)
        sources += ParticipatingSource(
            sourceIdentity(snapshot, "manifest.json"),
            SourceRole.INCLUDED,
            snapshot.manifestSha256,
            snapshot.manifestByteLength,
        )
        // The manifest covers every listed resource, including layout and import-template changes.
        // Each capture is bounded independently by PackageLimits before it reaches this adapter.
        snapshot.manifest.resources.forEach { resource ->
            participate(
                resource.path,
                when (resource.role) {
                    PackageResourceRole.SCHEMA -> SourceRole.SCHEMA
                    PackageResourceRole.CASE -> SourceRole.CASE
                    PackageResourceRole.PARAMETERS -> SourceRole.PARAMETERS
                    PackageResourceRole.LAYOUT -> SourceRole.LAYOUT
                    PackageResourceRole.DATA -> SourceRole.DATA
                    else -> SourceRole.INCLUDED
                },
            )
        }
        val schema = snapshot.schema(mounted.entry.schema)
        val authoredCase = snapshot.case(mounted.entry.id)
        val capturedData = snapshot.dataSources(mounted.entry.id)
        val effectiveCase = if (capturedData.isEmpty()) {
            authoredCase
        } else {
            val host = importer ?: fail("MANTRA-PACKAGE-IMPORT", "This package requires a captured-data importer")
            host.import(schema, authoredCase, capturedData, control).also { imported ->
                if (imported.id != authoredCase.id || imported.schemaId != authoredCase.schemaId ||
                    imported.schemaVersion != authoredCase.schemaVersion || imported.links != authoredCase.links
                ) {
                    fail("MANTRA-PACKAGE-IMPORT", "An importer changed source-owned case or link identity")
                }
            }
        }
        val choice = parameters.choice(mounted)
        val selection = choice?.let {
            val ids = java.util.List.copyOf(it.candidateIds)
            val keys = java.util.Set.copyOf(it.requiredKeys)
            when (it.mode) {
                ParameterSelectionMode.EFFECTIVE_DATE -> ParameterSelector.effectiveDate(
                    snapshot,
                    mounted.entry.schema,
                    it.effectiveDate,
                    ids,
                    keys,
                )
                ParameterSelectionMode.WHAT_IF -> ParameterSelector.whatIf(
                    snapshot,
                    mounted.entry.schema,
                    it.effectiveDate,
                    ids,
                    keys,
                )
            }
        }
        val selected = selection?.parameters ?: mounted.entry.parameters.map(snapshot::parameters)
        val revision = fingerprint(listOf(snapshot.revision, key.value, selection?.revision))
        val prepared = PreparedCasePackage(
            key,
            effectiveCase.id,
            schema.identity,
            schema,
            effectiveCase,
            selected,
            sources,
            revision,
        )
        bindings[key] = PackageGraphBinding(mounted, prepared, selection)
        return prepared
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Package graph resolvers must stay on their opening thread" }
    }

    private fun <T> graphBoundary(action: () -> T): T = try {
        action()
    } catch (error: PackageException) {
        throw MantraException(error.diagnostics).also { it.initCause(error) }
    }
}

internal fun sourceIdentity(snapshot: PackageSnapshot, path: String): String =
    "${snapshot.manifest.identity.id}@${snapshot.manifest.identity.version}/$path"
