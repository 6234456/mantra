package com.xqiou.mantra.workbench

import com.xqiou.mantra.excel.ExcelDescription

/** Wire projection of an actual generated workbook, including its native exporter report. */
internal object ExportDocuments {
    fun preview(description: ExcelDescription): Map<String, Any?> = linkedMapOf(
        "sheets" to description.sheets.map { mapOf("name" to it.name, "rows" to it.rows, "columns" to it.columns) },
        "selectedSheet" to description.selectedSheet,
        "preview" to mapOf(
            "rows" to description.preview.rows,
            "columns" to description.preview.columns,
            "truncated" to description.preview.truncated,
            "cells" to description.preview.cells.map { cell ->
                mapOf("address" to cell.address, "kind" to cell.kind, "value" to cell.value, "formula" to cell.formula)
            },
        ),
        "names" to description.names.map { mapOf("name" to it.name, "refersTo" to it.refersTo) },
        "report" to mapOf(
            "formulaCells" to description.report.formulaCells,
            "inputCells" to description.report.inputCells,
            "names" to description.report.names,
            "fallbacks" to description.report.fallbacks.map { fallback ->
                mapOf(
                    "sheet" to fallback.sheet,
                    "cell" to fallback.cell,
                    "nodeId" to fallback.nodeId,
                    "reason" to fallback.reason,
                )
            },
            "evaluationErrors" to description.report.evaluationErrors,
        ),
    )
}
