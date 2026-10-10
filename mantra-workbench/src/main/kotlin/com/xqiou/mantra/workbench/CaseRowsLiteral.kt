package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.literal
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** The authored compact table stays available for minimal cell patches. Structural edits may
 * expand it to ordinary records, which can represent a missing cell independently of nil. */
internal class CaseRowsLiteral private constructor(
    val columns: List<String>,
    val rows: List<DslForm.Sequence>,
    val value: Value.Vec,
) {
    fun cell(selector: String, column: String, keyColumn: String?): DslForm {
        val index = if (keyColumn == null) {
            selector.toIntOrNull() ?: -1
        } else {
            value.items.indexOfFirst { row ->
                val key = (row as Value.MapV).entries[Value.Kw(keyColumn)]
                when (key) {
                    is Value.Kw -> key.name
                    is Value.Text -> key.value
                    is Value.Num -> key.value.toPlainString()
                    else -> null
                } == selector
            }
        }
        require(index in rows.indices) { "Table row was not found" }
        val position = columns.indexOf(column)
        require(position >= 0) { "Table column was not found" }
        return rows[index].values[position]
    }

    companion object {
        fun read(document: Document, form: DslForm.Sequence): CaseRowsLiteral? {
            if (form.listHead != "rows") return null
            val header = (form.values.getOrNull(1) as? DslForm.Sequence)
                ?.takeIf { it.kind == DslFormSequenceKind.VECTOR }
            require(header != null && header.values.isNotEmpty()) { "Rows require a nonempty column vector" }
            val columns = header.values.map { it.keyword ?: error("Rows columns must be keywords") }
            require(columns.distinct().size == columns.size) { "Rows columns must be unique" }
            val sink = DiagnosticSink()
            val rows = form.values.drop(2).map { row ->
                (row as? DslForm.Sequence)?.takeIf {
                    it.kind == DslFormSequenceKind.VECTOR && it.values.size == columns.size
                } ?: error("Rows data must match the column vector")
            }
            val values = rows.map { row ->
                Value.MapV(
                    columns.zip(row.values).associateTo(linkedMapOf()) { (column, cell) ->
                        Value.Kw(column) to (document.literal(cell, sink, "rows cell") ?: error("Invalid rows literal"))
                    },
                )
            }
            require(!sink.hasErrors) { "Rows contain invalid literal cells" }
            return CaseRowsLiteral(columns, rows, Value.Vec(values))
        }
    }
}
