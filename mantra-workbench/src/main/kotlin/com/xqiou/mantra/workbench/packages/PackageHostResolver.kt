package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.ParticipatingSource
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.packages.MountedPackageCase
import com.xqiou.mantra.packages.PackageCatalog
import com.xqiou.mantra.packages.PackageException
import com.xqiou.mantra.packages.PackageGraphResolver
import com.xqiou.mantra.packages.PackageParameterChoice
import com.xqiou.mantra.packages.PackageParameterPolicy
import com.xqiou.mantra.packages.PackageResourceRole
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.packages.ParameterSelection
import com.xqiou.mantra.packages.ParameterSelectionMode
import com.xqiou.mantra.packages.ParameterSelector
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.workbench.PackageImports
import java.nio.ByteBuffer
import java.security.MessageDigest

/** One request-local resolver, created and used on the graph runner's thread. */
internal class PackageHostResolver(
    private val catalog: PackageCatalog,
    private val policies: Map<String, PackageParameterChoice>,
    private val overlays: Map<String, EditablePackageCase>,
    private val candidates: Map<String, SourceText> = emptyMap(),
) : CasePackageResolver {
    data class Binding(
        val resourceCase: MountedPackageCase,
        val prepared: PreparedCasePackage,
        val selection: ParameterSelection?,
        val layout: LayoutSpec?,
        val source: SourceText,
        val editable: Boolean,
    )
    private val delegate =
        PackageGraphResolver(catalog, PackageImports, PackageParameterPolicy { policies[it.canonicalPath] })
    val bindings = linkedMapOf<CanonicalCaseKey, Binding>()

    override fun identify(reference: CaseReference, control: CaseLoadControl): CanonicalCaseKey =
        delegate.identify(reference, control)

    override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage = try {
        loadOwned(key, control)
    } catch (error: PackageException) {
        throw MantraException(error.diagnostics).also { it.initCause(error) }
    } catch (error: IllegalArgumentException) {
        throw invalid(error.message ?: "Invalid host package binding", error)
    } catch (error: IllegalStateException) {
        throw invalid(error.message ?: "Invalid host package binding", error)
    }

    private fun loadOwned(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage {
        control.checkpoint()
        val origin = catalog.resolveCase(key.value)
        val editable = overlays[key.value]
        if (editable == null) {
            val prepared = delegate.load(key, control)
            val selection = delegate.binding(key).selection
            val layout = origin.entry.layout?.let {
                com.xqiou.mantra.render.layout.LayoutReader.read(origin.snapshot.layout(it))
            }
            bindings[key] =
                Binding(origin, prepared, selection, layout, origin.snapshot.source(origin.entry.path), false)
            return prepared
        }
        val source = candidates[key.value] ?: editable.store.read(editable.storePath)
        val sourceBytes = source.text.toByteArray(Charsets.UTF_8)
        require(sourceBytes.size <= editable.maxBytes) { "Writable case exceeds host byte limit" }
        control.chargeParticipatingBytes(sourceBytes.size.toLong())
        val authored = Mantra.loadCase(SourceText(key.value, source.text, key.value))
        require(authored.id == origin.entry.id) { "An editable host case cannot change source-owned case identity" }
        val pin = PackageHostPins.read(authored)
        // Pinning resources is an explicit host capability, distinct from declaring a new link.
        val resourceCase = pin?.let { catalog.resolveCase(it.case) } ?: origin
        val snapshot = resourceCase.snapshot
        require(pin == null || pin.revision == snapshot.revision) {
            "Pinned host package revision differs from the captured package"
        }
        require(
            authored.schemaId == resourceCase.entry.schema.identity.id &&
                authored.schemaVersion == resourceCase.entry.schema.identity.version,
        ) {
            "Writable case and exact package schema binding differ; use explicit migration"
        }
        val choice = pin?.choice ?: if (pin == null) policies[key.value] else null
        val selection = choice?.let {
            when (it.mode) {
                ParameterSelectionMode.EFFECTIVE_DATE -> ParameterSelector.effectiveDate(
                    snapshot,
                    resourceCase.entry.schema,
                    it.effectiveDate,
                    it.candidateIds,
                    it.requiredKeys,
                )
                ParameterSelectionMode.WHAT_IF -> ParameterSelector.whatIf(
                    snapshot,
                    resourceCase.entry.schema,
                    it.effectiveDate,
                    it.candidateIds,
                    it.requiredKeys,
                )
            }
        }
        val parameterIds = choice?.candidateIds ?: resourceCase.entry.parameters
        val authoredIds = (authored.meta["parameters"] as? Value.Vec)?.items?.map {
            (it as? Value.Text)?.value ?: error("Parameter bindings must contain text")
        }
        require(authored.meta["parameters"] == null || authoredIds == parameterIds) {
            "Authored parameters differ from explicit host selection"
        }
        val schema = snapshot.schema(resourceCase.entry.schema)
        // Source data is still owned by the original captured case. Editing paths cannot authorize reads.
        val captured = origin.snapshot.dataSources(origin.entry.id)
        require(
            authored.sources.map { it.kind to it.options } ==
                origin.snapshot.case(origin.entry.id).sources.map { it.kind to it.options },
        ) {
            "Host edits cannot change captured data sources; mount another reviewed package explicitly"
        }
        val effective = if (captured.isEmpty()) {
            authored
        } else {
            PackageImports.import(
                schema,
                authored.copy(
                    sources = captured.map {
                        it.binding
                    },
                ),
                captured,
                control,
            )
        }
        val parameters = selection?.parameters ?: resourceCase.entry.parameters.map(snapshot::parameters)
        val layoutId = (authored.meta["layout"] as? Value.Text)?.value ?: resourceCase.entry.layout
        require(
            layoutId == null ||
                snapshot.manifest.layouts.any { it.id == layoutId && it.schema == resourceCase.entry.schema },
        ) {
            "Layout does not belong to the exact target schema"
        }
        val sources = linkedMapOf<String, ParticipatingSource>()
        fun participate(packageSnapshot: PackageSnapshot) {
            fun add(path: String, role: SourceRole, hash: String, size: Long) {
                val identity = "${packageSnapshot.manifest.identity.id}@" +
                    "${packageSnapshot.manifest.identity.version}/$path"
                if (sources.containsKey(identity)) return
                control.chargeParticipatingBytes(size)
                sources[identity] = ParticipatingSource(identity, role, hash, size)
            }
            add(
                "manifest.json",
                SourceRole.INCLUDED,
                packageSnapshot.manifestSha256,
                packageSnapshot.manifestByteLength,
            )
            packageSnapshot.manifest.resources.forEach {
                val role = when (it.role) {
                    PackageResourceRole.SCHEMA -> SourceRole.SCHEMA
                    // Only the host overlay is the effective CASE.
                    PackageResourceRole.CASE -> SourceRole.INCLUDED
                    PackageResourceRole.PARAMETERS -> SourceRole.PARAMETERS
                    PackageResourceRole.LAYOUT -> SourceRole.LAYOUT
                    PackageResourceRole.DATA -> SourceRole.DATA
                    else -> SourceRole.INCLUDED
                }
                add(it.path, role, it.sha256, it.byteLength)
            }
        }
        participate(origin.snapshot)
        participate(snapshot)
        val identity = "host:${key.value}"
        sources[identity] =
            ParticipatingSource(identity, SourceRole.CASE, hostHash(sourceBytes), sourceBytes.size.toLong())
        val revision =
            hostFingerprint(
                listOf(
                    origin.snapshot.revision,
                    snapshot.revision,
                    key.value,
                    hostHash(sourceBytes),
                    selection?.revision,
                ),
            )
        val prepared =
            PreparedCasePackage(
                key,
                effective.id,
                schema.identity,
                schema,
                effective,
                parameters,
                sources.values.toList(),
                revision,
            )
        val layout = layoutId?.let { com.xqiou.mantra.render.layout.LayoutReader.read(snapshot.layout(it)) }
        bindings[key] = Binding(resourceCase, prepared, selection, layout, source, true)
        return prepared
    }

    private fun invalid(message: String, cause: Throwable): MantraException =
        MantraException(listOf(Diagnostic(Severity.ERROR, "MANTRA-PACKAGE-HOST-BINDING", message))).also {
            it.initCause(cause)
        }
}

internal fun hostHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
    "%02x".format(it)
}
internal fun hostFingerprint(parts: List<String?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach {
        val bytes = it?.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
        if (bytes != null) digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
