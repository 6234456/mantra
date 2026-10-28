package com.xqiou.mantra.cli

import com.xqiou.mantra.workbench.json.WorkbenchJson

/** Inspect the already-rendered candidate only; selectors never read the ambiguous Run coordinate keys. */
internal fun paperAcceptanceAssertions(
    expected: AcceptancePaperExpectation,
    paper: Map<String, Any?>,
): List<Map<String, Any?>> = buildList {
    fun assertion(path: String, value: Any?, actual: Any?, passed: Boolean = value == actual) {
        add(linkedMapOf("path" to path, "expected" to value, "actual" to actual, "passed" to passed))
    }
    val tables =
        paper["tables"] as? List<*> ?: throw IllegalArgumentException("Candidate Paper has no table collection")
    fun matchingTables(id: String): List<Map<*, *>> = tables.map {
        it as? Map<*, *> ?: throw IllegalArgumentException("Candidate Paper contains an invalid table")
    }.filter { it["id"] == id }.also {
        require(it.size <= 1) { "Paper table id is ambiguous: $id" }
    }
    if ("title" in expected.fields) assertion("/paper/title", expected.title, paper["title"])
    expected.tables.forEach { table ->
        val matches = matchingTables(table.id)
        val actual = matches.singleOrNull()
        val path = "/paper/tables/${paperPointerSegment(table.id)}"
        assertion("$path/present", table.present, actual != null)
        if (table.present) {
            if ("title" in table.fields) {
                assertion(
                    "$path/title",
                    table.title,
                    actual?.get("title"),
                    actual != null && table.title == actual["title"],
                )
            }
            if ("style" in table.fields) {
                assertion(
                    "$path/style",
                    table.style,
                    actual?.get("style"),
                    actual != null && table.style == actual["style"],
                )
            }
            table.columns?.let { columns ->
                val actualColumns = actual?.get("columns") as? List<*>
                val actualPairs = actualColumns?.map { column ->
                    val item =
                        column as? Map<*, *> ?: throw IllegalArgumentException("Candidate Paper has an invalid column")
                    listOf(item["id"], item["header"])
                }
                require(actualPairs == null || actualPairs.map { it[0] }.distinct().size == actualPairs.size) {
                    "Paper column ids are ambiguous in table ${table.id}"
                }
                val pairs = columns.map { listOf(it.id, it.header) }
                assertion("$path/columns", pairs, actualPairs, actual != null && pairs == actualPairs)
            }
        }
    }
    expected.cells.forEach { selected ->
        val table = matchingTables(selected.table).singleOrNull()
            ?: throw IllegalArgumentException("Paper cell parent table was not found: ${selected.table}")
        val columns = table["columns"] as? List<*>
            ?: throw IllegalArgumentException("Paper cell parent table has no columns: ${selected.table}")
        val indices = columns.indices.filter { (columns[it] as? Map<*, *>)?.get("id") == selected.column }
        require(indices.size == 1) {
            "Paper cell parent column must exist exactly once: ${selected.table}/${selected.column}"
        }
        val index = indices.single()
        val rows = table["rows"] as? List<*>
            ?: throw IllegalArgumentException("Paper cell parent table has no rows: ${selected.table}")
        val matches = rows.map { row ->
            row as? Map<*, *> ?: throw IllegalArgumentException("Candidate Paper contains an invalid row")
        }.filter { row ->
            val target = selected.row
            target == null ||
                (
                    row["node"] == target.node &&
                        ("kind" !in target.fields || row["kind"] == target.kind) &&
                        ("optionKey" !in target.fields || row["optionKey"] == target.optionKey)
                    )
        }.map { row ->
            val cells = row["cells"] as? List<*>
                ?: throw IllegalArgumentException("Candidate Paper row has no cells")
            require(index in cells.indices) { "Candidate Paper row does not contain its selected column" }
            cells[index] as? Map<*, *> ?: throw IllegalArgumentException("Candidate Paper contains an invalid cell")
        }.filter { cell ->
            val target = selected.address
            if (target == null) {
                true
            } else {
                val address = cell["address"] as? Map<*, *>
                address != null && address["case"] == null && address["node"] == target.node &&
                    (address["coord"] ?: emptyList<String>()) == target.coord
            }
        }
        require(matches.size <= 1) {
            "Paper cell selector is ambiguous: ${WorkbenchJson.write(selected.selector)}"
        }
        val actual = matches.singleOrNull()
        val path = "/paper/cells/${paperPointerSegment(WorkbenchJson.write(selected.selector))}"
        assertion("$path/present", selected.present, actual != null)
        if (selected.present) {
            if ("text" in selected.fields) {
                assertion(
                    "$path/text",
                    selected.text,
                    actual?.get("text"),
                    actual != null && selected.text == actual["text"],
                )
            }
            if ("editable" in selected.fields) {
                assertion(
                    "$path/editable",
                    selected.editable,
                    actual?.get("editable"),
                    actual != null && selected.editable == actual["editable"],
                )
            }
            selected.style?.forEach { (key, style) ->
                val resolved = (actual?.get("style") as? Map<*, *>)?.get(key)
                assertion("$path/style/$key", style, resolved, actual != null && style == resolved)
            }
        }
    }
}

private fun paperPointerSegment(value: String): String = value.replace("~", "~0").replace("/", "~1")
