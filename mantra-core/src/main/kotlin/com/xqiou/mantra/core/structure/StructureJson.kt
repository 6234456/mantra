package com.xqiou.mantra.core.structure

import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.CalculationResult
import com.xqiou.mantra.core.engine.InputVertex
import com.xqiou.mantra.core.engine.ParamVertex
import com.xqiou.mantra.core.model.Value

/**
 * JSON projection of a [SchemaMap] (and optionally the values of one calculation) for UI clients:
 * a UI renders each panel independently from `fields`, `imports` and `exports`, and shows its place
 * on the mainline from `breadcrumb` and `entries`.
 */
object StructureJson {
    fun write(map: SchemaMap, plan: CalculationPlan, result: CalculationResult? = null): String {
        val root = linkedMapOf<String, Any?>(
            "schema" to map.schemaId,
            "title" to map.title,
            "mainline" to map.mainline.map { id ->
                val panel = map.panel(id)
                linkedMapOf("step" to panel.step, "panel" to id, "title" to panel.title, "result" to panel.resultId, "value" to result?.let { r -> panel.resultId?.let { value(r, it) } })
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
                    "resultValue" to result?.let { r -> panel.resultId?.let { value(r, it) } },
                    "breadcrumb" to panel.breadcrumb.map { linkedMapOf("panel" to it.panelId, "label" to it.label, "node" to it.nodeId) },
                    "entries" to panel.entries.map { linkedMapOf("step" to it.step, "panel" to it.stepPanel, "via" to it.viaNode, "viaLabel" to it.viaLabel, "path" to it.path) },
                    "fields" to panel.fields.map { field(plan, it, result) },
                    "nodes" to panel.nodes,
                    "imports" to panel.imports.map(::flow),
                    "exports" to panel.exports.map(::flow),
                )
            },
            "generalInputs" to map.generalInputs.map { field(plan, it, result) },
            "params" to map.params.map { id ->
                val vertex = plan.valueVertices[id] as ParamVertex
                linkedMapOf("id" to id, "label" to vertex.label, "value" to plain(vertex.value), "source" to vertex.source,
                    "reference" to vertex.decl.presentation.reference,
                    "attributes" to vertex.decl.presentation.attributes.mapValues { (_, value) -> plain(value) }.takeIf { it.isNotEmpty() })
            },
        )
        return buildString { emit(root, 0) }
    }

    private fun flow(flow: Flow) = linkedMapOf("fromPanel" to flow.fromPanel, "fromNode" to flow.fromNode, "toPanel" to flow.toPanel, "toNode" to flow.toNode)

    private fun field(plan: CalculationPlan, id: String, result: CalculationResult?): Map<String, Any?> {
        val input = plan.valueVertices[id] as? InputVertex ?: return linkedMapOf("id" to id)
        val decl = input.decl
        return linkedMapOf(
            "id" to id,
            "label" to input.label,
            "type" to decl.type.keyword,
            "dims" to input.dims,
            "optional" to decl.optional,
            "default" to decl.default?.let(::plain),
            "options" to decl.options.takeIf { it.isNotEmpty() },
            "columns" to decl.columns.takeIf { it.isNotEmpty() }?.map { linkedMapOf("name" to it.name, "type" to it.type.keyword, "optional" to it.optional) },
            "references" to decl.references.takeIf { it.isNotEmpty() },
            "constraints" to decl.presentation.attributes.filterKeys { it in CONSTRAINT_KEYS }.mapValues { (_, v) -> plain(v) }.takeIf { it.isNotEmpty() },
            "help" to (decl.presentation.attributes["help"] as? Value.Text)?.value,
            "unit" to (decl.presentation.attributes["unit"] as? Value.Kw)?.name,
            "reference" to decl.presentation.reference,
            "attributes" to decl.presentation.attributes.mapValues { (_, value) -> plain(value) }.takeIf { it.isNotEmpty() },
            "value" to result?.let { value(it, id) },
        )
    }

    private val CONSTRAINT_KEYS = setOf("min", "max", "required", "pattern", "max-length")

    private fun value(result: CalculationResult, id: String): Any? {
        val node = result.nodes[id] ?: return null
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
