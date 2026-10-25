package com.xqiou.mantra.cli

import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplatePaperAcceptanceRunnerTest {
    @TempDir lateinit var root: Path

    private fun workspace(matrix: Boolean = false, transpose: Boolean = false): WorkspaceCatalog {
        val schema = if (matrix || transpose) {
            """
            (schema test/paper {:version "1" :mainline [main]}
              (dimension entity {:members [:north/south :other]})
              (dimension period {:members [:Y1 :Y2]})
              (section main "Summary" {:panel true :per [entity period]}
                (info answer "Answer" 7 {:aggregate :sum})))
            """
        } else {
            """
            (schema test/paper {:version "1" :mainline [main]}
              (input base-amount :decimal)
              (section main "Summary" {:panel true}
                (field base-amount "Amount" {:op :info})
                (info answer "Answer" (* base-amount 2) {:class :custom})
                (info all-zero "Zero" 0)))
            """
        }
        val columns = when {
            matrix ->
                """(table main {:style :matrix :row-dimension entity}
                (col :label {:header "Label"})
                (col :first {:content (member period :Y1) :header "First"})
                (col :second {:content (member period :Y2) :header "Second"}) :cross-total)"""
            transpose ->
                """(table main {:style :transpose :row-dimension entity}
                (col :label {:header "Label"}) (node answer))"""
            else ->
                """(table main {:style :tiered}
                (col :label {:header "Label"}) (col :value {:header "Amount"}))"""
        }
        Files.writeString(root.resolve("schema.mantra"), schema.trimIndent())
        Files.writeString(
            root.resolve("layout.mantra"),
            """(layout test/paper-layout {:title "Original" :language :en :locale "de-DE"
                :precision 2 :hide-zero true :zero "–"}
                (style-class :custom {:weight :bold :tone :muted :fill :subtle}) $columns)""",
        )
        Files.writeString(
            root.resolve("case.mantra"),
            "(case example {:schema \"test/paper\" :layout \"test/paper-layout\"} " +
                if (matrix || transpose) "(inputs {}))" else "(inputs {:base-amount 10}))",
        )
        return WorkspaceCatalog(root)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cases(suite: Map<String, Any?>): List<Map<String, Any?>> = suite["cases"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun results(run: AcceptanceRun): List<Map<String, Any?>> = run.report["cases"] as List<Map<String, Any?>>

    private fun parse(suite: Map<String, Any?>): TemplateAcceptanceSuite =
        parseTemplateAcceptanceSuite(WorkbenchJson.write(suite).toByteArray(Charsets.UTF_8))

    private fun expected(
        paper: Map<String, Any?>? = null,
        values: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>("succeeded" to true, "validationPassed" to true, "values" to values).apply {
            if (paper != null) put("paper", paper)
        }

    private fun cell(
        node: String,
        text: String? = null,
        present: Boolean = true,
        column: String = "value",
        coord: List<String> = emptyList(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "table" to "main",
        "column" to column,
        "address" to mapOf("node" to node, "coord" to coord),
        "present" to present,
    ).apply { if (present) put("text", text ?: "20,00") }

    private fun withCases(capture: AcceptanceCapture, expectations: List<Map<String, Any?>?>): Map<String, Any?> =
        capture.suite + (
            "cases" to expectations.mapIndexed { index, expectation ->
                cases(capture.suite).first() + mapOf("id" to "example-$index", "expect" to expectation)
            }
            )

    @Test fun `v2 preserves scalar report shape apart from format input digest and explicit Paper`() {
        workspace().use { catalog ->
            val one = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"))
            val two = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"), TEMPLATE_ACCEPTANCE_FORMAT_V2)
            assertEquals(TEMPLATE_ACCEPTANCE_FORMAT, one.suite["format"])
            assertEquals(TEMPLATE_ACCEPTANCE_FORMAT_V2, two.suite["format"])
            assertTrue(cases(two.suite).all { it["expect"] == null })
            val v1 = runTemplateAcceptanceSuite(catalog, parse(withCases(one, listOf(expected()))))
            val v2 = runTemplateAcceptanceSuite(catalog, parse(withCases(two, listOf(expected()))))
            assertEquals(0, v1.status)
            assertEquals(0, v2.status)
            assertFalse(results(v1).single().containsKey("paper"))
            assertNotNull(results(v2).single()["paper"])
            val normalized = v2.report + mapOf(
                "format" to "mantra.template-acceptance-report/1",
                "suiteSha256" to v1.report["suiteSha256"],
                "cases" to results(v2).map { it - "paper" },
            )
            assertEquals(WorkbenchJson.write(v1.report), WorkbenchJson.write(normalized))
        }
    }

    @Test fun `layout changes verify final style without changing Run values`() {
        workspace().use { catalog ->
            val original = Files.list(root).use { it.toList().associateWith(Files::readAllBytes) }
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"), TEMPLATE_ACCEPTANCE_FORMAT_V2)
            val paper = mapOf(
                "title" to "Candidate",
                "tables" to listOf(
                    mapOf(
                        "id" to "main",
                        "present" to true,
                        "title" to "Summary",
                        "style" to "tiered",
                        "columns" to listOf(
                            mapOf("id" to "label", "header" to "Label"),
                            mapOf("id" to "value", "header" to "Amount"),
                        ),
                    ),
                ),
                "cells" to listOf(
                    cell("answer", "20,000") + mapOf(
                        "editable" to false,
                        "style" to mapOf("weight" to "bold", "tone" to "accent", "fill" to "accent"),
                    ),
                    mapOf(
                        "table" to "main",
                        "column" to "label",
                        "row" to mapOf("node" to "answer"),
                        "present" to true,
                        "text" to "Answer",
                    ),
                ),
            )
            val tree = withCases(
                capture,
                listOf(
                    expected(
                        paper,
                        listOf(
                            mapOf("node" to "answer", "present" to true, "value" to mapOf("n" to "20")),
                        ),
                    ),
                ),
            )

            @Suppress("UNCHECKED_CAST")
            val documents = capture.suite["documents"] as List<Map<String, Any?>>
            val changed = tree + (
                "documents" to documents.map { document ->
                    if (document["role"] != "layout") {
                        document
                    } else {
                        document + (
                            "text" to (document["text"] as String)
                                .replace("Original", "Candidate").replace(":precision 2", ":precision 3")
                                .replace(":tone :muted", ":tone :accent").replace(":fill :subtle", ":fill :accent")
                            )
                    }
                }
                )
            val run = runTemplateAcceptanceSuite(catalog, parse(changed))
            assertEquals(0, run.status, WorkbenchJson.write(run.report))
            assertEquals("Candidate", (results(run).single()["paper"] as Map<*, *>)["title"])
            val difference = results(run).single()["difference"] as Map<*, *>
            assertTrue((difference["changes"] as List<*>).isEmpty())
            original.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        }
    }

    @Test fun `includeZero changes real row visibility without inventing missing tables or columns`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"), TEMPLATE_ACCEPTANCE_FORMAT_V2)
            val hidden = expected(
                mapOf(
                    "cells" to listOf(cell("all-zero", present = false)),
                    "tables" to listOf(mapOf("id" to "not-rendered", "present" to false)),
                ),
            )
            val shown = expected(mapOf("cells" to listOf(cell("all-zero", "–"))))
            val examples = cases(withCases(capture, listOf(hidden, shown))).mapIndexed { index, example ->
                example + ("includeZero" to (index == 1))
            }
            val run = runTemplateAcceptanceSuite(catalog, parse(capture.suite + ("cases" to examples)))
            assertEquals(0, run.status, WorkbenchJson.write(run.report))
            assertTrue(results(run).all { it["status"] == "passed" })
        }
    }

    @Test fun `failed text and invalid parent selectors preserve Paper while independent cases continue`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"), TEMPLATE_ACCEPTANCE_FORMAT_V2)
            val run = runTemplateAcceptanceSuite(
                catalog,
                parse(
                    withCases(
                        capture,
                        listOf(
                            expected(mapOf("cells" to listOf(cell("answer", "999")))),
                            expected(
                                mapOf("cells" to listOf(cell("all-zero", present = false, column = "misspelled"))),
                            ),
                            expected(mapOf("title" to "Original")),
                        ),
                    ),
                ),
            )
            assertEquals(2, run.status)
            assertEquals(listOf("failed", "error", "passed"), results(run).map { it["status"] })
            assertTrue(results(run).all { it["paper"] != null })
        }
    }

    @Test fun `technical preview rejection has null Paper rather than another scenario or saved rendering`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"), TEMPLATE_ACCEPTANCE_FORMAT_V2)
            val tree = withCases(capture, listOf(expected(mapOf("title" to "Original"))))
            val invalid = cases(tree).single() + ("inputs" to listOf(mapOf("node" to "base-amount", "text" to "nine")))
            val run = runTemplateAcceptanceSuite(catalog, parse(tree + ("cases" to listOf(invalid))))
            assertEquals(2, run.status)
            assertEquals("error", results(run).single()["status"])
            assertNull(results(run).single()["paper"])
        }
    }

    @Test fun `a final source conflict retains the candidate Paper but invalidates acceptance`() {
        workspace().use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"), TEMPLATE_ACCEPTANCE_FORMAT_V2)
            val run = runTemplateAcceptanceSuite(
                catalog,
                parse(
                    withCases(
                        capture,
                        listOf(expected(mapOf("title" to "Original"))),
                    ),
                ),
            ) {
                Files.writeString(
                    root.resolve("layout.mantra"),
                    Files.readString(root.resolve("layout.mantra")) + "\n; external",
                )
            }
            assertEquals(2, run.status)
            assertEquals("conflict", results(run).single()["status"])
            assertEquals("Original", (results(run).single()["paper"] as Map<*, *>)["title"])
            assertTrue((results(run).single()["assertions"] as List<*>).isNotEmpty())
        }
    }

    @Test fun `matrix addresses keep slash members as exact tuples without reading flattened Run keys`() {
        workspace(matrix = true).use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"), TEMPLATE_ACCEPTANCE_FORMAT_V2)
            val paper = mapOf(
                "tables" to listOf(mapOf("id" to "main", "present" to true, "style" to "matrix")),
                "cells" to listOf(
                    cell("answer", "7,00", column = "first", coord = listOf("north/south", "Y1")),
                    cell("answer", "7,00", column = "second", coord = listOf("other", "Y2")),
                ),
            )
            val run = runTemplateAcceptanceSuite(catalog, parse(withCases(capture, listOf(expected(paper)))))
            assertEquals(0, run.status, WorkbenchJson.write(run.report))
            val assertions = results(run).single()["assertions"] as List<*>
            assertTrue(assertions.any { ((it as Map<*, *>)["path"] as String).contains("north~1south") })
        }
    }

    @Test fun `transpose uses the same candidate Paper with authoritative rendered aggregate text`() {
        workspace(transpose = true).use { catalog ->
            val capture = captureTemplateAcceptanceSuite(catalog, listOf("case.mantra"), TEMPLATE_ACCEPTANCE_FORMAT_V2)
            val paper = mapOf("tables" to listOf(mapOf("id" to "main", "present" to true, "style" to "transpose")))
            val run = runTemplateAcceptanceSuite(catalog, parse(withCases(capture, listOf(expected(paper)))))
            assertEquals(0, run.status, WorkbenchJson.write(run.report))
            val rendered = results(run).single()["paper"] as Map<*, *>
            assertTrue(WorkbenchJson.write(rendered).contains("14,00")) // Two periods of 7 for each entity.
            assertTrue(WorkbenchJson.write(rendered).contains("28,00")) // Two entities, two periods of 7.
        }
    }

    private fun syntheticPaper(rows: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "title" to "Synthetic protocol boundary",
        "tables" to listOf(
            mapOf(
                "id" to "main",
                "columns" to listOf(mapOf("id" to "value", "header" to "Value")),
                "rows" to rows,
            ),
        ),
    )

    private fun row(
        node: String? = "answer",
        coord: List<String> = emptyList(),
        case: String? = null,
    ): Map<String, Any?> = mapOf(
        "node" to node,
        "kind" to "value",
        "optionKey" to null,
        "cells" to listOf(
            mapOf(
                "text" to "7",
                "editable" to false,
                "style" to mapOf("weight" to "normal", "tone" to "default", "fill" to "none"),
                "address" to mapOf("case" to case, "node" to "answer", "coord" to coord),
            ),
        ),
    )

    private fun paperSpec(tree: Map<String, Any?>): AcceptancePaperExpectation {
        val cell = tree["cells"] as? List<*> ?: emptyList<Any>()

        @Suppress("UNCHECKED_CAST")
        val values = cell as List<Map<String, Any?>>
        return AcceptancePaperExpectation(
            null,
            emptyList(),
            values.map { value ->
                val addr = value["address"] as? Map<*, *>
                val target = value["row"] as? Map<*, *>
                AcceptancePaperCell(
                    value["table"] as String, value["column"] as String,
                    target?.let { AcceptancePaperRow(it["node"] as String?, null, null, setOf("node")) },
                    addr?.let {
                        AcceptancePaperAddress(
                            it["node"] as String,
                            (it["coord"] as List<*>).map { item -> item as String },
                        )
                    },
                    value["present"] as Boolean, value["text"] as String?, null, null, value.keys,
                )
            },
            setOf("cells"),
        )
    }

    @Test fun `ambiguous rows and duplicate parent ids cannot silently choose a first object`() {
        val target = paperSpec(mapOf("cells" to listOf(cell("answer", "7"))))
        val paper = syntheticPaper(listOf(row(), row()))
        assertFailsWith<IllegalArgumentException> { paperAcceptanceAssertions(target, paper) }
        val table = (paper["tables"] as List<*>).single() as Map<*, *>
        assertFailsWith<IllegalArgumentException> {
            paperAcceptanceAssertions(target, paper + ("tables" to listOf(table, table)))
        }
        assertFailsWith<IllegalArgumentException> {
            paperAcceptanceAssertions(
                target,
                paper + (
                    "tables" to listOf(
                        table + (
                            "columns" to listOf(
                                mapOf("id" to "value", "header" to "One"),
                                mapOf("id" to "value", "header" to "Two"),
                            )
                            ),
                    )
                    ),
            )
        }
    }

    @Test fun `typed cell tuples preserve empty members and distinguish foreign case addresses`() {
        val exact = cell("answer", "7", coord = listOf("", "north/south"))
        val target = paperSpec(mapOf("cells" to listOf(exact)))
        assertTrue(
            paperAcceptanceAssertions(target, syntheticPaper(listOf(row(coord = listOf("", "north/south")))))
                .all { it["passed"] == true },
        )
        val foreign = paperAcceptanceAssertions(
            target,
            syntheticPaper(listOf(row(coord = listOf("", "north/south"), case = "linked-case.mantra"))),
        )
        assertTrue(foreign.any { it["passed"] == false })
        val swapped = paperAcceptanceAssertions(target, syntheticPaper(listOf(row(coord = listOf("north/south", "")))))
        assertTrue(swapped.any { it["passed"] == false })
    }

    @Test fun `missing parents are errors even when the row is expected absent`() {
        val missing = paperSpec(mapOf("cells" to listOf(cell("all-zero", present = false, column = "typo"))))
        assertFailsWith<IllegalArgumentException> { paperAcceptanceAssertions(missing, syntheticPaper(emptyList())) }
        val absent = paperSpec(mapOf("cells" to listOf(cell("all-zero", present = false))))
        assertFailsWith<IllegalArgumentException> {
            paperAcceptanceAssertions(absent, mapOf("tables" to emptyList<Any>()))
        }
    }
}
