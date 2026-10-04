package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.model.Value

/** JSON primitives used by the versioned workbench contract. */
object WorkbenchJson {
    const val CONTRACT = "mantra.workbench/2"

    /** Keep engine decimals exact and distinguish keywords, dates, and ordered map keys. */
    fun value(value: Value): Any? = when (value) {
        Value.Nil -> null
        is Value.Num -> mapOf("n" to value.value.toPlainString())
        is Value.Bool -> value.value
        is Value.Kw -> mapOf("kw" to value.name)
        is Value.Text -> value.value
        is Value.Date -> mapOf("date" to value.value.toString())
        is Value.Vec -> value.items.map(::value)
        is Value.MapV -> mapOf("map" to value.entries.map { (key, entry) -> listOf(value(key), value(entry)) })
    }

    fun envelope(revision: String, mantraVersion: String, normeinVersion: String, data: Any?): Map<String, Any?> =
        linkedMapOf(
            "contract" to CONTRACT,
            "revision" to revision,
            "engine" to linkedMapOf("mantra" to mantraVersion, "normein" to normeinVersion),
            "data" to data,
        )

    /** Serializes contract trees. BigDecimal is rejected so callers cannot leak a JSON float. */
    fun write(tree: Any?): String = buildString { appendJson(tree) }

    private fun StringBuilder.appendJson(tree: Any?) {
        when (tree) {
            null -> append("null")
            is String -> appendQuoted(tree)
            is Boolean -> append(tree)
            is Int, is Long -> append(tree)
            is List<*> -> {
                append('[')
                tree.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendJson(item)
                }
                append(']')
            }
            is Map<*, *> -> {
                append('{')
                tree.entries.forEachIndexed { index, (key, item) ->
                    require(key is String) { "JSON object keys must be strings" }
                    if (index > 0) append(',')
                    appendQuoted(key)
                    append(':')
                    appendJson(item)
                }
                append('}')
            }
            else -> error("Unsupported JSON value ${tree::class.qualifiedName}; encode engine values first")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
