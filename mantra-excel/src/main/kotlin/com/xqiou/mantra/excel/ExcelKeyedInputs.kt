package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.ColumnDecl
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellReference

/** Editable facts have explicit coordinate keys; moving a domain row never moves its facts. */
internal data class KeyedInputTable(val node: ViewNode, val table: DynamicWorkbookTables.Table)

internal fun ExcelWorkbookBuilder.layoutKeyedInputs() {
    val live = dynamic ?: return
    view.nodes.values.filter { it.kind == NodeKind.INPUT && it.type != ValueType.TABLE && live.isDynamicNode(it.id) }
        .forEach { node ->
            val sheet = sheet("Keyed ${node.id}")
            val columns = node.dims.indices.map { ColumnDecl("axis${it + 1}", ValueType.KEYWORD, false) } +
                listOf(ColumnDecl("fact", node.type, true), ColumnDecl("provided", ValueType.BOOLEAN, false))
            columns.forEachIndexed { index, column ->
                text(
                    sheet,
                    0,
                    index,
                    node.dims.getOrNull(index) ?: column.name,
                    StyleKey(bold = true, fill = Fill.HEADER),
                )
                sheet.setColumnWidth(index, 20 * 256)
            }
            val initial = node.dims.fold(listOf(emptyList<String>())) { partial, dim ->
                partial.flatMap { prefix ->
                    view.members[dim].orEmpty().map { member ->
                        reader.chargeCoordinateVisits()
                        prefix + member.key
                    }
                }
            }
            val capacity = coordinates(node.dims).size.coerceAtLeast(1)
            require(initial.size <= capacity)
            for (index in 0 until capacity) {
                val coord = initial.getOrNull(index)
                columns.forEachIndexed { col, column ->
                    val slot = Slot(sheet, index + 1, col)
                    val value = when {
                        col < node.dims.size -> coord?.get(col)?.let { Value.Kw(it) } ?: Value.Nil
                        column.name == "fact" -> coord?.let { inputValue(node, it) } ?: Value.Nil
                        else -> Value.Bool(coord?.let { view.inputProvided(node.id, it) } == true)
                    }
                    writeValue(slot, value)
                    if (column.name == "fact" && coord != null) writeInputProvenance(slot, node, coord)
                    cell(sheet, slot.row, slot.col).cellStyle = styles.get(
                        StyleKey(
                            fill = Fill.INPUT,
                            format = if (column.type == ValueType.DATE) {
                                "yyyy-mm-dd"
                            } else if (column.type.isNumeric) {
                                numberFormat(node.presentation, false)
                            } else {
                                "General"
                            },
                        ),
                    )
                    inputCells++
                }
            }
            val table = sheet.createTable(
                AreaReference(
                    CellReference(0, 0),
                    CellReference(capacity, columns.lastIndex),
                    SpreadsheetVersion.EXCEL2007,
                ),
            )
            table.name = "Mantra_Keyed_${live.keyedInputs.size + 1}"
            table.displayName = table.name
            // OOXML headers are stable symbolic columns, independent of display dimension names.
            columns.forEachIndexed { index, column -> cell(sheet, 0, index).setCellValue(column.name) }
            table.updateHeaders()
            live.keyedInputs[node.id] =
                KeyedInputTable(node, DynamicWorkbookTables.Table(node.id, table, columns, capacity))
            sheet.createFreezePane(0, 1)
        }
}

internal fun ExcelWorkbookBuilder.dynamicInputValue(node: ViewNode, coord: Coord): X.Scalar {
    val bank = requireNotNull(dynamic).keyedInputs.getValue(node.id)
    val default = node.input!!.default ?: when {
        node.type.isNumeric -> Value.ZERO
        node.type == ValueType.BOOLEAN -> Value.Bool(false)
        else -> Value.Nil
    }
    return Ex.iff(
        requireNotNull(dynamic).capacityGuard(),
        keyedInputLookup(bank, coord, "fact", translator.toScalar(literal(default))),
        Ex.fn("NA"),
    )
}

internal fun ExcelWorkbookBuilder.dynamicInputProvided(node: ViewNode, coord: Coord): X.Scalar? =
    dynamic?.keyedInputs?.get(node.id)?.let { bank ->
        Ex.fn(
            "AND",
            keyedInputLookup(bank, coord, "provided", Ex.FALSE),
            Ex.cmp("<>", keyedInputLookup(bank, coord, "fact", Ex.EMPTY), Ex.EMPTY),
            kind = XKind.BOOL,
        )
    }

private fun ExcelWorkbookBuilder.keyedInputLookup(
    bank: KeyedInputTable,
    coord: Coord,
    column: String,
    default: X.Scalar,
): X.Scalar {
    val live = requireNotNull(dynamic)
    val wanted = bank.node.dims.mapIndexed { index, axis ->
        if (axis in live.dimensions) live.key(axis, coord[index]) else Ex.text(coord[index])
    }
    var result = default
    for (row in bank.table.capacity - 1 downTo 0) {
        reader.chargeScans()
        val selected = boundedReductionBoolean(
            "AND",
            wanted.mapIndexed { axis, key ->
                Ex.fn(
                    "AND",
                    Ex.cmp("<>", key, Ex.EMPTY),
                    Ex.cmp("=", key, live.field(bank.node.id, row, "axis${axis + 1}")),
                    kind = XKind.BOOL,
                )
            },
        )
        val value = live.field(bank.node.id, row, column)
        val supplied = Ex.iff(Ex.cmp("=", value, Ex.EMPTY), default, value)
        result = materializeExpression(
            Ex.iff(selected, supplied, result).copy(
                kind = default.kind,
                numericOrNil = default.numericOrNil,
                booleanOrNil = default.booleanOrNil,
            ),
        )
    }
    return result
}

/** Blank reserve rows are not facts. Partial keys, duplicate coordinates and overcapacity are errors. */
internal fun keyedInputGuard(builder: ExcelWorkbookBuilder, bank: KeyedInputTable): X.Scalar {
    val live = requireNotNull(builder.dynamic)
    val checks = mutableListOf(
        Ex.cmp("<=", Ex.fn("ROWS", Ex.atom("${bank.table.table.name}[fact]")), Ex.num(bank.table.capacity.toLong())),
    )
    for (row in 0 until bank.table.capacity) {
        builder.reader.chargeScans()
        val keys = bank.node.dims.indices.map { live.field(bank.node.id, row, "axis${it + 1}") }
        val allKeys = builder.boundedReductionBoolean(
            "AND",
            keys.map { key ->
                Ex.fn(
                    "AND",
                    Ex.fn("ISTEXT", key, kind = XKind.BOOL),
                    Ex.cmp(">", Ex.fn("LEN", Ex.fn("TRIM", key, kind = XKind.TEXT)), Ex.ZERO),
                    kind = XKind.BOOL,
                )
            },
        )
        val noKeys = builder.boundedReductionBoolean("AND", keys.map { Ex.cmp("=", it, Ex.EMPTY) })
        val value = live.field(bank.node.id, row, "fact")
        val blank = Ex.fn("AND", noKeys, Ex.cmp("=", value, Ex.EMPTY), kind = XKind.BOOL)
        val arguments = keys.flatMapIndexed { index, key ->
            listOf(Ex.atom("${bank.table.table.name}[axis${index + 1}]", XKind.ANY), key)
        }
        val unique = Ex.cmp("=", Ex.fn("COUNTIFS", arguments), Ex.num(1))
        val provided = live.field(bank.node.id, row, "provided")
        val typed = when {
            bank.node.type.isNumeric || bank.node.type == ValueType.DATE -> Ex.fn("ISNUMBER", value, kind = XKind.BOOL)
            bank.node.type == ValueType.BOOLEAN -> Ex.fn("ISLOGICAL", value, kind = XKind.BOOL)
            else -> Ex.fn("ISTEXT", value, kind = XKind.BOOL)
        }
        val typeOkay = Ex.fn("OR", Ex.cmp("=", value, Ex.EMPTY), typed, kind = XKind.BOOL)
        val providedOkay = Ex.fn(
            "OR",
            Ex.cmp("=", provided, Ex.EMPTY),
            Ex.fn("ISLOGICAL", provided, kind = XKind.BOOL),
            kind = XKind.BOOL,
        )
        checks += Ex.iff(blank, providedOkay, Ex.fn("AND", allKeys, unique, typeOkay, providedOkay, kind = XKind.BOOL))
    }
    return Ex.iff(builder.boundedReductionBoolean("AND", checks), Ex.TRUE, Ex.fn("NA"))
}

internal fun DynamicWorkbookTables.keyedInputAddress(input: String, coord: Coord): String? {
    val bank = keyedInputs[input] ?: return null
    if (coord.size != bank.node.dims.size) return null
    val table = bank.table.table
    val matches = (0 until physicalRows(bank.table)).filter { row ->
        coord.indices.all { index ->
            val cell = table.xssfSheet.getRow(table.startRowIndex + row + 1)?.getCell(table.startColIndex + index)
            cell?.cellType == CellType.STRING && cell.stringCellValue == coord[index]
        }
    }
    val row = matches.singleOrNull() ?: return null
    return Slot(table.xssfSheet, table.startRowIndex + row + 1, table.startColIndex + coord.size).address
}
