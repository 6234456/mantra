package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.FormulaAuthoring
import com.xqiou.mantra.core.model.NodeItem
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.workbench.WorkspaceCatalog.DocumentResult
import com.xqiou.mantra.workbench.json.WorkbenchDocuments

/** Editor assistance uses the same typed scope as the planner, with final semantic checks via edit preview. */
internal fun WorkspaceCatalog.formulaAssistance(
    caseId: String,
    target: AuthoringTarget,
    source: String,
    cursorOffset: Int?,
    action: String,
): DocumentResult {
    if (source.length > 65_536 || source.toByteArray(Charsets.UTF_8).size > 1_048_576) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Formula source exceeds reader limit")
    }
    val base = resolve(caseId, scan())
    val editor = try {
        when (target) {
            is AuthoringTarget.Extension -> FormulaAuthoring.forExtension(
                base.schema,
                base.view.case,
                base.parameters,
                target.slot,
                target.id,
            )
            is AuthoringTarget.FormulaSlot -> FormulaAuthoring.forFormulaSlot(
                base.schema,
                base.view.case,
                base.parameters,
                target.id,
            )
        }
    } catch (error: IllegalArgumentException) {
        throw WorkspaceException(WorkspaceProblem.REQUEST, error.message ?: "Unknown authoring target")
    }
    fun range(start: Int, end: Int) = mapOf("startOffset" to start, "endOffset" to end)
    val data: Map<String, Any?> = when (action) {
        "complete" -> {
            val cursor = cursorOffset ?: throw WorkspaceException(WorkspaceProblem.REQUEST, "cursorOffset is required")
            val result = editor.complete(source, cursor)
            linkedMapOf(
                "query" to result.query,
                "replacementRange" to range(result.replacementRange.startOffset, result.replacementRange.endOffset),
                "items" to result.items.map { item ->
                    linkedMapOf<String, Any?>(
                        "label" to item.label,
                        "insertText" to item.insertText,
                        "kind" to item.kind.name.lowercase(),
                        "detail" to item.detail,
                        "documentation" to item.documentation,
                        "deprecated" to item.deprecated,
                    )
                },
            )
        }
        "hover" -> {
            val cursor = cursorOffset ?: throw WorkspaceException(WorkspaceProblem.REQUEST, "cursorOffset is required")
            val hover = editor.hover(source, cursor)

            @Suppress("UNCHECKED_CAST")
            val runValues = WorkbenchDocuments.run(
                base.view,
                base.layout,
            )["values"] as Map<String, Map<String, Map<String, Any?>>>
            val current = hover?.symbol?.removePrefix("mantra/")?.let { runValues[it]?.get("") }
            mapOf(
                "hover" to hover?.let {
                    linkedMapOf<String, Any?>(
                        "range" to range(it.range.startOffset, it.range.endOffset),
                        "symbol" to it.symbol,
                        "kind" to it.kind.name.lowercase(),
                        "detail" to it.detail,
                        "documentation" to it.documentation,
                        "current" to current,
                    )
                },
            )
        }
        "check" -> {
            val diagnostics = editor.check(source).map { item ->
                linkedMapOf<String, Any?>(
                    "severity" to item.severity.name.lowercase(),
                    "code" to item.code,
                    "message" to item.message,
                    "range" to item.span?.let { range(it.startOffset, it.endOffset) },
                )
            }.toMutableList()
            if (diagnostics.isEmpty()) {
                val operation = when (target) {
                    is AuthoringTarget.FormulaSlot -> CaseTextEditor.Operation.BindFormula(target.id, source)
                    is AuthoringTarget.Extension -> {
                        val exists = base.view.case.extensions[target.slot].orEmpty().filterIsInstance<NodeItem>()
                            .any { it.id == target.id }
                        if (exists) {
                            CaseTextEditor.Operation.UpdateExtension(target.slot, target.id, target.title, source)
                        } else {
                            CaseTextEditor.Operation.AddExtension(target.slot, target.id, target.title, source)
                        }
                    }
                }
                try {
                    previewEdits(caseId, base.revision, listOf(operation))
                } catch (error: WorkspaceException) {
                    if (error.problem != WorkspaceProblem.INVALID) throw error
                    error.diagnostics.forEach { item ->
                        diagnostics += linkedMapOf<String, Any?>(
                            "severity" to item.severity.name.lowercase(),
                            "code" to item.code,
                            "message" to item.message,
                            "range" to null,
                        )
                    }
                    if (error.diagnostics.isEmpty()) {
                        diagnostics += linkedMapOf<String, Any?>(
                            "severity" to "error",
                            "code" to "MANTRA-WORKBENCH-EDIT",
                            "message" to (error.message ?: "Formula was rejected"),
                            "range" to null,
                        )
                    }
                }
            }
            mapOf("valid" to diagnostics.none { it["severity"] == "error" }, "diagnostics" to diagnostics)
        }
        else -> throw WorkspaceException(WorkspaceProblem.REQUEST, "Unknown authoring action")
    }
    return DocumentResult(base.revision, data)
}
