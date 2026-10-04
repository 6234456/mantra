package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.ChoiceRule
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.structure.SchemaMap
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.StyleSpec
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook

internal class ExcelWorkbookBuilder(
    internal val result: CalculationView,
    internal val layout: LayoutSpec,
    internal val options: ExcelOptions,
) : ExcelResolver {
    internal val view = result
    internal val texts = layout.texts
    internal val de = texts.language == "de"
    internal val wb = XSSFWorkbook()
    internal val styles = ExcelStyles(wb, layout.number)
    internal val map: SchemaMap = view.structure
    internal val translator = FormulaTranslator(this, view.functions)

    internal data class Slot(val sheet: XSSFSheet, val row: Int, val col: Int) {
        val address: String get() = "'${sheet.sheetName.replace(
            "'",
            "''",
        )}'!\$${CellReference.convertNumToColString(col)}\$${row + 1}"
        val local: String get() = "${CellReference.convertNumToColString(col)}${row + 1}"
    }

    internal class XMember(val key: String, val label: String, val index: Int)

    /** All declared members (static dimensions keep members that are inactive in this case). */
    internal val members: Map<String, List<XMember>> = view.dimensions.mapValues { (id, decl) ->
        if (decl.fromTable == null && decl.periods == null) {
            decl.members.mapIndexed { i, m -> XMember(m.key, m.label, i) }
        } else {
            view.members[id].orEmpty().map { XMember(it.key, it.label, it.index) }
        }
    }

    internal val nodeSlots = linkedMapOf<String, LinkedHashMap<Coord, Slot>>()

    /** First visible cell containing a node's aggregate over all member dimensions. */
    internal val aggregateSlots = linkedMapOf<String, Slot>()
    internal val reductionSlots = linkedMapOf<Pair<String, Map<String, String>>, Slot>()
    internal val reductionExpressions = hashMapOf<Pair<String, Map<String, String>>, X.Scalar?>()
    internal val optionSlots = linkedMapOf<Pair<String, String>, LinkedHashMap<Coord, Slot>>()
    internal val guardSlots = linkedMapOf<String, LinkedHashMap<Coord, Slot>>()
    internal val activeSlots = linkedMapOf<Pair<String, String>, Slot>()
    internal val recordSlots = hashMapOf<Triple<String, String, String>, Slot>()
    internal val tableSlots = hashMapOf<Triple<String, Int, String>, Slot>()
    internal val presentation = mutableListOf<Pair<Slot, () -> X.Scalar?>>()
    internal val statusFormulas = mutableListOf<Pair<Slot, () -> X.Scalar?>>()
    internal val providedSlots = mutableListOf<Slot>()
    internal val inputStatusSlots = mutableListOf<Pair<Slot, String>>()
    internal val slotNames = hashMapOf<Slot, String>()
    internal val rangeNames = hashMapOf<String, String>()
    internal val expressionSlots = linkedMapOf<X.Scalar, Slot>()
    internal var expressionSheet: XSSFSheet? = null
    internal val usedNames = hashSetOf<String>()
    internal val tableSheets = linkedMapOf<String, XSSFSheet>()
    internal val sectionSheets = linkedMapOf<String, XSSFSheet>()
    internal val valueStyles = hashMapOf<Slot, StyleKey>()
    internal val paperCellStyles = hashMapOf<Slot, StyleSpec>()
    internal val fallbacks = mutableListOf<ExcelFallback>()
    internal var formulaCells = 0
    internal var inputCells = 0
    internal var createdCells = 0

    fun build(): ExcelWorkbook {
        try {
            val paper = Render.completePaper(result, layout)
            val overview = sheet(if (de) "Übersicht" else "Overview")
            paper.tables.forEach { layoutTable(it) }
            layoutInputs()
            layoutParams()
            layoutHelpers()
            defineNames()
            writeValuesAndFormulas()
            writeBusinessChecks()
            writeOverview(overview, paper)
            writeAudit(paper)
            val errors = if (options.evaluate) evaluate() else emptyList()
            wb.setForceFormulaRecalculation(true)
            val report = ExcelReport(
                sheets = (0 until wb.numberOfSheets).map { wb.getSheetName(it) },
                formulaCells = formulaCells,
                inputCells = inputCells,
                names = wb.allNames.size,
                fallbacks = fallbacks.toList(),
                evaluationErrors = errors,
            )
            val addresses = nodeSlots.mapValues { (_, slots) -> slots.mapValues { (_, slot) -> slot.address } }
                .toMutableMap()
            reductionSlots.forEach { (scope, slot) ->
                val coord = view.node(scope.first).dims.filter { it in scope.second }.map(scope.second::getValue)
                addresses.getOrPut("aggregate.${scope.first}") { emptyMap() }
                addresses["aggregate.${scope.first}"] =
                    addresses.getValue("aggregate.${scope.first}") + (coord to slot.address)
            }
            aggregateSlots.forEach { (id, slot) ->
                addresses["aggregate.$id"] =
                    addresses["aggregate.$id"].orEmpty() + (emptyList<String>() to slot.address)
            }
            return ExcelWorkbook(
                wb,
                report,
                addresses,
                recordSlots.mapValues { (_, slot) ->
                    slot.address
                },
                tableSlots.mapValues { (_, slot) -> slot.address },
                reductionSlots.mapValues { (_, slot) -> slot.address },
            )
        } catch (error: Exception) {
            runCatching { wb.close() }
            throw error
        }
    }

    // ── Sheets ─────────────────────────────────────────────────────────────────────────────────

    internal val sheetNames = hashSetOf<String>()

    internal fun sheetName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\[\\]:*?/\\\\]"), " ").replace(Regex("\\s+"), " ").trim().trim('\'')
        var candidate = cleaned.take(31).trim()
        var counter = 2
        while (candidate.lowercase() in sheetNames) {
            val suffix = " ($counter)"
            candidate = cleaned.take(31 - suffix.length).trim() + suffix
            counter++
        }
        sheetNames += candidate.lowercase()
        return candidate
    }

    internal fun sheet(name: String): XSSFSheet {
        if (wb.numberOfSheets >= options.maxSheets) {
            throw ExcelExportLimitException("Workbook exceeds ${options.maxSheets} sheets")
        }
        return wb.createSheet(sheetName(name))
    }

    internal fun cell(sheet: XSSFSheet, row: Int, col: Int): XSSFCell {
        val existing = sheet.getRow(row)?.getCell(col)
        if (existing != null) return existing
        if (createdCells >= options.maxCells) {
            throw ExcelExportLimitException("Workbook exceeds ${options.maxCells} cells")
        }
        createdCells++
        return (sheet.getRow(row) ?: sheet.createRow(row)).createCell(col)
    }

    internal fun text(sheet: XSSFSheet, row: Int, col: Int, value: String, style: StyleKey = StyleKey()) {
        if (value.isEmpty() && style == StyleKey()) return
        cell(sheet, row, col).apply {
            setCellValue(value)
            cellStyle = styles.get(style)
        }
    }

    internal fun link(target: XSSFSheet, row: Int, col: Int, destination: String) {
        val hyperlink = wb.creationHelper.createHyperlink(HyperlinkType.DOCUMENT)
        hyperlink.address = "'${destination.replace("'", "''")}'!A1"
        cell(target, row, col).hyperlink = hyperlink
    }

    internal val pendingLinks = mutableListOf<Triple<XSSFSheet, Pair<Int, Int>, String>>()
    internal val fallbackPlacement = mutableListOf<Triple<ViewNode, Slot, String>>()

    // ── Names ──────────────────────────────────────────────────────────────────────────────────

    internal fun sanitize(raw: String): String = ExcelNames.sanitize(raw)

    internal fun define(name: String, refersTo: String): String? {
        if (!options.useNames) return null
        var candidate = sanitize(name).take(250)
        var counter = 2
        while (candidate.lowercase() in usedNames) candidate = "${sanitize(name).take(245)}_${counter++}"
        return try {
            wb.createName().apply {
                nameName = candidate
                refersToFormula = refersTo
            }
            usedNames += candidate.lowercase()
            candidate
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    internal fun defineNames() {
        nodeSlots.forEach { (id, slots) ->
            if (slots.keys.singleOrNull()?.isEmpty() == true) {
                val slot = slots.values.single()
                define(id, slot.address)?.let { slotNames[slot] = it }
            } else {
                slots.forEach { (coord, slot) ->
                    define("${id}__${coord.joinToString("__")}", slot.address)?.let {
                        slotNames[slot] =
                            it
                    }
                }
                val cells = slots.values.toList()
                if (cells.map { it.sheet to it.row }.distinct().size == 1 &&
                    cells.zipWithNext().all { (a, b) -> b.col == a.col + 1 }
                ) {
                    val first = cells.first()
                    val last = cells.last()
                    define(
                        id,
                        first.address + ":" + "\$${CellReference.convertNumToColString(last.col)}\$${last.row + 1}",
                    )?.let {
                        rangeNames[id] =
                            it
                    }
                }
            }
        }
        optionSlots.forEach { (key, slots) ->
            slots.forEach { (coord, slot) ->
                define((listOf(key.first, "opt", key.second) + coord).joinToString("__"), slot.address)?.let {
                    slotNames[slot] =
                        it
                }
            }
        }
        guardSlots.forEach { (id, slots) ->
            slots.forEach { (coord, slot) ->
                define((listOf("guard", id.removeSuffix("?when")) + coord).joinToString("__"), slot.address)?.let {
                    slotNames[slot] =
                        it
                }
            }
        }
        activeSlots.forEach { (key, slot) ->
            define("active__${key.first}__${key.second}", slot.address)?.let {
                slotNames[slot] =
                    it
            }
        }
        recordSlots.forEach { (key, slot) ->
            val table = view.dimensions[key.first]?.fromTable ?: key.first
            define("${table}__${key.second}__${key.third}", slot.address)?.let { slotNames[slot] = it }
        }
    }

    internal fun ref(slot: Slot, kind: XKind = XKind.NUM): X.Scalar = Ex.atom(slotNames[slot] ?: slot.address, kind)

    // ── ExcelResolver ──────────────────────────────────────────────────────────────────────────

    internal fun kindOf(vertex: ViewNode): XKind = when {
        vertex.kind == NodeKind.PARAM -> when (vertex.parameterValue) {
            is Value.Bool -> XKind.BOOL
            is Value.Kw, is Value.Text -> XKind.TEXT
            is Value.Date -> XKind.DATE
            else -> XKind.NUM
        }
        vertex.type == ValueType.BOOLEAN -> XKind.BOOL
        vertex.type == ValueType.KEYWORD || vertex.type == ValueType.TEXT -> XKind.TEXT
        vertex.type == ValueType.DATE -> XKind.DATE
        vertex.type.isNumeric -> XKind.NUM
        else -> XKind.ANY
    }

    internal fun literal(value: Value): X = when (value) {
        is Value.Num -> Ex.num(value.value)
        is Value.Bool -> if (value.value) Ex.TRUE else Ex.FALSE
        is Value.Kw -> Ex.text(value.name)
        is Value.Text -> Ex.text(value.value)
        Value.Nil -> X.Nil
        is Value.Vec -> X.Vec(value.items.map(::literal))
        is Value.MapV -> X.MapX(
            value.entries.keys.map {
                (it as? Value.Kw)?.name ?: it.toString()
            },
            value.entries.values.map(::literal),
        )
        is Value.Date -> excelDate(value.value)
    }

    override fun reference(nodeId: String, contextDims: List<String>, contextCoord: Coord): X? {
        val relation = view.dimensions.values.firstOrNull {
            it.parentDimension != null && "relation_${it.id}" == nodeId
        }
        if (relation != null) {
            val keys = members[relation.id].orEmpty().map { it.key }
            return X.MapX(
                keys,
                keys.map { key ->
                    record(relation.id, key, relation.parentKeyColumn ?: "parent-key")
                        ?: throw Untranslatable("$nodeId has no parent cell for $key")
                },
            )
        }
        val vertex = view.nodes[nodeId] ?: return null
        if (vertex.kind == NodeKind.INPUT && vertex.type == ValueType.TABLE) {
            val count = (view.case.inputs[nodeId] as? Value.Vec)?.items?.size ?: 0
            return X.Vec(
                (0 until count).map { row ->
                    X.MapX(
                        vertex.input!!.columns.map { it.name },
                        vertex.input!!.columns.map { column ->
                            val slot = tableSlots[Triple(nodeId, row, column.name)]
                                ?: throw Untranslatable("$nodeId row $row has no ${column.name} cell")
                            ref(
                                slot,
                                when {
                                    column.type.isNumeric -> XKind.NUM
                                    column.type == ValueType.DATE -> XKind.DATE
                                    column.type == ValueType.BOOLEAN -> XKind.BOOL
                                    column.type == ValueType.TEXT || column.type == ValueType.KEYWORD -> XKind.TEXT
                                    else -> XKind.ANY
                                },
                            )
                        },
                    )
                },
            )
        }
        val parameterValue = vertex.parameterValue
        if (vertex.kind == NodeKind.PARAM &&
            (parameterValue is Value.Vec || parameterValue is Value.MapV)
        ) {
            return literal(parameterValue)
        }
        val extra = vertex.dims.filter { it !in contextDims }
        // A memberless dimension has no worksheet range. Preserve its empty-map shape for
        // count/get/vals, while aggregation consumes it as the additive identity.
        if (extra.isNotEmpty() && vertex.dims.any { members[it].isNullOrEmpty() }) {
            return X.MapX(emptyList(), emptyList())
        }
        val slots = nodeSlots[nodeId] ?: throw Untranslatable("$nodeId has no cell")
        if (extra.isEmpty()) {
            val coord = vertex.dims.map { contextCoord[contextDims.indexOf(it)] }
            return slots[coord]?.let { ref(it, kindOf(vertex)) }
                ?: throw Untranslatable("$nodeId has no cell for $coord")
        }
        val fixed = contextDims.zip(contextCoord).toMap()
        fun nested(index: Int, assignment: Map<String, String>): X {
            if (index == vertex.dims.size) {
                val coordinate = vertex.dims.map(assignment::getValue)
                return slots[coordinate]?.let { ref(it, kindOf(vertex)) }
                    ?: throw Untranslatable("$nodeId has no cell for $coordinate")
            }
            val dimension = vertex.dims[index]
            fixed[dimension]?.let { return nested(index + 1, assignment + (dimension to it)) }
            val keys = members[dimension].orEmpty().map { it.key }
            return X.MapX(keys, keys.map { nested(index + 1, assignment + (dimension to it)) })
        }
        return nested(0, emptyMap())
    }

    override fun record(dim: String, key: String, field: String): X? {
        val decl = view.dimensions[dim] ?: return null
        if (decl.periods != null) {
            return view.members[dim].orEmpty().firstOrNull { it.key == key }?.record?.get(field)?.let(::literal)
        }
        if (decl.fromTable == null) {
            val member = members[dim].orEmpty().firstOrNull { it.key == key } ?: return null
            return when (field) {
                "key" -> Ex.text(member.key)
                "label" -> Ex.text(member.label)
                "index" -> Ex.num(member.index.toLong())
                else -> null
            }
        }
        val slot = recordSlots[Triple(dim, key, field)] ?: return when (field) {
            "key" -> Ex.text(key)
            "label" -> members[dim]?.firstOrNull { it.key == key }?.label?.let(Ex::text)
            else -> null
        }
        val column = view.nodes[decl.fromTable]?.input?.columns?.firstOrNull { it.name == field }
        return ref(
            slot,
            when {
                column?.type == ValueType.DATE -> XKind.DATE
                column?.type == ValueType.BOOLEAN -> XKind.BOOL
                column?.type == ValueType.TEXT || column?.type == ValueType.KEYWORD -> XKind.TEXT
                column?.type?.isNumeric == true -> XKind.NUM
                else -> XKind.ANY
            },
        )
    }

    override fun periodKeys(dimension: String): X.Vec? = periodKeyValues(dimension)

    override fun previous(nodeId: String, contextDims: List<String>, contextCoord: Coord): ExcelPrevious =
        previousReference(nodeId, contextDims, contextCoord)

    override fun materialize(value: X.Scalar): X.Scalar = materializeExpression(value)

    override fun isNode(nodeId: String): Boolean = nodeId in view.nodes ||
        view.dimensions.values.any { it.parentDimension != null && "relation_${it.id}" == nodeId }

    override fun isDimension(name: String): Boolean = name in view.dimensions

    // ── Evaluation ─────────────────────────────────────────────────────────────────────────────

    internal fun evaluate(): List<String> {
        val evaluator = wb.creationHelper.createFormulaEvaluator()
        val errors = mutableListOf<String>()
        for (s in 0 until wb.numberOfSheets) {
            val sheet = wb.getSheetAt(s)
            for (row in sheet) {
                for (c in row) {
                    if (c.cellType != CellType.FORMULA) continue
                    try {
                        evaluator.evaluateFormulaCell(c)
                    } catch (e: Exception) {
                        errors += "${sheet.sheetName}!${c.address}: ${e.message?.take(200)}"
                    }
                }
            }
        }
        return errors
    }

    internal fun dslFormula(vertex: ViewNode): String {
        vertex.line?.let { return it.formula.source.replace(Regex("\\s+"), " ") }
        if (vertex.total !=
            null
        ) {
            return vertex.components.joinToString(" ") {
                (
                    if (it.sign <
                        0
                    ) {
                        "− "
                    } else {
                        "+ "
                    }
                    ) + it.vertexId
            }.removePrefix("+ ")
        }
        vertex.choice?.let {
            return (if (it.rule == ChoiceRule.MIN) "min" else "max") + "(" +
                it.options.joinToString("; ") { option -> option.formula.source } +
                ")"
        }
        vertex.check?.let { return it.formula.source }
        vertex.reconcile?.let { return "${it.left.source} − ${it.right.source}" }
        return ""
    }

    companion object {
        const val HEADER_ROW = 3
        const val FIRST_ROW = 4
    }
}
