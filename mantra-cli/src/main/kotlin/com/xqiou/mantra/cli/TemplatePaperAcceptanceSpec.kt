package com.xqiou.mantra.cli

import com.fasterxml.jackson.databind.JsonNode

internal data class AcceptancePaperExpectation(
    val title: String?,
    val tables: List<AcceptancePaperTable>,
    val cells: List<AcceptancePaperCell>,
    val fields: Set<String>,
) {
    val selectorCount: Int get() = tables.size + cells.size + if ("title" in fields) 1 else 0
}

internal data class AcceptancePaperTable(
    val id: String,
    val present: Boolean,
    val title: String?,
    val style: String?,
    val columns: List<AcceptancePaperColumn>?,
    val fields: Set<String>,
)

internal data class AcceptancePaperColumn(val id: String, val header: String)

internal data class AcceptancePaperCell(
    val table: String,
    val column: String,
    val row: AcceptancePaperRow?,
    val address: AcceptancePaperAddress?,
    val present: Boolean,
    val text: String?,
    val editable: Boolean?,
    val style: Map<String, String>?,
    val fields: Set<String>,
) {
    /** Fixed key order keeps duplicate detection and logical target paths independent of JSON field order. */
    val selector: Map<String, Any?> get() = linkedMapOf<String, Any?>("table" to table, "column" to column).apply {
        row?.let { target ->
            put(
                "row",
                linkedMapOf<String, Any?>("node" to target.node).apply {
                    if ("kind" in target.fields) put("kind", target.kind)
                    if ("optionKey" in target.fields) put("optionKey", target.optionKey)
                },
            )
        }
        address?.let { put("address", linkedMapOf("node" to it.node, "coord" to it.coord)) }
    }
}

internal data class AcceptancePaperRow(
    val node: String?,
    val kind: String?,
    val optionKey: String?,
    val fields: Set<String>,
)

internal data class AcceptancePaperAddress(val node: String, val coord: List<String>)

internal fun parseAcceptancePaper(node: JsonNode): AcceptancePaperExpectation {
    node.acceptanceFields(emptySet(), setOf("title", "tables", "cells"))
    val fields = node.fieldNames().asSequence().toSet()
    require(fields.isNotEmpty()) { "Paper expectation must check title, tables or cells" }
    val tables = if (node.has("tables")) node.acceptanceArray("tables", 0, 64).map(::parsePaperTable) else emptyList()
    acceptanceUnique(tables.map { it.id }, "Paper table id")
    val cells = if (node.has("cells")) node.acceptanceArray("cells", 0, 256).map(::parsePaperCell) else emptyList()
    acceptanceUnique(cells.map { it.selector }, "Paper cell selector")
    return AcceptancePaperExpectation(
        if (node.has("title")) node.acceptanceText("title") else null,
        tables,
        cells,
        fields,
    )
}

private fun parsePaperTable(node: JsonNode): AcceptancePaperTable {
    node.acceptanceFields(setOf("id", "present"), setOf("title", "style", "columns"))
    val fields = node.fieldNames().asSequence().toSet()
    val present = node.acceptanceBoolean("present")
    require(present || fields == setOf("id", "present")) { "An absent table cannot check title, style or columns" }
    val style = if (node.has("style")) node.acceptanceText("style") else null
    require(style == null || style in setOf("tiered", "matrix", "transpose")) { "Unknown Paper table style" }
    val columns = if (node.has("columns")) {
        node.acceptanceArray("columns", 0, 64).map { column ->
            column.acceptanceFields(setOf("id", "header"))
            AcceptancePaperColumn(column.acceptanceText("id", nonblank = true), column.acceptanceText("header"))
        }.also { columns -> acceptanceUnique(columns.map { it.id }, "Paper column id") }
    } else {
        null
    }
    return AcceptancePaperTable(
        node.acceptanceText("id", nonblank = true),
        present,
        if (node.has("title")) node.acceptanceText("title") else null,
        style,
        columns,
        fields,
    )
}

private fun parsePaperCell(node: JsonNode): AcceptancePaperCell {
    node.acceptanceFields(
        setOf("table", "column", "present"),
        setOf("row", "address", "text", "editable", "style"),
    )
    val fields = node.fieldNames().asSequence().toSet()
    require(node.has("row") || node.has("address")) { "A Paper cell requires a row or address selector" }
    val present = node.acceptanceBoolean("present")
    val checked = setOf("text", "editable", "style").intersect(fields)
    require(if (present) checked.isNotEmpty() else checked.isEmpty()) {
        "A present cell requires text, editable or style; absent cells cannot check those fields"
    }
    return AcceptancePaperCell(
        node.acceptanceText("table", nonblank = true),
        node.acceptanceText("column", nonblank = true),
        if (node.has("row")) parsePaperRow(node["row"]) else null,
        if (node.has("address")) parsePaperAddress(node["address"]) else null,
        present,
        if (node.has("text")) node.acceptanceText("text") else null,
        if (node.has("editable")) node.acceptanceBoolean("editable") else null,
        if (node.has("style")) parsePaperStyle(node["style"]) else null,
        fields,
    )
}

private fun parsePaperRow(node: JsonNode): AcceptancePaperRow {
    node.acceptanceFields(setOf("node"), setOf("kind", "optionKey"))
    val kind = if (node.has("kind")) node.acceptanceText("kind") else null
    require(
        kind == null || kind in setOf(
            "heading", "value", "option", "member", "subtotal", "result", "total", "note", "reference",
        ),
    ) { "Unknown Paper row kind" }
    return AcceptancePaperRow(
        node.nullablePaperText("node"),
        kind,
        if (node.has("optionKey")) node.nullablePaperText("optionKey") else null,
        node.fieldNames().asSequence().toSet(),
    )
}

private fun parsePaperAddress(node: JsonNode): AcceptancePaperAddress {
    node.acceptanceFields(setOf("node", "coord"))
    val coordinate = node.acceptanceArray("coord", 0, 8).map { member ->
        require(member.isTextual) { "Paper address coordinates must be text" }
        member.textValue()
    }
    return AcceptancePaperAddress(node.acceptanceText("node", nonblank = true), coordinate)
}

private fun parsePaperStyle(node: JsonNode): Map<String, String> {
    node.acceptanceFields(emptySet(), setOf("weight", "tone", "fill"))
    require(!node.isEmpty) { "Paper style expectation must check at least one field" }
    val allowed = linkedMapOf(
        "weight" to setOf("normal", "bold"),
        "tone" to setOf("default", "muted", "accent"),
        "fill" to setOf("none", "subtle", "accent"),
    )
    return buildMap {
        allowed.forEach { (field, values) ->
            if (node.has(field)) {
                val value = node.acceptanceText(field)
                require(value in values) { "Unknown Paper $field value" }
                put(field, value)
            }
        }
    }
}

private fun JsonNode.nullablePaperText(key: String): String? =
    if (this[key].isNull) null else acceptanceText(key, nonblank = true)
