package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.ChoiceRule
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.view.signLabels
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import com.xqiou.mantra.render.layout.StyleSpec
import org.apache.poi.ss.formula.FormulaParseException
import org.apache.poi.ss.usermodel.HorizontalAlignment

// ── Values and formulas ───────────────────────────────────────────────────────────────────

internal fun ExcelWorkbookBuilder.presentationOf(vertex: ViewNode): Presentation = vertex.presentation

internal fun ExcelWorkbookBuilder.numberFormat(presentation: Presentation?, deduction: Boolean): String =
    when (presentation?.format) {
        "percent" -> styles.percentFormat(presentation.precision ?: layout.number.percentPrecision)
        "integer", "years" -> styles.amountFormat(0, deduction)
        "number", "factor" -> presentation.precision?.let { styles.amountFormat(it, deduction) } ?: "General"
        else -> styles.amountFormat(presentation?.precision ?: layout.number.precision, deduction)
    }

internal fun ExcelWorkbookBuilder.neutral(vertex: ViewNode): X.Scalar = when {
    vertex.type.isNumeric -> Ex.ZERO
    else -> Ex.EMPTY
}

internal fun ExcelWorkbookBuilder.aggregateRef(nodeId: String, fixed: Map<String, String> = emptyMap()): X.Scalar? =
    view.nodes[nodeId]?.let { reductionFormula(it, fixed) }

internal fun ExcelWorkbookBuilder.signLabelFormula(node: ViewNode, suffix: String = ""): X.Scalar? {
    val labels = node.signLabels ?: return null
    val value = aggregateRef(node.id) ?: return null
    return Ex.iff(
        Ex.cmp(">", value, Ex.ZERO),
        Ex.text((labels.positive ?: node.label) + suffix),
        Ex.iff(
            Ex.cmp("<", value, Ex.ZERO),
            Ex.text((labels.negative ?: node.label) + suffix),
            Ex.text((labels.zero ?: node.label) + suffix),
        ),
    )
}

internal fun ExcelWorkbookBuilder.sectionConditions(vertex: ViewNode, coord: Coord): X.Scalar? {
    if (vertex.guards.isEmpty()) return null
    val guards = vertex.guards.map { view.conditions.getValue(it) }
    val aligned = reader.alignGuards(vertex, coord) { dim -> members[dim].orEmpty().map { it.key } }
    val alternatives = aligned.assignments.map { assignment ->
        val tests = guards.map { guard ->
            val guardCoord = guard.dims.map(assignment::getValue)
            val slot = guardSlots[guard.id]?.get(guardCoord)
                ?: throw Untranslatable("section condition ${guard.sectionId} has no cell for $guardCoord")
            ref(slot, XKind.BOOL)
        } +
            aligned.extraDimensions.mapNotNull { dim ->
                activeSlots[dim to assignment.getValue(dim)]?.let { ref(it, XKind.BOOL) }
            }
        if (tests.size == 1) tests.single() else Ex.fn("AND", tests, kind = XKind.BOOL)
    }
    return when (alternatives.size) {
        0 -> Ex.FALSE
        1 -> alternatives.single()
        else -> Ex.fn("OR", alternatives, kind = XKind.BOOL)
    }
}

internal fun ExcelWorkbookBuilder.conditions(vertex: ViewNode, coord: Coord): List<X.Scalar> = buildList {
    sectionConditions(vertex, coord)?.let(::add)
    vertex.dims.forEachIndexed { i, dim -> activeSlots[dim to coord[i]]?.let { add(ref(it, XKind.BOOL)) } }
    vertex.ownCondition?.let { condition ->
        add(translator.truthy(translator.scalar(condition.form, FormulaTranslator.Ctx(vertex.dims, coord))))
    }
}

internal fun ExcelWorkbookBuilder.guarded(vertex: ViewNode, coord: Coord, core: X.Scalar): X.Scalar {
    val conditions = conditions(vertex, coord)
    if (conditions.isEmpty()) return core
    val test = conditions.singleOrNull() ?: Ex.fn("AND", conditions, kind = XKind.BOOL)
    return Ex.iff(test, core, neutral(vertex))
}

internal fun ExcelWorkbookBuilder.lineFormula(vertex: ViewNode, coord: Coord): X.Scalar {
    val item = vertex.line!!
    val context = if (item.spread) {
        FormulaTranslator.Ctx(vertex.dims.dropLast(1), coord.dropLast(1))
    } else {
        FormulaTranslator.Ctx(vertex.dims, coord)
    }
    val core = if (item.spread) {
        val key = coord.last()
        when (val x = translator.translate(item.formula.form, context)) {
            is X.MapX -> x.values.getOrNull(x.keys.indexOf(key))?.let(translator::toScalar) ?: Ex.ZERO
            is X.Range -> x.cells.getOrNull(x.keys.indexOf(key)) ?: Ex.ZERO
            else -> throw Untranslatable(":spread formula does not produce a member map")
        }
    } else {
        translator.scalar(item.formula.form, context)
    }
    val preserveNil = vertex.undefinedValues || vertex.boundary != null || item.ratio != null
    val coerced = if (vertex.type.isNumeric && !preserveNil && core.kind == XKind.ANY) {
        if (core.numericOrNil) {
            Ex.iff(Ex.cmp("=", core, Ex.EMPTY), Ex.ZERO, core)
        } else {
            Ex.iff(
                Ex.fn("ISERROR", core, kind = XKind.BOOL),
                core,
                Ex.iff(Ex.fn("ISNUMBER", core, kind = XKind.BOOL), core, Ex.ZERO),
            )
        }
    } else {
        core
    }
    val rounded = item.rounding?.let { rounding ->
        val rounded = Ex.round(coerced, Ex.num(rounding.scale.toLong()), rounding.mode)
        if (preserveNil) Ex.iff(Ex.cmp("=", coerced, Ex.EMPTY), Ex.EMPTY, rounded) else rounded
    } ?: coerced
    return guarded(vertex, coord, rounded)
}

internal fun ExcelWorkbookBuilder.sumMap(values: X.MapX): X.Scalar = Ex.fn(
    "SUM",
    values.values.map(translator::toScalar).ifEmpty {
        listOf(Ex.ZERO)
    },
)

internal fun ExcelWorkbookBuilder.totalFormula(vertex: ViewNode, coord: Coord): X.Scalar {
    val terms = vertex.components.map { component ->
        val componentNode = view.nodes.getValue(component.vertexId)
        val fixed = vertex.dims.zip(coord).toMap()
        val reduced = reductionFormula(componentNode, fixed) ?: Ex.ZERO
        val value = if (componentNode.line?.ratio != null && componentNode.dims.any { it !in vertex.dims }) {
            Ex.iff(reductionHasActiveMembers(componentNode, fixed), reduced, Ex.ZERO)
        } else {
            reduced
        }
        component.sign to value
    }
    val core = if (terms.isEmpty()) {
        Ex.ZERO
    } else {
        terms.drop(1).fold(
            if (terms.first().first <
                0
            ) {
                Ex.neg(terms.first().second)
            } else {
                terms.first().second
            },
        ) { acc, (sign, value) ->
            if (sign < 0) Ex.sub(acc, value) else Ex.add(acc, value)
        }
    }
    val complete = if (terms.isNotEmpty()) {
        val defined = terms.map { Ex.fn("ISNUMBER", it.second, kind = XKind.BOOL) }
        Ex.iff(boundedReductionBoolean("AND", defined), core, Ex.EMPTY)
    } else {
        core
    }
    return guarded(vertex, coord, complete)
}

internal fun ExcelWorkbookBuilder.optionFormula(vertex: ViewNode, optionKey: String, coord: Coord): X.Scalar {
    val option = vertex.choice!!.options.first { it.key == optionKey }
    val ctx = FormulaTranslator.Ctx(vertex.dims, coord)
    val value = translator.scalar(option.formula.form, ctx)
    val available = option.condition?.let { translator.truthy(translator.scalar(it.form, ctx)) }
    return if (available == null) value else Ex.iff(available, value, Ex.EMPTY)
}

internal fun ExcelWorkbookBuilder.choiceFormula(vertex: ViewNode, coord: Coord): X.Scalar {
    val options = vertex.choice!!.options.map { option ->
        optionSlots[vertex.id to option.key]?.get(coord)?.let(::ref)
            ?: throw Untranslatable("option ${option.key} has no cell")
    }
    val selected = Ex.fn(if (vertex.choice!!.rule == ChoiceRule.MIN) "MIN" else "MAX", options)
    val anyNumeric = boundedReductionBoolean("OR", options.map { Ex.fn("ISNUMBER", it, kind = XKind.BOOL) })
    val anyAvailable = boundedReductionBoolean(
        "OR",
        vertex.choice!!.options.map { option ->
            option.condition?.let {
                translator.truthy(translator.scalar(it.form, FormulaTranslator.Ctx(vertex.dims, coord)))
            }
                ?: Ex.TRUE
        },
    )
    val noNumber = if (vertex.boundary != null ||
        vertex.choice!!.ratio != null
    ) {
        Ex.iff(anyAvailable, Ex.EMPTY, Ex.ZERO)
    } else {
        Ex.ZERO
    }
    val core = Ex.iff(anyNumeric, selected, noNumber)
    val rounded = vertex.choice!!.rounding?.let {
        Ex.iff(Ex.fn("ISNUMBER", core, kind = XKind.BOOL), Ex.round(core, Ex.num(it.scale.toLong()), it.mode), Ex.EMPTY)
    } ?: core
    return guarded(vertex, coord, rounded)
}

/** Carries the schema's variable metadata into Excel data validation (dropdowns, ranges). */
internal fun ExcelWorkbookBuilder.validate(slot: Slot, input: ViewNode) {
    val helper = slot.sheet.dataValidationHelper
    val attributes = input.input!!.presentation.attributes
    val constraint = when {
        input.input!!.options.isNotEmpty() -> helper.createExplicitListConstraint(
            input.input!!.options.keys.toTypedArray(),
        )
        input.type == ValueType.BOOLEAN -> helper.createExplicitListConstraint(arrayOf("TRUE", "FALSE"))
        input.type.isNumeric && (attributes["min"] is Value.Num || attributes["max"] is Value.Num) -> {
            val min = (attributes["min"] as? Value.Num)?.value?.toPlainString()
            val max = (attributes["max"] as? Value.Num)?.value?.toPlainString()
            val type = if (input.type ==
                ValueType.INTEGER
            ) {
                org.apache.poi.ss.usermodel.DataValidationConstraint.ValidationType.INTEGER
            } else {
                org.apache.poi.ss.usermodel.DataValidationConstraint.ValidationType.DECIMAL
            }
            when {
                min != null && max != null -> helper.createNumericConstraint(
                    type,
                    org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.BETWEEN,
                    min,
                    max,
                )
                min != null -> helper.createNumericConstraint(
                    type,
                    org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.GREATER_OR_EQUAL,
                    min,
                    null,
                )
                else -> helper.createNumericConstraint(
                    type,
                    org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.LESS_OR_EQUAL,
                    max,
                    null,
                )
            }
        }
        else -> return
    }
    val validation = helper.createValidation(
        constraint,
        org.apache.poi.ss.util.CellRangeAddressList(slot.row, slot.row, slot.col, slot.col),
    )
    validation.showErrorBox = true
    (attributes["help"] as? Value.Text)?.value?.let { help ->
        validation.createPromptBox(input.label.take(32), help.take(255))
        validation.showPromptBox = true
    }
    slot.sheet.addValidationData(validation)
}

internal fun ExcelWorkbookBuilder.writeValue(slot: Slot, value: Value) {
    val c = cell(slot.sheet, slot.row, slot.col)
    when (value) {
        is Value.Num -> c.setCellValue(value.value.toDouble())
        is Value.Bool -> c.setCellValue(value.value)
        is Value.Kw -> c.setCellValue(value.name)
        is Value.Text -> c.setCellValue(value.value)
        is Value.Date -> {
            c.setCellValue(value.value.atStartOfDay())
            c.cellStyle = styles.get(StyleKey(format = "yyyy-mm-dd"))
        }
        Value.Nil -> c.setBlank()
        else -> c.setCellValue(value.toString())
    }
}

internal fun ExcelWorkbookBuilder.setFormula(slot: Slot, nodeId: String, build: () -> X.Scalar?) {
    reader.chargeCoordinateVisits()
    val c = cell(slot.sheet, slot.row, slot.col)
    valueStyles[slot]?.let { c.cellStyle = styles.get(it.applyRule(paperCellStyles[slot] ?: StyleSpec())) }
    val previousStrict = strictDynamicFormula
    strictDynamicFormula = false
    try {
        val formula = build() ?: return
        try {
            Ex.validateFormula(formula.text)
        } catch (error: Untranslatable) {
            if (strictDynamicFormula || fallbackValue(slot, nodeId) == null) {
                throw ExcelExportLimitException("Cannot export $nodeId at ${slot.address}: ${error.reason}")
            }
            throw error
        }
        c.cellFormula = formula.text
        formulaCells++
    } catch (e: Untranslatable) {
        if (strictDynamicFormula) {
            throw ExcelExportLimitException("Cannot export live calculation $nodeId at ${slot.address}: ${e.reason}")
        }
        fallback(slot, nodeId, e.reason)
    } catch (e: FormulaParseException) {
        if (strictDynamicFormula) {
            throw ExcelExportLimitException("POI rejected live calculation $nodeId at ${slot.address}")
        }
        fallback(slot, nodeId, "formula rejected by POI: ${e.message?.take(160)}")
    } finally {
        strictDynamicFormula = previousStrict
    }
}

private fun ExcelWorkbookBuilder.fallbackValue(slot: Slot, nodeId: String): Value? {
    val coord = nodeSlots[nodeId]?.entries?.firstOrNull { it.value == slot }?.key ?: return null
    return view.nodes[nodeId]?.values?.get(coord)
}

internal fun ExcelWorkbookBuilder.fallback(slot: Slot, nodeId: String, reason: String) {
    // Helper cells have no computed node snapshot. Guessing Nil can silently alter dependent results.
    val value = fallbackValue(slot, nodeId)
        ?: throw IllegalArgumentException(
            "Cannot export $nodeId at ${slot.address}: $reason; no computed fallback value",
        )
    val c = cell(slot.sheet, slot.row, slot.col)
    writeValue(slot, value)
    c.cellStyle =
        styles.get(
            (valueStyles[slot] ?: StyleKey()).applyRule(
                paperCellStyles[slot] ?: StyleSpec(),
            ).copy(fill = Fill.FALLBACK),
        )
    fallbacks += ExcelFallback(slot.sheet.sheetName, slot.local, nodeId, reason)
}

internal fun ExcelWorkbookBuilder.inputValue(input: ViewNode, coord: Coord): Value {
    var supplied: Value? = view.case.inputs[input.id]
    coord.forEach { key ->
        supplied =
            (supplied as? Value.MapV)?.entries?.let { it[Value.Kw(key)] ?: it[Value.Text(key)] }
    }
    val value =
        supplied?.takeIf { it != Value.Nil } ?: input.input!!.default ?: view.nodes[input.id]?.values?.get(coord)
            ?: when {
                input.type.isNumeric -> Value.ZERO
                input.type == ValueType.BOOLEAN -> Value.Bool(false)
                else -> Value.Nil
            }
    return if (input.type == ValueType.DATE && value is Value.Text) {
        Value.Date(java.time.LocalDate.parse(value.value))
    } else {
        value
    }
}

internal fun ExcelWorkbookBuilder.writeValuesAndFormulas() {
    nodeSlots.forEach { (id, slots) ->
        val vertex = view.nodes.getValue(id)
        slots.forEach { (coord, slot) ->
            if (vertex.type ==
                ValueType.DATE
            ) {
                valueStyles[slot] = (valueStyles[slot] ?: StyleKey()).copy(format = "yyyy-mm-dd")
            }
            when {
                vertex.kind == NodeKind.INPUT -> {
                    writeValue(slot, inputValue(vertex, coord))
                    writeInputProvenance(slot, vertex, coord)
                    cell(slot.sheet, slot.row, slot.col).cellStyle =
                        styles.get(
                            (valueStyles[slot] ?: StyleKey()).applyRule(
                                paperCellStyles[slot] ?: StyleSpec(),
                            ).copy(fill = Fill.INPUT),
                        )
                    validate(slot, vertex)
                    inputCells++
                }
                vertex.kind == NodeKind.PARAM -> {
                    writeValue(slot, vertex.parameterValue ?: Value.Nil)
                    cell(slot.sheet, slot.row, slot.col).cellStyle =
                        styles.get(
                            (valueStyles[slot] ?: StyleKey()).applyRule(
                                paperCellStyles[slot] ?: StyleSpec(),
                            ).copy(fill = Fill.PARAM),
                        )
                }
                vertex.check != null || vertex.reconcile != null -> setFormula(slot, id) {
                    businessValue(vertex, coord)
                }
                vertex.line != null -> setFormula(slot, id) { lineFormula(vertex, coord) }
                vertex.total != null -> setFormula(slot, id) { totalFormula(vertex, coord) }
                vertex.choice != null -> setFormula(slot, id) { choiceFormula(vertex, coord) }
            }
        }
    }
    optionSlots.forEach { (key, slots) ->
        val vertex = view.nodes.getValue(key.first)
        slots.forEach { (coord, slot) -> setFormula(slot, vertex.id) { optionFormula(vertex, key.second, coord) } }
    }
    guardSlots.forEach { (id, slots) ->
        val guard = view.conditions.getValue(id)
        slots.forEach { (coord, slot) ->
            setFormula(slot, id) {
                translator.truthy(translator.scalar(guard.formula.form, FormulaTranslator.Ctx(guard.dims, coord)))
            }
        }
    }
    activeSlots.forEach { (key, slot) ->
        val member = view.dimensions.getValue(key.first).members.first { it.key == key.second }
        setFormula(slot, "${key.first}.${key.second}") {
            translator.truthy(
                translator.scalar(member.condition!!.form, FormulaTranslator.Ctx(emptyList(), emptyList())),
            )
        }
    }
    presentation.forEach { (slot, build) -> setFormula(slot, "presentation") { build() } }
    statusFormulas.forEach { (slot, build) ->
        setFormula(slot, "status") { build() }
        cell(slot.sheet, slot.row, slot.col).cellStyle = styles.get(
            StyleKey(align = HorizontalAlignment.CENTER, muted = true).applyRule(paperCellStyles[slot] ?: StyleSpec()),
        )
    }
    fallbackPlacement.forEach { (vertex, slot, reason) -> fallback(slot, vertex.id, reason) }
    pendingLinks.forEach { (sheet, position, ref) ->
        tableSheets[ref]?.let { link(sheet, position.first, position.second, it.sheetName) }
    }
}
