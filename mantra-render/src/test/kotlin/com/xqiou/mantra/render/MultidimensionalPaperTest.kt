package com.xqiou.mantra.render

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.RowNumberMode
import com.xqiou.mantra.render.paper.RowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultidimensionalPaperTest {
    private fun calculate() = Mantra.calculateForAudit(
        Mantra.loadSchema(
            SourceText(
                "matrix.mantra",
                """
            (schema t/multidimensional {}
              (dimension asset {:members [:A :B]})
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (input seed :decimal {:per asset})
              (section bridge "Bridge" {:per [asset year]}
                (line opening "Opening" (+ seed (* 20 year.index)) {:op :info :aggregate {:first year}})
                (line movement "Movement" 20 {:op :info})
                (line closing "Closing" (+ seed (* 20 (+ year.index 1))) {:op :info :aggregate {:last year}})))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        ),
        Mantra.loadCase(SourceText("case.mantra", "(case t/case {} (inputs {:seed {:A 100 :B 200}}))")),
    )

    private fun layout(style: String, columns: String, opts: String = "") = LayoutReader.read(
        SourceText(
            "layout.mantra",
            """
            (layout t/paper {:language :en :locale "en-US" :precision 2 :hide-zero false}
              (table bridge {:style :$style :row-dimension asset $opts} $columns))
            """.trimIndent(),
        ),
    )

    @Test
    fun `matrix groups one axis and preserves first last and flow reductions on the other`() {
        val result = calculate()
        val spec = layout("matrix", ":label (members year) :cross-total")
        val paper = Render.paper(result, spec)
        val table = paper.tables.single()
        assertEquals(
            listOf(
                "Description",
                "2026-01-01 – 2026-12-31",
                "2027-01-01 – 2027-12-31",
                "2028-01-01 – 2028-12-31",
                "Total",
            ),
            table.columns.map {
                it.header
            },
        )
        val closings = table.rows.filter { it.nodeId == "closing" }
        assertEquals(listOf("Closing", "120.00", "140.00", "160.00", "160.00"), closings[0].cells)
        assertEquals(listOf("Closing", "220.00", "240.00", "260.00", "260.00"), closings[1].cells)
        assertEquals(listOf("Closing", "340.00", "380.00", "420.00", "420.00"), closings[2].cells)
        assertEquals(
            listOf("100.00", "200.00", "300.00"),
            table.rows.filter {
                it.nodeId == "opening"
            }.map { it.cells.last() },
        )
        assertEquals(
            listOf("60.00", "60.00", "120.00"),
            table.rows.filter {
                it.nodeId == "movement"
            }.map { it.cells.last() },
        )
        assertEquals(listOf("A", "P2"), closings[0].valueAddresses[2]!!.coord)
        assertEquals(mapOf("asset" to "A"), closings[0].valueAddresses.last()!!.fixed)
        assertTrue(closings[0].valueAddresses.last()!!.aggregate)
        assertEquals(emptyMap(), closings[2].valueAddresses.last()!!.fixed)
        assertTrue(Render.html(result, spec).contains("420.00"))
        assertTrue(Render.text(result, spec).contains("420.00"))
        assertTrue(paper.audit.any { it.nodeId == "aggregate.closing" && it.working.contains("last(year)") })
        val numbered = Render.paper(result, spec.copy(rowNumbers = RowNumberMode.TABLE)).tables.single()
        assertEquals((1..9).map(Int::toString), numbered.rows.filter { it.nodeId != null }.map { it.cells.first() })
    }

    @Test
    fun `transpose keeps distinct node addresses and applies a fixed period slice without changing values`() {
        val result = calculate()
        val spec = layout("transpose", ":label (node opening) (node movement) (node closing)")
        val table = Render.paper(result, spec).tables.single()
        assertEquals(listOf("Description", "Opening", "Movement", "Closing"), table.columns.map { it.header })
        assertEquals(listOf("A", "100.00", "60.00", "160.00"), table.rows[0].cells)
        assertEquals(listOf("Total", "300.00", "120.00", "420.00"), table.rows.last().cells)
        assertEquals(RowKind.TOTAL, table.rows.last().kind)
        assertEquals("movement", table.rows[0].valueAddresses[2]!!.nodeId)
        assertEquals(mapOf("asset" to "A"), table.rows[0].valueAddresses[2]!!.fixed)
        val sliced = Render.paper(
            result,
            layout("transpose", ":label (node closing)", ":fixed {:year :P2}"),
        ).tables.single()
        assertEquals(listOf("A", "140.00"), sliced.rows[0].cells)
        assertEquals(listOf("A", "P2"), sliced.rows[0].valueAddresses[1]!!.coord)
        assertTrue(!sliced.rows[0].valueAddresses[1]!!.aggregate)
        assertEquals(listOf("Total", "380.00"), sliced.rows.last().cells)
    }

    @Test
    fun `invalid axes and node columns are rejected instead of silently rendering empty amounts`() {
        val result = calculate()
        for (spec in listOf(
            layout("matrix", ":label (members asset)"),
            layout("transpose", ":label (node unknown)"),
            layout("transpose", ":label (node closing)", ":fixed {:year :missing}"),
        )) {
            val error = assertFailsWith<MantraException> { Render.paper(result, spec) }
            assertTrue(error.diagnostics.any { it.code == "MANTRA-LAYOUT-AXES" })
        }
    }

    @Test
    fun `fixed parent period scopes both member columns and stock subtotal addresses`() {
        val result = Mantra.calculateForAudit(
            Mantra.loadSchema(
                SourceText(
                    "parents.mantra",
                    """
            (schema t/parents
              (dimension asset {:members [:A]})
              (dimension quarter {:periods {:start "2026-04-01" :unit :quarter :count 2}})
              (dimension month {:periods {:start "2026-04-01" :unit :month :count 6} :parent quarter})
              (section bridge "Bridge" {:per [asset month]}
                (line balance "Balance" (+ month.index 10) {:aggregate {:last month}})))
                    """.trimIndent(),
                ),
                SourceResolver { _, _ -> null },
            ),
            Mantra.loadCase(SourceText("case.mantra", "(case c)")),
        )
        val table = Render.paper(
            result,
            layout("matrix", ":label (members month) :cross-total", ":fixed {:quarter :P1}"),
        ).tables.single()
        val row = table.rows.first { it.nodeId == "balance" }
        assertEquals(listOf("Balance", "10.00", "11.00", "12.00", "", "", "", "12.00"), row.cells)
        assertEquals(mapOf("quarter" to "P1", "asset" to "A"), row.valueAddresses.last()!!.fixed)
        assertEquals(listOf("A", "P3"), row.valueAddresses[3]!!.coord)
        assertEquals(null, row.valueAddresses[4])
    }
}
