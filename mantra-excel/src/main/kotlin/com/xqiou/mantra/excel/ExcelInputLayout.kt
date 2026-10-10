package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.view.groupKey
import com.xqiou.mantra.core.view.groupTitle
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.xssf.usermodel.XSSFSheet

// ── Inputs, parameters and helpers ─────────────────────────────────────────────────────────

internal fun ExcelWorkbookBuilder.layoutInputs() {
    val sheet = sheet(if (de) "Eingaben" else "Inputs")
    text(
        sheet,
        0,
        0,
        if (de) "Eingaben (gelb: änderbar)" else "Inputs (yellow: editable)",
        StyleKey(bold = true, size = 13),
    )
    sheet.setColumnWidth(0, 58 * 256)
    sheet.setColumnWidth(1, 30 * 256)
    (2..14).forEach { sheet.setColumnWidth(it, 16 * 256) }
    layoutKeyedInputs()
    var r = 2
    val unplaced = view.nodes.values.filter { it.kind == NodeKind.INPUT }.filter { it.id !in nodeSlots }
    val groups = unplaced.groupBy { input ->
        input.groupKey?.let(view::groupTitle)
            ?: map.panelOf(input.id)?.title ?: if (de) "Allgemeine Angaben" else "General"
    }
    groups.forEach { (title, inputs) ->
        text(sheet, r++, 0, title, StyleKey(bold = true, fill = Fill.HEADER))
        var headerDim: String? = null
        inputs.sortedBy { if (it.type == ValueType.TABLE) 2 else it.dims.size }.forEach { input ->
            when {
                input.type == ValueType.TABLE -> {
                    r = layoutTableInput(sheet, r, input)
                    headerDim = null
                }
                input.dims.isEmpty() -> {
                    text(sheet, r, 0, input.label)
                    text(sheet, r, 1, input.id, StyleKey(muted = true))
                    nodeSlots[input.id] =
                        linkedMapOf(
                            emptyList<String>() to
                                Slot(sheet, r, 2).also {
                                    valueStyles[it] =
                                        StyleKey(
                                            format = numberFormat(input.input!!.presentation, false),
                                            fill = Fill.INPUT,
                                        )
                                },
                        )
                    r++
                }
                input.dims.size == 1 -> {
                    val dim = input.dims.single()
                    if (headerDim != dim) {
                        members[dim].orEmpty().forEachIndexed { i, m ->
                            if (dim in dynamic?.dimensions.orEmpty()) {
                                presentation +=
                                    Slot(sheet, r, 2 + i) to
                                    { translator.toScalar(dynamic!!.record(dim, m.key, "label") ?: X.Nil) }
                            } else {
                                text(sheet, r, 2 + i, m.label, StyleKey(bold = true, align = HorizontalAlignment.RIGHT))
                            }
                        }
                        r++
                        headerDim = dim
                    }
                    text(sheet, r, 0, input.label)
                    text(sheet, r, 1, input.id, StyleKey(muted = true))
                    val slots = linkedMapOf<Coord, Slot>()
                    members[dim].orEmpty().forEachIndexed { i, m ->
                        slots[listOf(m.key)] =
                            Slot(sheet, r, 2 + i).also {
                                valueStyles[it] =
                                    StyleKey(
                                        format = numberFormat(input.input!!.presentation, false),
                                        fill = Fill.INPUT,
                                    )
                            }
                    }
                    nodeSlots[input.id] = slots
                    r++
                }
                else -> Unit
            }
        }
        r++
    }
    layoutRemainingInputs(sheet, r)
}

internal fun ExcelWorkbookBuilder.layoutTableInput(sheet: XSSFSheet, start: Int, input: ViewNode): Int {
    if (input.id in options.dynamicTableCapacities) {
        layoutDynamicTableInput(input)
        return start
    }
    var r = start
    text(sheet, r++, 0, input.label, StyleKey(bold = true))
    val columns = input.input!!.columns
    columns.forEachIndexed { i, column ->
        text(sheet, r, 1 + i, column.name, StyleKey(bold = true, fill = Fill.HEADER, headerRule = true))
    }
    r++
    val rows = (view.case.inputs[input.id] as? Value.Vec)?.items.orEmpty()
    val dims = view.dimensions.values.filter { it.fromTable == input.id }
    rows.forEachIndexed { index, row ->
        val fields = (row as? Value.MapV)?.entries?.entries?.associate { (k, v) ->
            (
                (k as? Value.Kw)?.name
                    ?: k.toString()
                ) to
                v
        }.orEmpty()
        text(sheet, r, 0, "#${index + 1}", StyleKey(muted = true))
        columns.forEachIndexed { i, column ->
            val slot = Slot(sheet, r, 1 + i)
            tableSlots[Triple(input.id, index, column.name)] = slot
            val raw = fields[column.name] ?: Value.Nil
            val value = when {
                column.type == ValueType.DATE && raw is Value.Text -> Value.Date(java.time.LocalDate.parse(raw.value))
                column.type == ValueType.KEYWORD && raw is Value.Text -> Value.Kw(raw.value.removePrefix(":"))
                else -> raw
            }
            writeValue(slot, value)
            cell(sheet, r, 1 + i).cellStyle =
                styles.get(
                    StyleKey(
                        format = when {
                            column.type == ValueType.DATE -> "yyyy-mm-dd"
                            column.type.isNumeric -> styles.amountFormat(layout.number.precision)
                            else -> null
                        },
                        fill = Fill.INPUT,
                    ),
                )
            inputCells++
            dims.forEach { dim ->
                val key = when (val k = fields[dim.keyColumn]) {
                    is Value.Kw -> k.name
                    is Value.Text -> if (columns.first { it.name == dim.keyColumn }.type == ValueType.KEYWORD) {
                        k.value.removePrefix(":")
                    } else {
                        k.value
                    }
                    is Value.Num -> k.value.toPlainString()
                    else -> null
                }
                if (key != null) recordSlots[Triple(dim.id, key, column.name)] = slot
            }
        }
        r++
    }
    return r + 1
}

internal fun ExcelWorkbookBuilder.layoutParams() {
    val sheet = sheet(if (de) "Parameter" else "Parameters")
    text(
        sheet,
        0,
        0,
        if (de) "Parameter (blau: änderbar)" else "Parameters (blue: editable)",
        StyleKey(bold = true, size = 13),
    )
    listOf(texts.label, "Name", if (de) "Wert" else "Value", texts.reference).forEachIndexed { i, h ->
        text(sheet, 2, i, h, StyleKey(bold = true, fill = Fill.HEADER, headerRule = true))
    }
    sheet.setColumnWidth(0, 50 * 256)
    sheet.setColumnWidth(1, 32 * 256)
    sheet.setColumnWidth(2, 16 * 256)
    sheet.setColumnWidth(3, 40 * 256)
    var r = 3
    view.nodes.values.filter { it.kind == NodeKind.PARAM }.forEach { param ->
        text(sheet, r, 0, param.label)
        text(sheet, r, 1, param.id, StyleKey(muted = true))
        text(sheet, r, 3, param.parameter!!.presentation.reference.orEmpty(), StyleKey(muted = true))
        when (param.parameterValue) {
            is Value.Num, is Value.Bool, is Value.Kw, is Value.Text, is Value.Date -> {
                val slot = Slot(sheet, r, 2)
                nodeSlots[param.id] = linkedMapOf(emptyList<String>() to slot)
                valueStyles[slot] =
                    StyleKey(format = if (param.parameterValue is Value.Num) "General" else null, fill = Fill.PARAM)
            }
            else -> text(sheet, r, 2, param.parameterValue.toString(), StyleKey(muted = true))
        }
        r++
    }
}

internal fun ExcelWorkbookBuilder.layoutHelpers() {
    val sheet = sheet(if (de) "Hilfsrechnungen" else "Helper calculations")
    text(
        sheet,
        0,
        0,
        if (de) {
            "Hilfsrechnungen (Bedingungen und nicht dargestellte Zeilen)"
        } else {
            "Helper calculations (conditions and lines not presented)"
        },
        StyleKey(bold = true, size = 13),
    )
    sheet.setColumnWidth(0, 58 * 256)
    sheet.setColumnWidth(1, 30 * 256)
    (2..14).forEach { sheet.setColumnWidth(it, 16 * 256) }
    if (options.formulaColumn) sheet.setColumnWidth(15, 60 * 256)
    var r = layoutRemainingComputations(sheet, 2)
    var headerDim: String? = null

    fun memberHeader(dim: String) {
        if (headerDim == dim) return
        members[dim].orEmpty().forEachIndexed { i, m ->
            text(sheet, r, 2 + i, m.label, StyleKey(bold = true, align = HorizontalAlignment.RIGHT))
        }
        r++
        headerDim = dim
    }

    // Member activity of static dimensions (e.g. Person B only in joint assessment).
    view.dimensions.values.filter { it.fromTable == null }.forEach { dim ->
        dim.members.filter { it.condition != null }.forEach { member ->
            text(sheet, r, 0, "${dim.label}: ${member.label} " + if (de) "aktiv" else "active")
            text(sheet, r, 1, "${dim.id}.${member.key}", StyleKey(muted = true))
            activeSlots[dim.id to member.key] = Slot(sheet, r, 2).also { valueStyles[it] = StyleKey() }
            r++
        }
    }
    // Section guards.
    view.conditions.values.forEach { guard ->
        if (guard.id in guardSlots) return@forEach
        val section = findSectionLabel(guard.sectionId) ?: guard.sectionId
        val slots = linkedMapOf<Coord, Slot>()
        if (guard.dims.isEmpty()) {
            slots[emptyList()] = Slot(sheet, r, 2)
        } else if (guard.dims.size == 1) {
            memberHeader(guard.dims.single())
            members[guard.dims.single()].orEmpty().forEachIndexed { i, m ->
                slots[listOf(m.key)] = Slot(sheet, r, 2 + i)
            }
        }
        text(sheet, r, 0, (if (de) "Bedingung: " else "Condition: ") + section)
        text(sheet, r, 1, guard.id, StyleKey(muted = true))
        guardSlots[guard.id] = slots
        r++
    }
    // Lines, totals and choices that no table presents.
    view.nodes.values.filter {
        it.kind != NodeKind.INPUT && it.kind != NodeKind.PARAM && it.id !in nodeSlots
    }.forEach { vertex ->
        if (vertex.dims.size > 1) {
            fallbackPlacement += Triple(vertex, Slot(sheet, r++, 2), "more than one dimension")
            return@forEach
        }
        vertex.dims.singleOrNull()?.let(::memberHeader)
        text(sheet, r, 0, vertex.label)
        text(sheet, r, 1, vertex.id, StyleKey(muted = true))
        val style = StyleKey(format = numberFormat(presentationOf(vertex), false))
        val slots = linkedMapOf<Coord, Slot>()
        if (vertex.dims.isEmpty()) {
            slots[emptyList()] = Slot(sheet, r, 2).also { valueStyles[it] = style }
        } else {
            members[vertex.dims.single()].orEmpty().forEachIndexed { i, m ->
                slots[listOf(m.key)] =
                    Slot(sheet, r, 2 + i).also { valueStyles[it] = style }
            }
        }
        nodeSlots[vertex.id] = slots
        if (options.formulaColumn) text(sheet, r, 15, dslFormula(vertex), StyleKey(muted = true, italic = true))
        r++
    }
    // Choice options without a presented row.
    view.nodes.values.filter { it.choice != null }.forEach { choice ->
        choice.choice!!.options.forEach { option ->
            if ((choice.id to option.key) in optionSlots) return@forEach
            if (choice.dims.size > 1) return@forEach
            choice.dims.singleOrNull()?.let(::memberHeader)
            text(sheet, r, 0, "${choice.label}: ${option.label}", StyleKey(italic = true, muted = true))
            text(sheet, r, 1, "${choice.id}/${option.key}", StyleKey(muted = true))
            val slots = linkedMapOf<Coord, Slot>()
            if (choice.dims.isEmpty()) {
                slots[emptyList()] = Slot(sheet, r, 2)
            } else {
                members[choice.dims.single()].orEmpty().forEachIndexed { i, m ->
                    slots[listOf(m.key)] =
                        Slot(sheet, r, 2 + i)
                }
            }
            optionSlots[choice.id to option.key] = slots
            r++
        }
    }
    // Global aggregates exist independently of presentation slices or transposed columns.
    view.nodes.values.filter {
        it.dims.isNotEmpty() && it.type.isNumeric && it.aggregate != com.xqiou.mantra.core.model.AggregateRule.NONE &&
            it.check == null && it.reconcile == null && it.id !in aggregateSlots
    }.forEach { node ->
        text(sheet, r, 0, node.label + " (" + texts.total + ")")
        text(sheet, r, 1, "aggregate.${node.id}", StyleKey(muted = true))
        val slot = Slot(sheet, r++, 2)
        valueStyles[slot] = StyleKey(format = numberFormat(node.presentation, false))
        aggregateSlots[node.id] = slot
        reductionSlots[node.id to emptyMap()] = slot
        presentation += slot to { aggregateRef(node.id) }
    }
}

internal fun ExcelWorkbookBuilder.findSectionLabel(id: String): String? {
    fun visit(section: com.xqiou.mantra.core.view.ViewSection): String? {
        if (section.id == id) return section.label
        return section.children.filterIsInstance<com.xqiou.mantra.core.view.ViewSection>().firstNotNullOfOrNull(::visit)
    }
    return visit(view.tree)
}
