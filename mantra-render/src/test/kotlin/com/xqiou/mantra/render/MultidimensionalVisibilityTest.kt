package com.xqiou.mantra.render

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.paper.RowFlag
import com.xqiou.mantra.render.paper.RowKind
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MultidimensionalVisibilityTest {
    private fun calculate(lines: String, declarations: String = "", inputs: String = "{}") = Mantra.calculateForAudit(
        Mantra.loadSchema(
            SourceText(
                "visibility.mantra",
                """
                    (schema t/visibility
                      (dimension asset {:members [:A :B]})
                      (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
                      $declarations
                      (section bridge "Bridge" {:per [asset year]}
                        $lines))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        ),
        Mantra.loadCase(SourceText("case.mantra", "(case t/case (inputs $inputs))")),
    )

    private fun layout(style: String, columns: String, options: String = "", body: String = "") = LayoutReader.read(
        SourceText(
            "layout.mantra",
            """
                (layout t/visibility-paper
                  {:language :en :locale "en-US" :precision 2 :grouping false :zero nil
                   :hide-zero true :show-inactive false :negative :minus}
                  (table bridge {:style :$style :row-dimension asset $options} $columns)
                  $body)
            """.trimIndent(),
        ),
    )

    @Test
    fun `matrix and transpose retain zero results that explain nonzero input references`() {
        val result = calculate(
            """
            (line clamped "Clamped" (max 0 (- seed 100)) {:op :info})
            (line constant-zero "Constant zero" 0 {:op :info})
            """.trimIndent(),
            "(input seed :decimal {:per asset})",
            "{:seed {:A 50 :B 0}}",
        )
        assertTrue(result.succeeded)
        assertEquals(0, BigDecimal.ZERO.compareTo(result.decimal("clamped", "A", "P1")))
        val trace = assertIs<NodeTrace.Computed>(result.node("clamped").trace(listOf("A", "P1")))
        assertTrue(
            trace.references.any {
                it.id == "seed" && (it.value as? Value.Num)?.value?.compareTo(BigDecimal("50")) == 0
            },
        )

        val matrix = Render.paper(result, layout("matrix", ":label (members year) :cross-total")).tables.single()
        val rows = matrix.rows.filter { it.nodeId == "clamped" }
        assertEquals(2, rows.size)
        assertEquals(listOf("Clamped", "0.00", "0.00", "0.00"), rows.first().cells)
        assertEquals(listOf("A", "P1"), rows.first().valueAddresses[1]!!.coord)
        assertEquals(mapOf("asset" to "A"), rows.first().valueAddresses.last()!!.fixed)
        assertTrue(rows.all { RowFlag.EXPLAINS_ZERO in it.flags })
        assertFalse(matrix.rows.any { it.nodeId == "constant-zero" })

        val transpose = Render.paper(
            result,
            layout("transpose", ":label (node clamped)", ":fixed {:year :P1}"),
        ).tables.single()
        val members = transpose.rows.filter { it.kind == RowKind.MEMBER }
        assertEquals(listOf(listOf("A", "0.00")), members.map { it.cells })
        assertTrue(RowFlag.EXPLAINS_ZERO in members.single().flags)
        assertEquals(listOf("A", "P1"), members.single().valueAddresses[1]!!.coord)
    }

    @Test
    fun `ordinary paper filters zero and inactive members while complete paper preserves inactivity`() {
        val result = calculate(
            """
            (line balance "Balance" 0 {:when enabled :op :info})
            (line never "Never" 123 {:when false :op :info})
            """.trimIndent(),
            "(input enabled :boolean {:per asset})",
            "{:enabled {:A true :B false}}",
        )
        assertTrue(result.succeeded)
        assertTrue(result.node("balance").isActive(listOf("A", "P1")))
        assertFalse(result.node("balance").isActive(listOf("B", "P1")))
        assertFalse(result.node("never").isActive(listOf("A", "P1")))
        val spec = layout("transpose", ":label :status (node balance)", ":fixed {:year :P1}")
        assertTrue(Render.paper(result, spec).tables.single().rows.none { it.kind == RowKind.MEMBER })
        val zerosShown = Render.paper(result, spec.copy(hideZero = false)).tables.single()
        assertEquals(listOf("A"), zerosShown.rows.filter { it.kind == RowKind.MEMBER }.map { it.cells.first() })

        val complete = Render.completePaper(result, spec).tables.single()
        val inactive = complete.rows.single { it.kind == RowKind.MEMBER && it.cells.first() == "B" }
        assertEquals(listOf("B", "–", "n/a"), inactive.cells)
        assertTrue(RowFlag.INACTIVE in inactive.flags)
        assertEquals(listOf("B", "P1"), inactive.valueAddresses[2]!!.coord)
        val active = complete.rows.single { it.kind == RowKind.MEMBER && it.cells.first() == "A" }
        assertEquals(listOf("A", "", "0.00"), active.cells)
        assertFalse(RowFlag.INACTIVE in active.flags)

        val matrixSpec = layout("matrix", ":label :status (members year) :cross-total")
        assertFalse(Render.paper(result, matrixSpec).tables.single().rows.any { it.nodeId == "never" })
        val neverRows = Render.completePaper(result, matrixSpec).tables.single().rows.filter { it.nodeId == "never" }
        assertEquals(3, neverRows.size)
        assertTrue(neverRows.all { RowFlag.INACTIVE in it.flags })
        assertTrue(neverRows.all { it.cells == listOf("Never", "–", "n/a", "n/a", "n/a") })
    }

    @Test
    fun `signed deductions render negatively on both axes without changing engine values`() {
        val result = calculate("(line deduction \"Deduction\" 20 {:op :minus})")
        assertTrue(result.succeeded)
        val matrixSpec = layout("matrix", ":label (members year) :cross-total").copy(signedValues = true)
        val matrix = Render.paper(result, matrixSpec).tables.single()
        val memberRow = matrix.rows.first { it.nodeId == "deduction" }
        assertEquals(listOf("Deduction", "-20.00", "-20.00", "-40.00"), memberRow.cells)
        assertTrue(RowFlag.NEGATED in memberRow.flags)
        assertEquals(
            "20.00",
            Render.paper(result, matrixSpec.copy(signedValues = false)).tables.single()
                .rows.first { it.nodeId == "deduction" }.cells[1],
        )

        val transposeSpec = layout("transpose", ":label (node deduction)", ":fixed {:year :P1}")
            .copy(signedValues = true)
        val transpose = Render.paper(result, transposeSpec).tables.single()
        assertEquals(listOf("A", "-20.00"), transpose.rows.first().cells)
        assertEquals(listOf("Total", "-40.00"), transpose.rows.last().cells)
        assertTrue(transpose.rows.all { RowFlag.NEGATED in it.flags })
        assertEquals(listOf("A", "P1"), transpose.rows.first().valueAddresses[1]!!.coord)
        assertEquals(0, BigDecimal("20").compareTo(result.decimal("deduction", "A", "P1")))
    }

    @Test
    fun `status columns report member business checks and preserve failure precedence in totals`() {
        val result = calculate(
            "(check within-limit \"Within limit\" (<= basis 10))",
            "(input basis :decimal {:per asset})",
            "{:basis {:A 5 :B 15}}",
        )
        assertTrue(result.succeeded)
        assertFalse(result.validationPassed)
        val matrix = Render.paper(result, layout("matrix", ":label :status (members year)")).tables.single()
        val checks = matrix.rows.filter { it.nodeId == "within-limit" }
        assertEquals(listOf("Within limit", "✓", "✓", "✓"), checks[0].cells)
        assertEquals(listOf("Within limit", "✗", "✗", "✗"), checks[1].cells)
        assertEquals("✗", checks.last().cells[1])
        assertTrue(RowFlag.VALIDATION_PASSED in checks[0].flags)
        assertTrue(RowFlag.VALIDATION_FAILED in checks[1].flags)

        val transpose = Render.paper(
            result,
            layout("transpose", ":label :status (node within-limit)", ":fixed {:year :P1}"),
        ).tables.single()
        assertEquals(listOf("A", "✓", "✓"), transpose.rows[0].cells)
        assertEquals(listOf("B", "✗", "✗"), transpose.rows[1].cells)
        assertEquals("Σ✗", transpose.rows.last().cells[1])
        assertTrue(RowFlag.VALIDATION_FAILED in transpose.rows.last().flags)
        assertFalse(RowFlag.VALIDATION_PASSED in transpose.rows.last().flags)
    }

    @Test
    fun `explicit transpose node columns obey schema and layout hiding even in complete paper`() {
        val result = calculate(
            """
            (line visible "Visible" 10 {:op :info})
            (line schema-hidden "Schema hidden" 99 {:op :info :hidden true})
            (line layout-hidden "Layout hidden" 77 {:op :info})
            (section hidden-ancestor "Hidden ancestor" {:hidden true}
              (line ancestor-hidden "Ancestor hidden" 66 {:op :info}))
            (section hidden-display "Hidden display" {:display :hidden}
              (line display-hidden-child "Display hidden child" 55 {:op :info}))
            """.trimIndent(),
        )
        val spec = layout(
            "transpose",
            ":label (node visible) (node schema-hidden) (node layout-hidden) " +
                "(node ancestor-hidden) (node display-hidden-child)",
            ":fixed {:year :P1}",
            "(hide layout-hidden)",
        )
        for (paper in listOf(Render.paper(result, spec), Render.completePaper(result, spec))) {
            val table = paper.tables.single()
            assertEquals(listOf("Description", "Visible"), table.columns.map { it.header })
            assertEquals(listOf("visible"), table.columns.mapNotNull { (it.content as? ColumnContent.Node)?.nodeId })
            assertEquals(listOf("A", "10.00"), table.rows.first().cells)
            assertTrue(table.rows.all { row -> row.valueAddresses.filterNotNull().all { it.nodeId == "visible" } })
            assertFalse(
                paper.audit.any {
                    it.nodeId in setOf("schema-hidden", "layout-hidden", "ancestor-hidden", "display-hidden-child")
                },
            )
        }
    }
}
