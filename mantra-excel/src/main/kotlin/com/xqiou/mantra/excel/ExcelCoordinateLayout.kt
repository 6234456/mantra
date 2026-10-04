package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import org.apache.poi.xssf.usermodel.XSSFSheet

internal fun ExcelWorkbookBuilder.coordinates(dims: List<String>): List<Coord> = dims.fold(listOf(emptyList())) {
        partial,
        dim,
    ->
    partial.flatMap { prefix -> members[dim].orEmpty().map { prefix + it.key } }
}

/** Every original input coordinate remains editable, even when the paper presents only a slice. */
internal fun ExcelWorkbookBuilder.layoutRemainingInputs(sheet: XSSFSheet, start: Int) {
    var row = start
    view.nodes.values.filter { it.kind == NodeKind.INPUT && it.type != ValueType.TABLE }.forEach { node ->
        val slots = nodeSlots.getOrPut(node.id) { linkedMapOf() }
        coordinates(node.dims).filter { it !in slots }.forEach { coord ->
            text(sheet, row, 0, node.label)
            text(sheet, row, 1, node.id, StyleKey(muted = true))
            coord.forEachIndexed { index, key -> text(sheet, row, 2 + index, key) }
            val slot = Slot(sheet, row++, 2 + coord.size)
            slots[coord] = slot
            valueStyles[slot] =
                StyleKey(
                    format = if (node.type ==
                        ValueType.DATE
                    ) {
                        "yyyy-mm-dd"
                    } else {
                        numberFormat(node.presentation, false)
                    },
                )
        }
    }
}

/** Complete hidden coordinate grids before any formula can reference prior/omitted cells. */
internal fun ExcelWorkbookBuilder.layoutRemainingComputations(sheet: XSSFSheet, start: Int): Int {
    var row = start
    fun cells(label: String, id: String, dims: List<String>, slots: MutableMap<Coord, Slot>, style: StyleKey) {
        coordinates(dims).filter { it !in slots }.forEach { coord ->
            text(sheet, row, 0, label)
            text(sheet, row, 1, id, StyleKey(muted = true))
            coord.forEachIndexed { index, key -> text(sheet, row, 2 + index, key) }
            val slot = Slot(sheet, row++, 2 + coord.size)
            slots[coord] = slot
            valueStyles[slot] = style
        }
    }
    view.nodes.values.filter { it.kind != NodeKind.INPUT && it.kind != NodeKind.PARAM }.forEach { node ->
        val slots = nodeSlots[node.id]
        if (node.dims.size > 1 || (slots != null && coordinates(node.dims).any { it !in slots })) {
            cells(
                node.label,
                node.id,
                node.dims,
                nodeSlots.getOrPut(node.id) { linkedMapOf() },
                StyleKey(
                    format = if (node.type ==
                        ValueType.DATE
                    ) {
                        "yyyy-mm-dd"
                    } else {
                        numberFormat(node.presentation, false)
                    },
                ),
            )
        }
    }
    view.conditions.values.filter { it.dims.size > 1 }.forEach { condition ->
        cells(
            condition.sectionId,
            condition.id,
            condition.dims,
            guardSlots.getOrPut(condition.id) {
                linkedMapOf()
            },
            StyleKey(),
        )
    }
    view.nodes.values.filter { it.choice != null && it.dims.size > 1 }.forEach { node ->
        node.choice!!.options.forEach { option ->
            cells(
                option.label,
                "${node.id}/${option.key}",
                node.dims,
                optionSlots.getOrPut(node.id to option.key) {
                    linkedMapOf()
                },
                StyleKey(format = numberFormat(node.presentation, false)),
            )
        }
    }
    return row
}
