package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.CaseLink
import com.xqiou.mantra.core.model.CaseLinkMapping
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.SchemaReference
import com.xqiou.mantra.core.view.frozenList
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** Reads data-only link records; linking never interprets a transfer expression. */
internal object LinkReader {
    fun read(document: Document, list: DslForm.Sequence, sink: DiagnosticSink): List<CaseLink> {
        if (list.values.size == 1) {
            sink.error("MANTRA-CASE-LINK", "(links ...) requires at least one source record", document.location(list))
        }
        return frozenList(list.values.drop(1).mapNotNull { declaration(document, it, sink) })
    }

    private fun declaration(document: Document, form: DslForm, sink: DiagnosticSink): CaseLink? {
        val options = document.options(form, sink, "link")
        if (!keys(document, form, options, setOf("path", "schema", "schema-version", "mappings"), sink)) return null
        val path = text(document, options.getValue("path"), "path", sink) ?: return null
        val schema = options.getValue("schema").let {
            it.string
                ?: it.symbol?.takeUnless { value -> value in setOf("nil", "true", "false") }
        }?.takeIf(String::isNotBlank)
        if (schema == null) {
            sink.error("MANTRA-CASE-LINK", "Link :schema must be a nonblank schema id", document.location(form))
            return null
        }
        val version = text(document, options.getValue("schema-version"), "schema-version", sink) ?: return null
        val rawMappings = options.getValue("mappings")
        val mappings = (rawMappings as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.VECTOR }
        if (mappings == null || mappings.values.isEmpty()) {
            sink.error("MANTRA-CASE-LINK", "Link :mappings must be a nonempty vector", document.location(rawMappings))
            return null
        }
        val parsed = mappings.values.mapNotNull { mapping(document, it, sink) }
        if (parsed.size != mappings.values.size) return null
        return CaseLink(path, SchemaReference(schema, version), frozenList(parsed), document.location(form))
    }

    private fun mapping(document: Document, form: DslForm, sink: DiagnosticSink): CaseLinkMapping? {
        val options = document.options(form, sink, "link mapping")
        if (!keys(document, form, options, setOf("from", "to"), sink)) return null
        val from = address(document, options.getValue("from"), "node", sink) ?: return null
        val to = address(document, options.getValue("to"), "input", sink) ?: return null
        return CaseLinkMapping(from, to, document.location(form))
    }

    private fun address(document: Document, form: DslForm, nodeField: String, sink: DiagnosticSink): InputAddress? {
        val options = document.options(form, sink, "link address")
        if (!keys(document, form, options, setOf(nodeField, "coord"), sink)) return null
        val node = options.getValue(nodeField).let {
            it.keyword ?: it.string
                ?: it.symbol?.takeUnless { value -> value in setOf("nil", "true", "false") }?.takeUnless { value ->
                    value in
                        setOf("nil", "true", "false")
                }
        }
        if (node == null || !isIdentifier(node)) {
            sink.error("MANTRA-LINK-ADDRESS", "Link :$nodeField must be a node identifier", document.location(form))
            return null
        }
        val raw = options.getValue("coord")
        val coord = (raw as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.VECTOR }
        if (coord == null) {
            sink.error(
                "MANTRA-LINK-ADDRESS",
                "Link :coord must be a complete vector, including [] for a scalar",
                document.location(raw),
            )
            return null
        }
        val keys = coord.values.mapNotNull { value ->
            (value.keyword ?: value.string ?: value.number?.toPlainString())?.takeIf(String::isNotBlank)
                ?: run {
                    sink.error(
                        "MANTRA-LINK-ADDRESS",
                        "Coordinate members must be nonblank literal member keys",
                        document.location(value),
                    )
                    null
                }
        }
        return if (keys.size == coord.values.size) InputAddress(node, frozenList(keys)) else null
    }

    private fun keys(
        document: Document,
        form: DslForm,
        options: Map<String, DslForm>,
        required: Set<String>,
        sink: DiagnosticSink,
    ): Boolean {
        if (options.keys != required) {
            sink.error(
                "MANTRA-CASE-LINK",
                "Link record requires exactly ${required.joinToString { ":$it" }}",
                document.location(form),
            )
            return false
        }
        return true
    }

    private fun text(document: Document, form: DslForm, key: String, sink: DiagnosticSink): String? =
        form.string?.takeIf(String::isNotBlank) ?: run {
            sink.error("MANTRA-CASE-LINK", "Link :$key must be nonblank literal text", document.location(form))
            null
        }
}
