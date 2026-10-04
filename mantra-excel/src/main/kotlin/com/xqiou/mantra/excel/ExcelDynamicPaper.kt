package com.xqiou.mantra.excel

import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowKind

/** Only semantic member rows/headings acquire live labels; arbitrary matching text is never changed. */
internal fun ExcelWorkbookBuilder.dynamicPaperLabel(table: PaperTable, rowIndex: Int, column: Int): (() -> X.Scalar)? {
    val live = dynamic ?: return null
    if (table.columns[column].content != ColumnContent.Label) return null
    val row = table.rows[rowIndex]
    val dimension = layout.tables.firstOrNull { it.sectionId == table.id }?.rowDimension ?: return null
    if (dimension !in live.dimensions) return null
    if (row.kind != RowKind.MEMBER && row.kind != RowKind.HEADING) return null
    val candidates = if (row.kind == RowKind.MEMBER) {
        listOf(row)
    } else {
        table.rows.drop(rowIndex + 1).takeWhile { it.kind != RowKind.HEADING }
    }
    val exact = candidates.flatMap { it.valueAddresses.filterNotNull() }.firstOrNull { address ->
        !address.aggregate && dimension in view.node(address.nodeId).dims
    } ?: return null
    val node = view.node(exact.nodeId)
    val publicKey = exact.coord[node.dims.indexOf(dimension)]
    val token = live.initialToken(dimension, publicKey) ?: return null
    return { translator.toScalar(live.record(dimension, token, "label") ?: live.key(dimension, token)) }
}

internal fun ExcelWorkbookBuilder.dynamicCoordinateCaption(
    node: com.xqiou.mantra.core.view.ViewNode,
    coord: com.xqiou.mantra.core.view.Coord,
): X.Scalar? {
    val live = dynamic ?: return null
    if (!live.isDynamicNode(node.id)) return null
    val members = node.dims.mapIndexed { index, axis ->
        if (axis in
            live.dimensions
        ) {
            live.key(axis, coord[index])
        } else {
            Ex.text(coord[index])
        }
    }
    val fragments = listOf(Ex.text(node.label + " [")) + members.flatMapIndexed { index, member ->
        if (index == 0) listOf(member) else listOf(Ex.text("/"), member)
    } + listOf(Ex.text("]"))
    return Ex.chain("&", fragments, Ex.CONCAT, XKind.TEXT)
}
