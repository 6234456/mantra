package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import com.xqiou.mantra.render.paper.WorkingPaper
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFSheet

private const val AUDIT_STATUS_PROPERTY = "mantra.audit.status-address"
private const val MAX_CELL_CHARACTERS = 32_767

/** Address of the recalculating `current` / `outdated` audit-snapshot status, when an audit is included. */
fun ExcelWorkbook.auditSnapshotStatusAddress(): String? =
    workbook.properties.customProperties.getProperty(AUDIT_STATUS_PROPERTY)?.lpwstr

/** The trace remains generation-time evidence. Editing inputs updates this status, never the trace. */
internal fun ExcelWorkbookBuilder.writeAudit(paper: WorkingPaper) {
    if (paper.audit.isEmpty()) return
    val audit = sheet(if (de) "Audit-Snapshot" else "Audit snapshot")
    listOf(16, 36, 64, 80, 24, 12).forEachIndexed { index, width -> audit.setColumnWidth(index, width * 256) }
    text(audit, 0, 0, texts.audit, StyleKey(bold = true, size = 13))
    text(
        audit,
        1,
        0,
        if (de) {
            "Trace und Originalwerte sind ein Snapshot bei Erstellung. Änderungen rechnen Formeln neu, nicht den Trace."
        } else {
            "Trace and original values are a generation-time snapshot. Edits recalculate formulas, not the trace."
        },
        StyleKey(muted = true, wrap = true),
    )
    text(audit, 2, 0, if (de) "Snapshot-Status" else "Snapshot status", StyleKey(bold = true))
    val status = Slot(audit, 2, 1)
    wb.properties.customProperties.addProperty(AUDIT_STATUS_PROPERTY, status.address)
    text(audit, 3, 0, texts.schema)
    text(audit, 3, 1, view.schema.id)
    text(audit, 4, 0, "Case")
    text(audit, 4, 1, view.case.id)
    var row = 6
    auditHeader(audit, row++, listOf(texts.row, texts.label, texts.formula, texts.explain, texts.result))
    paper.audit.forEach { entry ->
        val formula = auditChunks(entry.formula)
        val working = auditChunks(entry.working)
        repeat(maxOf(formula.size, working.size)) { index ->
            if (index == 0) {
                text(audit, row, 0, entry.citation)
                text(audit, row, 1, entry.label + entry.member?.let { " [$it]" }.orEmpty())
                text(audit, row, 4, entry.result)
            }
            formula.getOrNull(index)?.let { text(audit, row, 2, it, StyleKey(muted = true, wrap = true)) }
            working.getOrNull(index)?.let { text(audit, row, 3, it, StyleKey(wrap = true)) }
            row++
        }
    }
    row += 2
    text(
        audit,
        row++,
        0,
        if (de) "Originale Eingaben und wirksame Parameter" else "Original inputs and effective parameters",
        StyleKey(bold = true),
    )
    auditHeader(
        audit,
        row++,
        listOf("Node", "Coordinate / field", "Original Excel value", "Exact engine value", "Origin", "Changed"),
    )
    val firstBasisRow = row
    val basis = auditBasis()
    basis.forEach { item ->
        text(audit, row, 0, item.id)
        text(audit, row, 1, item.coordinate)
        text(audit, row, 4, item.origin)
        val original = Slot(audit, row, 2)
        val live = cell(item.live.sheet, item.live.row, item.live.col)
        copyAuditValue(live, cell(audit, row, 2))
        val changed = Slot(audit, row, 5)
        setFormula(changed, item.id) {
            Ex.iff(Ex.fn("IFERROR", auditEqual(item.live, original), Ex.FALSE, kind = XKind.BOOL), Ex.ZERO, Ex.num(1))
        }
        val chunks = auditChunks(item.exact)
        chunks.forEachIndexed { index, chunk -> text(audit, row + index, 3, chunk, StyleKey(wrap = true)) }
        row += chunks.size
    }
    setFormula(status, "audit snapshot") {
        val changes = if (basis.isEmpty()) {
            Ex.ZERO
        } else {
            Ex.fn("SUM", Ex.atom(Slot(audit, firstBasisRow, 5).address + ":\$F\$$row"))
        }
        val structureChanges = dynamic?.tables?.values.orEmpty().map { table ->
            val rows = Ex.fn("ROWS", Ex.atom("${table.table.name}[${table.columns.first().name}]"))
            val original = ((view.case.inputs[table.inputId] as? Value.Vec)?.items?.size ?: 0).coerceAtLeast(1)
            Ex.iff(Ex.cmp("=", rows, Ex.num(original.toLong())), Ex.ZERO, Ex.num(1))
        }
        val keyedChanges = dynamic?.keyedInputs?.values.orEmpty().map { bank ->
            val rows = Ex.fn("ROWS", Ex.atom("${bank.table.table.name}[fact]"))
            Ex.iff(Ex.cmp("=", rows, Ex.num(bank.table.capacity.toLong())), Ex.ZERO, Ex.num(1))
        }
        val allChanges = Ex.fn("SUM", listOf(changes) + structureChanges + keyedChanges)
        Ex.iff(Ex.cmp(">", allChanges, Ex.ZERO), Ex.text("outdated"), Ex.text("current"))
    }
    val overview = wb.getSheetAt(0)
    text(overview, 1, 4, if (de) "Audit-Snapshot (bei Erstellung)" else "Audit snapshot (at generation)")
    link(overview, 1, 4, audit.sheetName)
    setFormula(Slot(overview, 2, 4), "audit snapshot") { ref(status, XKind.TEXT) }
    audit.createFreezePane(0, 7)
    audit.protectSheet("mantra-audit-snapshot")
}

private data class AuditBasis(
    val id: String,
    val coordinate: String,
    val origin: String,
    val exact: String,
    val live: Slot,
)

private fun ExcelWorkbookBuilder.auditBasis(): List<AuditBasis> = buildList {
    view.nodes.values.filter { it.kind == NodeKind.INPUT || it.kind == NodeKind.PARAM }.forEach { node ->
        nodeSlots[node.id].orEmpty().takeUnless {
            node.id in dynamic?.keyedInputs.orEmpty()
        }.orEmpty().forEach { (coord, slot) ->
            add(
                AuditBasis(
                    node.id,
                    node.dims.zip(coord).joinToString(", ") { (dimension, member) -> "$dimension=$member" },
                    node.parameterSource ?: node.trace(coord)?.let { trace ->
                        (trace as? com.xqiou.mantra.core.view.NodeTrace.Input)?.label()
                    }.orEmpty(),
                    (node.parameterValue ?: node.value(coord)).toString(),
                    slot,
                ),
            )
        }
        if (node.kind == NodeKind.PARAM && node.id !in nodeSlots) {
            val parameters = wb.getSheet(if (de) "Parameter" else "Parameters")
            parameters?.firstOrNull {
                it.getCell(1)?.let { cell ->
                    cell.cellType == CellType.STRING && cell.stringCellValue == node.id
                } == true
            }?.let { parameter ->
                add(
                    AuditBasis(
                        node.id,
                        "",
                        node.parameterSource.orEmpty(),
                        node.parameterValue.toString(),
                        Slot(parameters, parameter.rowNum, 2),
                    ),
                )
            }
        }
    }
    dynamic?.keyedInputs?.values.orEmpty().forEach { bank ->
        for (row in 0 until bank.table.capacity) {
            reader.chargeScans()
            val coord = bank.node.dims.indices.map { index ->
                val cell = bank.table.table.xssfSheet.getRow(row + 1).getCell(index)
                if (cell.cellType == CellType.STRING) cell.stringCellValue else ""
            }
            bank.table.columns.forEachIndexed { column, field ->
                val actual = cell(bank.table.table.xssfSheet, row + 1, column)
                val exact = when (actual.cellType) {
                    CellType.NUMERIC -> actual.numericCellValue.toString()
                    CellType.STRING -> actual.stringCellValue
                    CellType.BOOLEAN -> actual.booleanCellValue.toString()
                    else -> "nil"
                }
                val input = bank.node.trace(coord) as? com.xqiou.mantra.core.view.NodeTrace.Input
                add(
                    AuditBasis(
                        bank.node.id,
                        "[${row + 1}].${field.name}",
                        input?.label().orEmpty(),
                        if (field.name == "fact" &&
                            coord.all { it.isNotEmpty() }
                        ) {
                            bank.node.value(coord).toString()
                        } else {
                            exact
                        },
                        Slot(bank.table.table.xssfSheet, row + 1, column),
                    ),
                )
            }
        }
    }
    tableSlots.forEach { (key, slot) ->
        val (id, index, column) = key
        val rows = (view.node(id).value() as? Value.Vec)?.items.orEmpty()
        val original = (rows.getOrNull(index) as? Value.MapV)?.entries?.get(Value.Kw(column)) ?: Value.Nil
        add(AuditBasis(id, "[${index + 1}].$column", "input", original.toString(), slot))
    }
    providedSlots.forEach { slot ->
        val id = cell(slot.sheet, slot.row, 0).stringCellValue
        val present = cell(slot.sheet, slot.row, slot.col).booleanCellValue
        add(AuditBasis(id, "provided", "presence", present.toString(), slot))
    }
}

private fun auditEqual(live: Slot, original: Slot): X.Scalar {
    val current = Ex.atom(live.address, XKind.ANY)
    val source = Ex.atom(original.address, XKind.ANY)
    fun blank(value: X.Scalar) = Ex.fn("ISBLANK", value, kind = XKind.BOOL)
    val valueEqual = Ex.iff(
        Ex.fn("ISTEXT", source, kind = XKind.BOOL),
        Ex.fn("EXACT", current, source, kind = XKind.BOOL),
        Ex.cmp("=", current, source),
    )
    return Ex.iff(
        blank(source),
        blank(current),
        Ex.fn(
            "AND",
            Ex.fn("NOT", blank(current), kind = XKind.BOOL),
            Ex.cmp("=", Ex.fn("ISNUMBER", current, kind = XKind.BOOL), Ex.fn("ISNUMBER", source, kind = XKind.BOOL)),
            Ex.cmp("=", Ex.fn("ISTEXT", current, kind = XKind.BOOL), Ex.fn("ISTEXT", source, kind = XKind.BOOL)),
            Ex.cmp("=", Ex.fn("ISLOGICAL", current, kind = XKind.BOOL), Ex.fn("ISLOGICAL", source, kind = XKind.BOOL)),
            valueEqual,
            kind = XKind.BOOL,
        ),
    )
}

private fun copyAuditValue(source: XSSFCell, target: XSSFCell) {
    when (source.cellType) {
        CellType.NUMERIC -> target.setCellValue(source.numericCellValue)
        CellType.BOOLEAN -> target.setCellValue(source.booleanCellValue)
        CellType.STRING -> target.setCellValue(source.stringCellValue)
        CellType.BLANK, CellType._NONE -> target.setBlank()
        else -> throw IllegalArgumentException(
            "Audit basis must be an editable input or parameter at ${source.address}",
        )
    }
}

private fun ExcelWorkbookBuilder.auditHeader(sheet: XSSFSheet, row: Int, labels: List<String>) {
    labels.forEachIndexed { column, label ->
        text(sheet, row, column, label, StyleKey(bold = true, fill = Fill.HEADER, headerRule = true))
    }
}

/** Keep every character while respecting Excel's cell string limit, including surrogate boundaries. */
private fun auditChunks(text: String): List<String> {
    if (text.isEmpty()) return listOf("")
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + MAX_CELL_CHARACTERS, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
