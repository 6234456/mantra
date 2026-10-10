package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.options
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.workbench.WorkspaceCatalog.DocumentResult
import com.xqiou.mantra.workbench.WorkspaceCatalog.EditHistory
import com.xqiou.mantra.workbench.WorkspaceCatalog.Resolved
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.normein.dsl.form.DslForm
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

data class TemplateSourceEdit(val handle: String, val text: String)
data class TemplateInputText(val node: String, val text: String)
data class TemplatePreviewRequest(
    val baseRevision: String,
    val baseRevisions: Map<String, String>,
    val draftSequence: Long,
    val documents: List<TemplateSourceEdit>,
    val inputs: List<TemplateInputText>,
    val panel: String? = null,
    val includeZero: Boolean = false,
    val explain: ExplainAddress? = null,
)

private data class TemplateDocument(
    val source: SourceText,
    val role: SourceRole,
    val hash: String,
    val handle: String,
) {
    val editable: Boolean get() = role == SourceRole.SCHEMA || role == SourceRole.LAYOUT
    fun data(): Map<String, Any?> = linkedMapOf(
        "handle" to handle,
        "document" to source.name,
        "role" to role.name.lowercase(),
        "text" to source.text,
        "sha256" to hash,
        "editable" to editable,
        "reason" to if (editable) null else "Only the root schema and layout can be replaced in a template preview",
    )
}

private data class TemplateSnapshot(val revisions: Map<String, String>, val documents: List<TemplateDocument>)

private fun templateSnapshot(base: Resolved): TemplateSnapshot {
    val graph = checkNotNull(base.graph)
    val revisions = graph.cases.values.flatMap { it.sources }.associate { it.identity to it.sha256 }.toSortedMap()
    val root = checkNotNull(graph.root)
    val sources = graph.cases.getValue(root).sources.associateBy { it.identity }
    val documents = base.caseSources.getValue(root).values.sortedBy { it.name }.map { source ->
        val participating = sources.getValue(source.name)
        val handle = MessageDigest.getInstance("SHA-256")
            .digest("${base.revision}\u0000${source.name}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        TemplateDocument(source, participating.role, participating.sha256, handle)
    }
    if (documents.size > 128 ||
        documents.sumOf { it.source.text.toByteArray(Charsets.UTF_8).size.toLong() } > 1_048_576
    ) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Template source snapshot exceeds its text budget")
    }
    return TemplateSnapshot(revisions, documents)
}

/** A source snapshot is trusted only when its saved baseline can be resolved by the real engine. */
internal fun WorkspaceCatalog.readTemplateSources(caseId: String): DocumentResult {
    val base = resolve(caseId, scan(), freshDiagnostics = true)
    checkEditDiagnostics(base)
    val snapshot = templateSnapshot(base)
    checkTemplateBaseline(caseId, base)
    return DocumentResult(
        base.revision,
        linkedMapOf(
            "document" to caseId,
            "baseRevisions" to snapshot.revisions,
            "documents" to snapshot.documents.map(TemplateDocument::data),
        ),
    )
}

/** Every value and trace comes from one disposable, captured-source graph; nothing is saved or cached. */
internal fun WorkspaceCatalog.previewTemplate(caseId: String, request: TemplatePreviewRequest): DocumentResult =
    synchronized(histories.computeIfAbsent(caseId) { EditHistory() }) {
        validateTemplateRequest(request)
        val scanned = scan()
        val base = try {
            resolve(caseId, scanned, freshDiagnostics = true)
        } catch (error: WorkspaceException) {
            if (error.problem in setOf(WorkspaceProblem.INVALID, WorkspaceProblem.NOT_FOUND) &&
                expectedTemplateSourcesChanged(request.baseRevisions)
            ) {
                throw WorkspaceException(
                    WorkspaceProblem.CONFLICT,
                    "Template baseline sources changed and can no longer be resolved",
                )
            }
            throw error
        }
        checkRevision(request.baseRevision, base.revision)
        checkEditDiagnostics(base)
        val snapshot = templateSnapshot(base)
        if (request.baseRevisions != snapshot.revisions) {
            throw WorkspaceException(
                WorkspaceProblem.CONFLICT,
                "Participating template source revisions differ",
                currentRevision = base.revision,
            )
        }
        val changes = request.documents.associate { edit ->
            val document = snapshot.documents.singleOrNull { it.handle == edit.handle }
                ?: throw WorkspaceException(WorkspaceProblem.REQUEST, "Unknown or expired template document handle")
            if (!document.editable) throw WorkspaceException(WorkspaceProblem.REQUEST, "Template document is read-only")
            checkTemplateIdentity(document.source, edit.text)
            document.source.name to edit.text
        }
        rejectLinkedEdits(base, request.inputs.map { CaseTextEditor.Operation.SetInput(it.node, Value.Nil) })
        val variant = resolve(
            caseId,
            scanned,
            audit = true,
            freshDiagnostics = true,
            capturedLoader = checkNotNull(base.loader),
            templateSources = changes,
            templateInputs = request.inputs,
            allowedSources = snapshot.revisions.keys,
        )
        checkEditDiagnostics(variant)
        request.inputs.forEach { input ->
            val node = variant.view.nodes[input.node]
            if (node?.input == null || node.dims.isNotEmpty()) {
                throw WorkspaceException(WorkspaceProblem.INVALID, "Template preview accepts only scalar inputs")
            }
        }
        if (request.panel != null && variant.view.structure.panels.none { it.id == request.panel }) {
            throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Panel was not found")
        }
        val graph = checkNotNull(variant.graph)
        val data = linkedMapOf(
            "document" to caseId,
            "preview" to true,
            "draftSequence" to request.draftSequence,
            "baseRevisions" to snapshot.revisions,
            "proposedRevision" to variant.revision,
            "succeeded" to graph.succeeded,
            "validationPassed" to graph.validationPassed,
            "diagnostics" to variant.view.diagnostics.map(WorkbenchDocuments::diagnostic),
            "structure" to WorkbenchDocuments.structure(variant.view),
            "run" to WorkbenchDocuments.run(variant.view, variant.layout, graph),
            "difference" to WorkbenchDocuments.compare(base.view, variant.view, base.layout),
            "paper" to WorkbenchDocuments.paper(variant.view, variant.layout, request.panel, request.includeZero),
            "explain" to request.explain?.let { address ->
                val node = variant.view.nodes[address.node]
                    ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain node was not found")
                if (node.dims.size != address.coord.size || address.coord !in node.values) {
                    throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Explain coordinate was not found")
                }
                val trace = when (val captured = node.trace(address.coord)) {
                    is NodeTrace.Computed -> captured.explanation
                    is NodeTrace.Validation -> captured.explanation
                    is NodeTrace.Failed -> captured.explanation
                    is NodeTrace.Choice -> captured.options.firstOrNull { it.key == captured.selected }?.explanation
                    else -> null
                }
                WorkbenchDocuments.explain(
                    variant.view,
                    variant.layout,
                    address.node,
                    address.coord,
                    trace,
                ) + ("revision" to variant.revision)
            },
        )
        beforePreviewCheck?.invoke()
        checkTemplateBaseline(caseId, base)
        DocumentResult(base.revision, data)
    }

private fun validateTemplateRequest(request: TemplatePreviewRequest) {
    val hash = Regex("[0-9a-f]{64}")
    if (!hash.matches(request.baseRevision) ||
        request.baseRevisions.any { (name, value) -> name.isBlank() || !hash.matches(value) } ||
        request.draftSequence !in 0..9_007_199_254_740_991L ||
        request.documents.size > 32 || request.inputs.size > 100 ||
        request.documents.isEmpty() && request.inputs.isEmpty() ||
        request.documents.map { it.handle }.distinct().size != request.documents.size ||
        request.inputs.map { it.node }.distinct().size != request.inputs.size ||
        request.documents.any { !hash.matches(it.handle) } ||
        request.inputs.any { it.node.isBlank() } ||
        request.panel?.isBlank() == true ||
        request.explain?.let {
            it.case != null || it.cell != null || it.expectedRevision != null || it.coord.size > 8
        } ==
        true
    ) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "Invalid template preview request")
    }
    if (request.documents.any { it.text.length > 65_536 } || request.inputs.any { it.text.length > 65_536 } ||
        (request.documents.map { it.text } + request.inputs.map { it.text })
            .sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > 1_048_576
    ) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Template preview source exceeds reader limit")
    }
}

private fun checkTemplateIdentity(original: SourceText, replacement: String) {
    fun parsed(text: String): Pair<Document, DslForm.Sequence> {
        val sink = DiagnosticSink()
        val doc = Document.read(SourceText(original.name, text, original.base), sink)
            ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Template source cannot be read", sink.all)
        val root =
            doc?.root as? DslForm.Sequence
                ?: templateInvalid("MANTRA-TEMPLATE-IDENTITY", "Template must have one root declaration")
        return checkNotNull(doc) to root
    }
    val (beforeDocument, before) = parsed(original.text)
    val (afterDocument, after) = parsed(replacement)
    fun identity(document: Document, root: DslForm.Sequence): List<String?> {
        val sink = DiagnosticSink()
        val version = document.options(root.values.getOrNull(2), sink, "template metadata")["version"]?.string
        return listOf(root.listHead, root.values.getOrNull(1)?.let { it.symbol ?: it.string }, version)
    }
    if (identity(beforeDocument, before) != identity(afterDocument, after)) {
        templateInvalid("MANTRA-TEMPLATE-IDENTITY", "Template id, version and root kind must remain unchanged")
    }
    fun includePaths(form: DslForm): List<String> = if (form is DslForm.Sequence) {
        if (form.listHead == "include") {
            listOf(form.values.getOrNull(1)?.string ?: "<invalid include>")
        } else {
            form.values.flatMap(::includePaths)
        }
    } else {
        emptyList()
    }
    if (includePaths(before) != includePaths(after)) {
        templateInvalid("MANTRA-TEMPLATE-DEPENDENCY", "Template include paths must remain unchanged")
    }
}

private fun templateInvalid(code: String, message: String): Nothing = throw WorkspaceException(
    WorkspaceProblem.INVALID,
    message,
    listOf(com.xqiou.mantra.core.Diagnostic(com.xqiou.mantra.core.Severity.ERROR, code, message)),
)

private fun WorkspaceCatalog.checkTemplateBaseline(caseId: String, base: Resolved) {
    val current = runCatching { resolve(caseId, scan(), freshDiagnostics = true).revision }.getOrNull()
    if (current != base.revision) {
        throw WorkspaceException(
            WorkspaceProblem.CONFLICT,
            "Workspace changed during template preview",
            currentRevision = current,
        )
    }
}

/** A now-invalid or deleted baseline is still a conflict when captured bytes have changed. */
private fun WorkspaceCatalog.expectedTemplateSourcesChanged(revisions: Map<String, String>): Boolean {
    var bytes = 0L
    for ((document, expected) in revisions) {
        val target = try {
            path(document)
        } catch (_: WorkspaceException) {
            return true
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (Files.size(target) > ImportFiles.MAX_BYTES) return true
            Files.newInputStream(target).use { stream ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    bytes += count
                    if (bytes > RunLimits().maxParticipatingBytes) {
                        throw WorkspaceException(
                            WorkspaceProblem.TOO_LARGE,
                            "Template baseline revision check exceeds its byte budget",
                        )
                    }
                    digest.update(buffer, 0, count)
                }
            }
        } catch (_: IOException) {
            return true
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) return true
    }
    return false
}
