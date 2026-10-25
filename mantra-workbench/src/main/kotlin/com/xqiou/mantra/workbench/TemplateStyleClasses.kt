package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.workbench.WorkspaceCatalog.DocumentResult
import com.xqiou.mantra.workbench.WorkspaceCatalog.EditHistory

data class TemplateStyleSpec(val weight: String?, val tone: String?, val fill: String?)

data class TemplateStyleClassOperation(val op: String, val name: String, val style: TemplateStyleSpec? = null)

data class TemplateStyleClassesRequest(
    val baseRevision: String,
    val baseRevisions: Map<String, String>,
    val draftSequence: Long,
    val handle: String,
    val text: String,
    val operations: List<TemplateStyleClassOperation>,
)

/** A local definition edit is source evidence only; the current draft must still be previewed. */
internal fun WorkspaceCatalog.styleClasses(caseId: String, request: TemplateStyleClassesRequest): DocumentResult =
    synchronized(histories.computeIfAbsent(caseId) { EditHistory() }) {
        validateStyleClassesRequest(request)
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
        if (!source.editable || source.role != SourceRole.LAYOUT) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Only the root layout has editable style classes")
        }
        checkTemplateIdentity(source.source, request.text)
        val result = TemplateStyleClassesEditor.apply(source.source, request.text, request.operations)
        beforePreviewCheck?.invoke()
        checkTemplateBaseline(caseId, base)
        DocumentResult(
            base.revision,
            linkedMapOf(
                "document" to caseId,
                "draftSequence" to request.draftSequence,
                "baseRevisions" to snapshot.revisions,
                "scope" to "layout-style-classes",
                "layoutId" to result.layoutId,
                "stylePresets" to result.stylePresets,
                "usageScope" to "submitted-layout",
                "source" to linkedMapOf(
                    "handle" to request.handle,
                    "document" to source.source.name,
                    "text" to result.text,
                ),
                "classes" to result.classes,
                "patches" to result.patches.map { patch ->
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

private fun validateStyleClassesRequest(request: TemplateStyleClassesRequest) {
    val hash = Regex("[0-9a-f]{64}")
    if (!hash.matches(request.baseRevision) || !hash.matches(request.handle) ||
        request.baseRevisions.isEmpty() ||
        request.baseRevisions.any { (name, value) -> name.isBlank() || !hash.matches(value) } ||
        request.draftSequence !in 0..9_007_199_254_740_991L || request.operations.size > 16 ||
        request.operations.map { it.name }.distinct().size != request.operations.size ||
        request.operations.any { operation ->
            !Regex("[a-z][a-z0-9-]*").matches(operation.name) ||
                operation.op !in setOf("upsert", "remove") ||
                (operation.op == "upsert") != (operation.style != null) ||
                operation.style?.let {
                    it.weight !in setOf(null, "normal", "bold") ||
                        it.tone !in setOf(null, "default", "muted", "accent") ||
                        it.fill !in setOf(null, "none", "subtle", "accent")
                } == true
        }
    ) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "Invalid template style classes request")
    }
    if (request.text.length > 65_536 || request.text.toByteArray(Charsets.UTF_8).size > 1_048_576) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Template style classes exceed the source budget")
    }
}
