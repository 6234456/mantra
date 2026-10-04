package com.xqiou.mantra.lsp

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.ParticipatingSource
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.api.language.LanguageAnalysis
import com.xqiou.mantra.core.api.language.LanguageDefinition
import com.xqiou.mantra.core.api.language.LanguageDocumentContext
import com.xqiou.mantra.core.api.language.LanguageOccurrence
import com.xqiou.mantra.core.api.language.LanguageSpan
import com.xqiou.mantra.core.api.language.LanguageSymbolId
import com.xqiou.mantra.core.api.language.LanguageSymbolKind
import com.xqiou.mantra.core.api.language.MantraLanguage
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.CaseReader
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.ParameterSetReader
import com.xqiou.mantra.core.read.SchemaReader
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.nio.file.Path

internal data class ParsedSource(val source: SourceText, val document: Document, val kind: String, val id: String?)
data class PathTarget(val span: LanguageSpan, val uri: String)

data class WorkspaceAnalysis(
    val epoch: DocumentEpoch,
    val documents: Map<String, DocumentSnapshot>,
    val analyses: List<LanguageAnalysis>,
    val diagnostics: List<Diagnostic>,
    val issues: List<String>,
    internal val paths: List<PathTarget>,
) {
    val complete: Boolean get() = issues.isEmpty() && analyses.all { it.complete } &&
        diagnostics.none { it.severity == Severity.ERROR }
    val definitions: List<LanguageDefinition> get() = analyses.flatMap { it.definitions }.distinct()
    val occurrences: List<LanguageOccurrence> get() = analyses.flatMap { it.occurrences }.map { occurrence ->
        // Foreign link targets join the independently compiled, exact source owner. An input may
        // be a source node; no consumer-local kind is guessed from its identifier spelling.
        val id = occurrence.symbol
        val matches = definitions.filter {
            id != null && it.id.schema == id.schema && it.id.name == id.name &&
                it.id.kind != LanguageSymbolKind.LOCAL && it.id.kind != LanguageSymbolKind.FUNCTION &&
                (id.ownerSource == null || it.id.ownerSource == null || it.id.ownerSource == id.ownerSource)
        }
        if (id != null && definitions.none { it.id == id } &&
            matches.size == 1
        ) {
            occurrence.copy(symbol = matches.single().id)
        } else {
            occurrence
        }
    }.distinct()
    fun symbolAt(uri: String, offset: Int): LanguageSymbolId? {
        val uses = occurrences.filter { it.span.source == uri && it.span.contains(offset) }
        if (uses.any { !it.definite }) return null
        val symbols = (
            uses.mapNotNull { it.symbol } + definitions.filter {
                it.span.source == uri && it.span.contains(offset)
            }.map { it.id }
            ).distinct()
        return symbols.singleOrNull()
    }
    fun references(symbol: LanguageSymbolId, includeDeclaration: Boolean): List<LanguageSpan> = (
        occurrences.filter { it.symbol == symbol && it.definite }.map { it.span } +
            if (includeDeclaration) definitions.filter { it.id == symbol }.map { it.span } else emptyList()
        ).distinct()
}

/** Confined inventory plus real reader/compiler checks; there is no calculation or import path. */
interface LanguageAnalysisEngine {
    fun analyze(epoch: DocumentEpoch, checkpoint: () -> Unit = {}): WorkspaceAnalysis
    fun verifySources(analysis: WorkspaceAnalysis, checkpoint: () -> Unit = {})
}

class WorkspaceAnalyzer(private val roots: List<Path>) : LanguageAnalysisEngine {
    override fun verifySources(analysis: WorkspaceAnalysis, checkpoint: () -> Unit) {
        val sources = ConfinedSources(roots, analysis.epoch, checkpoint)
        sources.discover()
        val expected = analysis.documents.filterKeys { it.startsWith("file:") }
        if (sources.snapshots.keys !=
            expected.keys
        ) {
            throw RenameRefused("Workspace source inventory changed during rename")
        }
        expected.values.forEach { old ->
            checkpoint()
            if (sources.snapshots.getValue(old.uri).hash != old.hash) {
                throw RenameRefused("Source changed during rename: ${old.uri}")
            }
        }
    }
    override fun analyze(epoch: DocumentEpoch, checkpoint: () -> Unit): WorkspaceAnalysis {
        val resolver = ConfinedSources(roots, epoch, checkpoint)
        val sink = DiagnosticSink()
        val issues = mutableListOf<String>()
        val parsed = mutableListOf<ParsedSource>()
        val paths = mutableListOf<PathTarget>()
        val sources = try {
            resolver.discover()
        } catch (failure: IllegalArgumentException) {
            return WorkspaceAnalysis(
                epoch,
                resolver.snapshots,
                emptyList(),
                emptyList(),
                listOf(failure.message ?: "Workspace resolution failed"),
                emptyList(),
            )
        }
        sources.forEach { source ->
            checkpoint()
            val document = Document.read(source, sink) ?: return@forEach
            val root = document.root as? DslForm.Sequence
            val kind = root?.listHead
            if (kind !in setOf("schema", "fragment", "case", "parameters", "layout")) {
                issues += "Unsupported document kind in ${source.name}"
            } else {
                parsed += ParsedSource(source, document, kind!!, root?.values?.getOrNull(1)?.let(::name))
            }
        }
        val schemas = parsed.filter { it.kind == "schema" }.mapNotNull { item ->
            checkpoint()
            try {
                SchemaReader(resolver).read(item.source, sink)?.let { item to it }
            } catch (
                failure: IllegalArgumentException,
            ) {
                issues += failure.message ?: "Schema include resolution failed"
                null
            }
        }
        val cases = parsed.filter { it.kind == "case" }.mapNotNull { item ->
            CaseReader.read(item.source, sink)?.let { item to it }
        }
        val parameters = parsed.filter { it.kind == "parameters" }.mapNotNull { item ->
            ParameterSetReader.read(item.source, sink)?.let { item to it }
        }
        val layouts = parsed.filter { it.kind == "layout" }.onEach { LayoutReader.read(it.source, sink) }
        val analyses = mutableListOf<LanguageAnalysis>()
        val packages = linkedMapOf<CanonicalCaseKey, PreparedCasePackage>()
        val usedLayouts = mutableSetOf<String>()
        val usedParameters = mutableSetOf<String>()
        fun schemaFor(case: CaseData): Schema? {
            val candidates = schemas.map { it.second }.filter {
                it.id == case.schemaId &&
                    (case.schemaVersion == null || it.version == case.schemaVersion)
            }
            if (candidates.size != 1) {
                issues += "Case ${case.id} requires one exact schema ${case.schemaId}@${case.schemaVersion}"
                return null
            }
            return candidates.single()
        }
        val caseOwners = cases.mapNotNull { (source, case) ->
            schemaFor(case)?.let { source.source.name to it.identity }
        }.toMap()
        fun pathReferences(parsed: ParsedSource, case: CaseData?): Map<String, SchemaIdentity> {
            val bindings = linkedMapOf<String, SchemaIdentity>()
            case?.links?.forEach { link ->
                try {
                    val source = resolver.resolve(link.path, parsed.source) ?: error("Relative link has no owner")
                    val owner = caseOwners[source.name]
                    if (owner == null || owner.id != link.schema.id || owner.version != link.schema.version) {
                        issues += "Link ${link.path} has no matching exact source schema"
                    } else {
                        bindings[link.path] = owner
                    }
                } catch (
                    failure: IllegalArgumentException,
                ) {
                    issues += failure.message ?: "Link path escapes workspace"
                }
            }
            fun walk(form: DslForm) {
                val sequence = form as? DslForm.Sequence ?: return
                if (sequence.listHead == "include") {
                    val target = sequence.values.getOrNull(1)
                    val path = target?.string
                    if (path != null) {
                        try {
                            val resolved = resolver.resolve(path, parsed.source)
                            if (resolved != null) {
                                paths += PathTarget(
                                    LanguageSpan(
                                        parsed.source.name,
                                        target.span.startOffset + 1,
                                        target.span.endOffset - 1,
                                    ),
                                    resolved.name,
                                )
                            }
                        } catch (failure: IllegalArgumentException) {
                            issues +=
                                failure.message ?: "Include path escapes workspace"
                        }
                    }
                }
                sequence.values.forEach(::walk)
            }
            walk(parsed.document.root)
            return bindings
        }
        schemas.forEach { (schemaSource, schema) ->
            val ownedCases = cases.filter { caseOwners[it.first.source.name] == schema.identity }
            val contexts = if (ownedCases.isEmpty()) listOf(null) else ownedCases.map { it }
            contexts.forEach { entry ->
                checkpoint()
                val case = entry?.second ?: CaseData.empty("language-static")
                val selectedParameters = case.meta["parameters"].let { value ->
                    when (value) {
                        null -> emptyList()
                        is Value.Text -> {
                            issues += "Parameter metadata must be a vector in ${case.id}"
                            emptyList()
                        }
                        is Value.Vec -> value.items.mapNotNull { (it as? Value.Text)?.value }
                        else -> {
                            issues += "Unsupported parameter metadata in ${case.id}"
                            emptyList()
                        }
                    }
                }
                val sets = selectedParameters.mapNotNull { id ->
                    val matches = parameters.filter {
                        it.second.id == id &&
                            (it.second.forSchema == null || it.second.forSchema == schema.id)
                    }
                    if (matches.size != 1) {
                        issues += "Parameter set $id is missing or ambiguous"
                        null
                    } else {
                        usedParameters += matches.single().first.source.name
                        matches.single().second
                    }
                }
                val layoutId = case.text("layout")
                val selectedLayouts = if (layoutId == null) emptyList() else layouts.filter { it.id == layoutId }
                if (layoutId != null && selectedLayouts.size != 1) issues += "Layout $layoutId is missing or ambiguous"
                selectedLayouts.forEach { usedLayouts += it.source.name }
                val selectedSources = (
                    schema.sources.mapNotNull { uri ->
                        parsed.firstOrNull { it.source.name == uri }
                    } +
                        listOfNotNull(entry?.first) + parameters.filter { it.second in sets }.map { it.first } +
                        selectedLayouts
                    )
                    .distinctBy { it.source.name }
                val documents = selectedSources.map { item ->
                    val supplied = entry?.takeIf { item.source.name == it.first.source.name }?.second
                    val linkedOwners = supplied?.links.orEmpty().mapNotNull { link ->
                        resolver.resolve(link.path, item.source)?.let { link.path to it.name }
                    }.toMap()
                    LanguageDocumentContext(item.source, schema.identity, pathReferences(item, supplied), linkedOwners)
                }
                analyses += MantraLanguage.analyze(schema, case, sets, documents, checkpoint)
                if (entry != null) {
                    val participating = selectedSources.map { item ->
                        val snapshot = resolver.snapshots.getValue(item.source.name)
                        val role = when (item.kind) {
                            "schema" -> SourceRole.SCHEMA
                            "case" -> SourceRole.CASE
                            "parameters" -> SourceRole.PARAMETERS
                            "layout" -> SourceRole.LAYOUT
                            else -> SourceRole.INCLUDED
                        }
                        ParticipatingSource(
                            snapshot.uri,
                            role,
                            snapshot.hash,
                            snapshot.text.toByteArray(Charsets.UTF_8).size.toLong(),
                        )
                    }
                    val revisionText = participating.joinToString("\n") { it.identity + " " + it.sha256 }
                    val revision = DocumentSnapshot("metadata-revision", revisionText, null).hash
                    val key = CanonicalCaseKey(entry.first.source.name)
                    packages[key] = PreparedCasePackage(
                        key,
                        case.id,
                        schema.identity,
                        schema,
                        case,
                        sets,
                        participating,
                        revision,
                    )
                }
            }
            pathReferences(schemaSource, null)
        }
        // Bare parameter/layout documents cannot be assigned by proximity. A declared :for schema
        // is usable for parameter checking; an otherwise unbound document makes rename incomplete.
        parameters.filter { it.first.source.name !in usedParameters }.forEach { (source, parameter) ->
            val matching = schemas.filter { it.second.id == parameter.forSchema }
            if (matching.size != 1) {
                issues += "Parameter document ${source.source.name} has ambiguous schema ownership"
            } else {
                val schema = matching.single().second
                val documents = schema.sources.mapNotNull { uri -> parsed.firstOrNull { it.source.name == uri } }
                    .map { LanguageDocumentContext(it.source, schema.identity) } +
                    LanguageDocumentContext(source.source, schema.identity)
                analyses += MantraLanguage.analyze(schema, CaseData.empty(), listOf(parameter), documents, checkpoint)
            }
        }
        layouts.filter { it.source.name !in usedLayouts }.forEach {
            issues += "Layout ${it.source.name} has no explicit case context"
        }
        epoch.overlays.values.filter { it.uri.startsWith("untitled:") }.forEach {
            Document.read(SourceText(it.uri, it.text), sink)
            issues += "Unsaved document ${it.uri} has no workspace schema owner"
        }
        checkpoint()
        try {
            sink.addAll(staticGraphChecks(packages, checkpoint))
        } catch (
            failure: IllegalArgumentException,
        ) {
            issues += failure.message ?: "Static case graph is incomplete"
        }
        return WorkspaceAnalysis(
            epoch,
            resolver.snapshots + epoch.overlays,
            analyses.toList(),
            (sink.all + analyses.flatMap { it.diagnostics }).distinct(),
            issues.distinct(),
            paths.distinct(),
        )
    }
}

private fun name(form: DslForm): String? = form.symbol ?: form.keyword ?: form.string
