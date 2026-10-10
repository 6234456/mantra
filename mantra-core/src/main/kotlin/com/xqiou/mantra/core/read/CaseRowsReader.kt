package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.InputCellLocation
import com.xqiou.mantra.core.model.Value
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** A case-input-only literal spelling. No expression evaluation or generated source spans. */
internal object CaseRowsReader {
    data class Result(val value: Value.Vec, val cells: List<InputCellLocation>)

    fun read(document: Document, form: DslForm.Sequence, sink: DiagnosticSink, input: String): Result? {
        val header = form.values.getOrNull(1) as? DslForm.Sequence
        if (header?.kind != DslFormSequenceKind.VECTOR || header.values.isEmpty()) {
            sink.error(
                "MANTRA-CASE-ROWS-HEADER",
                "(rows ...) requires a nonempty vector of column keywords",
                document.location(form.values.getOrNull(1) ?: form),
                nodeId = input,
            )
            return null
        }
        val columns = linkedSetOf<String>()
        var valid = true
        header.values.forEach { column ->
            val key = column.keyword
            if (key == null || !columns.add(key)) {
                sink.error(
                    "MANTRA-CASE-ROWS-COLUMN",
                    "Rows columns must be unique keywords",
                    document.location(column),
                    nodeId = input,
                    column = key,
                )
                valid = false
            }
        }
        if (!valid) return null
        val values = mutableListOf<Value>()
        val cells = mutableListOf(InputCellLocation(emptyList(), null, null, document.location(form)))
        form.values.drop(2).forEachIndexed { index, rowForm ->
            val row = (rowForm as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.VECTOR }
            if (row == null || row.values.size != columns.size) {
                sink.error(
                    "MANTRA-CASE-ROWS-ROW",
                    "Rows data must be vectors with exactly ${columns.size} cells",
                    document.location(rowForm),
                    nodeId = input,
                    rowIndex = index,
                )
                valid = false
            } else {
                cells += InputCellLocation(emptyList(), index, null, document.location(row))
                val entries = linkedMapOf<Value, Value>()
                columns.zip(row.values).forEach { (column, cell) ->
                    val value = document.literal(cell, sink, "inputs :$input row $index :$column")
                    if (value == null) {
                        valid = false
                    } else {
                        entries[Value.Kw(column)] = value
                        cells += InputCellLocation(emptyList(), index, column, document.location(cell))
                    }
                }
                values += Value.MapV(entries)
            }
        }
        return if (valid) Result(Value.Vec(values), cells) else null
    }
}
