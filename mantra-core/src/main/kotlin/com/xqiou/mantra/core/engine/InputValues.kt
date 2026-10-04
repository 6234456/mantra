package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.Coord
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Typed input conversion and source positions, with actual row/column scan accounting. */
internal class InputValues(
    private val plan: () -> CalculationPlan,
    private val sink: DiagnosticSink,
    private val context: () -> RunContext,
    private val coordText: (List<String>, Coord) -> String,
) {
    fun location(
        id: String,
        declaration: SourceLocation,
        coord: Coord = emptyList(),
        rowIndex: Int? = null,
        column: String? = null,
    ): SourceLocation {
        context().checkpoint()
        val cells = plan().case.inputCells[id].orEmpty()
        return cells.firstOrNull {
            context().charge(RunCounter.HOST_SCANS)
            it.coord == coord && it.rowIndex == rowIndex &&
                it.column == column
        }?.location
            ?: cells.firstOrNull {
                context().charge(RunCounter.HOST_SCANS)
                it.coord == coord && it.rowIndex == rowIndex &&
                    it.column == null
            }?.location
            ?: plan().case.inputLocations[id] ?: declaration
    }

    fun convert(raw: Value, decl: InputDecl, coord: Coord, rowIndex: Int? = null, column: String? = null): Value {
        context().charge(RunCounter.HOST_SCANS)
        fun fail(message: String): Value {
            sink.error(
                "MANTRA-INPUT-TYPE",
                "Input ${decl.id}${coordText(emptyList(), coord)}: $message",
                location(decl.id, decl.location, coord, rowIndex, column),
                decl.id,
                coord,
                rowIndex = rowIndex,
                column = column,
            )
            return Value.Nil
        }
        return when (decl.type) {
            ValueType.DECIMAL -> raw as? Value.Num ?: fail("expected a number, got $raw")
            ValueType.INTEGER -> (raw as? Value.Num)?.takeIf { it.value.stripTrailingZeros().scale() <= 0 }
                ?: fail("expected an integer, got $raw")
            ValueType.BOOLEAN -> raw as? Value.Bool ?: fail("expected true or false, got $raw")
            ValueType.TEXT -> raw as? Value.Text ?: fail("expected a string, got $raw")
            ValueType.KEYWORD -> {
                // Text from JSON/CSV sources is accepted as keyword name.
                val keyword =
                    raw as? Value.Kw ?: (raw as? Value.Text)?.let { Value.Kw(it.value.removePrefix(":")) }
                        ?: return fail("expected a keyword, got $raw")
                if (decl.options.isNotEmpty() &&
                    keyword.name !in decl.options
                ) {
                    fail("`$keyword` is not one of ${decl.options.keys.joinToString { ":$it" }}")
                } else {
                    keyword
                }
            }
            ValueType.DATE -> when (raw) {
                is Value.Date -> raw
                is Value.Text -> try {
                    Value.Date(LocalDate.parse(raw.value))
                } catch (_: DateTimeParseException) {
                    fail("expected an ISO date yyyy-mm-dd")
                }
                else -> fail("expected a date string")
            }
            ValueType.TABLE -> {
                val rows = raw as? Value.Vec ?: return fail("expected a vector of row maps")
                Value.Vec(rows.items.mapIndexed { index, row -> convertRow(row, decl, coord, index) })
            }
            ValueType.ANY -> raw
        }
    }

    private fun convertRow(row: Value, decl: InputDecl, coord: Coord, index: Int): Value {
        context().charge(RunCounter.HOST_SCANS)
        val map = row as? Value.MapV
        if (map == null) {
            sink.error(
                "MANTRA-INPUT-TYPE",
                "Row ${index + 1} of ${decl.id} must be a map",
                location(decl.id, decl.location, coord, index),
                decl.id,
                coord,
                rowIndex = index,
            )
            return Value.MapV(emptyMap())
        }
        val byName = map.entries.entries.associate { (k, v) ->
            context().charge(RunCounter.HOST_SCANS)
            (
                (k as? Value.Kw)?.name ?: (k as? Value.Text)?.value
                    ?: k.toString()
                ) to
                v
        }
        byName.keys.filter { key ->
            decl.columns.none {
                context().charge(RunCounter.HOST_SCANS)
                it.name == key
            }
        }.forEach {
            sink.error(
                "MANTRA-INPUT-COLUMN",
                "Row ${index + 1} of ${decl.id} has unknown column :$it",
                location(decl.id, decl.location, coord, index, it),
                decl.id,
                coord,
                rowIndex = index,
                column = it,
            )
        }
        val converted = linkedMapOf<Value, Value>()
        decl.columns.forEach { column ->
            context().charge(RunCounter.HOST_SCANS)
            val value = byName[column.name]
            val columnDecl = decl.copy(
                type = column.type,
                options = emptyMap(),
                optional = column.optional,
            )
            converted[Value.Kw(column.name)] = when {
                value != null && value != Value.Nil -> convert(value, columnDecl, coord, index, column.name)
                column.optional -> Value.Nil
                column.type.isNumeric -> Value.ZERO
                else -> {
                    sink.error(
                        "MANTRA-INPUT-COLUMN",
                        "Row ${index + 1} of ${decl.id} is missing column :${column.name}",
                        location(decl.id, decl.location, coord, index, column.name),
                        decl.id,
                        coord,
                        rowIndex = index,
                        column = column.name,
                    )
                    Value.Nil
                }
            }
        }
        return Value.MapV(converted)
    }
}
