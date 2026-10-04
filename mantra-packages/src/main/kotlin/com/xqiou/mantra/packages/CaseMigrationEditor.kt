package com.xqiou.mantra.packages

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.options
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** Uses public form spans. Retains authored comments, facts, links and unrelated metadata. */
object CaseMigrationEditor : MigrationEditor {
    override fun apply(source: SourceText, operations: List<MigrationOperation>): SourceText {
        var text = source.text
        operations.forEach { operation ->
            fun meta(key: String, value: String?) {
                text =
                    setMeta(SourceText(source.name, text, source.base), key, value)
            }
            when (operation) {
                is MigrationOperation.PinSchema -> {
                    meta("schema", quoted(operation.schema.identity.id))
                    meta("schema-version", operation.schema.identity.version?.let(::quoted))
                }
                is MigrationOperation.BindParameters -> {
                    if (operation.ids.toSet().size != operation.ids.size ||
                        operation.ids.any(String::isBlank)
                    ) {
                        fail("MANTRA-MIGRATION-EDIT", "Parameter bindings must be unique nonblank IDs")
                    }
                    meta("parameters", operation.ids.joinToString(" ", "[", "]", transform = ::quoted))
                }
                is MigrationOperation.BindLayout -> {
                    if (operation.id?.isBlank() ==
                        true
                    ) {
                        fail("MANTRA-MIGRATION-EDIT", "Layout binding must be a nonblank ID or explicit removal")
                    }
                    meta("layout", operation.id?.let(::quoted))
                }
                is MigrationOperation.ReplaceText -> {
                    val end = operation.offset.toLong() + operation.expected.length
                    if (operation.offset < 0 || end > text.length ||
                        text.substring(operation.offset, end.toInt()) != operation.expected
                    ) {
                        fail("MANTRA-MIGRATION-EDIT", "Explicit patch no longer matches its source")
                    }
                    text = text.replaceRange(operation.offset, end.toInt(), operation.replacement)
                }
            }
        }
        val result = SourceText(source.name, text, source.base)
        Mantra.loadCase(result) // Syntax/literal errors cannot become a migration preview.
        return result
    }

    private fun setMeta(source: SourceText, key: String, value: String?): String {
        val sink = DiagnosticSink()
        val document = Document.read(source, sink) ?: run {
            sink.throwIfErrors()
            fail("MANTRA-MIGRATION-EDIT", "Expected a readable case document")
        }
        sink.throwIfErrors()
        val root = document.root as? DslForm.Sequence ?: fail("MANTRA-MIGRATION-EDIT", "Expected a case document")
        if (root.listHead != "case") fail("MANTRA-MIGRATION-EDIT", "Expected a case document")
        val id = root.values.getOrNull(1) ?: fail("MANTRA-MIGRATION-EDIT", "Case ID is missing")
        val metadata = (root.values.getOrNull(2) as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.MAP }
        if (metadata == null) {
            if (value == null) return source.text
            return source.text.replaceRange(id.span.endOffset, id.span.endOffset, " {:$key $value}")
        }
        val options = document.options(metadata, sink, "case metadata")
        sink.throwIfErrors()
        val old = options[key]
        if (old != null) {
            if (value != null) return source.text.replaceRange(old.span.startOffset, old.span.endOffset, value)
            val pair = metadata.values.chunked(2).first { it[1] === old }
            return source.text.removeRange(pair[0].span.startOffset, old.span.endOffset)
        }
        if (value == null) return source.text
        val offset = metadata.span.endOffset - 1
        return source.text.replaceRange(offset, offset, " :$key $value")
    }

    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
