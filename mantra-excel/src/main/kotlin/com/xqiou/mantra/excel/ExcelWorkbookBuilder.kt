package com.xqiou.mantra.excel

import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.view.ViewCondition
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.displayLabel
import com.xqiou.mantra.core.view.groupKey
import com.xqiou.mantra.core.view.groupTitle
import com.xqiou.mantra.core.view.headlineId
import com.xqiou.mantra.core.view.signLabels
import com.xqiou.mantra.core.model.ChoiceRule
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.structure.PanelRole
import com.xqiou.mantra.core.structure.SchemaMap
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.TableStyle
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.styleRole
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowFlag
import com.xqiou.mantra.render.paper.RowKind
import com.xqiou.mantra.render.paper.WorkingPaper
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.ss.formula.FormulaParseException
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class ExcelOptions(
    /** Define workbook names for every node, so formulas read like the schema (`=MAX(wk.A, pausch)`). */
    val useNames: Boolean = true,
    /** Add a collapsed column with the original DSL formula of each line. */
    val formulaColumn: Boolean = true,
    /** Evaluate all formulas once with POI so that the file carries cached values. */
    val evaluate: Boolean = true,
    val maxSheets: Int = Int.MAX_VALUE,
    val maxCells: Int = Int.MAX_VALUE,
) {
    init { require(maxSheets > 0 && maxCells > 0) }
}

class ExcelExportLimitException(message: String) : RuntimeException(message)

data class ExcelFallback(val sheet: String, val cell: String, val nodeId: String, val reason: String)

data class ExcelCellDescription(val address: String, val kind: String, val value: String?, val formula: String?)
data class ExcelSheetDescription(val name: String, val rows: Int, val columns: Int)
data class ExcelNameDescription(val name: String, val refersTo: String)
data class ExcelPreview(val rows: Int, val columns: Int, val truncated: Boolean, val cells: List<ExcelCellDescription>)
data class ExcelDescription(
    val sheets: List<ExcelSheetDescription>,
    val selectedSheet: String,
    val preview: ExcelPreview,
    val names: List<ExcelNameDescription>,
    val report: ExcelReport,
)

class ExcelReport(
    val sheets: List<String>,
    val formulaCells: Int,
    val inputCells: Int,
    val names: Int,
    /** Cells that hold a computed value instead of a formula, with the reason. */
    val fallbacks: List<ExcelFallback>,
    val evaluationErrors: List<String>,
)

/** A generated workbook plus the map of where every node lives (used by tests and re-import). */
class ExcelWorkbook internal constructor(
    val workbook: XSSFWorkbook,
    val report: ExcelReport,
    private val nodeAddresses: Map<String, Map<Coord, String>>,
    private val recordAddresses: Map<Triple<String, String, String>, String>,
    private val tableAddresses: Map<Triple<String, Int, String>, String>,
) : AutoCloseable {
    override fun close() = workbook.close()

    fun bytes(maxBytes: Int = Int.MAX_VALUE): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        require(maxBytes > 0)
        try {
            workbook.write(BoundedWorkbookOutput(output, maxBytes))
        } catch (error: RuntimeException) {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is ExcelExportLimitException) throw cause
                cause = cause.cause
            }
            throw error
        }
        output.toByteArray()
    }
    /** Bounded, read-only description of the workbook actually written by this exporter. */
    fun describe(selectedSheet: String? = null): ExcelDescription? {
        val sheets = (0 until workbook.numberOfSheets).map { index ->
            val sheet = workbook.getSheetAt(index)
            ExcelSheetDescription(sheet.sheetName, if (sheet.physicalNumberOfRows == 0) 0 else sheet.lastRowNum + 1,
                sheet.maxOfOrNull { row -> row.lastCellNum.toInt().coerceAtLeast(0) } ?: 0)
        }
        val chosen = selectedSheet ?: sheets.firstOrNull()?.name ?: return null
        val selected = sheets.firstOrNull { it.name == chosen } ?: return null
        val sheet = workbook.getSheet(chosen)
        val cells = buildList {
            for (rowIndex in 0 until minOf(selected.rows, 50)) {
                val row = sheet.getRow(rowIndex) ?: continue
                for (columnIndex in 0 until minOf(selected.columns, 20)) {
                    val cell = row.getCell(columnIndex) ?: continue
                    if (cell.cellType == CellType.BLANK) continue
                    val valueType = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
                    val value = when (valueType) {
                        CellType.NUMERIC -> java.math.BigDecimal.valueOf(cell.numericCellValue).toPlainString()
                        CellType.STRING -> cell.stringCellValue
                        CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        else -> null
                    }
                    add(ExcelCellDescription(CellReference(rowIndex, columnIndex).formatAsString(), cell.cellType.name.lowercase(), value,
                        if (cell.cellType == CellType.FORMULA) cell.cellFormula else null))
                }
            }
        }
        return ExcelDescription(sheets, chosen,
            ExcelPreview(selected.rows, selected.columns, selected.rows > 50 || selected.columns > 20, cells),
            workbook.allNames.map { ExcelNameDescription(it.nameName, it.refersToFormula) }, report)
    }

    /** A1 address (`'Sheet'!$C$5`) of a node's cell for a member coordinate. */
    fun address(nodeId: String, coord: Coord = emptyList()): String? = nodeAddresses[nodeId]?.get(coord)

    /** A1 address of a table-input cell: row identified by the member key of a dimension drawn from the table. */
    fun recordAddress(dimension: String, key: String, column: String): String? = recordAddresses[Triple(dimension, key, column)]

    /** A1 address of a table-input cell, with a zero-based row index. */
    fun tableAddress(inputId: String, rowIndex: Int, column: String): String? = tableAddresses[Triple(inputId, rowIndex, column)]

    fun write(path: java.nio.file.Path) {
        path.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.newOutputStream(path).use { workbook.write(it) }
    }
}

private class BoundedWorkbookOutput(
    private val target: java.io.OutputStream,
    private val limit: Int,
) : java.io.OutputStream() {
    private var written = 0L
    override fun write(value: Int) {
        checkCapacity(1)
        target.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkCapacity(length)
        target.write(bytes, offset, length)
    }
    private fun checkCapacity(length: Int) {
        if (written + length > limit) throw ExcelExportLimitException("Workbook exceeds $limit bytes")
        written += length
    }
}

internal class ExcelWorkbookBuilder(
    private val result: CalculationView,
    private val layout: LayoutSpec,
    private val options: ExcelOptions,
) : ExcelResolver {
    private val view = result
    private val texts = layout.texts
    private val de = texts.language == "de"
    private val wb = XSSFWorkbook()
    private val styles = ExcelStyles(wb, layout.number)
    private val map: SchemaMap = view.structure
    private val translator = FormulaTranslator(this, view.functions)

    private data class Slot(val sheet: XSSFSheet, val row: Int, val col: Int) {
        val address: String get() = "'${sheet.sheetName.replace("'", "''")}'!\$${CellReference.convertNumToColString(col)}\$${row + 1}"
        val local: String get() = "${CellReference.convertNumToColString(col)}${row + 1}"
    }

    private class XMember(val key: String, val label: String, val index: Int)

    /** All declared members (static dimensions keep members that are inactive in this case). */
    private val members: Map<String, List<XMember>> = view.dimensions.mapValues { (id, decl) ->
        if (decl.fromTable == null) {
            decl.members.mapIndexed { i, m -> XMember(m.key, m.label, i) }
        } else {
            view.members[id].orEmpty().map { XMember(it.key, it.label, it.index) }
        }
    }

    private val nodeSlots = linkedMapOf<String, LinkedHashMap<Coord, Slot>>()
    private val optionSlots = linkedMapOf<Pair<String, String>, LinkedHashMap<Coord, Slot>>()
    private val guardSlots = linkedMapOf<String, LinkedHashMap<Coord, Slot>>()
    private val activeSlots = linkedMapOf<Pair<String, String>, Slot>()
    private val recordSlots = hashMapOf<Triple<String, String, String>, Slot>()
    private val tableSlots = hashMapOf<Triple<String, Int, String>, Slot>()
    private val presentation = mutableListOf<Pair<Slot, () -> X.Scalar?>>()
    private val statusFormulas = mutableListOf<Pair<Slot, () -> X.Scalar?>>()
    private val slotNames = hashMapOf<Slot, String>()
    private val rangeNames = hashMapOf<String, String>()
    private val usedNames = hashSetOf<String>()
    private val tableSheets = linkedMapOf<String, XSSFSheet>()
    private val sectionSheets = linkedMapOf<String, XSSFSheet>()
    private val valueStyles = hashMapOf<Slot, StyleKey>()
    private val paperCellStyles = hashMapOf<Slot, StyleSpec>()
    private val fallbacks = mutableListOf<ExcelFallback>()
    private var formulaCells = 0
    private var inputCells = 0
    private var createdCells = 0

    fun build(): ExcelWorkbook {
        try {
        val paper = Render.completePaper(result, layout)
        val overview = sheet(if (de) "Übersicht" else "Overview")
        paper.tables.forEach(::layoutTable)
        layoutInputs()
        layoutParams()
        layoutHelpers()
        defineNames()
        writeValuesAndFormulas()
        writeOverview(overview, paper)
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
        return ExcelWorkbook(wb, report, addresses, recordSlots.mapValues { (_, slot) -> slot.address }, tableSlots.mapValues { (_, slot) -> slot.address })
        } catch (error: Exception) {
            runCatching { wb.close() }
            throw error
        }
    }

    // ── Sheets ─────────────────────────────────────────────────────────────────────────────────

    private val sheetNames = hashSetOf<String>()

    private fun sheetName(raw: String): String {
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

    private fun sheet(name: String): XSSFSheet {
        if (wb.numberOfSheets >= options.maxSheets)
            throw ExcelExportLimitException("Workbook exceeds ${options.maxSheets} sheets")
        return wb.createSheet(sheetName(name))
    }

    private fun cell(sheet: XSSFSheet, row: Int, col: Int): XSSFCell {
        val existing = sheet.getRow(row)?.getCell(col)
        if (existing != null) return existing
        if (createdCells >= options.maxCells)
            throw ExcelExportLimitException("Workbook exceeds ${options.maxCells} cells")
        createdCells++
        return (sheet.getRow(row) ?: sheet.createRow(row)).createCell(col)
    }

    private fun text(sheet: XSSFSheet, row: Int, col: Int, value: String, style: StyleKey = StyleKey()) {
        if (value.isEmpty() && style == StyleKey()) return
        cell(sheet, row, col).apply {
            setCellValue(value)
            cellStyle = styles.get(style)
        }
    }

    private fun link(target: XSSFSheet, row: Int, col: Int, destination: String) {
        val hyperlink = wb.creationHelper.createHyperlink(HyperlinkType.DOCUMENT)
        hyperlink.address = "'${destination.replace("'", "''")}'!A1"
        cell(target, row, col).hyperlink = hyperlink
    }

    // ── Table sheets ───────────────────────────────────────────────────────────────────────────

    private class XColumn(val content: ColumnContent, val header: String, val width: Int, val grouped: Boolean = false)

    private fun layoutTable(table: PaperTable) {
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
                    members[content.dimension].orEmpty().forEach { columns += XColumn(ColumnContent.Member(content.dimension, it.key), it.label, 14) }
                }
                is ColumnContent.Members -> Unit
                else -> {
                    paperToX[index] = columns.size
                    columns += XColumn(content, column.header, widthFor(content))
                }
            }
        }
        val hasValueColumn = columns.any { it.content == ColumnContent.Pre || it.content == ColumnContent.Main || it.content == ColumnContent.Value || it.content == ColumnContent.CrossTotal }
        if (!hasValueColumn) columns += XColumn(if (table.style == TableStyle.MATRIX) ColumnContent.CrossTotal else ColumnContent.Value, texts.total, 15)
        (rowDims - emittedDims).forEach { dim ->
            members[dim].orEmpty().forEach { columns += XColumn(ColumnContent.Member(dim, it.key), it.label, 14, grouped = true) }
        }
        val formulaColumn = if (options.formulaColumn) columns.size.also { columns += XColumn(ColumnContent.Formula, if (de) "Formel (Mantra-DSL)" else "Formula (Mantra DSL)", 60, grouped = true) } else null

        fun col(content: ColumnContent) = columns.indexOfFirst { it.content == content }.takeIf { it >= 0 }
        val preCol = col(ColumnContent.Pre)
        val mainCol = col(ColumnContent.Main)
        val valueCol = col(ColumnContent.Value) ?: col(ColumnContent.CrossTotal)
        val labelCol = col(ColumnContent.Label) ?: 0
        val statusCol = col(ColumnContent.Status)
        fun memberCol(dim: String, key: String) = columns.indexOfFirst { (it.content as? ColumnContent.Member)?.let { m -> m.dimension == dim && m.key == key } == true }.takeIf { it >= 0 }

        // Title, breadcrumb and header.
        text(sheet, 0, 0, table.title, StyleKey(bold = true, size = 13))
        table.breadcrumb?.let {
            text(sheet, 1, 0, "⌂ $it", StyleKey(italic = true, muted = true, link = true))
            link(sheet, 1, 0, wb.getSheetName(0))
        }
        columns.forEachIndexed { index, column ->
            text(sheet, HEADER_ROW, index, column.header, StyleKey(bold = true, fill = Fill.HEADER, headerRule = true, align = if (isNumeric(column.content)) HorizontalAlignment.RIGHT else HorizontalAlignment.LEFT, wrap = true))
            sheet.setColumnWidth(index, column.width * 256)
        }

        val headingStack = ArrayDeque<Pair<Int, Int>>()
        table.rows.forEachIndexed { index, row ->
            val r = FIRST_ROW + index
            columns.forEachIndexed { x, column ->
                val paperIndex = table.columns.indexOfFirst { it.content == column.content }
                val rule = row.cellStyles.getOrNull(paperIndex) ?: row.cellContexts.firstOrNull()?.let { context ->
                    layout.styleFor(context.copy(columnId = column.content.styleRole(), columnRole = column.content.styleRole()))
                } ?: row.style
                paperCellStyles[Slot(sheet, r, x)] = rule
            }
            while (headingStack.isNotEmpty() && headingStack.last().second >= row.depth) {
                val (start, _) = headingStack.removeLast()
                if (r - 1 > start) sheet.groupRow(start + 1, r - 1)
            }
            val bold = row.kind == RowKind.HEADING || row.kind == RowKind.TOTAL || row.kind == RowKind.RESULT || RowFlag.GRAND in row.flags
            val muted = RowFlag.INFO in row.flags || row.kind == RowKind.OPTION || row.kind == RowKind.NOTE
            val italic = row.kind == RowKind.OPTION || row.kind == RowKind.NOTE || RowFlag.USER_DEFINED in row.flags
            val rule = row.kind == RowKind.SUBTOTAL || row.kind == RowKind.RESULT || row.kind == RowKind.TOTAL
            // Text columns from the paper.
            table.columns.forEachIndexed { paperIndex, column ->
                val x = paperToX[paperIndex] ?: return@forEachIndexed
                if (isNumeric(column.content) || column.content == ColumnContent.Formula || column.content == ColumnContent.Explain) return@forEachIndexed
                val value = row.cells[paperIndex]
                val style = if (x == labelCol) {
                    StyleKey(bold = bold, italic = italic, muted = muted, indent = row.depth.coerceAtMost(15).toShort(), link = value.endsWith(")") && "(→ " in value)
                } else {
                    StyleKey(bold = bold && column.content == ColumnContent.Operator, muted = column.content != ColumnContent.Operator, align = if (column.content == ColumnContent.Status || column.content == ColumnContent.Operator) HorizontalAlignment.CENTER else HorizontalAlignment.GENERAL)
                }
                if (column.content == ColumnContent.Status && row.kind == RowKind.OPTION) return@forEachIndexed
                text(sheet, r, x, value, style.applyRule(row.cellStyles.getOrNull(paperIndex) ?: row.style))
            }
            // Cross-reference links ("→ Tabelle n").
            Regex("\\(→ [^)]*? (\\S+)\\)$").find(row.cells.getOrNull(table.columns.indexOfFirst { it.content == ColumnContent.Label }) ?: "")
                ?.groupValues?.get(1)?.let { ref -> pendingLinks += Triple(sheet, r to labelCol, ref) }

            val numberKey = { presentation: Presentation?, deduction: Boolean ->
                StyleKey(format = numberFormat(presentation, deduction), bold = bold, muted = muted, italic = italic, topRule = rule, doubleBottom = RowFlag.GRAND in row.flags)
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
                        valueSlotFor(row.lead)?.let { c -> Slot(sheet, r, c).also { slots[emptyList()] = it; valueStyles[it] = style } }
                    } else {
                        vertex.dims.singleOrNull()?.let { dim ->
                            members[dim].orEmpty().forEach { m -> memberCol(dim, m.key)?.let { c -> Slot(sheet, r, c).also { slots[listOf(m.key)] = it; valueStyles[it] = style } } }
                        }
                    }
                    statusCol?.let { c ->
                        statusFormulas += Slot(sheet, r, c) to {
                            val choiceRef = reference(vertex.id, vertex.dims, slots.keys.firstOrNull() ?: emptyList()) as? X.Scalar
                            val optionRef = slots.values.firstOrNull()?.let(::ref)
                            if (choiceRef != null && optionRef != null && vertex.dims.isEmpty()) Ex.iff(Ex.cmp("=", choiceRef, optionRef), Ex.text("✓"), Ex.EMPTY) else null
                        }
                    }
                }
                vertex != null && row.kind != RowKind.OPTION -> {
                    val style = numberKey(presentationOf(vertex), deduction)
                    val slots = nodeSlots[vertex.id]
                    if (slots != null) {
                        // Presented twice: the second place links to the first.
                        valueSlotFor(row.lead)?.let { c -> Slot(sheet, r, c).also { valueStyles[it] = style; presentation += it to { aggregateRef(vertex.id) } } }
                    } else {
                        val target = linkedMapOf<Coord, Slot>()
                        when (vertex.dims.size) {
                            0 -> valueSlotFor(row.lead)?.let { c -> Slot(sheet, r, c).also { target[emptyList()] = it; valueStyles[it] = style } }
                            1 -> {
                                val dim = vertex.dims.single()
                                members[dim].orEmpty().forEach { m -> memberCol(dim, m.key)?.let { c -> Slot(sheet, r, c).also { target[listOf(m.key)] = it; valueStyles[it] = style } } }
                                val aggregate = vertex.type.isNumeric && view.nodes[vertex.id]?.crossTotal() != null
                                valueSlotFor(row.lead)?.takeIf { aggregate }?.let { c -> Slot(sheet, r, c).also { valueStyles[it] = style; presentation += it to { aggregateRef(vertex.id) } } }
                            }
                            else -> fallbackPlacement += Triple(vertex, Slot(sheet, r, valueSlotFor(row.lead) ?: labelCol), "more than one dimension")
                        }
                        if (target.isNotEmpty()) nodeSlots[vertex.id] = target
                    }
                    if (formulaColumn != null) text(sheet, r, formulaColumn, dslFormula(vertex), StyleKey(muted = true, italic = true))
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

    private val pendingLinks = mutableListOf<Triple<XSSFSheet, Pair<Int, Int>, String>>()
    private val fallbackPlacement = mutableListOf<Triple<ViewNode, Slot, String>>()

    private fun isNumeric(content: ColumnContent) = content.numeric

    private fun widthFor(content: ColumnContent): Int = when (content) {
        ColumnContent.Label -> 62
        ColumnContent.Operator -> 5
        ColumnContent.RowNumber -> 6
        ColumnContent.Status -> 4
        ColumnContent.Reference, ColumnContent.Note, ColumnContent.Source -> 30
        is ColumnContent.Attribute -> 12
        ColumnContent.Formula, ColumnContent.Explain -> 60
        else -> 16
    }

    // ── Inputs, parameters and helpers ─────────────────────────────────────────────────────────

    private fun layoutInputs() {
        val sheet = sheet(if (de) "Eingaben" else "Inputs")
        text(sheet, 0, 0, if (de) "Eingaben (gelb: änderbar)" else "Inputs (yellow: editable)", StyleKey(bold = true, size = 13))
        sheet.setColumnWidth(0, 58 * 256)
        sheet.setColumnWidth(1, 30 * 256)
        (2..14).forEach { sheet.setColumnWidth(it, 16 * 256) }
        var r = 2
        val unplaced = view.nodes.values.filter { it.kind == NodeKind.INPUT }.filter { it.id !in nodeSlots }
        val groups = unplaced.groupBy { input -> input.groupKey?.let(view::groupTitle)
            ?: map.panelOf(input.id)?.title ?: if (de) "Allgemeine Angaben" else "General" }
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
                        nodeSlots[input.id] = linkedMapOf(emptyList<String>() to Slot(sheet, r, 2).also { valueStyles[it] = StyleKey(format = numberFormat(input.input!!.presentation, false), fill = Fill.INPUT) })
                        r++
                    }
                    input.dims.size == 1 -> {
                        val dim = input.dims.single()
                        if (headerDim != dim) {
                            members[dim].orEmpty().forEachIndexed { i, m -> text(sheet, r, 2 + i, m.label, StyleKey(bold = true, align = HorizontalAlignment.RIGHT)) }
                            r++
                            headerDim = dim
                        }
                        text(sheet, r, 0, input.label)
                        text(sheet, r, 1, input.id, StyleKey(muted = true))
                        val slots = linkedMapOf<Coord, Slot>()
                        members[dim].orEmpty().forEachIndexed { i, m ->
                            slots[listOf(m.key)] = Slot(sheet, r, 2 + i).also { valueStyles[it] = StyleKey(format = numberFormat(input.input!!.presentation, false), fill = Fill.INPUT) }
                        }
                        nodeSlots[input.id] = slots
                        r++
                    }
                    else -> fallbackPlacement += Triple(input, Slot(sheet, r++, 2), "input with more than one dimension")
                }
            }
            r++
        }
    }

    private fun layoutTableInput(sheet: XSSFSheet, start: Int, input: ViewNode): Int {
        var r = start
        text(sheet, r++, 0, input.label, StyleKey(bold = true))
        val columns = input.input!!.columns
        columns.forEachIndexed { i, column -> text(sheet, r, 1 + i, column.name, StyleKey(bold = true, fill = Fill.HEADER, headerRule = true)) }
        r++
        val rows = (view.case.inputs[input.id] as? Value.Vec)?.items.orEmpty()
        val dims = view.dimensions.values.filter { it.fromTable == input.id }
        rows.forEachIndexed { index, row ->
            val fields = (row as? Value.MapV)?.entries?.entries?.associate { (k, v) -> ((k as? Value.Kw)?.name ?: k.toString()) to v }.orEmpty()
            text(sheet, r, 0, "#${index + 1}", StyleKey(muted = true))
            columns.forEachIndexed { i, column ->
                val slot = Slot(sheet, r, 1 + i)
                tableSlots[Triple(input.id, index, column.name)] = slot
                val value = fields[column.name] ?: Value.Nil
                writeValue(slot, value)
                cell(sheet, r, 1 + i).cellStyle = styles.get(StyleKey(format = if (column.type.isNumeric) styles.amountFormat(layout.number.precision) else null, fill = Fill.INPUT))
                inputCells++
                dims.forEach { dim ->
                    val key = when (val k = fields[dim.keyColumn]) {
                        is Value.Kw -> k.name
                        is Value.Text -> k.value
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

    private fun layoutParams() {
        val sheet = sheet(if (de) "Parameter" else "Parameters")
        text(sheet, 0, 0, if (de) "Parameter (blau: änderbar)" else "Parameters (blue: editable)", StyleKey(bold = true, size = 13))
        listOf(texts.label, "Name", if (de) "Wert" else "Value", texts.reference).forEachIndexed { i, h -> text(sheet, 2, i, h, StyleKey(bold = true, fill = Fill.HEADER, headerRule = true)) }
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
                is Value.Num, is Value.Bool, is Value.Kw, is Value.Text -> {
                    val slot = Slot(sheet, r, 2)
                    nodeSlots[param.id] = linkedMapOf(emptyList<String>() to slot)
                    valueStyles[slot] = StyleKey(format = if (param.parameterValue is Value.Num) "General" else null, fill = Fill.PARAM)
                }
                else -> text(sheet, r, 2, param.parameterValue.toString(), StyleKey(muted = true))
            }
            r++
        }
    }

    private fun layoutHelpers() {
        val sheet = sheet(if (de) "Hilfsrechnungen" else "Helper calculations")
        text(sheet, 0, 0, if (de) "Hilfsrechnungen (Bedingungen und nicht dargestellte Zeilen)" else "Helper calculations (conditions and lines not presented)", StyleKey(bold = true, size = 13))
        sheet.setColumnWidth(0, 58 * 256)
        sheet.setColumnWidth(1, 30 * 256)
        (2..14).forEach { sheet.setColumnWidth(it, 16 * 256) }
        if (options.formulaColumn) sheet.setColumnWidth(15, 60 * 256)
        var r = 2
        var headerDim: String? = null

        fun memberHeader(dim: String) {
            if (headerDim == dim) return
            members[dim].orEmpty().forEachIndexed { i, m -> text(sheet, r, 2 + i, m.label, StyleKey(bold = true, align = HorizontalAlignment.RIGHT)) }
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
            val section = findSectionLabel(guard.sectionId) ?: guard.sectionId
            val slots = linkedMapOf<Coord, Slot>()
            if (guard.dims.isEmpty()) {
                slots[emptyList()] = Slot(sheet, r, 2)
            } else if (guard.dims.size == 1) {
                memberHeader(guard.dims.single())
                members[guard.dims.single()].orEmpty().forEachIndexed { i, m -> slots[listOf(m.key)] = Slot(sheet, r, 2 + i) }
            }
            text(sheet, r, 0, (if (de) "Bedingung: " else "Condition: ") + section)
            text(sheet, r, 1, guard.id, StyleKey(muted = true))
            guardSlots[guard.id] = slots
            r++
        }
        // Lines, totals and choices that no table presents.
        view.nodes.values.filter { it.kind != NodeKind.INPUT && it.kind != NodeKind.PARAM && it.id !in nodeSlots }.forEach { vertex ->
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
                members[vertex.dims.single()].orEmpty().forEachIndexed { i, m -> slots[listOf(m.key)] = Slot(sheet, r, 2 + i).also { valueStyles[it] = style } }
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
                    members[choice.dims.single()].orEmpty().forEachIndexed { i, m -> slots[listOf(m.key)] = Slot(sheet, r, 2 + i) }
                }
                optionSlots[choice.id to option.key] = slots
                r++
            }
        }
    }

    private fun findSectionLabel(id: String): String? {
        fun visit(section: com.xqiou.mantra.core.view.ViewSection): String? {
            if (section.id == id) return section.label
            return section.children.filterIsInstance<com.xqiou.mantra.core.view.ViewSection>().firstNotNullOfOrNull(::visit)
        }
        return visit(view.tree)
    }

    // ── Names ──────────────────────────────────────────────────────────────────────────────────

    private fun sanitize(raw: String): String = ExcelNames.sanitize(raw)

    private fun define(name: String, refersTo: String): String? {
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

    private fun defineNames() {
        nodeSlots.forEach { (id, slots) ->
            if (slots.keys.singleOrNull()?.isEmpty() == true) {
                val slot = slots.values.single()
                define(id, slot.address)?.let { slotNames[slot] = it }
            } else {
                slots.forEach { (coord, slot) -> define("${id}__${coord.joinToString("__")}", slot.address)?.let { slotNames[slot] = it } }
                val cells = slots.values.toList()
                if (cells.map { it.sheet to it.row }.distinct().size == 1 && cells.zipWithNext().all { (a, b) -> b.col == a.col + 1 }) {
                    val first = cells.first()
                    val last = cells.last()
                    define(id, first.address + ":" + "\$${CellReference.convertNumToColString(last.col)}\$${last.row + 1}")?.let { rangeNames[id] = it }
                }
            }
        }
        optionSlots.forEach { (key, slots) ->
            slots.forEach { (coord, slot) -> define((listOf(key.first, "opt", key.second) + coord).joinToString("__"), slot.address)?.let { slotNames[slot] = it } }
        }
        guardSlots.forEach { (id, slots) ->
            slots.forEach { (coord, slot) -> define((listOf("guard", id.removeSuffix("?when")) + coord).joinToString("__"), slot.address)?.let { slotNames[slot] = it } }
        }
        activeSlots.forEach { (key, slot) -> define("active__${key.first}__${key.second}", slot.address)?.let { slotNames[slot] = it } }
        recordSlots.forEach { (key, slot) ->
            val table = view.dimensions[key.first]?.fromTable ?: key.first
            define("${table}__${key.second}__${key.third}", slot.address)?.let { slotNames[slot] = it }
        }
    }

    private fun ref(slot: Slot, kind: XKind = XKind.NUM): X.Scalar = Ex.atom(slotNames[slot] ?: slot.address, kind)

    // ── ExcelResolver ──────────────────────────────────────────────────────────────────────────

    private fun kindOf(vertex: ViewNode): XKind = when {
        vertex.kind == NodeKind.PARAM -> when (vertex.parameterValue) {
            is Value.Bool -> XKind.BOOL
            is Value.Kw, is Value.Text -> XKind.TEXT
            else -> XKind.NUM
        }
        vertex.type == ValueType.BOOLEAN -> XKind.BOOL
        vertex.type == ValueType.KEYWORD || vertex.type == ValueType.TEXT -> XKind.TEXT
        vertex.type.isNumeric -> XKind.NUM
        else -> XKind.ANY
    }

    private fun literal(value: Value): X = when (value) {
        is Value.Num -> Ex.num(value.value)
        is Value.Bool -> if (value.value) Ex.TRUE else Ex.FALSE
        is Value.Kw -> Ex.text(value.name)
        is Value.Text -> Ex.text(value.value)
        Value.Nil -> X.Nil
        is Value.Vec -> X.Vec(value.items.map(::literal))
        is Value.MapV -> X.MapX(value.entries.keys.map { (it as? Value.Kw)?.name ?: it.toString() }, value.entries.values.map(::literal))
        is Value.Date -> Ex.text(value.value.toString())
    }

    override fun reference(nodeId: String, contextDims: List<String>, contextCoord: Coord): X? {
        val relation = view.dimensions.values.firstOrNull { it.parentDimension != null && "relation_${it.id}" == nodeId }
        if (relation != null) {
            val keys = members[relation.id].orEmpty().map { it.key }
            return X.MapX(keys, keys.map { key ->
                record(relation.id, key, relation.parentKeyColumn!!)
                    ?: throw Untranslatable("$nodeId has no parent cell for $key")
            })
        }
        val vertex = view.nodes[nodeId] ?: return null
        if (vertex.kind == NodeKind.INPUT && vertex.type == ValueType.TABLE) {
            val count = (view.case.inputs[nodeId] as? Value.Vec)?.items?.size ?: 0
            return X.Vec((0 until count).map { row ->
                X.MapX(vertex.input!!.columns.map { it.name }, vertex.input!!.columns.map { column ->
                    val slot = tableSlots[Triple(nodeId, row, column.name)]
                        ?: throw Untranslatable("$nodeId row $row has no ${column.name} cell")
                    ref(slot, if (column.type.isNumeric) XKind.NUM else XKind.ANY)
                })
            })
        }
        val parameterValue = vertex.parameterValue
        if (vertex.kind == NodeKind.PARAM && (parameterValue is Value.Vec || parameterValue is Value.MapV)) return literal(parameterValue)
        val extra = vertex.dims.filter { it !in contextDims }
        // A memberless dimension has no worksheet range. Preserve its empty-map shape for
        // count/get/vals, while aggregation consumes it as the additive identity.
        if (extra.isNotEmpty() && vertex.dims.any { members[it].isNullOrEmpty() }) {
            return X.MapX(emptyList(), emptyList())
        }
        val slots = nodeSlots[nodeId] ?: throw Untranslatable("$nodeId has no cell")
        if (extra.isEmpty()) {
            val coord = vertex.dims.map { contextCoord[contextDims.indexOf(it)] }
            return slots[coord]?.let { ref(it, kindOf(vertex)) } ?: throw Untranslatable("$nodeId has no cell for $coord")
        }
        if (vertex.dims.size == 1) {
            val dim = vertex.dims.single()
            val keys = members[dim].orEmpty().map { it.key }
            val cells = keys.map { key -> slots[listOf(key)]?.let { ref(it, kindOf(vertex)) } ?: throw Untranslatable("$nodeId has no cell for $key") }
            val first = slots[listOf(keys.first())]!!
            val last = slots[listOf(keys.last())]!!
            val text = rangeNames[nodeId] ?: (first.address + ":" + "\$${CellReference.convertNumToColString(last.col)}\$${last.row + 1}")
            return X.Range(text, keys, cells)
        }
        throw Untranslatable("$nodeId has more than one dimension")
    }

    override fun record(dim: String, key: String, field: String): X? {
        val decl = view.dimensions[dim] ?: return null
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
        return ref(slot, if (column?.type?.isNumeric == true) XKind.NUM else XKind.ANY)
    }

    override fun isNode(nodeId: String): Boolean = nodeId in view.nodes ||
        view.dimensions.values.any { it.parentDimension != null && "relation_${it.id}" == nodeId }

    override fun isDimension(name: String): Boolean = name in view.dimensions

    // ── Values and formulas ───────────────────────────────────────────────────────────────────

    private fun presentationOf(vertex: ViewNode): Presentation = vertex.presentation

    private fun numberFormat(presentation: Presentation?, deduction: Boolean): String = when (presentation?.format) {
        "percent" -> styles.percentFormat(presentation.precision ?: layout.number.percentPrecision)
        "integer", "years" -> styles.amountFormat(0, deduction)
        "number", "factor" -> presentation.precision?.let { styles.amountFormat(it, deduction) } ?: "General"
        else -> styles.amountFormat(presentation?.precision ?: layout.number.precision, deduction)
    }

    private fun neutral(vertex: ViewNode): X.Scalar = when {
        vertex.type.isNumeric -> Ex.ZERO
        else -> Ex.EMPTY
    }

    private fun aggregateRef(nodeId: String): X.Scalar? {
        val vertex = view.nodes[nodeId] ?: return null
        if (view.nodes[nodeId]?.crossTotal() == null) return null
        return when (val x = reference(nodeId, emptyList(), emptyList())) {
            is X.Scalar -> x
            is X.Range -> if (vertex.type.isNumeric) Ex.fn("SUM", Ex.atom(x.text)) else null
            is X.MapX -> if (vertex.type.isNumeric) sumMap(x) else null
            else -> null
        }
    }

    private fun signLabelFormula(node: ViewNode, suffix: String = ""): X.Scalar? {
        val labels = node.signLabels ?: return null
        val value = aggregateRef(node.id) ?: return null
        return Ex.iff(Ex.cmp(">", value, Ex.ZERO), Ex.text((labels.positive ?: node.label) + suffix),
            Ex.iff(Ex.cmp("<", value, Ex.ZERO), Ex.text((labels.negative ?: node.label) + suffix),
                Ex.text((labels.zero ?: node.label) + suffix)))
    }

    private fun sectionConditions(vertex: ViewNode, coord: Coord): X.Scalar? {
        if (vertex.guards.isEmpty()) return null
        val guards = vertex.guards.map { view.conditions.getValue(it) }
        val aligned = view.alignGuards(vertex, coord) { dim -> members[dim].orEmpty().map { it.key } }
        val alternatives = aligned.assignments.map { assignment ->
            val tests = guards.map { guard ->
                val guardCoord = guard.dims.map(assignment::getValue)
                val slot = guardSlots[guard.id]?.get(guardCoord)
                    ?: throw Untranslatable("section condition ${guard.sectionId} has no cell for $guardCoord")
                ref(slot, XKind.BOOL)
            } + aligned.extraDimensions.mapNotNull { dim -> activeSlots[dim to assignment.getValue(dim)]?.let { ref(it, XKind.BOOL) } }
            if (tests.size == 1) tests.single() else Ex.fn("AND", tests, kind = XKind.BOOL)
        }
        return when (alternatives.size) {
            0 -> Ex.FALSE
            1 -> alternatives.single()
            else -> Ex.fn("OR", alternatives, kind = XKind.BOOL)
        }
    }

    private fun conditions(vertex: ViewNode, coord: Coord): List<X.Scalar> = buildList {
        sectionConditions(vertex, coord)?.let(::add)
        vertex.dims.forEachIndexed { i, dim -> activeSlots[dim to coord[i]]?.let { add(ref(it, XKind.BOOL)) } }
        vertex.ownCondition?.let { condition ->
            add(translator.truthy(translator.scalar(condition.form, FormulaTranslator.Ctx(vertex.dims, coord))))
        }
    }

    private fun guarded(vertex: ViewNode, coord: Coord, core: X.Scalar): X.Scalar {
        val conditions = conditions(vertex, coord)
        if (conditions.isEmpty()) return core
        val test = conditions.singleOrNull() ?: Ex.fn("AND", conditions, kind = XKind.BOOL)
        return Ex.iff(test, core, neutral(vertex))
    }

    private fun lineFormula(vertex: ViewNode, coord: Coord): X.Scalar {
        val item = vertex.line!!
        val core = if (item.spread) {
            val context = FormulaTranslator.Ctx(vertex.dims.dropLast(1), coord.dropLast(1))
            val key = coord.last()
            when (val x = translator.translate(item.formula.form, context)) {
                is X.MapX -> x.values.getOrNull(x.keys.indexOf(key))?.let(translator::toScalar) ?: Ex.ZERO
                is X.Range -> x.cells.getOrNull(x.keys.indexOf(key)) ?: Ex.ZERO
                else -> throw Untranslatable(":spread formula does not produce a member map")
            }
        } else {
            translator.scalar(item.formula.form, FormulaTranslator.Ctx(vertex.dims, coord))
        }
        val rounded = item.rounding?.let { Ex.round(core, Ex.num(it.scale.toLong()), it.mode) } ?: core
        return guarded(vertex, coord, rounded)
    }

    private fun sumMap(values: X.MapX): X.Scalar =
        Ex.fn("SUM", values.values.map(translator::toScalar).ifEmpty { listOf(Ex.ZERO) })

    private fun totalFormula(vertex: ViewNode, coord: Coord): X.Scalar {
        val terms = vertex.components.map { component ->
            val value = when (val x = reference(component.vertexId, vertex.dims, coord)) {
                is X.Scalar -> x
                is X.Range -> Ex.fn("SUM", Ex.atom(x.text))
                is X.MapX -> sumMap(x)
                else -> throw Untranslatable("component ${component.vertexId}")
            }
            component.sign to value
        }
        val core = if (terms.isEmpty()) {
            Ex.ZERO
        } else {
            terms.drop(1).fold(if (terms.first().first < 0) Ex.neg(terms.first().second) else terms.first().second) { acc, (sign, value) ->
                if (sign < 0) Ex.sub(acc, value) else Ex.add(acc, value)
            }
        }
        return guarded(vertex, coord, core)
    }

    private fun optionFormula(vertex: ViewNode, optionKey: String, coord: Coord): X.Scalar {
        val option = vertex.choice!!.options.first { it.key == optionKey }
        val ctx = FormulaTranslator.Ctx(vertex.dims, coord)
        val value = translator.scalar(option.formula.form, ctx)
        val available = option.condition?.let { translator.truthy(translator.scalar(it.form, ctx)) }
        return if (available == null) value else Ex.iff(available, value, Ex.EMPTY)
    }

    private fun choiceFormula(vertex: ViewNode, coord: Coord): X.Scalar {
        val options = vertex.choice!!.options.map { option ->
            optionSlots[vertex.id to option.key]?.get(coord)?.let(::ref) ?: throw Untranslatable("option ${option.key} has no cell")
        }
        val core = Ex.fn(if (vertex.choice!!.rule == ChoiceRule.MIN) "MIN" else "MAX", options)
        val rounded = vertex.choice!!.rounding?.let { Ex.round(core, Ex.num(it.scale.toLong()), it.mode) } ?: core
        return guarded(vertex, coord, rounded)
    }

    /** Carries the schema's variable metadata into Excel data validation (dropdowns, ranges). */
    private fun validate(slot: Slot, input: ViewNode) {
        val helper = slot.sheet.dataValidationHelper
        val attributes = input.input!!.presentation.attributes
        val constraint = when {
            input.input!!.options.isNotEmpty() -> helper.createExplicitListConstraint(input.input!!.options.keys.toTypedArray())
            input.type == ValueType.BOOLEAN -> helper.createExplicitListConstraint(arrayOf("TRUE", "FALSE"))
            input.type.isNumeric && (attributes["min"] is Value.Num || attributes["max"] is Value.Num) -> {
                val min = (attributes["min"] as? Value.Num)?.value?.toPlainString()
                val max = (attributes["max"] as? Value.Num)?.value?.toPlainString()
                val type = if (input.type == ValueType.INTEGER) org.apache.poi.ss.usermodel.DataValidationConstraint.ValidationType.INTEGER else org.apache.poi.ss.usermodel.DataValidationConstraint.ValidationType.DECIMAL
                when {
                    min != null && max != null -> helper.createNumericConstraint(type, org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.BETWEEN, min, max)
                    min != null -> helper.createNumericConstraint(type, org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.GREATER_OR_EQUAL, min, null)
                    else -> helper.createNumericConstraint(type, org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType.LESS_OR_EQUAL, max, null)
                }
            }
            else -> return
        }
        val validation = helper.createValidation(constraint, org.apache.poi.ss.util.CellRangeAddressList(slot.row, slot.row, slot.col, slot.col))
        validation.showErrorBox = true
        (attributes["help"] as? Value.Text)?.value?.let { help ->
            validation.createPromptBox(input.label.take(32), help.take(255))
            validation.showPromptBox = true
        }
        slot.sheet.addValidationData(validation)
    }

    private fun writeValue(slot: Slot, value: Value) {
        val c = cell(slot.sheet, slot.row, slot.col)
        when (value) {
            is Value.Num -> c.setCellValue(value.value.toDouble())
            is Value.Bool -> c.setCellValue(value.value)
            is Value.Kw -> c.setCellValue(value.name)
            is Value.Text -> c.setCellValue(value.value)
            is Value.Date -> c.setCellValue(value.value.toString())
            Value.Nil -> c.setBlank()
            else -> c.setCellValue(value.toString())
        }
    }

    private fun setFormula(slot: Slot, nodeId: String, build: () -> X.Scalar?) {
        val c = cell(slot.sheet, slot.row, slot.col)
        valueStyles[slot]?.let { c.cellStyle = styles.get(it.applyRule(paperCellStyles[slot] ?: StyleSpec())) }
        try {
            val formula = build() ?: return
            c.cellFormula = formula.text
            formulaCells++
        } catch (e: Untranslatable) {
            fallback(slot, nodeId, e.reason)
        } catch (e: FormulaParseException) {
            fallback(slot, nodeId, "formula rejected by POI: ${e.message?.take(160)}")
        }
    }

    private fun fallback(slot: Slot, nodeId: String, reason: String) {
        val c = cell(slot.sheet, slot.row, slot.col)
        val coord = nodeSlots[nodeId]?.entries?.firstOrNull { it.value == slot }?.key ?: emptyList()
        writeValue(slot, view.nodes[nodeId]?.values?.get(coord) ?: Value.Nil)
        c.cellStyle = styles.get((valueStyles[slot] ?: StyleKey()).applyRule(paperCellStyles[slot] ?: StyleSpec()).copy(fill = Fill.FALLBACK))
        fallbacks += ExcelFallback(slot.sheet.sheetName, slot.local, nodeId, reason)
    }

    private fun inputValue(input: ViewNode, coord: Coord): Value {
        var supplied: Value? = view.case.inputs[input.id]
        coord.forEach { key -> supplied = (supplied as? Value.MapV)?.entries?.let { it[Value.Kw(key)] ?: it[Value.Text(key)] } }
        return supplied?.takeIf { it != Value.Nil } ?: input.input!!.default ?: view.nodes[input.id]?.values?.get(coord) ?: when {
            input.type.isNumeric -> Value.ZERO
            input.type == ValueType.BOOLEAN -> Value.Bool(false)
            else -> Value.Nil
        }
    }

    private fun writeValuesAndFormulas() {
        nodeSlots.forEach { (id, slots) ->
            val vertex = view.nodes.getValue(id)
            slots.forEach { (coord, slot) ->
                when {
                    vertex.kind == NodeKind.INPUT -> {
                        writeValue(slot, inputValue(vertex, coord))
                        cell(slot.sheet, slot.row, slot.col).cellStyle = styles.get((valueStyles[slot] ?: StyleKey()).applyRule(paperCellStyles[slot] ?: StyleSpec()).copy(fill = Fill.INPUT))
                        validate(slot, vertex)
                        inputCells++
                    }
                    vertex.kind == NodeKind.PARAM -> {
                        writeValue(slot, vertex.parameterValue ?: Value.Nil)
                        cell(slot.sheet, slot.row, slot.col).cellStyle = styles.get((valueStyles[slot] ?: StyleKey()).applyRule(paperCellStyles[slot] ?: StyleSpec()).copy(fill = Fill.PARAM))
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
                setFormula(slot, id) { translator.truthy(translator.scalar(guard.formula.form, FormulaTranslator.Ctx(guard.dims, coord))) }
            }
        }
        activeSlots.forEach { (key, slot) ->
            val member = view.dimensions.getValue(key.first).members.first { it.key == key.second }
            setFormula(slot, "${key.first}.${key.second}") { translator.truthy(translator.scalar(member.condition!!.form, FormulaTranslator.Ctx(emptyList(), emptyList()))) }
        }
        presentation.forEach { (slot, build) -> setFormula(slot, "presentation") { build() } }
        statusFormulas.forEach { (slot, build) ->
            setFormula(slot, "status") { build() }
            cell(slot.sheet, slot.row, slot.col).cellStyle = styles.get(
                StyleKey(align = HorizontalAlignment.CENTER, muted = true).applyRule(paperCellStyles[slot] ?: StyleSpec()),
            )
        }
        fallbackPlacement.forEach { (vertex, slot, reason) -> fallback(slot, vertex.id, reason) }
        pendingLinks.forEach { (sheet, position, ref) -> tableSheets[ref]?.let { link(sheet, position.first, position.second, it.sheetName) } }
    }

    // ── Overview ───────────────────────────────────────────────────────────────────────────────

    private fun writeOverview(sheet: XSSFSheet, paper: WorkingPaper) {
        sheet.setColumnWidth(0, 10 * 256)
        sheet.setColumnWidth(1, 60 * 256)
        sheet.setColumnWidth(2, 22 * 256)
        sheet.setColumnWidth(3, 18 * 256)
        sheet.setColumnWidth(4, 60 * 256)
        text(sheet, 0, 0, paper.title, StyleKey(bold = true, size = 15))
        paper.subtitle?.let { text(sheet, 1, 0, it, StyleKey(muted = true)) }
        var r = 3
        paper.header.forEach { (k, v) ->
            text(sheet, r, 0, k, StyleKey(muted = true))
            text(sheet, r++, 1, v)
        }
        view.headlineId?.let { id ->
            val node = view.nodes[id] ?: return@let
            text(sheet, r, 0, node.displayLabel(), StyleKey(bold = true))
            if (node.signLabels != null) setFormula(Slot(sheet, r, 0), id) { signLabelFormula(node) }
            val slot = Slot(sheet, r, 1)
            valueStyles[slot] = StyleKey(format = styles.amountFormat(layout.number.precision), bold = true, fill = Fill.STEP)
            setFormula(slot, id) { aggregateRef(id) }
            r++
        }
        r++
        text(sheet, r++, 0, texts.structure, StyleKey(bold = true, size = 12))
        listOf(if (de) "Schritt" else "Step", if (de) "Bereich" else "Panel", if (de) "Rolle" else "Role", texts.result, texts.feeds)
            .forEachIndexed { i, h -> text(sheet, r, i, h, StyleKey(bold = true, fill = Fill.HEADER, headerRule = true, align = if (i == 3) HorizontalAlignment.RIGHT else HorizontalAlignment.LEFT)) }
        r++
        fun panelRow(panelId: String, step: String, role: String, entry: String?, bold: Boolean, indent: Short) {
            val panel = map.panel(panelId)
            text(sheet, r, 0, step, StyleKey(bold = bold))
            text(sheet, r, 1, panel.title, StyleKey(bold = bold, link = true, indent = indent))
            findSheetFor(panelId)?.let { link(sheet, r, 1, it.sheetName) }
            text(sheet, r, 2, role, StyleKey(muted = true))
            panel.resultId?.let { resultId ->
                val slot = Slot(sheet, r, 3)
                valueStyles[slot] = StyleKey(format = styles.amountFormat(layout.number.precision), bold = bold, fill = if (bold) Fill.STEP else Fill.NONE)
                setFormula(slot, resultId) { aggregateRef(resultId) }
            }
            entry?.let { text(sheet, r, 4, it, StyleKey(muted = true)) }
            r++
        }
        map.mainline.forEach { id ->
            val step = map.panel(id).step ?: 0
            panelRow(id, "$step", texts.mainline, null, bold = true, indent = 0)
            map.panels.filter { it.role == PanelRole.BRANCH && it.position?.stepPanel == id }.forEach { branch ->
                panelRow(branch.id, "", texts.branch, branch.entries.joinToString(", ") { "→ ${it.step} · ${it.viaLabel}" }, bold = false, indent = 1)
            }
        }
        map.panels.filter { it.role == PanelRole.AUXILIARY }.forEach { panelRow(it.id, "", texts.auxiliary, null, bold = false, indent = 0) }
        r++
        text(sheet, r++, 0, texts.legend, StyleKey(bold = true))
        listOf(
            Fill.INPUT to (if (de) "Eingabe – änderbar, alle Formeln rechnen neu" else "Input – editable, all formulas recalculate"),
            Fill.PARAM to (if (de) "Parameter des Berechnungsschemas – änderbar" else "Schema parameter – editable"),
            Fill.FALLBACK to (if (de) "Wert ohne Formel (siehe unten)" else "Value without formula (see below)"),
        ).forEach { (fill, label) ->
            cell(sheet, r, 0).cellStyle = styles.get(StyleKey(fill = fill))
            text(sheet, r++, 1, label)
        }
        r++
        text(sheet, r++, 0, if (de) "Formelabdeckung" else "Formula coverage", StyleKey(bold = true))
        text(sheet, r, 1, if (de) "Formelzellen" else "Formula cells")
        text(sheet, r++, 2, formulaCells.toString())
        text(sheet, r, 1, if (de) "Werte ohne Formel" else "Values without formula")
        text(sheet, r++, 2, fallbacks.size.toString())
        fallbacks.forEach { f ->
            text(sheet, r, 1, "${f.sheet}!${f.cell} (${f.nodeId})", StyleKey(fill = Fill.FALLBACK))
            text(sheet, r++, 4, f.reason, StyleKey(muted = true))
        }
    }

    private fun findSheetFor(panelId: String): XSSFSheet? {
        sectionSheets[panelId]?.let { return it }
        val resultId = map.panel(panelId).resultId ?: return null
        val slot = nodeSlots[resultId]?.values?.firstOrNull() ?: return null
        return slot.sheet
    }

    // ── Evaluation ─────────────────────────────────────────────────────────────────────────────

    private fun evaluate(): List<String> {
        val evaluator = wb.creationHelper.createFormulaEvaluator()
        val errors = mutableListOf<String>()
        for (s in 0 until wb.numberOfSheets) {
            val sheet = wb.getSheetAt(s)
            for (row in sheet) for (c in row) {
                if (c.cellType != CellType.FORMULA) continue
                try {
                    evaluator.evaluateFormulaCell(c)
                } catch (e: Exception) {
                    errors += "${sheet.sheetName}!${c.address}: ${e.message?.take(200)}"
                }
            }
        }
        return errors
    }

    private fun dslFormula(vertex: ViewNode): String {
        vertex.line?.let { return it.formula.source.replace(Regex("\\s+"), " ") }
        if (vertex.total != null) return vertex.components.joinToString(" ") { (if (it.sign < 0) "− " else "+ ") + it.vertexId }.removePrefix("+ ")
        vertex.choice?.let { return (if (it.rule == ChoiceRule.MIN) "min" else "max") + "(" + it.options.joinToString("; ") { option -> option.formula.source } + ")" }
        return ""
    }

    companion object {
        const val HEADER_ROW = 3
        const val FIRST_ROW = 4
    }
}
