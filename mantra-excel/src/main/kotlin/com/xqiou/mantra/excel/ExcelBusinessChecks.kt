package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot

/** Live business decisions use the same input cells and guards as the calculation formulas. */
internal fun ExcelWorkbookBuilder.businessValue(node: ViewNode, coord: Coord): X.Scalar {
    val context = FormulaTranslator.Ctx(node.dims, coord)
    val value = node.check?.let { translator.scalar(it.formula.form, context) }
        ?: node.reconcile?.let {
            Ex.sub(translator.scalar(it.left.form, context), translator.scalar(it.right.form, context))
        } ?: throw Untranslatable("${node.id} is not a business check")
    return Ex.iff(applicable(node, coord), value, Ex.EMPTY)
}

internal fun ExcelWorkbookBuilder.businessPredicate(node: ViewNode, coord: Coord): X.Scalar = if (node.check != null) {
    reference(node.id, node.dims, coord) as? X.Scalar ?: throw Untranslatable("${node.id} has no decision cell")
} else {
    val difference = reference(node.id, node.dims, coord) as? X.Scalar
        ?: throw Untranslatable("${node.id} has no difference cell")
    Ex.cmp("<=", Ex.fn("ABS", difference), Ex.num(node.reconcile!!.tolerance))
}

internal fun ExcelWorkbookBuilder.applicable(node: ViewNode, coord: Coord): X.Scalar {
    val tests = conditions(node, coord)
    return if (tests.isEmpty()) {
        Ex.TRUE
    } else if (tests.size == 1) {
        tests.single()
    } else {
        Ex.fn("AND", tests, XKind.BOOL)
    }
}

/** Undefined rate components also make any enclosing numeric checkpoint undefined. */
internal fun ExcelWorkbookBuilder.usesRatio(node: ViewNode): Boolean =
    node.line?.ratio != null || node.components.any { usesRatio(view.node(it.vertexId)) }

internal fun ExcelWorkbookBuilder.businessStatus(node: ViewNode): X.Scalar {
    val coordinates = nodeSlots[node.id]?.keys.orEmpty().toList()
    if (coordinates.isEmpty()) return Ex.EMPTY
    val active = coordinates.map { applicable(node, it) }
    val allPass = coordinates.mapIndexed { index, coord ->
        Ex.iff(active[index], businessPredicate(node, coord), Ex.TRUE)
    }
    val anyActive = if (active.size == 1) active.single() else Ex.fn("OR", active, XKind.BOOL)
    val passed = if (allPass.size == 1) allPass.single() else Ex.fn("AND", allPass, XKind.BOOL)
    return Ex.iff(anyActive, Ex.iff(passed, Ex.text("✓"), Ex.text("✗")), Ex.EMPTY)
}

/** Cross-foot a rate from its components, filtering by the rate's own applicability. */
internal fun ExcelWorkbookBuilder.ratioAggregate(
    node: ViewNode,
    contextDims: List<String> = emptyList(),
    contextCoord: Coord = emptyList(),
): X.Scalar {
    val ratio = node.line!!.ratio!!
    val coordinates = nodeSlots[node.id]?.keys.orEmpty().filter { coord ->
        node.dims.indices.all { index ->
            val fixed = contextDims.indexOf(node.dims[index])
            fixed < 0 || coord[index] == contextCoord[fixed]
        }
    }
    fun component(id: String): X.Scalar {
        val terms = coordinates.map { coord ->
            val value = reference(id, node.dims, coord) as? X.Scalar
                ?: throw Untranslatable("ratio component $id has no aligned cell")
            Ex.iff(applicable(node, coord), value, Ex.ZERO)
        }
        return Ex.fn("SUM", terms.ifEmpty { listOf(Ex.ZERO) })
    }
    val numerator = component(ratio.numerator)
    val denominator = component(ratio.denominator)
    val quotient = Ex.div(numerator, denominator)
    val rounded = ratio.rounding?.let { Ex.round(quotient, Ex.num(it.scale.toLong()), it.mode) } ?: quotient
    return Ex.iff(Ex.cmp("=", denominator, Ex.ZERO), Ex.EMPTY, rounded)
}

/** The workbook cannot infer whether an unchanged default zero was supplied as a fact. */
internal fun ExcelWorkbookBuilder.writeBusinessChecks() {
    val controls = view.nodes.values.filter { it.check != null || it.reconcile != null }
    val inputs = view.nodes.values.filter { node ->
        node.input?.let { input ->
            input.requiredWhen != null || input.minRows != null || input.columns.any { it.requiredWhen != null } ||
                input.presentation.attributes.keys.any { it in setOf("required", "min", "max") }
        } == true
    }
    if (controls.isEmpty() && inputs.isEmpty()) return
    val sheet = sheet(if (de) "Prüfungen" else "Checks")
    listOf(48, 18, 18, 22, 22, 22, 18).forEachIndexed { column, width -> sheet.setColumnWidth(column, width * 256) }
    text(sheet, 0, 0, if (de) "Geschäftliche Prüfungen" else "Business checks", StyleKey(bold = true, size = 13))
    val continuation = if (de) {
        "Berechnung läuft auch bei nicht bestandenen Prüfungen weiter."
    } else {
        "Calculation continues when business checks fail."
    }
    text(sheet, 1, 0, continuation)
    val presenceExplanation = if (de) {
        "Bereitgestellt: Tatsachen bestätigen; Vorgabe/implizite Null zählt nicht."
    } else {
        "Provided: confirm a supplied fact; defaults and implicit zeros do not count."
    }
    text(sheet, 2, 0, presenceExplanation)
    listOf("Check", "Provided", "Status", "Left", "Right", "Difference", "Tolerance").forEachIndexed { column, title ->
        text(sheet, 4, column, title, StyleKey(bold = true, fill = Fill.HEADER))
    }
    var row = 5
    var inputId: String? = null
    val inputDecisions = linkedMapOf<String, MutableList<Slot>>()
    fun decision(title: String, provided: Boolean? = null, active: X.Scalar = Ex.TRUE, build: (Slot?) -> X.Scalar) {
        text(sheet, row, 0, title)
        val presence = provided?.let {
            Slot(sheet, row, 1).also { slot ->
                writeValue(slot, Value.Bool(it))
                cell(sheet, row, 1).cellStyle = styles.get(StyleKey(fill = Fill.INPUT))
                providedSlots += slot
                inputCells++
            }
        }
        val status = Slot(sheet, row, 2)
        inputId?.let { inputDecisions.getOrPut(it) { mutableListOf() } += status }
        setFormula(status, "business-status") {
            Ex.iff(active, Ex.iff(build(presence), Ex.text("✓"), Ex.text("✗")), Ex.EMPTY)
        }
        row++
    }
    controls.forEach { node ->
        nodeSlots[node.id]?.keys.orEmpty().forEach { coord ->
            val current = row
            text(
                sheet,
                current,
                0,
                node.label +
                    if (coord.isEmpty()) {
                        ""
                    } else {
                        " [${(
                            dynamic?.initialCoordinate(node.dims, coord) ?: coord.map {
                                if (it.startsWith("\u0000")) "reserved" else it
                            }
                            ).joinToString("/")}]"
                    },
            )
            dynamicCoordinateCaption(node, coord)?.let { title ->
                setFormula(Slot(sheet, current, 0), node.id) { title }
            }
            setFormula(Slot(sheet, current, 2), node.id) {
                val status = Ex.iff(businessPredicate(node, coord), Ex.text("✓"), Ex.text("✗"))
                Ex.iff(applicable(node, coord), status, Ex.EMPTY)
            }
            node.reconcile?.let { item ->
                val context = FormulaTranslator.Ctx(node.dims, coord)
                setFormula(Slot(sheet, current, 3), node.id) { translator.scalar(item.left.form, context) }
                setFormula(Slot(sheet, current, 4), node.id) { translator.scalar(item.right.form, context) }
                setFormula(Slot(sheet, current, 5), node.id) {
                    reference(node.id, node.dims, coord) as? X.Scalar
                }
                writeValue(Slot(sheet, current, 6), Value.Num(item.tolerance))
            }
            row++
        }
    }
    inputs.forEach { node ->
        inputId = node.id
        val input = node.input!!
        val required = (input.presentation.attributes["required"] as? Value.Bool)?.value == true
        if (input.type != ValueType.TABLE) {
            nodeSlots[node.id]?.forEach { (coord, valueSlot) ->
                val context = FormulaTranslator.Ctx(node.dims, coord)
                val caption =
                    node.label +
                        if (coord.isEmpty()) {
                            ""
                        } else {
                            " [${(
                                dynamic?.initialCoordinate(node.dims, coord) ?: coord.map {
                                    if (it.startsWith("\u0000")) "reserved" else it
                                }
                                ).joinToString("/")}]"
                        }
                val coordinateTitle = dynamicCoordinateCaption(node, coord)
                if (required || input.requiredWhen != null) {
                    val active = applicable(node, coord)
                    val keyedProvided = dynamicInputProvided(node, coord)
                    decision(
                        "$caption · required",
                        if (keyedProvided ==
                            null
                        ) {
                            view.inputProvided(node.id, coord)
                        } else {
                            null
                        },
                        active,
                    ) { presence ->
                        val predicate = if (required) {
                            Ex.TRUE
                        } else {
                            input.requiredWhen?.let {
                                translator.scalar(it.form, context)
                            } ?: Ex.TRUE
                        }
                        val fact = keyedProvided ?: ref(requireNotNull(presence), XKind.BOOL)
                        val present = Ex.fn("AND", fact, filled(valueSlot), kind = XKind.BOOL)
                        Ex.iff(predicate, present, Ex.TRUE)
                    }
                    coordinateTitle?.let { title ->
                        setFormula(Slot(sheet, row - 1, 0), node.id) {
                            Ex.chain("&", listOf(title, Ex.text(" · required")), Ex.CONCAT, XKind.TEXT)
                        }
                    }
                }
                for ((key, operator) in listOf("min" to ">=", "max" to "<=")) {
                    val limit = (input.presentation.attributes[key] as? Value.Num)?.value ?: continue
                    decision("$caption · $key", active = applicable(node, coord)) {
                        Ex.iff(
                            Ex.fn("ISNUMBER", ref(valueSlot), kind = XKind.BOOL),
                            Ex.cmp(operator, ref(valueSlot), Ex.num(limit)),
                            Ex.TRUE,
                        )
                    }
                    coordinateTitle?.let { title ->
                        setFormula(Slot(sheet, row - 1, 0), node.id) {
                            Ex.chain("&", listOf(title, Ex.text(" · $key")), Ex.CONCAT, XKind.TEXT)
                        }
                    }
                }
            }
        } else {
            val records = (view.case.inputs[node.id] as? Value.Vec)?.items.orEmpty()
            if (required || input.requiredWhen != null) {
                val active = applicable(node, emptyList())
                val liveProvided = dynamic?.tables?.get(node.id)?.let {
                    Ex.cmp(">", dynamic!!.rowCount(node.id), Ex.ZERO)
                }
                decision(
                    "${node.label} · required",
                    if (liveProvided ==
                        null
                    ) {
                        view.inputProvided(node.id)
                    } else {
                        null
                    },
                    active,
                ) { presence ->
                    val predicate = if (required) {
                        Ex.TRUE
                    } else {
                        input.requiredWhen?.let {
                            translator.scalar(it.form, FormulaTranslator.Ctx(emptyList(), emptyList()))
                        } ?: Ex.TRUE
                    }
                    Ex.iff(predicate, liveProvided ?: ref(requireNotNull(presence), XKind.BOOL), Ex.TRUE)
                }
            }
            input.minRows?.let { minimum ->
                decision("${node.label} · minimum rows", active = applicable(node, emptyList())) {
                    val count = dynamic?.tables?.get(node.id)?.let { dynamic!!.rowCount(node.id) }
                        ?: Ex.num(records.size.toLong())
                    Ex.cmp(">=", count, Ex.num(minimum.toLong()))
                }
            }
            val liveTable = dynamic?.tables?.get(node.id)
            val indices = liveTable?.let { 0 until it.capacity } ?: records.indices
            indices.forEach { index ->
                val fields = input.columns.map { column ->
                    if (liveTable != null) {
                        dynamic!!.field(node.id, index, column.name)
                    } else {
                        val slot = tableSlots[Triple(node.id, index, column.name)]
                            ?: throw Untranslatable("${node.id} row $index has no ${column.name} cell")
                        ref(slot, if (column.type.isNumeric) XKind.NUM else XKind.ANY)
                    }
                }
                val record = X.MapX(input.columns.map { it.name }, fields)
                val context = FormulaTranslator.Ctx(emptyList(), emptyList(), mapOf("row" to record))
                input.columns.filter { it.requiredWhen != null }.forEach { column ->
                    val valueSlot = if (liveTable ==
                        null
                    ) {
                        tableSlots.getValue(Triple(node.id, index, column.name))
                    } else {
                        null
                    }
                    val caption = "${node.label} [${index + 1}] · ${column.name}"
                    val provided = view.inputProvided(node.id, rowIndex = index, column = column.name)
                    val active = if (liveTable == null) {
                        applicable(node, emptyList())
                    } else {
                        Ex.fn(
                            "AND",
                            applicable(node, emptyList()),
                            dynamic!!.rowPresent(node.id, index),
                            kind = XKind.BOOL,
                        )
                    }
                    decision(caption, if (liveTable == null) provided else null, active) { presence ->
                        val requiredNow = translator.scalar(column.requiredWhen!!.form, context)
                        val supplied = if (liveTable == null) {
                            val fact = ref(requireNotNull(presence), XKind.BOOL)
                            Ex.fn("AND", fact, filled(requireNotNull(valueSlot)), kind = XKind.BOOL)
                        } else {
                            val value = dynamic!!.field(node.id, index, column.name)
                            Ex.fn(
                                "AND",
                                Ex.cmp("<>", value, Ex.EMPTY),
                                Ex.cmp(">", Ex.fn("LEN", Ex.fn("TRIM", value, kind = XKind.TEXT)), Ex.ZERO),
                                kind = XKind.BOOL,
                            )
                        }
                        Ex.iff(requiredNow, supplied, Ex.TRUE)
                    }
                }
            }
        }
    }
    inputStatusSlots.forEach { (slot, id) ->
        val decisions = inputDecisions[id].orEmpty()
        if (decisions.isNotEmpty()) {
            setFormula(slot, id) {
                // COUNTIF on contiguous check rows avoids variadic boolean formula limits.
                val range = decisions.first().address + ":" + decisions.last().local
                val failed = Ex.cmp(">", Ex.fn("COUNTIF", Ex.atom(range), Ex.text("✗")), Ex.ZERO)
                val passed = Ex.cmp(">", Ex.fn("COUNTIF", Ex.atom(range), Ex.text("✓")), Ex.ZERO)
                Ex.iff(failed, Ex.text("✗"), Ex.iff(passed, Ex.text("✓"), Ex.EMPTY))
            }
        }
    }
    sheet.createFreezePane(0, 5)
}

private fun ExcelWorkbookBuilder.filled(slot: Slot): X.Scalar = Ex.fn(
    "AND",
    Ex.fn("NOT", Ex.fn("ISBLANK", ref(slot), kind = XKind.BOOL), kind = XKind.BOOL),
    Ex.cmp(">", Ex.fn("LEN", Ex.fn("TRIM", ref(slot), kind = XKind.TEXT)), Ex.ZERO),
    kind = XKind.BOOL,
)
