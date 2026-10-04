package com.xqiou.mantra.excel

import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot

/** Linked inputs are materialized snapshots, with complete source identity in a plain comment. */
internal fun ExcelWorkbookBuilder.writeInputProvenance(slot: Slot, node: ViewNode, coord: Coord) {
    val provenance = (node.trace(coord) as? NodeTrace.Input)?.link ?: return
    val description = listOf(
        "Materialized linked input; source changes require a new case-graph run.",
        "caseKey: ${provenance.caseKey}",
        "caseId: ${provenance.caseId}",
        "path: ${provenance.path}",
        "schema.id: ${provenance.schema.id}",
        "schema.version: ${provenance.schema.version}",
        "revision: ${provenance.revision}",
        "from.node: ${provenance.from.nodeId}",
        "from.coord: ${provenance.from.coord.joinToString(", ")}",
    ).joinToString("\n")
    if (description.length > 32_767) {
        throw ExcelExportLimitException("Linked input provenance exceeds Excel's comment text limit")
    }
    val helper = wb.creationHelper
    val anchor = helper.createClientAnchor().apply {
        setCol1(slot.col)
        setCol2(minOf(slot.col + 6, 16_383))
        setRow1(slot.row)
        setRow2(minOf(slot.row + 10, 1_048_575))
    }
    val comment = slot.sheet.createDrawingPatriarch().createCellComment(anchor)
    comment.author = "Mantra"
    comment.string = helper.createRichTextString(description)
    cell(slot.sheet, slot.row, slot.col).cellComment = comment
}
