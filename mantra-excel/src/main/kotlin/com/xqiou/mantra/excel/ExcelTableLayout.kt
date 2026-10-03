package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.displayLabel
import com.xqiou.mantra.core.view.signLabels
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Companion.FIRST_ROW
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Companion.HEADER_ROW
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.TableStyle
import com.xqiou.mantra.render.layout.styleRole
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowFlag
import com.xqiou.mantra.render.paper.RowKind
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.util.CellRangeAddress

// ── Table sheets ───────────────────────────────────────────────────────────────────────────

private class XColumn(val content: ColumnContent, val header: String, val width: Int, val grouped: Boolean = false)

internal fun ExcelWorkbookBuilder.layoutTable(table: PaperTable) {
    val sheet = sheet("${table.ref} ${table.title}")
    tableSheets[table.ref] = sheet
    sectionSheets[table.id] = sheet
    val rowDims = linkedSetOf<String>()
    table.rows.forEach { row ->
        row.nodeId?.let { id -> view.nodes[id]?.dims?.singleOrNull()?.let(rowDims::add) }
    }
    val columns = mutableListOf<XColumn>()
    val paperToX = hashMapOf<Int, Int>()
    val emittedDims = mutableSetOf<String>()
    table.columns.forEachIndexed { index, column ->
        when (val content = column.content) {
            is ColumnContent.Member -> if (emittedDims.add(content.dimension)) {
                members[content.dimension].orEmpty().forEach {
                    columns +=
                        XColumn(ColumnContent.Member(content.dimension, it.key), it.label, 14)
                }
            }
            is ColumnContent.Members -> Unit
            else -> {
                paperToX[index] = columns.size
                columns += XColumn(content, column.header, widthFor(content))
            }
        }
    }
    val hasValueColumn = columns.any {
        it.content == ColumnContent.Pre || it.content == ColumnContent.Main ||
            it.content == ColumnContent.Value ||
            it.content == ColumnContent.CrossTotal
    }
    if (!hasValueColumn) {
        columns +=
            XColumn(
                if (table.style ==
                    TableStyle.MATRIX
                ) {
                    ColumnContent.CrossTotal
                } else {
                    ColumnContent.Value
                },
                texts.total,
                15,
            )
    }
    (rowDims - emittedDims).forEach { dim ->
        members[dim].orEmpty().forEach {
            columns +=
                XColumn(ColumnContent.Member(dim, it.key), it.label, 14, grouped = true)
        }
    }
    val formulaColumn = if (options.formulaColumn) {
        columns.size.also {
            columns +=
                XColumn(
                    ColumnContent.Formula,
                    if (de) "Formel (Mantra-DSL)" else "Formula (Mantra DSL)",
                    60,
                    grouped = true,
                )
        }
    } else {
        null
    }

    fun col(content: ColumnContent) = columns.indexOfFirst { it.content == content }.takeIf { it >= 0 }
    val preCol = col(ColumnContent.Pre)
    val mainCol = col(ColumnContent.Main)
    val valueCol = col(ColumnContent.Value) ?: col(ColumnContent.CrossTotal)
    val labelCol = col(ColumnContent.Label) ?: 0
    val statusCol = col(ColumnContent.Status)
    fun memberCol(dim: String, key: String) = columns.indexOfFirst {
        (it.content as? ColumnContent.Member)?.let { m ->
            m.dimension ==
                dim &&
                m.key == key
        } ==
            true
    }.takeIf { it >= 0 }

    // Title, breadcrumb and header.
    text(sheet, 0, 0, table.title, StyleKey(bold = true, size = 13))
    table.breadcrumb?.let {
        text(sheet, 1, 0, "⌂ $it", StyleKey(italic = true, muted = true, link = true))
        link(sheet, 1, 0, wb.getSheetName(0))
    }
    columns.forEachIndexed { index, column ->
        text(
            sheet,
            HEADER_ROW,
            index,
            column.header,
            StyleKey(
                bold = true,
                fill = Fill.HEADER,
                headerRule = true,
                align = if (isNumeric(column.content)) HorizontalAlignment.RIGHT else HorizontalAlignment.LEFT,
                wrap = true,
            ),
        )
        sheet.setColumnWidth(index, column.width * 256)
    }

    val headingStack = ArrayDeque<Pair<Int, Int>>()
    table.rows.forEachIndexed { index, row ->
        val r = FIRST_ROW + index
        columns.forEachIndexed { x, column ->
            val paperIndex = table.columns.indexOfFirst { it.content == column.content }
            val rule = row.cellStyles.getOrNull(paperIndex) ?: row.cellContexts.firstOrNull()?.let { context ->
                layout.styleFor(
                    context.copy(columnId = column.content.styleRole(), columnRole = column.content.styleRole()),
                )
            } ?: row.style
            paperCellStyles[Slot(sheet, r, x)] = rule
        }
        while (headingStack.isNotEmpty() && headingStack.last().second >= row.depth) {
            val (start, _) = headingStack.removeLast()
            if (r - 1 > start) sheet.groupRow(start + 1, r - 1)
        }
        val bold =
            row.kind == RowKind.HEADING || row.kind == RowKind.TOTAL || row.kind == RowKind.RESULT ||
                RowFlag.GRAND in row.flags
        val muted = RowFlag.INFO in row.flags || row.kind == RowKind.OPTION || row.kind == RowKind.NOTE
        val italic = row.kind == RowKind.OPTION || row.kind == RowKind.NOTE || RowFlag.USER_DEFINED in row.flags
        val rule = row.kind == RowKind.SUBTOTAL || row.kind == RowKind.RESULT || row.kind == RowKind.TOTAL
        // Text columns from the paper.
        table.columns.forEachIndexed { paperIndex, column ->
            val x = paperToX[paperIndex] ?: return@forEachIndexed
            if (isNumeric(column.content) || column.content == ColumnContent.Formula ||
                column.content == ColumnContent.Explain
            ) {
                return@forEachIndexed
            }
            val value = row.cells[paperIndex]
            val style = if (x == labelCol) {
                StyleKey(
                    bold = bold,
                    italic = italic,
                    muted = muted,
                    indent = row.depth.coerceAtMost(15).toShort(),
                    link =
                    value.endsWith(")") && "(→ " in value,
                )
            } else {
                StyleKey(
                    bold = bold && column.content == ColumnContent.Operator,
                    muted =
                    column.content != ColumnContent.Operator,
                    align = if (column.content == ColumnContent.Status ||
                        column.content == ColumnContent.Operator
                    ) {
                        HorizontalAlignment.CENTER
                    } else {
                        HorizontalAlignment.GENERAL
                    },
                )
            }
            if (column.content == ColumnContent.Status && row.kind == RowKind.OPTION) return@forEachIndexed
            text(sheet, r, x, value, style.applyRule(row.cellStyles.getOrNull(paperIndex) ?: row.style))
        }
        // Cross-reference links ("→ Tabelle n").
        Regex("\\(→ [^)]*? (\\S+)\\)$").find(
            row.cells.getOrNull(
                table.columns.indexOfFirst {
                    it.content == ColumnContent.Label
                },
            ) ?: "",
        )
            ?.groupValues?.get(1)?.let { ref -> pendingLinks += Triple(sheet, r to labelCol, ref) }

        val numberKey = { presentation: Presentation?, deduction: Boolean ->
            StyleKey(
                format = numberFormat(presentation, deduction),
                bold = bold,
                muted = muted,
                italic = italic,
                topRule = rule,
                doubleBottom =
                RowFlag.GRAND in row.flags,
            )
        }
        val deduction = RowFlag.NEGATED in row.flags
        fun valueSlotFor(lead: Boolean): Int? = when {
            table.style == TableStyle.TIERED && lead && preCol != null -> preCol
            table.style == TableStyle.TIERED && mainCol != null -> mainCol
            else -> valueCol ?: mainCol ?: preCol
        }
        val nodeId = row.nodeId
        val vertex = nodeId?.let { view.nodes[it] }
        when {
            row.kind == RowKind.REFERENCE && vertex != null -> {
                val presentationKey = numberKey(presentationOf(vertex), deduction)
                valueSlotFor(row.lead)?.let { c ->
                    val slot = Slot(sheet, r, c)
                    valueStyles[slot] = presentationKey
                    presentation += slot to { aggregateRef(vertex.id) }
                }
                vertex.dims.singleOrNull()?.let { dim ->
                    members[dim].orEmpty().forEach { m ->
                        memberCol(dim, m.key)?.let { c ->
                            val slot = Slot(sheet, r, c)
                            valueStyles[slot] = presentationKey
                            presentation += slot to { reference(vertex.id, listOf(dim), listOf(m.key)) as? X.Scalar }
                        }
                    }
                }
            }
            row.kind == RowKind.OPTION && vertex?.choice != null && row.optionKey != null -> {
                val key = vertex.id to row.optionKey!!
                val slots = optionSlots.getOrPut(key) { linkedMapOf() }
                val style = numberKey(presentationOf(vertex), false)
                if (vertex.dims.isEmpty()) {
                    valueSlotFor(row.lead)?.let { c ->
                        Slot(sheet, r, c).also {
                            slots[emptyList()] = it
                            valueStyles[it] = style
                        }
                    }
                } else {
                    vertex.dims.singleOrNull()?.let { dim ->
                        members[dim].orEmpty().forEach { m ->
                            memberCol(dim, m.key)?.let { c ->
                                Slot(sheet, r, c).also {
                                    slots[listOf(m.key)] = it
                                    valueStyles[it] = style
                                }
                            }
                        }
                    }
                }
                statusCol?.let { c ->
                    statusFormulas += Slot(sheet, r, c) to {
                        val choiceRef = reference(
                            vertex.id,
                            vertex.dims,
                            slots.keys.firstOrNull() ?: emptyList(),
                        ) as? X.Scalar
                        val optionRef = slots.values.firstOrNull()?.let(::ref)
                        if (choiceRef != null && optionRef != null &&
                            vertex.dims.isEmpty()
                        ) {
                            Ex.iff(Ex.cmp("=", choiceRef, optionRef), Ex.text("✓"), Ex.EMPTY)
                        } else {
                            null
                        }
                    }
                }
            }
            vertex != null && row.kind != RowKind.OPTION -> {
                val style = numberKey(presentationOf(vertex), deduction)
                val slots = nodeSlots[vertex.id]
                if (slots != null) {
                    // Presented twice: the second place links to the first.
                    valueSlotFor(row.lead)?.let { c ->
                        Slot(sheet, r, c).also {
                            valueStyles[it] = style
                            presentation += it to { aggregateRef(vertex.id) }
                        }
                    }
                } else {
                    val target = linkedMapOf<Coord, Slot>()
                    when (vertex.dims.size) {
                        0 -> valueSlotFor(row.lead)?.let { c ->
                            Slot(sheet, r, c).also {
                                target[emptyList()] = it
                                valueStyles[it] = style
                            }
                        }
                        1 -> {
                            val dim = vertex.dims.single()
                            members[dim].orEmpty().forEach { m ->
                                memberCol(dim, m.key)?.let { c ->
                                    Slot(sheet, r, c).also {
                                        target[listOf(m.key)] = it
                                        valueStyles[it] = style
                                    }
                                }
                            }
                            val aggregate = vertex.type.isNumeric && view.nodes[vertex.id]?.crossTotal() != null
                            valueSlotFor(row.lead)?.takeIf { aggregate }?.let { c ->
                                Slot(sheet, r, c).also {
                                    valueStyles[it] = style
                                    presentation += it to { aggregateRef(vertex.id) }
                                }
                            }
                        }
                        else ->
                            fallbackPlacement +=
                                Triple(
                                    vertex,
                                    Slot(sheet, r, valueSlotFor(row.lead) ?: labelCol),
                                    "more than one dimension",
                                )
                    }
                    if (target.isNotEmpty()) nodeSlots[vertex.id] = target
                }
                if (formulaColumn !=
                    null
                ) {
                    text(sheet, r, formulaColumn, dslFormula(vertex), StyleKey(muted = true, italic = true))
                }
            }
        }
        if (row.kind != RowKind.OPTION && row.kind != RowKind.REFERENCE) {
            val signedNode = row.nodeId?.let(view.nodes::get)?.takeIf { it.signLabels != null }
            if (signedNode != null) {
                val labelIndex = table.columns.indexOfFirst { it.content == ColumnContent.Label }
                val suffix = row.cells.getOrNull(labelIndex)?.removePrefix(signedNode.displayLabel()).orEmpty()
                presentation += Slot(sheet, r, labelCol) to { signLabelFormula(signedNode, suffix) }
            }
        }
        if (row.kind == RowKind.HEADING) headingStack.addLast(r to row.depth)
    }
    val lastRow = FIRST_ROW + table.rows.size - 1
    while (headingStack.isNotEmpty()) {
        val (start, _) = headingStack.removeLast()
        if (lastRow > start) sheet.groupRow(start + 1, lastRow)
    }
    sheet.rowSumsBelow = true
    sheet.createFreezePane(0, FIRST_ROW)
    val groupedColumns = columns.indices.filter { columns[it].grouped }
    if (groupedColumns.isNotEmpty()) {
        sheet.groupColumn(groupedColumns.first(), groupedColumns.last())
        sheet.setColumnGroupCollapsed(groupedColumns.first(), true)
    }
    sheet.printSetup.landscape = true
    sheet.fitToPage = true
    sheet.printSetup.fitWidth = 1
    sheet.printSetup.fitHeight = 0
    sheet.repeatingRows = CellRangeAddress.valueOf("${HEADER_ROW + 1}:${HEADER_ROW + 1}")
}

internal fun ExcelWorkbookBuilder.isNumeric(content: ColumnContent) = content.numeric

internal fun ExcelWorkbookBuilder.widthFor(content: ColumnContent): Int = when (content) {
    ColumnContent.Label -> 62
    ColumnContent.Operator -> 5
    ColumnContent.RowNumber -> 6
    ColumnContent.Status -> 4
    ColumnContent.Reference, ColumnContent.Note, ColumnContent.Source -> 30
    is ColumnContent.Attribute -> 12
    ColumnContent.Formula, ColumnContent.Explain -> 60
    else -> 16
}
