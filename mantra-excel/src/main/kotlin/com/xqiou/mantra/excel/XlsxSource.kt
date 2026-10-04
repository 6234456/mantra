package com.xqiou.mantra.excel

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.data.DataSource
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import org.apache.poi.ooxml.POIXMLException
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Imports declared input names. Invalid containers, cells and coordinates are technical findings. */
class XlsxSource(
    private val path: Path,
    private val capturedBytes: ByteArray? = null,
    private val onRow: () -> Unit = {},
    private val checkpoint: () -> Unit = {},
) : DataSource {
    override val description: String = "xlsx:${path.fileName}"

    override fun read(schema: Schema, sink: DiagnosticSink): Map<String, Value> {
        val bytes = try {
            val captured = capturedBytes
            if (captured != null) {
                if (captured.size > XlsxContainerPreflight.MAX_COMPRESSED_BYTES) validateContainer(captured, checkpoint)
                captured.copyOf()
            } else {
                Files.newInputStream(path).use { XlsxContainerPreflight.capture(it, checkpoint) }
            }.also { validateContainer(it, checkpoint) }
        } catch (error: MantraException) {
            if (error.runFailure != null) throw error
            sink.addAll(error.diagnostics)
            return emptyMap()
        }
        checkpoint()
        val workbook = try {
            bytes.inputStream().use { XSSFWorkbook(it) }
        } catch (error: java.io.IOException) {
            sink.error("MANTRA-DATA-XLSX-CONTAINER", "${path.fileName}: ${error.message}")
            return emptyMap()
        } catch (error: POIXMLException) {
            sink.error("MANTRA-DATA-XLSX-CONTAINER", "${path.fileName}: ${error.message}")
            return emptyMap()
        }
        workbook.use { wb ->
            val evaluator = wb.creationHelper.createFormulaEvaluator()
            val names = wb.allNames.groupBy { it.nameName.lowercase() }

            fun valueOf(
                name: String,
                expected: ValueType,
                inputId: String,
                coord: List<String> = emptyList(),
                row: Int? = null,
                column: String? = null,
            ): Value? {
                checkpoint()
                fun invalid(code: String, message: String): Value? {
                    sink.error(
                        code,
                        "${path.fileName}/$name: $message",
                        nodeId = inputId,
                        coord = coord,
                        rowIndex = row,
                        column = column,
                    )
                    return null
                }
                val matches = names[name.lowercase()] ?: return null
                val defined =
                    matches.singleOrNull() ?: return invalid("MANTRA-DATA-XLSX-NAME", "Ambiguous named input cell")
                val area =
                    runCatching { AreaReference(defined.refersToFormula, SpreadsheetVersion.EXCEL2007) }.getOrNull()
                        ?: return invalid("MANTRA-DATA-XLSX-NAME", "Input name must refer to one local cell")
                if (!area.isSingleCell) {
                    return invalid(
                        "MANTRA-DATA-XLSX-NAME",
                        "Input name must refer to one local cell",
                    )
                }
                val ref: CellReference = area.firstCell
                val sheet =
                    wb.getSheet(ref.sheetName)
                        ?: return invalid("MANTRA-DATA-XLSX-NAME", "Named input sheet is missing")
                val cell = sheet.getRow(ref.row)?.getCell(ref.col.toInt()) ?: return Value.Nil
                return try {
                    val type = if (cell.cellType ==
                        CellType.FORMULA
                    ) {
                        evaluator.evaluateFormulaCell(cell)
                    } else {
                        cell.cellType
                    }
                    when (type) {
                        CellType.ERROR -> invalid(
                            "MANTRA-DATA-XLSX-CELL",
                            "Excel error ${FormulaError.forInt(cell.errorCellValue).string}",
                        )
                        CellType.NUMERIC -> if (expected == ValueType.DATE) {
                            val number = cell.numericCellValue
                            if (!DateUtil.isValidExcelDate(number) || !number.isFinite() || number % 1.0 != 0.0 ||
                                (!wb.isDate1904 && number == 60.0)
                            ) {
                                invalid("MANTRA-DATA-XLSX-CELL", "Invalid whole-date serial")
                            } else {
                                Value.Date(DateUtil.getLocalDateTime(number, wb.isDate1904).toLocalDate())
                            }
                        } else {
                            Value.Num(
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
                        }
                        CellType.BOOLEAN -> Value.Bool(cell.booleanCellValue)
                        CellType.STRING -> cell.stringCellValue.takeIf { it.isNotEmpty() }?.let {
                            if (expected == ValueType.KEYWORD) Value.Kw(it) else Value.Text(it)
                        } ?: Value.Nil
                        CellType.BLANK, CellType._NONE -> Value.Nil
                        else -> invalid("MANTRA-DATA-XLSX-CELL", "Unsupported input cell type $type")
                    }
                } catch (error: MantraException) {
                    throw error
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RuntimeException) {
                    invalid("MANTRA-DATA-XLSX-CELL", "Input cell could not be evaluated: ${error.message}")
                }
            }

            val result = linkedMapOf<String, Value>()
            // A field can repeat its explicit input declaration; the planner keeps the first declaration.
            val inputs = schema.inputs.distinctBy { it.id }
            inputs.groupBy {
                XlsxNamedCoordinates.fragment(it.id).lowercase()
            }.values.filter { it.size > 1 }.forEach { aliases ->
                sink.error(
                    "MANTRA-DATA-XLSX-NAME",
                    "Input names collide after Excel sanitization: ${aliases.joinToString {
                        it.id
                    }}",
                )
            }
            if (sink.hasErrors) return emptyMap()
            // Tables populate member domains before dimensioned named cells are decoded.
            inputs.filter { it.type == ValueType.TABLE }.forEach { input ->
                val prefix = (XlsxNamedCoordinates.fragment(input.id) + ExcelNames.SEPARATOR).lowercase()
                val rows = linkedMapOf<String, LinkedHashMap<Value, Value>>()
                XlsxNamedCoordinates.orderedTableNames(wb, names, input, checkpoint, sink).forEach { name ->
                    checkpoint()
                    val parts = name.substring(prefix.length).split(ExcelNames.SEPARATOR)
                    val column = if (parts.size == 2) {
                        input.columns.singleOrNull {
                            XlsxNamedCoordinates.fragment(it.name).equals(parts[1], true)
                        }
                    } else {
                        null
                    }
                    if (column == null) {
                        sink.error(
                            "MANTRA-DATA-XLSX-NAME",
                            "${path.fileName}/$name: Invalid table input name",
                            nodeId = input.id,
                        )
                        return@forEach
                    }
                    val index = rows.keys.indexOf(parts[0]).takeIf { it >= 0 } ?: rows.size
                    val fields = rows.getOrPut(parts[0]) {
                        onRow()
                        linkedMapOf()
                    }
                    val value =
                        valueOf(name, column.type, input.id, row = index, column = column.name) ?: return@forEach
                    fields[Value.Kw(column.name)] = value
                }
                if (rows.isNotEmpty()) result[input.id] = Value.Vec(rows.values.map { Value.MapV(it) })
            }
            val coordinates = XlsxNamedCoordinates(schema, { result }, sink)
            inputs.filter { it.type != ValueType.TABLE }.forEach { input ->
                val base = ExcelNames.sanitize(input.id)
                val prefix = (XlsxNamedCoordinates.fragment(input.id) + ExcelNames.SEPARATOR).lowercase()
                val members = names.keys.filter { it.startsWith(prefix) }
                if (members.isNotEmpty()) {
                    val seen = hashSetOf<List<String>>()
                    members.forEach { name ->
                        val coordinate = coordinates.decode(input.id, name.substring(prefix.length)) ?: return@forEach
                        if (!seen.add(coordinate)) {
                            sink.error(
                                "MANTRA-DATA-XLSX-NAME",
                                "Duplicate named input coordinate",
                                nodeId = input.id,
                                coord = coordinate,
                            )
                            return@forEach
                        }
                        valueOf(name, input.type, input.id, coordinate)?.let { value ->
                            result[input.id] = XlsxNamedCoordinates.insert(result[input.id], coordinate, value)
                        }
                    }
                } else if (names.containsKey(base.lowercase())) {
                    if (coordinates.axes(input.id).isNotEmpty()) {
                        sink.error(
                            "MANTRA-DATA-XLSX-NAME",
                            "Dimensioned input has no complete named member cells",
                            nodeId = input.id,
                        )
                    } else {
                        valueOf(base, input.type, input.id)?.let { result[input.id] = it }
                    }
                }
            }
            if (sink.hasErrors) return emptyMap()
            if (result.isEmpty()) sink.warning("MANTRA-DATA-XLSX", "${path.fileName}: no named input cells found")
            return result
        }
    }

    companion object {
        /** Before POI: 10 MiB compressed, 1000 entries, 16 MiB per entry and 64 MiB total expanded. */
        fun validateContainer(bytes: ByteArray, checkpoint: () -> Unit = {}) =
            XlsxContainerPreflight.verify(bytes, checkpoint)
    }
}
