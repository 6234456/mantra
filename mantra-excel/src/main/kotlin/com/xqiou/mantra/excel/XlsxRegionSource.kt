package com.xqiou.mantra.excel

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.data.DataSource
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import org.apache.poi.ooxml.POIXMLException
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/** One explicit worksheet rectangle supplies one table input, without requiring defined names. */
class XlsxRegionSource(
    private val path: Path,
    private val input: String,
    private val sheet: String,
    private val range: String,
    private val columns: Map<String, String> = emptyMap(),
    private val capturedBytes: ByteArray? = null,
    private val onRow: () -> Unit = {},
    private val checkpoint: () -> Unit = {},
) : DataSource {
    override val description: String = "xlsx:${path.fileName}#$sheet!$range"

    override fun read(schema: Schema, sink: DiagnosticSink): Map<String, Value> {
        fun invalid(message: String): Map<String, Value> {
            sink.error("MANTRA-DATA-XLSX-REGION", "${path.fileName}: $message", nodeId = input)
            return emptyMap()
        }
        val declaration = schema.inputs.firstOrNull { it.id == input && it.type == ValueType.TABLE }
            ?: return invalid("Region target must be a declared table input")
        val rectangle = rectangle(range) ?: return invalid("Expected one local A1 rectangle with ordered endpoints")
        val cells = (rectangle.lastRow - rectangle.firstRow + 1L) *
            (rectangle.lastColumn - rectangle.firstColumn + 1L)
        if (cells > MAX_REGION_CELLS) {
            sink.error("MANTRA-DATA-XLSX-LIMIT", "Region exceeds $MAX_REGION_CELLS cells", nodeId = input)
            return emptyMap()
        }
        val bytes = try {
            (
                capturedBytes?.let {
                    XlsxSource.validateContainer(it, checkpoint)
                    it.copyOf()
                } ?: Files.newInputStream(path).use { XlsxContainerPreflight.capture(it, checkpoint) }
                ).also {
                XlsxSource.validateContainer(it, checkpoint)
            }
        } catch (error: MantraException) {
            if (error.runFailure != null) throw error
            sink.addAll(error.diagnostics)
            return emptyMap()
        }
        checkpoint()
        val workbook = try {
            bytes.inputStream().use { XSSFWorkbook(it) }
        } catch (error: java.io.IOException) {
            return invalid("Workbook could not be read: ${error.message}")
        } catch (error: POIXMLException) {
            return invalid("Workbook could not be read: ${error.message}")
        }
        workbook.use { wb ->
            val selected = wb.getSheet(sheet)?.takeIf { it.sheetName == sheet }
                ?: return invalid("Worksheet '$sheet' does not exist")
            for (index in 0 until selected.numMergedRegions) {
                checkpoint()
                if (selected.getMergedRegion(index).intersects(rectangle)) {
                    return invalid("Merged cells intersect the region")
                }
            }
            val headers = headers(selected, rectangle) ?: return invalid("Headers must be unique nonempty literal text")
            val headerNames = headers.values.toSet()
            val declaredColumns = declaration.columns.associateBy {
                checkpoint()
                it.name
            }
            if (columns.keys.any {
                    checkpoint()
                    it !in headerNames
                } || columns.values.any { target ->
                    checkpoint()
                    target !in declaredColumns
                } || columns.values.distinct().size != columns.size
            ) {
                return invalid("Column mappings must use existing headers and unique declared target columns")
            }
            val mapped = headers.mapNotNull { (index, header) ->
                checkpoint()
                val target = columns[header] ?: if (columns.isEmpty()) normalize(header) else null
                val column = declaredColumns[target]
                if (target != null && column == null) {
                    sink.warning(
                        "MANTRA-DATA-XLSX-COLUMN",
                        "${path.fileName}: ignoring unknown column '$header'",
                        nodeId = input,
                    )
                }
                column?.let { Triple(index, it.name, it.type) }
            }
            if (mapped.isEmpty()) return invalid("Region has no mapped table columns")
            if (mapped.map { it.second }.distinct().size != mapped.size) {
                return invalid("Headers map to duplicate target columns")
            }
            val records = mutableListOf<Value>()
            for (rowIndex in rectangle.firstRow + 1..rectangle.lastRow) {
                checkpoint()
                val row = selected.getRow(rowIndex)
                if (mapped.none { (index, _, _) ->
                        checkpoint()
                        present(row?.getCell(index))
                    }
                ) {
                    continue
                }
                onRow()
                val fields = linkedMapOf<Value, Value>()
                mapped.forEach { (index, name, type) ->
                    checkpoint()
                    val value = value(row?.getCell(index), type, wb.isDate1904, records.size, name, sink)
                    if (value != null) fields[Value.Kw(name)] = value
                }
                records += Value.MapV(fields)
            }
            if (sink.hasErrors) return emptyMap()
            return mapOf(input to Value.Vec(records))
        }
    }

    private fun headers(selected: Sheet, rectangle: CellRangeAddress): Map<Int, String>? {
        val result = linkedMapOf<Int, String>()
        val names = hashSetOf<String>()
        for (index in rectangle.firstColumn..rectangle.lastColumn) {
            checkpoint()
            val cell = selected.getRow(rectangle.firstRow)?.getCell(index)
            if (cell?.cellType != CellType.STRING) return null
            val name = cell.stringCellValue.trim()
            if (name.isEmpty() || !names.add(name)) return null
            result[index] = name
        }
        return result
    }

    private fun present(cell: Cell?): Boolean {
        if (cell == null) return false
        if (cell.cellType == CellType.FORMULA && !(cell as XSSFCell).ctCell.isSetV) return true
        val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
        if (cell.cellType == CellType.FORMULA && (cell as XSSFCell).ctCell.v.isNullOrEmpty() &&
            type != CellType.STRING
        ) {
            return true
        }
        return type != CellType.BLANK && type != CellType._NONE &&
            (type != CellType.STRING || cell.stringCellValue.isNotEmpty())
    }

    private fun value(
        cell: Cell?,
        expected: ValueType,
        date1904: Boolean,
        row: Int,
        column: String,
        sink: DiagnosticSink,
    ): Value? {
        if (cell == null) return null
        fun invalid(message: String): Value? {
            sink.error(
                "MANTRA-DATA-XLSX-CELL",
                "${path.fileName}/$sheet!${cell.address}: $message",
                nodeId = input,
                rowIndex = row,
                column = column,
            )
            return null
        }
        if (cell.cellType == CellType.FORMULA && (
                !(cell as XSSFCell).ctCell.isSetV ||
                    cell.ctCell.v.isNullOrEmpty() && cell.cachedFormulaResultType != CellType.STRING
                )
        ) {
            return invalid("Formula has no stored result; recalculate and save the source workbook")
        }
        val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
        return when (type) {
            CellType.ERROR -> invalid("Excel error ${FormulaError.forInt(cell.errorCellValue).string}")
            CellType.NUMERIC -> {
                val number = cell.numericCellValue
                if (!number.isFinite()) {
                    invalid("Numeric input is not finite")
                } else if (expected == ValueType.DATE) {
                    val latest = DateUtil.getExcelDate(java.time.LocalDate.of(9999, 12, 31), date1904)
                    if (!DateUtil.isValidExcelDate(number) || number > latest || number % 1.0 != 0.0 ||
                        (!date1904 && number == 60.0)
                    ) {
                        invalid("Invalid whole-date serial")
                    } else {
                        Value.Date(DateUtil.getLocalDateTime(number, date1904).toLocalDate())
                    }
                } else {
                    Value.Num(
                        BigDecimal.valueOf(number).stripTrailingZeros().let {
                            if (it.scale() <
                                0
                            ) {
                                it.setScale(0)
                            } else {
                                it
                            }
                        },
                    )
                }
            }
            CellType.BOOLEAN -> Value.Bool(cell.booleanCellValue)
            CellType.STRING -> cell.stringCellValue.takeIf { it.isNotEmpty() }?.let {
                if (expected == ValueType.KEYWORD) Value.Kw(it.removePrefix(":")) else Value.Text(it)
            }
            CellType.BLANK, CellType._NONE -> null
            else -> invalid("Unsupported cell type $type")
        }
    }

    private fun normalize(name: String) = name.trim().lowercase().replace(Regex("[\\s_]+"), "-")

    companion object {
        const val MAX_REGION_CELLS = 50_000L

        private fun rectangle(source: String): CellRangeAddress? {
            if (source.length > 64 ||
                !Regex("""(?i)\$?[A-Z]{1,3}\$?[1-9]\d{0,6}:\$?[A-Z]{1,3}\$?[1-9]\d{0,6}""").matches(source)
            ) {
                return null
            }
            val ends = source.split(':').map { runCatching { CellReference(it) }.getOrNull() ?: return null }
            val first = ends[0]
            val last = ends[1]
            if (first.row > last.row || first.col > last.col || first.row < 0 || first.col < 0 ||
                last.row >= SpreadsheetVersion.EXCEL2007.maxRows || last.col >= SpreadsheetVersion.EXCEL2007.maxColumns
            ) {
                return null
            }
            return CellRangeAddress(first.row, last.row, first.col.toInt(), last.col.toInt())
        }
    }
}
