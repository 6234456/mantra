package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Companion.FIRST_ROW
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Companion.HEADER_ROW
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowFlag
import com.xqiou.mantra.render.paper.RowKind
import org.apache.poi.ss.usermodel.HorizontalAlignment

/** Paper carries exact node/coordinate or reduction addresses; column shape never guesses them. */
internal fun ExcelWorkbookBuilder.layoutAddressedTable(table: PaperTable) {
    val sheet = sheet("${table.ref} ${table.title}")
    tableSheets[table.ref] = sheet
    sectionSheets[table.id] = sheet
    text(sheet, 0, 0, table.title, StyleKey(bold = true, size = 13))
    table.breadcrumb?.let { text(sheet, 1, 0, it, StyleKey(muted = true)) }
    table.columns.forEachIndexed { column, spec ->
        text(
            sheet,
            HEADER_ROW,
            column,
            spec.header,
            StyleKey(bold = true, fill = Fill.HEADER, headerRule = true, wrap = true),
        )
        sheet.setColumnWidth(column, (spec.width ?: widthFor(spec.content)) * 256)
    }
    table.rows.forEachIndexed { index, row ->
        val r = FIRST_ROW + index
        table.columns.forEachIndexed { column, _ ->
            val slot = Slot(sheet, r, column)
            val rule = row.cellStyles.getOrNull(column) ?: row.style
            paperCellStyles[slot] = rule
            val address = row.valueAddresses.getOrNull(column)
            if (address == null) {
                val liveLabel = dynamicPaperLabel(table, index, column)
                if (liveLabel != null) {
                    presentation += slot to { liveLabel() }
                    return@forEachIndexed
                }
                text(
                    sheet,
                    r,
                    column,
                    row.cells.getOrNull(column).orEmpty(),
                    StyleKey(
                        bold = row.kind == RowKind.HEADING || row.kind == RowKind.TOTAL,
                        muted =
                        row.kind == RowKind.NOTE,
                    ).applyRule(rule),
                )
                return@forEachIndexed
            }
            val node = view.node(address.nodeId)
            valueStyles[slot] = StyleKey(
                format = if (node.type ==
                    ValueType.DATE
                ) {
                    "yyyy-mm-dd"
                } else {
                    numberFormat(node.presentation, layout.signedValues && node.op < 0)
                },
                bold = row.kind == RowKind.TOTAL || row.kind == RowKind.RESULT || RowFlag.GRAND in row.flags,
                align = HorizontalAlignment.RIGHT,
                topRule = row.kind == RowKind.TOTAL || row.kind == RowKind.SUBTOTAL,
            )
            when {
                address.aggregate -> {
                    val fixed = address.fixed.toMap()
                    presentation += slot to { aggregateRef(node.id, fixed) }
                    reductionSlots.putIfAbsent(node.id to fixed, slot)
                    if (fixed.isEmpty()) aggregateSlots.putIfAbsent(node.id, slot)
                }
                row.kind == RowKind.OPTION && row.optionKey != null -> {
                    val existing = optionSlots.getOrPut(node.id to row.optionKey!!) {
                        linkedMapOf()
                    }.putIfAbsent(dynamic?.storageCoordinate(node.dims, address.coord) ?: address.coord, slot)
                    if (existing != null) presentation += slot to { ref(existing, kindOf(node)) }
                }
                else -> {
                    val fixed = layout.tables.firstOrNull { it.sectionId == table.id }?.fixed.orEmpty()
                    if (fixed.keys.any { it in dynamic?.dimensions.orEmpty() }) {
                        presentation += slot to { reference(node.id, node.dims, address.coord) as? X.Scalar }
                        return@forEachIndexed
                    }
                    val existing = nodeSlots.getOrPut(node.id) {
                        linkedMapOf()
                    }.putIfAbsent(dynamic?.storageCoordinate(node.dims, address.coord) ?: address.coord, slot)
                    if (existing != null) presentation += slot to { ref(existing, kindOf(node)) }
                }
            }
        }
        if (options.formulaColumn) {
            val formulas = row.valueAddresses.filterNotNull().map {
                it.nodeId
            }.distinct().joinToString("; ") { dslFormula(view.node(it)) }
            text(sheet, r, table.columns.size, formulas, StyleKey(muted = true, italic = true))
        }
    }
    if (options.formulaColumn) sheet.setColumnWidth(table.columns.size, 60 * 256)
    sheet.createFreezePane(1, FIRST_ROW)
    sheet.printSetup.landscape = true
    sheet.fitToPage = true
    sheet.printSetup.fitWidth = 1
    sheet.printSetup.fitHeight = 0
}
