package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.number
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** Source-preserving edits to the case DSL. Each operation reparses the changed source, so spans
 * always refer to the current version. Semantic checks belong to the caller before a write. */
object CaseTextEditor {
    sealed interface Operation {
        data class SetInput(val id: String, val value: Value, val coord: List<String> = emptyList()) : Operation
        data class ClearInput(val id: String, val coord: List<String> = emptyList()) : Operation
        data class SetCell(val table: String, val row: String, val column: String, val value: Value, val keyColumn: String?) : Operation
        data class ClearCell(val table: String, val row: String, val column: String, val keyColumn: String?) : Operation
        data class SetParam(val id: String, val value: Value) : Operation
        data class ResetParam(val id: String) : Operation
        data class InsertRow(val table: String, val row: Value.MapV, val index: Int? = null) : Operation
        data class UpdateRow(val table: String, val index: Int, val row: Value.MapV) : Operation
        data class DeleteRow(val table: String, val index: Int) : Operation
        data class MoveRow(val table: String, val from: Int, val to: Int) : Operation
        data class AddExtension(val slot: String, val id: String, val title: String, val formula: String) : Operation
        data class UpdateExtension(val slot: String, val id: String, val title: String, val formula: String) : Operation
        data class RemoveExtension(val slot: String, val id: String) : Operation
        data class BindFormula(val id: String, val formula: String) : Operation
        data class UnbindFormula(val id: String) : Operation
        data class SetMeta(val key: String, val text: String) : Operation
        data class SetBindings(val parameters: List<String>?, val layout: String?, val clearLayout: Boolean = false) : Operation
    }

    private val identifier = Regex("[A-Za-z][A-Za-z0-9_-]*[?!*]?")
    private val metadata = setOf("title", "subject", "period", "prepared-by", "reviewed-by", "date", "reference")

    fun apply(source: String, operations: List<Operation>): String = operations.fold(source) { text, op -> applyOne(text, op) }

    private fun applyOne(text: String, op: Operation): String {
        val doc = read(text)
        val root = doc.root as DslForm.Sequence
        fun form(head: String, id: String? = null): DslForm.Sequence? = root.values.drop(2)
            .filterIsInstance<DslForm.Sequence>().firstOrNull { it.listHead == head && (id == null || it.values.getOrNull(1)?.symbol == id) }
        return when (op) {
            is Operation.SetInput -> {
                requireId(op.id)
                op.coord.forEach(::requireId)
                putData(doc, root, form("inputs"), "inputs", op.id, op.coord, literal(op.value))
            }
            is Operation.ClearInput -> {
                requireId(op.id)
                op.coord.forEach(::requireId)
                removeData(doc, form("inputs"), op.id, op.coord)
            }
            is Operation.SetCell -> editCell(doc, form("inputs"), op.table, op.row, op.column, op.keyColumn, literal(op.value))
            is Operation.ClearCell -> editCell(doc, form("inputs"), op.table, op.row, op.column, op.keyColumn, null)
            is Operation.SetParam -> { requireId(op.id); putData(doc, root, form("params"), "params", op.id, emptyList(), literal(op.value)) }
            is Operation.ResetParam -> { requireId(op.id); removeData(doc, form("params"), op.id, emptyList()) }
            is Operation.InsertRow -> editRows(doc, root, form("inputs"), op.table, RowEdit.Insert(op.row, op.index))
            is Operation.UpdateRow -> editRows(doc, root, form("inputs"), op.table, RowEdit.Update(op.index, op.row))
            is Operation.DeleteRow -> editRows(doc, root, form("inputs"), op.table, RowEdit.Delete(op.index))
            is Operation.MoveRow -> editRows(doc, root, form("inputs"), op.table, RowEdit.Move(op.from, op.to))
            is Operation.AddExtension -> {
                requireId(op.slot); requireId(op.id)
                val line = line(op.id, op.title, op.formula)
                val existing = form("extend", op.slot)
                if (existing == null) insertForm(doc, root, "(extend ${op.slot}\n    $line)")
                else {
                    require(existing.values.drop(2).none { (it as? DslForm.Sequence)?.values?.getOrNull(1)?.symbol == op.id }) { "Extension line already exists" }
                    insertBeforeClose(doc, existing, line)
                }
            }
            is Operation.UpdateExtension -> {
                requireId(op.slot); requireId(op.id)
                val existing = form("extend", op.slot) ?: error("Extension slot has no case form")
                val old = existing.values.drop(2).firstOrNull { (it as? DslForm.Sequence)?.values?.getOrNull(1)?.symbol == op.id }
                    ?: error("Extension line was not found")
                replace(doc, old, line(op.id, op.title, op.formula))
            }
            is Operation.RemoveExtension -> {
                requireId(op.slot); requireId(op.id)
                val existing = form("extend", op.slot) ?: error("Extension slot has no case form")
                val old = existing.values.drop(2).firstOrNull { (it as? DslForm.Sequence)?.values?.getOrNull(1)?.symbol == op.id }
                    ?: error("Extension line was not found")
                delete(doc, old)
            }
            is Operation.BindFormula -> {
                requireId(op.id); validFormula(op.formula)
                val old = form("bind", op.id)
                if (old == null) insertForm(doc, root, "(bind ${op.id} ${op.formula})")
                else replace(doc, old.values.getOrNull(2) ?: error("Invalid bind form"), op.formula)
            }
            is Operation.UnbindFormula -> { requireId(op.id); form("bind", op.id)?.let { delete(doc, it) } ?: text }
            is Operation.SetMeta -> {
                require(op.key in metadata) { "Metadata key is not editable" }
                putMeta(doc, root, op.key, literal(Value.Text(op.text)))
            }
            is Operation.SetBindings -> {
                var current = text
                op.parameters?.let { ids ->
                    current = putMeta(read(current), read(current).root as DslForm.Sequence, "parameters", literal(Value.Vec(ids.map(Value::Text))))
                }
                op.layout?.let { id ->
                    current = putMeta(read(current), read(current).root as DslForm.Sequence, "layout", literal(Value.Text(id)))
                }
                if (op.clearLayout) {
                    val document = read(current)
                    val metadata = map((document.root as DslForm.Sequence).values.getOrNull(2))
                    val existing = metadata?.let { pair(it, "layout") }
                    if (existing != null) current = patch(current, existing.first.span.startOffset, existing.second.span.endOffset, "")
                }
                current
            }
        }.also(::read)
    }

    private fun read(text: String): Document {
        val sink = DiagnosticSink()
        val doc = Document.read(SourceText("case.mantra", text), sink) ?: error("Case text is not valid DSL")
        require((doc.root as? DslForm.Sequence)?.listHead == "case") { "Expected a case form" }
        return doc
    }

    private fun requireId(id: String) { require(identifier.matches(id)) { "Invalid DSL identifier: $id" } }

    private fun literal(value: Value): String = when (value) {
        Value.Nil -> "nil"
        is Value.Num -> value.value.toPlainString()
        is Value.Bool -> value.value.toString()
        is Value.Kw -> { requireId(value.name); ":${value.name}" }
        is Value.Text -> "\"" + value.value.flatMap { c -> when (c) {
            '\\' -> listOf('\\', '\\'); '"' -> listOf('\\', '"'); '\n' -> listOf('\\', 'n'); '\r' -> listOf('\\', 'r'); '\t' -> listOf('\\', 't')
            else -> listOf(c)
        } }.joinToString("") + "\""
        is Value.Date -> literal(Value.Text(value.value.toString()))
        is Value.Vec -> value.items.joinToString(" ", "[", "]", transform = ::literal)
        is Value.MapV -> value.entries.entries.joinToString(" ", "{", "}") { "${literal(it.key)} ${literal(it.value)}" }
    }

    private fun map(form: DslForm?): DslForm.Sequence? = (form as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.MAP }

    private fun pair(map: DslForm.Sequence, key: String): Pair<DslForm, DslForm>? = map.values.chunked(2)
        .firstOrNull { it.size == 2 && it[0].keyword == key }?.let { it[0] to it[1] }

    private fun putData(doc: Document, root: DslForm.Sequence, container: DslForm.Sequence?, head: String,
                        id: String, coord: List<String>, value: String): String {
        val data = map(container?.values?.getOrNull(1))
        if (data == null) return insertForm(doc, root, "($head {:$id ${nested(coord, value)}})")
        return putNested(doc, data, listOf(id) + coord, value)
    }

    private fun nested(path: List<String>, value: String): String = path.reversed().fold(value) { acc, key -> "{:$key $acc}" }

    private fun putNested(doc: Document, map: DslForm.Sequence, path: List<String>, value: String): String {
        val found = pair(map, path.first())
        if (found == null) return insertMapEntry(doc, map, path.first(), nested(path.drop(1), value))
        if (path.size == 1) return replace(doc, found.second, value)
        val child = map(found.second) ?: error("Expected member map at :${path.first()}")
        return putNested(doc, child, path.drop(1), value)
    }

    private fun removeData(doc: Document, container: DslForm.Sequence?, id: String, coord: List<String>): String {
        val data = map(container?.values?.getOrNull(1)) ?: return doc.source.text
        return removeNested(doc, data, listOf(id) + coord)
    }

    private fun removeNested(doc: Document, map: DslForm.Sequence, path: List<String>): String {
        val found = pair(map, path.first()) ?: return doc.source.text
        if (path.size == 1) return patch(doc.source.text, found.first.span.startOffset, found.second.span.endOffset, "")
        val child = map(found.second) ?: error("Expected member map at :${path.first()}")
        val leaf = pair(child, path[1]) ?: return doc.source.text
        if (path.size == 2 && child.values.size == 2)
            return patch(doc.source.text, found.first.span.startOffset, found.second.span.endOffset, "")
        if (path.size == 2) return patch(doc.source.text, leaf.first.span.startOffset, leaf.second.span.endOffset, "")
        return removeNested(doc, child, path.drop(1))
    }

    private fun putMeta(doc: Document, root: DslForm.Sequence, key: String, value: String): String {
        val existing = map(root.values.getOrNull(2))
        if (existing == null) return patch(doc.source.text, root.values[1].span.endOffset, root.values[1].span.endOffset, " {:$key $value}")
        return putNested(doc, existing, listOf(key), value)
    }

    private fun insertMapEntry(doc: Document, map: DslForm.Sequence, key: String, value: String): String {
        val start = map.span.startOffset
        val end = map.span.endOffset - 1
        val text = doc.source.text
        val last = map.values.lastOrNull()
        val insertion = if (last == null) ":$key $value" else {
            val indent = text.substring(text.lastIndexOf('\n', last.span.startOffset - 1).coerceAtLeast(-1) + 1, last.span.startOffset)
                .takeIf { it.all(Char::isWhitespace) && '\n' !in it } ?: " "
            if ('\n' in text.substring(last.span.endOffset, end)) "\n$indent:$key $value" else " :$key $value"
        }
        return patch(text, end, end, insertion)
    }

    private fun insertForm(doc: Document, root: DslForm.Sequence, form: String): String {
        val defn = root.values.drop(2).firstOrNull { it.listHead == "defn" }
        val before = defn?.span?.startOffset ?: root.span.endOffset - 1
        val lineStart = doc.source.text.lastIndexOf('\n', before - 1) + 1
        val at = if (defn != null && doc.source.text.substring(lineStart, before).all(Char::isWhitespace)) lineStart else before
        val prefix = if (at == lineStart && defn != null) "  " else "\n  "
        return patch(doc.source.text, at, at, "$prefix$form\n")
    }

    private fun insertBeforeClose(doc: Document, sequence: DslForm.Sequence, form: String): String {
        val at = sequence.span.endOffset - 1
        return patch(doc.source.text, at, at, "\n    $form")
    }

    private fun replace(doc: Document, form: DslForm, value: String): String =
        patch(doc.source.text, form.span.startOffset, form.span.endOffset, value)

    private fun delete(doc: Document, form: DslForm): String =
        patch(doc.source.text, form.span.startOffset, form.span.endOffset, "")

    private fun patch(text: String, start: Int, end: Int, replacement: String): String =
        text.substring(0, start) + replacement + text.substring(end)

    private fun validFormula(formula: String) {
        val wrapper = read("(case test (bind sample $formula))")
        val bind = (wrapper.root as DslForm.Sequence).values[2] as DslForm.Sequence
        require(bind.values.size == 3) { "Expected one formula" }
    }

    private fun line(id: String, title: String, formula: String): String {
        validFormula(formula)
        return "(line $id ${literal(Value.Text(title))} $formula)"
    }

    private sealed interface RowEdit {
        data class Insert(val row: Value.MapV, val index: Int?) : RowEdit
        data class Update(val index: Int, val row: Value.MapV) : RowEdit
        data class Delete(val index: Int) : RowEdit
        data class Move(val from: Int, val to: Int) : RowEdit
    }

    private fun editCell(doc: Document, inputs: DslForm.Sequence?, table: String, row: String,
                         column: String, keyColumn: String?, value: String?): String {
        requireId(table); requireId(column)
        val data = map(inputs?.values?.getOrNull(1)) ?: error("Inputs map was not found")
        val tableForm = pair(data, table)?.second as? DslForm.Sequence ?: error("Table was not found")
        require(tableForm.kind == DslFormSequenceKind.VECTOR) { "Table must be a vector" }
        val selected = if (keyColumn == null) tableForm.values.getOrNull(row.toIntOrNull() ?: -1)
        else tableForm.values.firstOrNull { item ->
            val key = map(item)?.let { pair(it, keyColumn)?.second }
            (key?.keyword ?: key?.string ?: key?.number?.toPlainString()) == row
        }
        val rowMap = map(selected) ?: error("Table row was not found")
        return if (value == null) removeNested(doc, rowMap, listOf(column)) else putNested(doc, rowMap, listOf(column), value)
    }

    private fun editRows(doc: Document, root: DslForm.Sequence, inputs: DslForm.Sequence?, table: String, edit: RowEdit): String {
        requireId(table)
        val map = map(inputs?.values?.getOrNull(1))
        val pair = map?.let { pair(it, table) }
        if (pair == null) {
            require(edit is RowEdit.Insert && (edit.index == null || edit.index == 0)) { "Table was not found" }
            return putData(doc, root, inputs, "inputs", table, emptyList(), literal(Value.Vec(listOf(edit.row))))
        }
        val vector = (pair.second as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.VECTOR }
            ?: error("Table value must be a vector")
        val rows = vector.values
        return when (edit) {
            is RowEdit.Insert -> {
                val index = edit.index ?: rows.size
                require(index in 0..rows.size) { "Row index out of range" }
                val value = literal(edit.row)
                if (rows.isEmpty()) patch(doc.source.text, vector.span.startOffset + 1, vector.span.startOffset + 1, value)
                else if (index == rows.size) patch(doc.source.text, rows.last().span.endOffset, rows.last().span.endOffset, " $value")
                else patch(doc.source.text, rows[index].span.startOffset, rows[index].span.startOffset, "$value ")
            }
            is RowEdit.Update -> { require(edit.index in rows.indices); replace(doc, rows[edit.index], literal(edit.row)) }
            is RowEdit.Delete -> { require(edit.index in rows.indices); delete(doc, rows[edit.index]) }
            is RowEdit.Move -> {
                require(edit.from in rows.indices && edit.to in rows.indices)
                if (edit.from == edit.to) doc.source.text else {
                    // Carry each row's leading trivia with it, including comments and exact whitespace.
                    val start = vector.span.startOffset + 1
                    var cursor = start
                    val chunks = rows.map { row ->
                        val chunk = doc.source.text.substring(cursor, row.span.endOffset)
                        cursor = row.span.endOffset
                        chunk
                    }.toMutableList()
                    val moved = chunks.removeAt(edit.from)
                    chunks.add(edit.to, moved)
                    patch(doc.source.text, start, rows.last().span.endOffset,
                        chunks.mapIndexed { index, chunk -> if (index > 0 && chunk.firstOrNull()?.isWhitespace() == false) " $chunk" else chunk }
                            .joinToString(""))
                }
            }
        }
    }
}
