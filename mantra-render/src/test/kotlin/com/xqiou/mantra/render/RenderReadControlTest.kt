package com.xqiou.mantra.render

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellationSource
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RenderReadControlTest {
    private fun result(seed: String = "7") = Mantra.calculateForAudit(
        Mantra.loadSchema(
            SourceText(
                "matrix.mantra",
                """
            (schema test/reading
              (dimension entity {:members [:A :B]})
              (dimension year {:members [:Y1 :Y2]})
              (section main "Amounts" {:per [entity year]} (line balance "Balance" $seed {:aggregate :sum})))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        ),
    )
    private fun layout() = LayoutReader.read(
        SourceText(
            "layout.mantra",
            """
        (layout test/paper {:language :en :locale "en-GB" :hide-zero false :precision 2}
          (table main {:style :matrix :row-dimension entity} :label (members year) :cross-total))
            """.trimIndent(),
        ),
    )

    @Test fun `two rendered papers consume one caller owned scan allowance without changing calculation usage`() {
        val calculated = result()
        val usage = calculated.usage
        Render.paper(calculated.view, layout()) // Warm immutable reduction memo; only actual work is charged.
        val scans = calculated.view.openReader().use { reader ->
            Render.paper(calculated.view, layout(), reader)
            reader.usage[RunCounter.HOST_SCANS]
        }
        assertTrue(scans > 0)
        calculated.view.openReader(CalculationOptions(limits = RunLimits(maxHostScans = scans))).use { reader ->
            assertTrue(Render.paper(calculated.view, layout(), reader).tables.isNotEmpty())
            assertEquals(scans, reader.usage[RunCounter.HOST_SCANS])
            val failure = assertFailsWith<MantraException> { Render.paper(calculated.view, layout(), reader) }
            assertEquals("MANTRA-RUN-LIMIT", failure.diagnostics.single().code)
            assertEquals(RunCounter.HOST_SCANS, failure.runFailure?.counter)
        }
        assertSame(usage, calculated.usage)
        assertEquals("7", calculated.value("balance", "A", "Y1").toString())
    }

    @Test fun `cancelled paper construction fails before returning any paper and leaves view reusable`() {
        val calculated = result()
        val signal = RunCancellationSource().also { it.cancel() }
        val failure = assertFailsWith<MantraException> {
            Render.completePaper(calculated.view, layout(), CalculationOptions(control = RunControl(signal)))
        }
        assertEquals("MANTRA-RUN-CANCELLED", failure.diagnostics.single().code)
        assertTrue(Render.paper(calculated.view, layout()).tables.isNotEmpty())
    }

    @Test fun `one reader renders another source view using that source values and reductions`() {
        val root = result()
        val source = result("9")
        root.view.openReader().use { reader ->
            val paper = Render.paper(source.view, layout(), reader)
            val rows = paper.tables.single().rows.filter { it.nodeId == "balance" }
            assertTrue(rows.isNotEmpty())
            val members = rows.filter { row -> row.valueAddresses.any { it != null && !it.aggregate } }
            assertEquals(2, members.size)
            assertTrue(members.all { "9.00" in it.cells && "18.00" in it.cells }, members.map { it.cells }.toString())
            assertTrue(rows.any { "36.00" in it.cells }, rows.map { it.cells }.toString())
            assertTrue(rows.none { "7.00" in it.cells || "14.00" in it.cells })
            val before = reader.usage[RunCounter.HOST_SCANS]
            Render.completePaper(root.view, layout(), reader)
            assertTrue(reader.usage[RunCounter.HOST_SCANS] > before)
        }
        assertEquals("7", root.value("balance", "A", "Y1").toString())
        assertEquals("9", source.value("balance", "A", "Y1").toString())
    }
}
