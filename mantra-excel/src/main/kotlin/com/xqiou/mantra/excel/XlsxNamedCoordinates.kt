package com.xqiou.mantra.excel

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.data.DataSources
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.PeriodSpec
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.ss.usermodel.Name
import org.apache.poi.ss.util.AreaReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/** Recover original declared keys in the writer's canonical axis order. */
internal class XlsxNamedCoordinates(
    private val schema: Schema,
    private val imported: () -> Map<String, Value>,
    private val sink: DiagnosticSink,
) {
    private val declared = DataSources.inputDimensions(schema)
    fun axes(inputId: String): List<String> =
        schema.dimensions.map { it.id }.filter { it in declared[inputId].orEmpty() }

    fun decode(inputId: String, encoded: String): List<String>? {
        val dimensions = axes(inputId)
        val parts = encoded.split(ExcelNames.SEPARATOR)
        if (dimensions.isEmpty() ||
            parts.size != dimensions.size
        ) {
            return invalid(inputId, "Named member cell does not match all declared axes")
        }
        return parts.zip(dimensions).map { (part, dimension) ->
            val candidates = keys(dimension).filter { fragment(it).equals(part, true) }
            if (candidates.size !=
                1
            ) {
                return invalid(inputId, "Named member key is unknown or ambiguous for $dimension: $part")
            }
            candidates.single()
        }
    }

    private fun keys(id: String): List<String> {
        val dimension = schema.dimensions.single { it.id == id }
        return when (val periods = dimension.periods) {
            is PeriodSpec.Generated -> (1..periods.count).map { "P$it" }
            is PeriodSpec.Listed -> periods.entries.map { it.key }
            null -> if (dimension.fromTable == null) {
                dimension.members.map { it.key }
            } else {
                (imported()[dimension.fromTable] as? Value.Vec)?.items.orEmpty().mapNotNull { row ->
                    when (val value = (row as? Value.MapV)?.entries?.get(Value.Kw(dimension.keyColumn))) {
                        is Value.Kw -> value.name
                        is Value.Text -> value.value
                        is Value.Num -> value.value.stripTrailingZeros().toPlainString()
                        else -> null
                    }
                }
            }
        }
    }

    private fun invalid(inputId: String, message: String): List<String>? {
        sink.error("MANTRA-DATA-XLSX-NAME", message, nodeId = inputId)
        return null
    }

    companion object {
        /** Defined-name enumeration has no row order; recover records from their actual table cells. */
        fun orderedTableNames(
            workbook: XSSFWorkbook,
            names: Map<String, List<Name>>,
            input: InputDecl,
            checkpoint: () -> Unit,
            sink: DiagnosticSink,
        ): List<String> {
            data class Cell(val name: String, val member: String, val column: Int, val sheet: Int, val row: Int)
            val prefix = (fragment(input.id) + ExcelNames.SEPARATOR).lowercase()
            val cells = mutableListOf<Cell>()
            fun invalid(name: String, reason: String) {
                sink.error("MANTRA-DATA-XLSX-NAME", "$name: $reason", nodeId = input.id)
            }
            var valid = true
            names.filterKeys { it.startsWith(prefix) }.forEach { (name, matches) ->
                checkpoint()
                val parts = name.substring(prefix.length).split(ExcelNames.SEPARATOR)
                val columns = if (parts.size == 2) {
                    input.columns.withIndex().filter {
                        fragment(it.value.name).equals(parts[1], true)
                    }
                } else {
                    emptyList()
                }
                val column = columns.singleOrNull()
                val defined = matches.singleOrNull()
                val area = defined?.let {
                    runCatching { AreaReference(it.refersToFormula, SpreadsheetVersion.EXCEL2007) }.getOrNull()
                }
                val sheet = area?.firstCell?.sheetName?.let(workbook::getSheetIndex) ?: -1
                if (column == null || area == null || !area.isSingleCell || sheet < 0) {
                    valid = false
                    invalid(name, "Table input name requires one declared column and one local cell")
                } else {
                    cells += Cell(name, parts[0], column.index, sheet, area.firstCell.row)
                }
            }
            val records = cells.groupBy { it.member }.values.toList()
            records.forEach { record ->
                if (record.map { it.sheet to it.row }.distinct().size != 1) {
                    valid = false
                    invalid(record.first().name, "Columns of one table record must share a worksheet and physical row")
                }
            }
            records.groupBy { it.first().sheet to it.first().row }.values.filter { it.size > 1 }.forEach { aliases ->
                valid = false
                invalid(
                    aliases.first().first().name,
                    "Different table records share a physical row; their order is ambiguous",
                )
            }
            if (!valid) return emptyList()
            return records.sortedWith(compareBy({ it.first().sheet }, { it.first().row })).flatMap { record ->
                record.sortedBy { it.column }.map { it.name }
            }
        }

        /** Components are sanitized inside whole names: period P1 does not become standalone P1_. */
        fun fragment(raw: String): String =
            ExcelNames.sanitize("x${ExcelNames.SEPARATOR}$raw").removePrefix("x${ExcelNames.SEPARATOR}")
        fun insert(previous: Value?, coordinate: List<String>, value: Value): Value {
            if (coordinate.isEmpty()) return value
            val key = Value.Kw(coordinate.first())
            return Value.MapV(
                LinkedHashMap((previous as? Value.MapV)?.entries.orEmpty()).apply {
                    put(key, insert(get(key), coordinate.drop(1), value))
                },
            )
        }
    }
}
