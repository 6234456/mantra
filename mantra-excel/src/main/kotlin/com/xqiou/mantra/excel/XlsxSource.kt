package com.xqiou.mantra.excel

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.data.DataSource
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads input values back from a workbook through its defined names ([ExcelNames] convention):
 * a workbook exported by Mantra can be edited in Excel and re-imported, and any other workbook
 * that defines the same names can serve as a data source.
 */
class XlsxSource(
    private val path: Path,
    private val capturedBytes: ByteArray? = null,
    private val onRow: () -> Unit = {},
    private val checkpoint: () -> Unit = {},
) : DataSource {
    override val description: String = "xlsx:${path.fileName}"

    override fun read(schema: Schema, sink: DiagnosticSink): Map<String, Value> {
        val workbook = (capturedBytes?.inputStream() ?: Files.newInputStream(path)).use { XSSFWorkbook(it) }
        workbook.use { wb ->
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            val names = wb.allNames.associateBy { it.nameName.lowercase() }

            fun valueOf(name: String): Value? {
                checkpoint()
                val defined = names[name.lowercase()] ?: return null
                val area =
                    runCatching { AreaReference(defined.refersToFormula, SpreadsheetVersion.EXCEL2007) }.getOrNull()
                        ?: return null
                if (!area.isSingleCell) return null
                val ref: CellReference = area.firstCell
                val cell = wb.getSheet(ref.sheetName)?.getRow(ref.row)?.getCell(ref.col.toInt()) ?: return Value.Nil
                val type = if (cell.cellType == CellType.FORMULA) evaluator.evaluateFormulaCell(cell) else cell.cellType
                return when (type) {
                    CellType.NUMERIC -> Value.Num(
                        BigDecimal.valueOf(cell.numericCellValue).stripTrailingZeros().let {
                            if (it.scale() <
                                0
                            ) {
                                it.setScale(0)
                            } else {
                                it
                            }
                        },
                    )
                    CellType.BOOLEAN -> Value.Bool(cell.booleanCellValue)
                    CellType.STRING -> cell.stringCellValue.takeIf { it.isNotEmpty() }?.let(Value::Text) ?: Value.Nil
                    else -> Value.Nil
                }
            }

            val result = linkedMapOf<String, Value>()
            schema.inputs.forEach { input ->
                val base = ExcelNames.sanitize(input.id)
                when {
                    input.type == ValueType.TABLE -> {
                        val prefix = (base + ExcelNames.SEPARATOR).lowercase()
                        val rows = linkedMapOf<String, LinkedHashMap<Value, Value>>()
                        names.values.map { it.nameName }.filter { it.lowercase().startsWith(prefix) }.forEach { name ->
                            val parts = name.substring(prefix.length).split(ExcelNames.SEPARATOR)
                            if (parts.size != 2) return@forEach
                            val column =
                                input.columns.firstOrNull {
                                    ExcelNames.sanitize(it.name).equals(parts[1], ignoreCase = true)
                                }
                                    ?: return@forEach
                            val raw = valueOf(name) ?: return@forEach
                            val value = if (column.type == ValueType.KEYWORD &&
                                raw is Value.Text
                            ) {
                                Value.Kw(raw.value)
                            } else {
                                raw
                            }
                            rows.getOrPut(parts[0]) {
                                onRow()
                                linkedMapOf()
                            }[Value.Kw(column.name)] = value
                        }
                        if (rows.isNotEmpty()) result[input.id] = Value.Vec(rows.values.map { Value.MapV(it) })
                    }
                    else -> {
                        // Member cells (`id__A`) take precedence: for dimensioned inputs the plain name is a range.
                        val prefix = (base + ExcelNames.SEPARATOR).lowercase()
                        val members = names.values.map { it.nameName }.filter {
                            it.lowercase().startsWith(prefix) &&
                                ExcelNames.SEPARATOR !in it.substring(prefix.length)
                        }
                        if (members.isNotEmpty()) {
                            result[input.id] = Value.MapV(
                                LinkedHashMap<Value, Value>().apply {
                                    members.forEach { name ->
                                        valueOf(name)?.takeIf {
                                            it != Value.Nil
                                        }?.let { put(Value.Kw(name.substring(prefix.length)), it) }
                                    }
                                },
                            )
                        } else if (names.containsKey(base.lowercase())) {
                            valueOf(base)?.let { result[input.id] = it }
                        }
                    }
                }
            }
            if (result.isEmpty()) sink.warning("MANTRA-DATA-XLSX", "${path.fileName}: no named input cells found")
            return result
        }
    }
}
