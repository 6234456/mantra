package com.xqiou.mantra.core.data

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * A channel through which input data enters a calculation (Datenzugang). Sources return values
 * keyed by input id in the shape of case literals; type conversion and validation stay in the
 * engine so every channel behaves identically. Domain applications add their own sources
 * (DATEV, ERP, databases) by implementing this interface.
 */
interface DataSource {
    val description: String

    fun read(schema: Schema, sink: DiagnosticSink): Map<String, Value>
}

object DataSources {
    /** Merges sources in order (later wins) and lets the case's own `(inputs …)` win over all. */
    fun apply(case: CaseData, schema: Schema, sources: List<DataSource>, sink: DiagnosticSink): CaseData {
        if (sources.isEmpty()) return case
        val merged = linkedMapOf<String, Value>()
        sources.forEach { source ->
            source.read(schema, sink).forEach { (id, value) -> merged[id] = mergeValue(merged[id], value) }
        }
        case.inputs.forEach { (id, value) -> merged[id] = mergeValue(merged[id], value) }
        return case.copy(inputs = merged)
    }

    /** Per-member maps merge member by member so different sources can supply different members. */
    private fun mergeValue(old: Value?, new: Value): Value =
        if (old is Value.MapV && new is Value.MapV) Value.MapV(LinkedHashMap(old.entries).apply { putAll(new.entries) }) else new

    fun inputs(schema: Schema): Map<String, InputDecl> = schema.inputs.associateBy { it.id }
}

/**
 * JSON document with input ids as keys (optionally below [root], or below `"inputs"`), or any JSON
 * structure with an explicit [mapping] from input id to a dotted path such as `mandant.lohn.A`
 * or `objekte[0].miete`.
 */
class JsonSource(
    private val path: Path,
    private val root: String? = null,
    private val mapping: Map<String, String> = emptyMap(),
) : DataSource {
    override val description: String = "json:${path.fileName}"

    override fun read(schema: Schema, sink: DiagnosticSink): Map<String, Value> {
        val document = try {
            Json.parse(Files.readString(path))
        } catch (e: Json.JsonException) {
            sink.error("MANTRA-DATA-JSON", "${path.fileName}: ${e.message}")
            return emptyMap()
        }
        val inputs = DataSources.inputs(schema)
        if (mapping.isNotEmpty()) {
            return mapping.mapNotNull { (id, dotted) ->
                if (id !in inputs) sink.error("MANTRA-DATA-UNKNOWN-INPUT", "${path.fileName}: mapping targets unknown input $id")
                navigate(document, dotted)?.let { id to it } ?: run {
                    sink.warning("MANTRA-DATA-PATH", "${path.fileName}: path $dotted not found")
                    null
                }
            }.toMap()
        }
        val base = root?.let { navigate(document, it) }
            ?: (document as? Value.MapV)?.entries?.get(Value.Kw("inputs"))
            ?: document
        val entries = (base as? Value.MapV)?.entries ?: run {
            sink.error("MANTRA-DATA-JSON", "${path.fileName}: expected an object of inputs")
            return emptyMap()
        }
        return entries.entries.mapNotNull { (k, v) ->
            val id = (k as Value.Kw).name
            if (id !in inputs) {
                sink.warning("MANTRA-DATA-UNKNOWN-INPUT", "${path.fileName}: ignoring unknown input $id")
                null
            } else {
                id to v
            }
        }.toMap()
    }

    private fun navigate(value: Value, dotted: String): Value? {
        var current: Value? = value
        Regex("""[^.\[\]]+|\[\d+]""").findAll(dotted).forEach { match ->
            val token = match.value
            current = if (token.startsWith("[")) {
                (current as? Value.Vec)?.items?.getOrNull(token.trim('[', ']').toInt())
            } else {
                (current as? Value.MapV)?.entries?.get(Value.Kw(token))
            }
        }
        return current
    }
}

/**
 * CSV file (header row required). With [input] set, each row becomes a record of that table input;
 * header names map to columns directly or through [columns]. Without [input], rows are
 * `input;value` or `input;member;value` pairs. Numbers use the given [decimal] and [grouping]
 * separators (German default: `1.234,56`), dates may be `dd.MM.yyyy`.
 */
class CsvSource(
    private val path: Path,
    private val input: String? = null,
    private val delimiter: Char = ';',
    private val decimal: Char = ',',
    private val grouping: Char? = '.',
    private val columns: Map<String, String> = emptyMap(),
) : DataSource {
    override val description: String = "csv:${path.fileName}"

    override fun read(schema: Schema, sink: DiagnosticSink): Map<String, Value> {
        val rows = parse(Files.readString(path).removePrefix("﻿"))
        if (rows.isEmpty()) return emptyMap()
        val header = rows.first().map { it.trim() }
        val inputs = DataSources.inputs(schema)
        if (input != null) {
            val decl = inputs[input]
            if (decl == null || decl.type != ValueType.TABLE) {
                sink.error("MANTRA-DATA-CSV", "${path.fileName}: $input is not a table input")
                return emptyMap()
            }
            val columnFor = header.map { name ->
                columns[name] ?: decl.columns.firstOrNull { it.name == normalize(name) }?.name ?: run {
                    sink.warning("MANTRA-DATA-CSV", "${path.fileName}: column '$name' does not match a column of $input")
                    null
                }
            }
            val records = rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.map { row ->
                Value.MapV(
                    LinkedHashMap<Value, Value>().apply {
                        columnFor.forEachIndexed { index, column ->
                            if (column == null) return@forEachIndexed
                            val type = decl.columns.first { it.name == column }.type
                            put(Value.Kw(column), convert(row.getOrElse(index) { "" }, type))
                        }
                    },
                )
            }
            return mapOf(input to Value.Vec(records))
        }
        val idIndex = header.indexOfFirst { normalize(it) in setOf("input", "eingabe", "id") }.takeIf { it >= 0 } ?: 0
        val memberIndex = header.indexOfFirst { normalize(it) in setOf("member", "mitglied", "person") }.takeIf { it >= 0 }
        val valueIndex = header.indexOfFirst { normalize(it) in setOf("value", "wert") }.takeIf { it >= 0 } ?: (header.size - 1)
        val result = linkedMapOf<String, Value>()
        rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.forEach { row ->
            val id = row.getOrElse(idIndex) { "" }.trim()
            val decl = inputs[id] ?: run {
                sink.warning("MANTRA-DATA-UNKNOWN-INPUT", "${path.fileName}: ignoring unknown input '$id'")
                return@forEach
            }
            val value = convert(row.getOrElse(valueIndex) { "" }, decl.type)
            val member = memberIndex?.let { row.getOrElse(it) { "" }.trim() }?.takeIf { it.isNotEmpty() }
            result[id] = if (member == null) {
                value
            } else {
                Value.MapV(LinkedHashMap((result[id] as? Value.MapV)?.entries ?: emptyMap()).apply { put(Value.Kw(member.removePrefix(":")), value) })
            }
        }
        return result
    }

    private fun normalize(name: String) = name.trim().lowercase().replace(Regex("[\\s_]+"), "-")

    private fun convert(raw: String, type: ValueType): Value {
        val text = raw.trim()
        if (text.isEmpty()) return Value.Nil
        return when (type) {
            ValueType.DECIMAL, ValueType.INTEGER -> number(text)?.let(Value::Num) ?: Value.Text(text)
            ValueType.BOOLEAN -> when (text.lowercase()) {
                "true", "wahr", "ja", "yes", "1", "x" -> Value.Bool(true)
                "false", "falsch", "nein", "no", "0" -> Value.Bool(false)
                else -> Value.Text(text)
            }
            ValueType.KEYWORD -> Value.Kw(text.removePrefix(":"))
            ValueType.DATE -> Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""").matchEntire(text)?.destructured?.let { (d, m, y) ->
                Value.Text("%s-%02d-%02d".format(y, m.toInt(), d.toInt()))
            } ?: Value.Text(text)
            else -> Value.Text(text)
        }
    }

    private fun number(text: String): BigDecimal? {
        var t = text.replace(" ", "").replace(" ", "")
        val negative = t.startsWith("-") || t.endsWith("-") || (t.startsWith("(") && t.endsWith(")"))
        t = t.trim('-', '(', ')', '+')
        if (grouping != null) t = t.replace(grouping.toString(), "")
        t = t.replace(decimal, '.')
        return t.toBigDecimalOrNull()?.let { if (negative) it.negate() else it }
    }

    private fun parse(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            when {
                quoted && ch == '"' && content.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delimiter -> {
                    row += cell.toString()
                    cell.clear()
                }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && content.getOrNull(i + 1) == '\n') i++
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }
}
