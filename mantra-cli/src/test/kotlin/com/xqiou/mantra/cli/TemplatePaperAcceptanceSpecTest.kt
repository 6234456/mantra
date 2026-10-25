package com.xqiou.mantra.cli

import com.xqiou.mantra.workbench.json.WorkbenchJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplatePaperAcceptanceSpecTest {
    private val hash = "a".repeat(64)

    private fun cell(node: String = "answer", extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "table" to "main",
            "column" to "value",
            "row" to linkedMapOf("node" to node, "kind" to "value"),
            "present" to true,
            "text" to "3,75",
        ) + extra

    private fun table(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to "main", "present" to true) + extra

    private fun expectation(paper: Any?, values: List<Any?> = emptyList()): Map<String, Any?> = linkedMapOf(
        "succeeded" to true,
        "validationPassed" to true,
        "values" to values,
        "paper" to paper,
    )

    private fun case(id: String = "case", expected: Any? = null): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "case" to "case.mantra",
        "baseRevision" to hash,
        "baseRevisions" to mapOf("schema.mantra" to hash, "case.mantra" to hash),
        "inputs" to emptyList<Any>(),
        "expect" to expected,
    )

    private fun suite(
        cases: List<Map<String, Any?>>,
        format: String = TEMPLATE_ACCEPTANCE_FORMAT_V2,
    ): Map<String, Any?> = linkedMapOf(
        "format" to format,
        "documents" to listOf(
            linkedMapOf("document" to "schema.mantra", "role" to "schema", "sha256" to hash, "text" to "(schema test)"),
        ),
        "cases" to cases,
    )

    private fun parse(tree: Any?): TemplateAcceptanceSuite =
        parseTemplateAcceptanceSuite(WorkbenchJson.write(tree).toByteArray(Charsets.UTF_8))

    private fun paper(candidate: Any?): AcceptancePaperExpectation =
        parse(suite(listOf(case(expected = expectation(candidate))))).cases.single().expect!!.paper!!

    @Test fun `v2 retains typed Paper targets while v1 still rejects the added field`() {
        val candidate = linkedMapOf(
            "title" to "Candidate report",
            "tables" to listOf(table(mapOf("title" to "Main", "style" to "matrix"))),
            "cells" to listOf(cell(extra = mapOf("editable" to false, "style" to mapOf("weight" to "bold")))),
        )
        val parsedSuite = parse(suite(listOf(case(expected = expectation(candidate)))))
        val parsed = parsedSuite.cases.single().expect!!.paper!!
        assertEquals(TEMPLATE_ACCEPTANCE_FORMAT_V2, parsedSuite.format)
        assertEquals("Candidate report", parsed.title)
        assertEquals("matrix", parsed.tables.single().style)
        assertEquals(mapOf("weight" to "bold"), parsed.cells.single().style)
        assertFalse(parsed.cells.single().editable!!)
        assertEquals(3, parsed.selectorCount)
        assertFails { parse(suite(listOf(case(expected = expectation(candidate))), TEMPLATE_ACCEPTANCE_FORMAT)) }
        assertNull(parse(suite(listOf(case()))).cases.single().expect)
        assertEquals(TEMPLATE_ACCEPTANCE_FORMAT, TemplateAcceptanceSuite(emptyList(), emptyList(), hash).format)
    }

    @Test fun `optional text and null row members keep omission separate from explicit empty values`() {
        val parsed = paper(
            mapOf(
                "title" to "",
                "cells" to listOf(
                    cell(extra = mapOf("row" to mapOf("node" to null, "optionKey" to null), "text" to "")),
                    cell(extra = mapOf("row" to mapOf("node" to null), "text" to "other")),
                ),
            ),
        )
        assertEquals("", parsed.title)
        assertEquals("", parsed.cells.first().text)
        assertNull(parsed.cells.first().row!!.node)
        assertNull(parsed.cells.first().row!!.optionKey)
        assertTrue("optionKey" in parsed.cells.first().row!!.fields)
        assertFalse("optionKey" in parsed.cells.last().row!!.fields)
        assertTrue(parsed.cells.first().selector != parsed.cells.last().selector)
        assertEquals(0, paper(mapOf("tables" to emptyList<Any>())).selectorCount)
    }

    @Test fun `Paper addresses preserve full ordered member text including slash and empty members`() {
        val address = mapOf("node" to "balance", "coord" to listOf("A/B", "", "成员~1"))
        val parsed = paper(mapOf("cells" to listOf(cell(extra = mapOf("address" to address))))).cells.single()
        assertEquals(AcceptancePaperAddress("balance", listOf("A/B", "", "成员~1")), parsed.address)
        assertEquals(address, parsed.selector["address"])
        val withoutRow = cell().toMutableMap().apply {
            remove("row")
            put("address", mapOf("node" to "answer", "coord" to emptyList<String>()))
        }
        assertNull(paper(mapOf("cells" to listOf(withoutRow))).cells.single().row)
    }

    @Test fun `canonical duplicate selectors ignore JSON order and differing expected payloads`() {
        val first = cell(extra = mapOf("address" to mapOf("node" to "answer", "coord" to emptyList<String>())))
        val reordered = linkedMapOf<String, Any?>(
            "text" to "different expectation",
            "present" to true,
            "address" to linkedMapOf("coord" to emptyList<String>(), "node" to "answer"),
            "row" to linkedMapOf("kind" to "value", "node" to "answer"),
            "column" to "value",
            "table" to "main",
        )
        assertFails { paper(mapOf("cells" to listOf(first, reordered))) }
        val parsed = paper(mapOf("cells" to listOf(reordered))).cells.single()
        assertEquals(listOf("table", "column", "row", "address"), parsed.selector.keys.toList())
        assertEquals(
            "{\"table\":\"main\",\"column\":\"value\",\"row\":{\"node\":\"answer\",\"kind\":\"value\"}," +
                "\"address\":{\"node\":\"answer\",\"coord\":[]}}",
            WorkbenchJson.write(parsed.selector),
        )
        assertFails { paper(mapOf("tables" to listOf(table(), table()))) }
    }

    @Test fun `existence assertions prohibit accidental content checks or unchecked present cells`() {
        val absent = cell().toMutableMap().apply {
            put("present", false)
            remove("text")
        }
        assertFalse(paper(mapOf("cells" to listOf(absent))).cells.single().present)
        assertFails { paper(mapOf("cells" to listOf(absent + ("text" to "")))) }
        assertFails { paper(mapOf("cells" to listOf(absent + ("editable" to false)))) }
        val withStyle = absent + ("style" to mapOf("fill" to "none"))
        assertFails { paper(mapOf("cells" to listOf(withStyle))) }
        assertFails { paper(mapOf("cells" to listOf(absent + ("present" to true)))) }
        val absentTable = paper(mapOf("tables" to listOf(table(mapOf("present" to false)))))
        assertFalse(absentTable.tables.single().present)
        assertFails { paper(mapOf("tables" to listOf(table(mapOf("present" to false, "title" to ""))))) }
    }

    @Test fun `finite styles table layouts and row kinds reject unsupported or null values`() {
        val style = mapOf("weight" to "normal", "tone" to "default", "fill" to "none")
        assertEquals(style, paper(mapOf("cells" to listOf(cell(extra = mapOf("style" to style))))).cells.single().style)
        listOf<Any?>(null, emptyMap<String, String>(), mapOf("fill" to "highlight"), mapOf("weight" to null)).forEach {
            assertFails { paper(mapOf("cells" to listOf(cell(extra = mapOf("style" to it))))) }
        }
        assertFails { paper(mapOf("tables" to listOf(table(mapOf("style" to "grid"))))) }
        listOf<Any?>(null, "unknown").forEach { invalid ->
            val invalidRow = cell(extra = mapOf("row" to mapOf("node" to "x", "kind" to invalid)))
            assertFails { paper(mapOf("cells" to listOf(invalidRow))) }
        }
    }

    @Test fun `column checks preserve complete order and reject duplicates or unknown properties`() {
        val columns = listOf(mapOf("id" to "label", "header" to ""), mapOf("id" to "value", "header" to "Amount"))
        val parsed = paper(mapOf("tables" to listOf(table(mapOf("columns" to columns))))).tables.single()
        assertEquals(
            listOf(AcceptancePaperColumn("label", ""), AcceptancePaperColumn("value", "Amount")),
            parsed.columns,
        )
        assertFails { paper(mapOf("tables" to listOf(table(mapOf("columns" to listOf(columns[0], columns[0])))))) }
        assertFails {
            paper(mapOf("tables" to listOf(table(mapOf("columns" to listOf(columns[0] + ("width" to 10)))))))
        }
        assertFails { paper(mapOf("tables" to listOf(table(mapOf("columns" to null))))) }
    }

    @Test fun `strict shapes do not accept positional owners missing selectors or malformed addresses`() {
        listOf<Any?>(null, true, emptyMap<String, Any?>(), mapOf("rows" to emptyList<Any>())).forEach {
            assertFails { paper(it) }
        }
        listOf(
            cell() + ("anchor" to "t1-r1"),
            cell().filterKeys { it != "row" },
            cell() + ("row" to emptyMap<String, Any?>()),
            cell() + ("row" to mapOf("node" to " ")),
            cell() + ("row" to mapOf("node" to "x", "index" to 0)),
            cell() + ("address" to mapOf("node" to "x", "coord" to listOf(1))),
            cell() + ("address" to mapOf("node" to "x", "coord" to emptyList<String>(), "case" to null)),
            cell() + ("address" to mapOf("node" to "x", "coord" to List(9) { "A" })),
            cell() + ("editable" to null),
        ).forEach { assertFails { paper(mapOf("cells" to listOf(it))) } }
    }

    @Test fun `v2 budgets combine scalar and Paper selectors within case and suite bounds`() {
        val cells = (1..255).map { cell(node = "node-$it") }
        val candidate = mapOf("title" to "paper", "cells" to cells)
        assertEquals(256, paper(candidate).selectorCount)
        val value = mapOf("node" to "scalar", "present" to true, "value" to null)
        assertFails { parse(suite(listOf(case(expected = expectation(candidate, listOf(value)))))) }
        val cases = (1..8).map { case(id = "case-$it", expected = expectation(candidate)) }
        assertEquals(8, parse(suite(cases)).cases.size)
        val extra = case(id = "extra", expected = expectation(mapOf("title" to "x")))
        assertFails { parse(suite(cases + extra)) }
        assertFails { paper(mapOf("tables" to (1..65).map { table(mapOf("id" to "t$it")) })) }
        val manyColumns = (1..65).map { mapOf("id" to "c$it", "header" to "") }
        assertFails { paper(mapOf("tables" to listOf(table(mapOf("columns" to manyColumns))))) }
        assertFails { paper(mapOf("cells" to (1..257).map { cell(node = "n$it") })) }
    }

    @Test fun `strict duplicate key and UTF16 text checks also apply inside Paper fields`() {
        val tree = suite(listOf(case(expected = expectation(mapOf("title" to "ok")))))
        val text = WorkbenchJson.write(tree).replace("\"title\":\"ok\"", "\"title\":\"ok\",\"titl\\u0065\":\"other\"")
        assertFails { parseTemplateAcceptanceSuite(text.toByteArray(Charsets.UTF_8)) }
        assertEquals(65_536, paper(mapOf("title" to "x".repeat(65_536))).title!!.length)
        assertFails { paper(mapOf("title" to "x".repeat(65_537))) }
    }
}
