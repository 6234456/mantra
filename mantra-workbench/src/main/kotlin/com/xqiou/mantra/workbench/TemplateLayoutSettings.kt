package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.workbench.WorkspaceCatalog.DocumentResult
import com.xqiou.mantra.workbench.WorkspaceCatalog.EditHistory

data class TemplateLayoutOperation(val op: String, val property: String, val text: String? = null)

data class TemplateLayoutSettingsRequest(
    val baseRevision: String,
    val baseRevisions: Map<String, String>,
    val draftSequence: Long,
    val handle: String,
    val text: String,
    val operations: List<TemplateLayoutOperation>,
)

/** Source-only settings are never evidence that the draft schema or calculation is valid. */
internal fun WorkspaceCatalog.layoutSettings(caseId: String, request: TemplateLayoutSettingsRequest): DocumentResult =
    synchronized(histories.computeIfAbsent(caseId) { EditHistory() }) {
        validateLayoutSettingsRequest(request)
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
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Only the root layout has editable settings")
        }
        checkTemplateIdentity(source.source, request.text)
        val settings = TemplateLayoutSettingsEditor.apply(source.source, request.text, request.operations)
        beforePreviewCheck?.invoke()
        checkTemplateBaseline(caseId, base)
        DocumentResult(
            base.revision,
            linkedMapOf(
                "document" to caseId,
                "draftSequence" to request.draftSequence,
                "baseRevisions" to snapshot.revisions,
                "scope" to "layout",
                "layoutId" to settings.layout.id,
                "preset" to settings.layout.preset,
                "source" to linkedMapOf(
                    "handle" to request.handle,
                    "document" to source.source.name,
                    "text" to settings.text,
                ),
                "properties" to settings.properties,
                "patches" to settings.patches.map { patch ->
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

private fun validateLayoutSettingsRequest(request: TemplateLayoutSettingsRequest) {
    val hash = Regex("[0-9a-f]{64}")
    if (!hash.matches(request.baseRevision) || !hash.matches(request.handle) ||
        request.baseRevisions.isEmpty() ||
        request.baseRevisions.any { (name, value) -> name.isBlank() || !hash.matches(value) } ||
        request.draftSequence !in 0..9_007_199_254_740_991L ||
        request.operations.size > 16 ||
        request.operations.map { it.property }.distinct().size != request.operations.size ||
        request.operations.any {
            it.property !in TemplateLayoutSettingsEditor.propertyIds ||
                it.op !in setOf("set", "unset") ||
                (it.op == "set") != (it.text != null)
        }
    ) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "Invalid template layout settings request")
    }
    if (request.text.length > 65_536 || request.operations.any { (it.text?.length ?: 0) > 65_536 } ||
        (listOf(request.text) + request.operations.mapNotNull { it.text })
            .sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > 1_048_576
    ) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Template layout settings exceed the source budget")
    }
}
