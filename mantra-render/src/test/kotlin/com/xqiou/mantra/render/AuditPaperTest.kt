package com.xqiou.mantra.render

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.Presets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuditPaperTest {
    private fun schema(body: String) = Mantra.loadSchema(
        SourceText("paper.mantra", "(schema t/paper {} (section main \"Main\" $body))"),
        SourceResolver { _, _ -> null },
    )

    @Test
    fun `Text and HTML audit show the same exact executed trace as Explain`() {
        val schema = schema("(line amount \"Amount\" (if true (* 1.123456789 2) (- 99 1)))")
        val result = Mantra.calculateForAudit(schema)
        val paper = Render.paper(result, Presets.IFRS_SCHEDULE)
        val entry = paper.audit.single()
        val trace = assertNotNull((result.node("amount").trace() as NodeTrace.Computed).explanation)
        assertEquals(trace, entry.explanation)
        assertEquals("amount", entry.nodeId)
        assertTrue("(* 1.123456789 2) = 2.246913578" in entry.working)
        assertTrue("selected branch: (* 1.123456789 2)" in entry.working)
        assertFalse("(- 99 1) =" in entry.working)
        assertTrue(entry.working in Render.text(result, Presets.IFRS_SCHEDULE, includeAudit = true))
        assertTrue("2.246913578" in Render.html(result, Presets.IFRS_SCHEDULE))
    }

    @Test
    fun `unrequested and truncated source evidence are visible instead of invented substitutions`() {
        val schema = schema("(line amount \"Amount\" (+ 1 2))")
        val unrequested = Render.paper(Mantra.calculate(schema), Presets.IFRS_SCHEDULE).audit.single()
        assertTrue("Audit trace was not requested" in unrequested.working)
        val bounded = Mantra.calculateForAudit(schema, options = AuditOptions(maxCharacters = 0))
        val truncated = Render.paper(bounded, Presets.IFRS_SCHEDULE).audit.single()
        assertTrue("Audit trace truncated: budget reached" in truncated.working)
        assertEquals(Mantra.calculate(schema).value("amount"), bounded.value("amount"))
    }

    @Test
    fun `business failures and exact reconciliation evidence appear in paper status and audit`() {
        val schema = schema(
            """
            (line base-amount "Amount" 5)
            (check positive "Positive" (> base-amount 0))
            (check too-large "Too large" (> base-amount 10))
            (reconcile balance "Balance" base-amount 4 {:tolerance 0.25})
            (reconcile matching "Matching" base-amount 5)
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(schema)
        val layout = LayoutReader.read(
            SourceText(
                "layout.mantra",
                "(layout t/paper {:preset :ifrs-schedule :precision 2 :zero nil :hide-zero true} " +
                    "(columns :tiered :label :status :value :explain))",
            ),
        )
        val paper = Render.paper(result, layout)
        val table = paper.tables.single()
        val status = table.columns.indexOfFirst { it.content == ColumnContent.Status }
        val amount = table.columns.indexOfFirst { it.content == ColumnContent.Value }
        assertEquals("✓", table.rows.single { it.nodeId == "positive" }.cells[status])
        assertEquals("✗", table.rows.single { it.nodeId == "too-large" }.cells[status])
        assertEquals("✗", table.rows.single { it.nodeId == "balance" }.cells[status])
        assertEquals("1.00", table.rows.single { it.nodeId == "balance" }.cells[amount])
        assertEquals("✓", table.rows.single { it.nodeId == "matching" }.cells[status])
        assertEquals("0.00", table.rows.single { it.nodeId == "matching" }.cells[amount])
        val balance = paper.audit.single { it.nodeId == "balance" }
        assertTrue("5 − 4 = 1 (± 0.25)" in balance.working)
        val explained = Mantra.calculateForExplain(schema, result.case, emptyList(), "balance")
        val direct = requireNotNull(explained.explainTrace)
        val captured = requireNotNull(balance.explanation)
        assertEquals(
            direct.copy(
                steps = direct.steps.map {
                    it.copy(eventId = null)
                },
                branches = direct.branches.map { it.copy(eventId = null) },
            ),
            captured.copy(
                steps = captured.steps.map {
                    it.copy(eventId = null)
                },
                branches = captured.branches.map { it.copy(eventId = null) },
            ),
        )
        assertTrue(direct.steps.all { !it.eventId.isNullOrBlank() })
        assertTrue(captured.steps.all { !it.eventId.isNullOrBlank() })
        assertTrue(
            direct.steps.map {
                it.eventId
            } != captured.steps.map { it.eventId },
            "Kernel event identities belong to their actual execution attempt",
        )
        assertTrue("MANTRA-CHECK-FAILED" in Render.text(result, layout, includeAudit = true))
        assertTrue(result.succeeded)
    }

    @Test
    fun `weighted totals use engine aggregation evidence rather than summing displayed member ratios`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "ratio.mantra",
                """
                (schema t/ratio {}
                  (dimension member {:members [:A :B]})
                  (input charge :decimal {:per member})
                  (input units :decimal {:per member})
                  (section detail "Detail" {:per member}
                    (line ratio "Ratio" (decimal/divide charge units 8)
                      {:aggregate {:ratio [charge units] :round [8 :half-up]}}))
                  (total checkpoint "Checkpoint"))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val result = Mantra.calculateForAudit(
            schema,
            Mantra.loadCase(
                SourceText(
                    "case.mantra",
                    "(case c (inputs " +
                        "{:charge {:A 65400 :B 14400} :units {:A 240000 :B 80000}}))",
                ),
            ),
        )
        val paper = Render.paper(result, Presets.IFRS_SCHEDULE)
        val aggregate = paper.audit.single { it.nodeId == "aggregate.ratio" }
        assertEquals(result.node("ratio").aggregateTrace, aggregate.aggregate)
        assertTrue(aggregate.coord.isEmpty())
        assertEquals(null, aggregate.explanation)
        assertTrue("[member=A] 65400 / 240000 included" in aggregate.working)
        assertTrue("79800 ÷ 320000" in aggregate.working)
        assertTrue("0.24937500" in aggregate.working)
        assertTrue(aggregate.working in paper.audit.single { it.nodeId == "checkpoint" }.working)
        assertTrue(aggregate.working in Render.text(result, Presets.IFRS_SCHEDULE, includeAudit = true))
        assertTrue("Σ charge ÷ Σ units" in Render.html(result, Presets.IFRS_SCHEDULE))
    }

    @Test
    fun `zero reconciliation inside an otherwise zero section survives hide zero`() {
        val result = Mantra.calculateForAudit(schema("(section checks \"Checks\" (reconcile match \"Match\" 5 5))"))
        val layout = LayoutReader.read(
            SourceText(
                "layout.mantra",
                "(layout t/paper {:preset :ifrs-schedule :hide-zero true} (columns :tiered :label :status :value))",
            ),
        )
        val paper = Render.paper(result, layout)
        assertTrue(paper.tables.flatMap { it.rows }.any { it.nodeId == "match" })
        assertTrue(paper.audit.any { it.nodeId == "match" })
    }
}
