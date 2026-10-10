package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.ColumnDecl
import com.xqiou.mantra.core.model.DimensionDecl
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.Coord
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFTable

/** A live OOXML input table. Capacity bounds calculation slots, not its current physical row count. */
data class ExcelDynamicTable(
    val inputId: String,
    val name: String,
    val sheet: String,
    val columns: List<String>,
    val capacity: Int,
    val rows: Int,
)

internal class DynamicWorkbookTables(builder: ExcelWorkbookBuilder) {
    private var constructionBuilder: ExcelWorkbookBuilder? = builder
    private val builder: ExcelWorkbookBuilder get() = checkNotNull(constructionBuilder) {
        "Dynamic workbook translation is complete"
    }
    private val workbook = builder.wb
    private val styles = builder.styles
    private val initialKeys = builder.view.members.mapValues { (_, members) -> members.map { it.key } }
    internal fun detach() {
        constructionBuilder = null
    }
    internal data class Table(
        val inputId: String,
        val table: XSSFTable,
        val columns: List<ColumnDecl>,
        val capacity: Int,
    )
    internal val tables = linkedMapOf<String, Table>()
    private val fields = linkedMapOf<Triple<String, Int, String>, X.Scalar>()
    internal val dimensions = builder.view.dimensions.values.filter {
        it.fromTable in
            builder.options.dynamicTableCapacities
    }
        .associateBy { it.id }

    // Virtual storage identity is disjoint from public member identity. No original A/B key is a slot.
    private val tokens = dimensions.mapValues { (dimension, dim) ->
        List(builder.options.dynamicTableCapacities.getValue(dim.fromTable!!)) { index ->
            "\u0000mantra-slot:$dimension:$index"
        }
    }
    internal fun initialToken(dimension: String, key: String): String? = initialKeys[dimension]?.indexOf(key)?.takeIf {
        it >=
            0
    }
        ?.let { tokens[dimension]?.getOrNull(it) }
    internal fun storageCoordinate(dims: List<String>, coord: Coord): Coord = dims.mapIndexed { index, dim ->
        if (dim in dimensions) {
            initialToken(dim, coord[index])
                ?: throw Untranslatable("No initial dynamic member ${coord[index]} of $dim")
        } else {
            coord[index]
        }
    }
    internal fun initialCoordinate(dims: List<String>, coord: Coord): Coord? = dims.mapIndexed { index, dim ->
        if (dim in dimensions) {
            initialKeys[dim]?.getOrNull(this.index(dim, coord[index])) ?: return null
        } else {
            coord[index]
        }
    }
    internal val keyedInputs = linkedMapOf<String, KeyedInputTable>()
    private val nodeDims = builder.view.nodes.mapValues { it.value.dims.toList() }

    init {
        builder.options.dynamicTableCapacities.forEach { (id, capacity) ->
            val input = builder.view.nodes[id]?.input
                ?: throw IllegalArgumentException("Unknown table input $id")
            require(input.type == ValueType.TABLE) { "$id is not a table input" }
            val rows = (builder.view.case.inputs[id] as? Value.Vec)?.items.orEmpty()
            require(rows.size <= capacity) { "$id has ${rows.size} rows, above capacity $capacity" }
        }
    }

    internal fun memberTokens(dimension: String): List<String>? = tokens[dimension]
    internal fun index(dimension: String, token: String): Int =
        tokens.getValue(dimension).indexOf(token).also { require(it >= 0) }
    internal fun isDynamicNode(node: String): Boolean = nodeDims[node].orEmpty().any { it in dimensions }
    internal fun field(inputId: String, row: Int, column: String): X.Scalar =
        fields.getOrPut(Triple(inputId, row, column)) {
            val spec = tables[inputId] ?: keyedInputs.getValue(inputId).table
            val declared = spec.columns.first { it.name == column }
            val structured = Ex.atom("${spec.table.name}[$column]", XKind.ANY)
            val within = Ex.cmp("<=", Ex.num((row + 1).toLong()), Ex.fn("ROWS", structured))
            val value = Ex.fn("INDEX", structured, Ex.num((row + 1).toLong()), kind = kind(declared.type))
            val present = Ex.fn("NOT", Ex.fn("ISBLANK", value, kind = XKind.BOOL), kind = XKind.BOOL)
            val live = Ex.iff(within, Ex.iff(present, value, Ex.EMPTY), Ex.EMPTY)
                .copy(
                    kind = kind(declared.type),
                    numericOrNil = declared.type.isNumeric,
                    booleanOrNil =
                    declared.type == ValueType.BOOLEAN,
                )
            builder.materializeExpression(live)
        }
    internal fun record(dimension: String, token: String, field: String): X? {
        val dim = dimensions[dimension] ?: return null
        if (token !in tokens.getValue(dimension)) {
            val keys = tokens.getValue(dimension)
            return builder.translator.mapLookup(
                X.MapX(
                    keys,
                    keys.map { record(dimension, it, field) ?: X.Nil },
                    keys.map { key(dimension, it) },
                ),
                Ex.text(token),
            )
        }
        val column = when (field) {
            "key" -> dim.keyColumn
            "label" -> dim.titleColumn ?: dim.keyColumn
            else -> field
        }
        if (field == "index") return Ex.num(index(dimension, token).toLong())
        if (builder.view.node(dim.fromTable!!).input!!.columns.none { it.name == column }) return null
        return field(dim.fromTable!!, index(dimension, token), column)
    }
    internal fun key(dimension: String, token: String): X.Scalar =
        builder.translator.toScalar(record(dimension, token, "key") ?: Ex.text(token))
    internal fun present(dimension: String, token: String): X.Scalar = Ex.cmp("<>", key(dimension, token), Ex.EMPTY)
    private val rowPresence = linkedMapOf<Pair<String, Int>, X.Scalar>()
    internal fun rowPresent(input: String, index: Int): X.Scalar = rowPresence.getOrPut(input to index) {
        val table = tables.getValue(input)
        val count = rowCount(table)
        val within = Ex.cmp("<=", Ex.num((index + 1).toLong()), count)
        val singleEmpty = Ex.fn("AND", Ex.cmp("=", count, Ex.num(1)), rowBlank(table, 0), kind = XKind.BOOL)
        builder.materializeExpression(
            Ex.fn("AND", within, Ex.fn("NOT", singleEmpty, kind = XKind.BOOL), kind = XKind.BOOL),
        )
    }
    internal fun rowCount(input: String): X.Scalar = builder.boundedReductionSum(
        (0 until tables.getValue(input).capacity).map { row -> Ex.iff(rowPresent(input, row), Ex.num(1), Ex.ZERO) },
    )
    private fun rowCount(table: Table) = Ex.fn("ROWS", Ex.atom("${table.table.name}[${table.columns.first().name}]"))
    private fun rowBlank(table: Table, row: Int) = builder.boundedReductionBoolean(
        "AND",
        table.columns.map { column ->
            Ex.fn(
                "ISBLANK",
                Ex.fn("INDEX", Ex.atom("${table.table.name}[${column.name}]", XKind.ANY), Ex.num((row + 1).toLong())),
                kind = XKind.BOOL,
            )
        },
    )
    private var integrityGuard: X.Scalar? = null
    internal fun capacityGuard(): X.Scalar =
        integrityGuard ?: builder.materializeExpression(buildIntegrityGuard()).also { integrityGuard = it }
    private fun buildIntegrityGuard(): X.Scalar = builder.boundedReductionBoolean(
        "AND",
        tables.values.flatMap { table ->
            val count = rowCount(table)
            val valid = mutableListOf(Ex.cmp("<=", count, Ex.num(table.capacity.toLong())))
            for (row in 0 until table.capacity) {
                val present = rowPresent(table.inputId, row)
                val checks = mutableListOf(Ex.fn("NOT", rowBlank(table, row), kind = XKind.BOOL))
                dimensions.values.filter { it.fromTable == table.inputId }.forEach { dim ->
                    val key = field(table.inputId, row, dim.keyColumn)
                    checks += Ex.fn("ISTEXT", key, kind = XKind.BOOL)
                    checks += Ex.cmp(">", Ex.fn("LEN", Ex.fn("TRIM", key, kind = XKind.TEXT)), Ex.ZERO)
                    checks +=
                        Ex.cmp(
                            "=",
                            Ex.fn("COUNTIF", Ex.atom("${table.table.name}[${dim.keyColumn}]", XKind.ANY), key),
                            Ex.num(1),
                        )
                }
                table.columns.forEach { column ->
                    val value = field(table.inputId, row, column.name)
                    val typed = when {
                        column.type.isNumeric || column.type == ValueType.DATE -> Ex.fn(
                            "ISNUMBER",
                            value,
                            kind = XKind.BOOL,
                        )
                        column.type == ValueType.BOOLEAN -> Ex.fn("ISLOGICAL", value, kind = XKind.BOOL)
                        else -> Ex.fn("ISTEXT", value, kind = XKind.BOOL)
                    }
                    checks += Ex.fn("OR", Ex.cmp("=", value, Ex.EMPTY), typed, kind = XKind.BOOL)
                }
                valid += Ex.iff(present, builder.boundedReductionBoolean("AND", checks), Ex.TRUE)
            }
            listOf(Ex.iff(builder.boundedReductionBoolean("AND", valid), Ex.TRUE, Ex.fn("NA")))
        } + keyedInputs.values.map { bank -> keyedInputGuard(builder, bank) },
    )
    internal fun resolveCoordinate(node: String, coordinate: Coord): Coord? {
        val dims = nodeDims[node] ?: return coordinate
        if (dims.size != coordinate.size) return null
        return dims.mapIndexed { index, dimension ->
            if (dimension !in dimensions) {
                coordinate[index]
            } else {
                val declaration = dimensions.getValue(dimension)
                val table = tables.getValue(declaration.fromTable!!)
                val row = findKey(table, declaration.keyColumn, coordinate[index]) ?: return null
                tokens.getValue(dimension).getOrNull(row) ?: return null
            }
        }
    }
    internal fun recordAddress(dimension: String, key: String, column: String): String? {
        val declaration = dimensions[dimension] ?: return null
        val table = tables.getValue(declaration.fromTable!!)
        return findKey(table, declaration.keyColumn, key)?.let { tableAddress(table.inputId, it, column) }
    }
    internal fun tableAddress(input: String, row: Int, column: String): String? {
        val spec = tables[input] ?: return null
        val col = spec.columns.indexOfFirst { it.name == column }
        if (col < 0 || row !in 0 until physicalRows(spec)) return null
        return ExcelWorkbookBuilder.Slot(
            spec.table.xssfSheet,
            spec.table.startRowIndex + 1 + row,
            spec.table.startColIndex + col,
        ).address
    }
    internal fun descriptor(input: String): ExcelDynamicTable? = tables[input]?.let {
        ExcelDynamicTable(
            input,
            it.table.name,
            it.table.xssfSheet.sheetName,
            it.columns.map { c ->
                c.name
            },
            it.capacity,
            physicalRows(it),
        )
    }
    private fun findKey(spec: Table, column: String, key: String): Int? {
        val col = spec.columns.indexOfFirst { it.name == column }
        if (col < 0) return null
        val matches = (0 until physicalRows(spec)).filter { row ->
            val cell = spec.table.xssfSheet.getRow(
                spec.table.startRowIndex + row + 1,
            )?.getCell(spec.table.startColIndex + col)
            cell?.cellType == CellType.STRING && cell.stringCellValue == key
        }
        return matches.singleOrNull()
    }
    internal fun physicalRows(spec: Table): Int = spec.table.endRowIndex - spec.table.startRowIndex
    internal fun rows(input: String): List<Map<String, Value>> {
        val spec = tables.getValue(input)
        val rows = (0 until physicalRows(spec)).map { index ->
            spec.columns.associate { column ->
                val cell = spec.table.xssfSheet.getRow(
                    spec.table.startRowIndex + index + 1,
                )?.getCell(spec.columns.indexOf(column))
                val value = when (cell?.cellType) {
                    CellType.NUMERIC -> if (column.type ==
                        ValueType.DATE
                    ) {
                        Value.Date(cell.localDateTimeCellValue.toLocalDate())
                    } else {
                        Value.Num(java.math.BigDecimal.valueOf(cell.numericCellValue))
                    }
                    CellType.BOOLEAN -> Value.Bool(cell.booleanCellValue)
                    CellType.STRING -> if (column.type ==
                        ValueType.KEYWORD
                    ) {
                        Value.Kw(cell.stringCellValue)
                    } else {
                        Value.Text(cell.stringCellValue)
                    }
                    CellType.BLANK, CellType._NONE, null -> Value.Nil
                    else -> throw IllegalArgumentException("Dynamic input tables contain literal facts only")
                }
                column.name to value
            }
        }
        return if (rows.size == 1 && rows.single().values.all { it == Value.Nil }) emptyList() else rows
    }
    internal fun replace(input: String, rows: List<Map<String, Value>>) {
        val spec = tables.getValue(input)
        require(rows.size <= 10_000) { "Dynamic table mutation exceeds 10000 physical rows" }
        require(
            rows.all { row ->
                row.keys.all { key -> spec.columns.any { it.name == key } }
            },
        ) { "Unknown input column" }
        // Each input table owns one literal-only sheet. Move no formula rows and rewrite no A1 references.
        val sheet = spec.table.xssfSheet
        val originalStyles = spec.columns.indices.map { col -> sheet.getRow(1)?.getCell(col)?.cellStyle }
        for (row in sheet.lastRowNum downTo 1) sheet.getRow(row)?.let(sheet::removeRow)
        val materialized = rows.ifEmpty { listOf(emptyMap()) }
        materialized.forEachIndexed { row, fields ->
            spec.columns.forEachIndexed { col, column ->
                val cell = (sheet.getRow(row + 1) ?: sheet.createRow(row + 1)).createCell(col)
                cell.cellStyle = originalStyles[col] ?: styles.get(
                    StyleKey(
                        fill = Fill.INPUT,
                        format = if (column.type == ValueType.DATE) "yyyy-mm-dd" else "General",
                    ),
                )
                when (val fact = fields[column.name] ?: Value.Nil) {
                    is Value.Num -> cell.setCellValue(fact.value.toDouble())
                    is Value.Text -> when (column.type) {
                        ValueType.DATE -> cell.setCellValue(java.time.LocalDate.parse(fact.value).atStartOfDay())
                        ValueType.KEYWORD -> cell.setCellValue(fact.value.removePrefix(":"))
                        else -> cell.setCellValue(fact.value)
                    }
                    is Value.Kw -> cell.setCellValue(fact.name)
                    is Value.Bool -> cell.setCellValue(fact.value)
                    is Value.Date -> cell.setCellValue(fact.value.atStartOfDay())
                    Value.Nil -> cell.setBlank()
                    else -> throw IllegalArgumentException("Dynamic cells require scalar facts")
                }
            }
        }
        spec.table.setArea(
            AreaReference(
                CellReference(0, 0),
                CellReference(materialized.size, spec.columns.lastIndex),
                SpreadsheetVersion.EXCEL2007,
            ),
        )
        spec.table.updateReferences()
        spec.table.updateHeaders()
        workbook.creationHelper.createFormulaEvaluator().clearAllCachedResultValues()
        workbook.setForceFormulaRecalculation(true)
    }
    private fun kind(type: ValueType): XKind = when {
        type.isNumeric -> XKind.NUM
        type == ValueType.BOOLEAN -> XKind.BOOL
        type ==
            ValueType.DATE -> XKind.DATE
        type == ValueType.KEYWORD -> XKind.KEYWORD
        type == ValueType.TEXT -> XKind.TEXT
        else -> XKind.ANY
    }
}

/** Current structured input table metadata, read directly from the workbook. */
fun ExcelWorkbook.dynamicTable(inputId: String): ExcelDynamicTable? = dynamicTables?.descriptor(inputId)

/** Capture literal facts from the current physical table; a zero-row table uses one blank OOXML sentinel. */
fun ExcelWorkbook.dynamicTableRows(inputId: String): List<Map<String, Value>> =
    requireNotNull(dynamicTables) { "Workbook has no dynamic tables" }.rows(inputId)

/** Replace real table rows without changing unrelated formulas. Cached results require recalculation afterwards. */
fun ExcelWorkbook.replaceDynamicTableRows(inputId: String, rows: List<Map<String, Value>>) =
    requireNotNull(dynamicTables) { "Workbook has no dynamic tables" }.replace(inputId, rows)
fun ExcelWorkbook.insertDynamicTableRow(inputId: String, index: Int, values: Map<String, Value>) {
    val rows = dynamicTableRows(inputId).toMutableList()
    require(index in 0..rows.size)
    rows.add(index, values)
    replaceDynamicTableRows(inputId, rows)
}
fun ExcelWorkbook.removeDynamicTableRow(inputId: String, index: Int) {
    val rows = dynamicTableRows(inputId).toMutableList()
    require(index in rows.indices)
    rows.removeAt(index)
    replaceDynamicTableRows(inputId, rows)
}

internal fun ExcelWorkbookBuilder.layoutDynamicTableInput(input: com.xqiou.mantra.core.view.ViewNode) {
    val live = requireNotNull(dynamic)
    val columns = input.input!!.columns
    require(columns.isNotEmpty()) { "Dynamic table ${input.id} has no columns" }
    require(
        columns.all {
            it.type.isNumeric ||
                it.type in listOf(ValueType.DATE, ValueType.BOOLEAN, ValueType.TEXT, ValueType.KEYWORD)
        },
    ) { "Dynamic columns require scalar types" }
    val sheet = sheet("Input ${input.id}")
    columns.forEachIndexed { index, column ->
        text(sheet, 0, index, column.name, StyleKey(bold = true, fill = Fill.HEADER))
        sheet.setColumnWidth(index, 20 * 256)
    }
    val rows = (view.case.inputs[input.id] as? Value.Vec)?.items.orEmpty()
    val materialized = rows.ifEmpty { listOf(Value.MapV(emptyMap())) }
    materialized.forEachIndexed { row, value ->
        val fields = (value as? Value.MapV)?.entries.orEmpty()
        columns.forEachIndexed { col, column ->
            val slot = ExcelWorkbookBuilder.Slot(sheet, row + 1, col)
            val raw = fields[Value.Kw(column.name)] ?: fields[Value.Text(column.name)] ?: Value.Nil
            val fact = when {
                column.type == ValueType.DATE && raw is Value.Text -> Value.Date(java.time.LocalDate.parse(raw.value))
                column.type == ValueType.KEYWORD && raw is Value.Text -> Value.Kw(raw.value.removePrefix(":"))
                else -> raw
            }
            writeValue(slot, fact)
            cell(sheet, row + 1, col).cellStyle =
                styles.get(
                    StyleKey(
                        fill = Fill.INPUT,
                        format = if (column.type ==
                            ValueType.DATE
                        ) {
                            "yyyy-mm-dd"
                        } else {
                            null
                        },
                    ),
                )
            tableSlots[Triple(input.id, row, column.name)] = slot
            inputCells++
        }
    }
    val table = sheet.createTable(
        AreaReference(
            CellReference(0, 0),
            CellReference(materialized.size, columns.lastIndex),
            SpreadsheetVersion.EXCEL2007,
        ),
    )
    table.name = "Mantra_Input_${live.tables.size + 1}"
    table.displayName = table.name
    live.tables[input.id] =
        DynamicWorkbookTables.Table(input.id, table, columns, options.dynamicTableCapacities.getValue(input.id))
    sheet.createFreezePane(0, 1)
}

/** Both orientations share the same live grid, including reserved slots that acquire new identities. */
internal fun ExcelWorkbookBuilder.layoutDynamicOutputs() {
    val live = dynamic ?: return
    val groups = view.nodes.values.filter {
        it.kind != com.xqiou.mantra.core.view.NodeKind.INPUT &&
            live.isDynamicNode(it.id)
    }.groupBy { it.dims }
    if (groups.isEmpty()) return
    val matrix = sheet("Dynamic matrix")
    val transpose = sheet("Dynamic transpose")
    var matrixRow = 0
    var transposeRow = 0
    groups.forEach { (dims, nodes) ->
        val coordinates = coordinates(dims)
        text(matrix, matrixRow++, 0, dims.joinToString(" × "), StyleKey(bold = true))
        dims.forEachIndexed { index, dimension -> text(matrix, matrixRow, index, dimension, StyleKey(bold = true)) }
        nodes.forEachIndexed { index, node ->
            text(matrix, matrixRow, dims.size + index, node.label, StyleKey(bold = true, wrap = true))
        }
        matrixRow++
        coordinates.forEach { coord ->
            dims.forEachIndexed { index, dim ->
                val slot = ExcelWorkbookBuilder.Slot(matrix, matrixRow, index)
                presentation +=
                    slot to { if (dim in live.dimensions) live.key(dim, coord[index]) else Ex.text(coord[index]) }
            }
            nodes.forEachIndexed { index, node ->
                val slot = ExcelWorkbookBuilder.Slot(matrix, matrixRow, dims.size + index)
                presentation += slot to { translator.toScalar(reference(node.id, dims, coord) ?: X.Nil) }
                valueStyles[slot] = StyleKey(format = numberFormat(node.presentation, false))
            }
            matrixRow++
        }
        matrixRow += 2
        text(transpose, transposeRow++, 0, dims.joinToString(" × "), StyleKey(bold = true))
        coordinates.forEachIndexed { index, coord ->
            presentation += ExcelWorkbookBuilder.Slot(transpose, transposeRow, index + 1) to {
                val labels = dims.mapIndexed { at, dim ->
                    if (dim in
                        live.dimensions
                    ) {
                        live.key(dim, coord[at])
                    } else {
                        Ex.text(coord[at])
                    }
                }
                val pieces = labels.flatMapIndexed { at, label ->
                    if (at ==
                        0
                    ) {
                        listOf(label)
                    } else {
                        listOf(Ex.text(" / "), label)
                    }
                }
                Ex.chain("&", pieces, Ex.CONCAT)
            }
        }
        transposeRow++
        nodes.forEach { node ->
            text(transpose, transposeRow, 0, node.label)
            coordinates.forEachIndexed { index, coord ->
                val slot = ExcelWorkbookBuilder.Slot(transpose, transposeRow, index + 1)
                presentation += slot to { translator.toScalar(reference(node.id, dims, coord) ?: X.Nil) }
                valueStyles[slot] = StyleKey(format = numberFormat(node.presentation, false))
            }
            transposeRow++
        }
        transposeRow += 2
    }
    matrix.createFreezePane(1, 2)
    transpose.createFreezePane(1, 2)
}
