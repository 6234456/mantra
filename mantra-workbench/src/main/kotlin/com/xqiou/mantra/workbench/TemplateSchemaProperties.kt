package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.workbench.WorkspaceCatalog.DocumentResult
import com.xqiou.mantra.workbench.WorkspaceCatalog.EditHistory
import java.nio.file.Path

data class TemplateSchemaPropertyOperation(
    val op: String,
    val owner: String,
    val text: String? = null,
    val classes: List<String>? = null,
)

data class TemplateSchemaPropertiesRequest(
    val baseRevision: String,
    val baseRevisions: Map<String, String>,
    val draftSequence: Long,
    val handle: String,
    val text: String,
    val operations: List<TemplateSchemaPropertyOperation>,
)

/** A source-only property edit is not evidence that the candidate calculation or Paper is valid. */
internal fun WorkspaceCatalog.schemaProperties(
    caseId: String,
    request: TemplateSchemaPropertiesRequest,
): DocumentResult = synchronized(histories.computeIfAbsent(caseId) { EditHistory() }) {
    validateSchemaPropertiesRequest(request)
    val base = try {
        resolve(caseId, scan(), freshDiagnostics = true)
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
    val source = snapshot.documents.singleOrNull { it.handle == request.handle }
        ?: throw WorkspaceException(WorkspaceProblem.REQUEST, "Unknown or expired template document handle")
    if (!source.editable || source.role != SourceRole.SCHEMA) {
        throw WorkspaceException(
            WorkspaceProblem.REQUEST,
            "Only the root schema has editable presentation properties",
        )
    }
    checkTemplateIdentity(source.source, request.text)
    val captured = snapshot.documents.associate { it.source.name to it.source }
    val loader = checkNotNull(base.loader)
    val resolver = SourceResolver { path, from ->
        // The schema reader receives only the captured root source or a captured include. Its
        // original base and the loader's captured canonical-path map retain include aliases.
        // Resolve no source text from the live filesystem and never search by basename.
        val original = from?.name?.let(captured::get)
        val parent = original?.base
        if (parent == null || from?.base != parent) {
            null
        } else {
            val file = loader.capturedCasePath(Path.of(parent).resolve(path))
            val identity = loader.root.relativize(file).toString().replace('\\', '/')
            captured[identity]
        }
    }
    val edited = TemplateSchemaPropertiesEditor.apply(
        source.source,
        request.text,
        request.handle,
        request.operations,
        resolver,
    )
    beforePreviewCheck?.invoke()
    checkTemplateBaseline(caseId, base)
    DocumentResult(
        base.revision,
        linkedMapOf(
            "document" to caseId,
            "draftSequence" to request.draftSequence,
            "baseRevisions" to snapshot.revisions,
            "scope" to "schema-properties",
            "coverage" to "presentation-items",
            "schemaId" to edited.schemaId,
            "source" to linkedMapOf(
                "handle" to request.handle,
                "document" to source.source.name,
                "text" to edited.text,
            ),
            "owners" to edited.owners,
            "patches" to edited.patches.map { patch ->
                linkedMapOf(
                    "startOffset" to patch.startOffset,
                    "endOffset" to patch.endOffset,
                    "text" to patch.text,
                    "removedText" to patch.removedText,
                )
            },
        ),
    )
}

private fun validateSchemaPropertiesRequest(request: TemplateSchemaPropertiesRequest) {
    val hash = Regex("[0-9a-f]{64}")
    val className = Regex("[a-z][a-z0-9-]*")
    if (!hash.matches(request.baseRevision) || !hash.matches(request.handle) ||
        request.baseRevisions.isEmpty() ||
        request.baseRevisions.any { (name, value) -> name.isBlank() || !hash.matches(value) } ||
        request.draftSequence !in 0..9_007_199_254_740_991L || request.operations.size > 32 ||
        request.operations.map { it.owner }.distinct().size != request.operations.size ||
        request.operations.any { operation ->
            !hash.matches(operation.owner) ||
                when (operation.op) {
                    "setText" -> operation.text == null || operation.classes != null
                    "setClasses" -> operation.text != null || operation.classes == null
                    "unset" -> operation.text != null || operation.classes != null
                    else -> true
                } ||
                operation.classes?.let { classes ->
                    classes.size > 64 || classes.distinct().size != classes.size ||
                        classes.any { !className.matches(it) }
                } == true
        }
    ) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "Invalid template schema properties request")
    }
    val texts = listOf(request.text) + request.operations.mapNotNull { it.text } +
        request.operations.flatMap { it.classes.orEmpty() }
    if (texts.any { it.length > 65_536 } ||
        texts.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > 1_048_576
    ) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Template schema properties exceed the source budget")
    }
}
