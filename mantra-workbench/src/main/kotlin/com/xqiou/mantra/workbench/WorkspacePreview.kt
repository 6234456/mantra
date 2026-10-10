package com.xqiou.mantra.workbench

import com.xqiou.mantra.workbench.WorkspaceCatalog.DocumentResult
import com.xqiou.mantra.workbench.WorkspaceCatalog.EditHistory
import com.xqiou.mantra.workbench.json.WorkbenchDocuments

/** Candidate values never enter the catalog's persistent graph runner or its run statistics. */
internal fun WorkspaceCatalog.previewCaseEdits(
    caseId: String,
    baseRevision: String,
    operations: List<CaseTextEditor.Operation>,
    draftSequence: Long? = null,
    panel: String? = null,
    includeZero: Boolean = false,
): DocumentResult = synchronized(histories.computeIfAbsent(caseId) { EditHistory() }) {
    if (draftSequence != null && draftSequence !in 0..9_007_199_254_740_991L) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "draftSequence must be a safe nonnegative integer")
    }
    if (panel != null && panel.isBlank()) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, "panel must be nonempty text")
    }
    val snapshot = scan()
    val base = resolve(caseId, snapshot, freshDiagnostics = true)
    checkRevision(baseRevision, base.revision)
    rejectLinkedEdits(base, operations)
    val graph = checkNotNull(base.graph)
    val rootCase = checkNotNull(graph.root)
    val source = base.caseSources.getValue(rootCase).getValue(rootCase.value).text
    val candidate = applyCaseOperations(source, operations)
    val variant = resolve(
        caseId,
        snapshot,
        caseText = candidate,
        audit = draftSequence != null,
        freshDiagnostics = true,
        capturedLoader = checkNotNull(base.loader),
    )
    checkEditDiagnostics(variant)
    validateFinalCoordinates(variant, operations)
    val data = if (draftSequence == null) {
        editData(base, variant, caseId, true)
    } else {
        if (panel != null && variant.view.structure.panels.none { it.id == panel }) {
            throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Panel was not found")
        }
        val candidateGraph = checkNotNull(variant.graph)
        linkedMapOf(
            "document" to caseId,
            "preview" to true,
            "draftSequence" to draftSequence,
            "proposedRevision" to variant.revision,
            "succeeded" to candidateGraph.succeeded,
            "validationPassed" to candidateGraph.validationPassed,
            "diagnostics" to variant.view.diagnostics.map(WorkbenchDocuments::diagnostic),
            "run" to WorkbenchDocuments.run(variant.view, variant.layout, candidateGraph),
            "difference" to WorkbenchDocuments.compare(base.view, variant.view, base.layout),
            "paper" to WorkbenchDocuments.paper(variant.view, variant.layout, panel, includeZero),
        )
    }
    beforePreviewCheck?.invoke()
    val current = runCatching { resolve(caseId, scan(), freshDiagnostics = true).revision }.getOrNull()
    if (current != base.revision) {
        throw WorkspaceException(
            WorkspaceProblem.CONFLICT,
            "Workspace changed during preview",
            currentRevision = current,
        )
    }
    DocumentResult(base.revision, data)
}
