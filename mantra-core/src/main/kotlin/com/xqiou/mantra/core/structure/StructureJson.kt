package com.xqiou.mantra.core.structure

import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.groupKey
import com.xqiou.mantra.core.view.groupTitle
import com.xqiou.mantra.core.view.headlineId
import com.xqiou.mantra.core.view.signLabels

/**
 * JSON projection of a [SchemaMap] (and optionally the values of one calculation) for UI clients:
 * a UI renders each panel independently from `fields`, `imports` and `exports`, and shows its place
 * on the mainline from `breadcrumb` and `entries`.
 */
object StructureJson {
    internal fun write(map: SchemaMap, plan: CalculationPlan, result: CalculationResult? = null): String = write(
        map,
        if (result ==
            null
        ) {
            CalculationView.of(plan)
        } else {
            CalculationView.of(result)
        },
        result != null,
    )

    fun write(view: CalculationView): String = write(view.structure, view, true)

    private fun write(map: SchemaMap, view: CalculationView, includeValues: Boolean): String {
        val root = linkedMapOf<String, Any?>(
            "schema" to map.schemaId,
            "title" to map.title,
            "headline" to view.headlineId,
            "groupTitles" to view.schema.attributes["group-titles"]?.let(::plain),
            "signLabels" to view.nodes.mapNotNull { (id, node) ->
                node.signLabels?.let {
                    id to
                        linkedMapOf("positive" to it.positive, "negative" to it.negative, "zero" to it.zero)
                }
            }.toMap().takeIf { it.isNotEmpty() },
            "mainline" to map.mainline.map { id ->
                val panel = map.panel(id)
                linkedMapOf(
                    "step" to panel.step,
                    "panel" to id,
                    "title" to panel.title,
                    "result" to panel.resultId,
                    "value" to panel.resultId?.takeIf { includeValues }?.let { value(view, it) },
                )
            },
            "panels" to map.panels.map { panel ->
                linkedMapOf(
                    "id" to panel.id,
                    "title" to panel.title,
                    "role" to panel.role.name.lowercase(),
                    "step" to panel.step,
                    "parent" to panel.parentId,
                    "dims" to panel.dims,
                    "result" to panel.resultId,
                    "resultValue" to panel.resultId?.takeIf { includeValues }?.let { value(view, it) },
                    "breadcrumb" to
                        panel.breadcrumb.map {
                            linkedMapOf(
                                "panel" to it.panelId,
                                "label" to it.label,
                                "node" to it.nodeId,
                            )
                        },
                    "entries" to
                        panel.entries.map {
                            linkedMapOf(
                                "step" to it.step,
                                "panel" to it.stepPanel,
                                "via" to it.viaNode,
                                "viaLabel" to it.viaLabel,
                                "path" to it.path,
                            )
                        },
                    "fields" to panel.fields.map { field(view, it, includeValues) },
                    "nodes" to panel.nodes,
                    "imports" to panel.imports.map(::flow),
                    "exports" to panel.exports.map(::flow),
                )
            },
            "generalInputs" to map.generalInputs.map { field(view, it, includeValues) },
            "params" to map.params.map { id ->
                val node = view.node(id)
                linkedMapOf(
                    "id" to id,
                    "label" to node.label,
                    "value" to node.parameterValue?.let(::plain),
                    "source" to node.parameterSource,
                    "reference" to node.presentation.reference,
                    "attributes" to
                        node.presentation.attributes.mapValues { (_, value) ->
                            plain(value)
                        }.takeIf { it.isNotEmpty() },
                )
            },
        )
        return buildString { emit(root, 0) }
    }

    private fun flow(flow: Flow) = linkedMapOf(
        "fromPanel" to flow.fromPanel,
        "fromNode" to flow.fromNode,
        "toPanel" to flow.toPanel,
        "toNode" to flow.toNode,
    )

    private fun field(view: CalculationView, id: String, includeValues: Boolean): Map<String, Any?> {
        val input = view.nodes[id]?.takeIf { it.kind == NodeKind.INPUT } ?: return linkedMapOf("id" to id)
        val decl = input.input ?: return linkedMapOf("id" to id)
        return linkedMapOf(
            "id" to id,
            "label" to input.label,
            "type" to decl.type.keyword,
            "dims" to input.dims,
            "optional" to decl.optional,
            "requiredWhen" to decl.requiredWhen?.source,
            "minRows" to decl.minRows,
            "default" to decl.default?.let(::plain),
            "options" to decl.options.takeIf { it.isNotEmpty() },
            "columns" to
                decl.columns.takeIf { it.isNotEmpty() }?.map {
                    linkedMapOf(
                        "name" to it.name,
                        "type" to it.type.keyword,
                        "optional" to it.optional,
                        "requiredWhen" to it.requiredWhen?.source,
                    )
                },
            "references" to decl.references.takeIf { it.isNotEmpty() },
            "constraints" to
                decl.presentation.attributes.filterKeys {
                    it in CONSTRAINT_KEYS
                }.mapValues { (_, v) -> plain(v) }.takeIf { it.isNotEmpty() },
            "help" to (decl.presentation.attributes["help"] as? Value.Text)?.value,
            "unit" to (decl.presentation.attributes["unit"] as? Value.Kw)?.name,
            "reference" to decl.presentation.reference,
            "group" to input.groupKey,
            "groupTitle" to input.groupKey?.let(view::groupTitle),
            "attributes" to
                decl.presentation.attributes.mapValues { (_, value) -> plain(value) }.takeIf { it.isNotEmpty() },
            "value" to if (includeValues) value(view, id) else null,
        )
    }

    private val CONSTRAINT_KEYS = setOf("min", "max", "required", "pattern", "max-length")

    private fun value(view: CalculationView, id: String): Any? {
        val node = view.nodes[id] ?: return null
        if (node.dims.isEmpty()) return plain(node.value())
        return node.values.entries.associate { (coord, v) -> coord.joinToString("/") to plain(v) }
    }

    private fun plain(value: Value): Any? = when (value) {
        Value.Nil -> null
        is Value.Num -> value.value
        is Value.Bool -> value.value
        is Value.Kw -> value.name
        is Value.Text -> value.value
        is Value.Date -> value.value.toString()
        is Value.Vec -> value.items.map(::plain)
        is Value.MapV -> value.entries.entries.associate { (k, v) -> (plain(k)?.toString() ?: "nil") to plain(v) }
    }

    private fun StringBuilder.emit(value: Any?, indent: Int) {
        val pad = "  ".repeat(indent)
        when (value) {
            null -> append("null")
            is String -> quote(value)
            is Boolean -> append(value)
            is java.math.BigDecimal -> append(value.toPlainString())
            is Number -> append(value)
            is Map<*, *> -> {
                val entries = value.entries.filter { it.value != null }
                if (entries.isEmpty()) {
                    append("{}")
                    return
                }
                append("{\n")
                entries.forEachIndexed { i, (k, v) ->
                    append(pad).append("  ")
                    quote(k.toString())
                    append(": ")
                    emit(v, indent + 1)
                    if (i < entries.size - 1) append(',')
                    append('\n')
                }
                append(pad).append('}')
            }
            is Collection<*> -> {
                if (value.isEmpty()) {
                    append("[]")
                    return
                }
                if (value.all { it == null || it is String || it is Number || it is Boolean }) {
                    append('[')
                    value.forEachIndexed { i, v ->
                        if (i > 0) append(", ")
                        emit(v, indent)
                    }
                    append(']')
                    return
                }
                append("[\n")
                value.forEachIndexed { i, v ->
                    append(pad).append("  ")
                    emit(v, indent + 1)
                    if (i < value.size - 1) append(',')
                    append('\n')
                }
                append(pad).append(']')
            }
            else -> quote(value.toString())
        }
    }

    private fun StringBuilder.quote(text: String) {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
