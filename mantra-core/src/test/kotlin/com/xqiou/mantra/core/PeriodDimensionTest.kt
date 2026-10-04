package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.BoundaryAggregateTrace
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeriodDimensionTest {
    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text.trimIndent()), SourceResolver { _, _ -> null })
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text.trimIndent()))
    private fun number(expected: String, actual: Value?) =
        assertEquals(0, BigDecimal(expected).compareTo((actual as Value.Num).value))

    @Test
    fun `business findings in an empty derived domain do not block a later period domain or group total`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/empty-domain
              (input rows :table {:columns {:id :keyword :name :text} :min-rows 1})
              (dimension asset {:from rows :key :id :title :name})
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (line closing "Closing" (prev closing 10) {:per [asset year] :aggregate {:last year}})
              (total group "Group"))
        """,
            ),
            case("(case c (inputs {:rows []}))"),
        )
        assertTrue(result.succeeded)
        assertFalse(result.validationPassed)
        assertEquals("MANTRA-INPUT-MIN-ROWS", result.diagnostics.single().code)
        assertEquals(DiagnosticCategory.BUSINESS, result.diagnostics.single().category)
        assertEquals(emptyList(), result.members.getValue("asset"))
        assertEquals(2, result.members.getValue("year").size)
        number("0", result.value("group"))
    }

    @Test
    fun `a prior member condition evaluation failure remains a partial result when periods resolve`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/partial-domain
              (input divisor :decimal)
              (dimension asset {:members [{:key :A :when (> (/ 1 divisor) 0)}]})
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (line flow "Flow" 1 {:per [asset year]})
              (total group "Group"))
        """,
            ),
            case("(case c (inputs {:divisor 0}))"),
        )
        assertFalse(result.succeeded)
        assertTrue(result.diagnostics.all { it.category == DiagnosticCategory.EVALUATION })
        assertEquals(2, result.members.getValue("year").size)
        number("0", result.value("group"))
    }

    @Test
    fun `boundary rollup accepts literal ordered vectors and preserves selected nil or absent values`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/literal-rollup
              (dimension quarter {:periods {:start "2026-01-01" :unit :quarter :count 2}})
              (line absent "Absent" (dim/rollup {:P2 20.0} {:P1 :P1 :P2 :P1} quarter.key :first [:P1 :P2])
                {:per quarter :aggregate {:first quarter} :op :info})
              (line unknown "Unknown" (dim/rollup {:P1 nil :P2 20.0} {:P1 :P1 :P2 :P1} quarter.key :first [:P1 :P2])
                {:per quarter :aggregate {:first quarter} :op :info})
              (line known "Known" (dim/rollup {:P1 nil :P2 20.0} {:P1 :P1 :P2 :P1} quarter.key :last [:P1 :P2])
                {:per quarter :op :info})
              (line reordered "Declared order" (dim/rollup {:P1 10.0 :P2 20.0} {:P1 :P1 :P2 :P1} quarter.key :first [:P2 :P1])
                {:per quarter :op :info}))
        """,
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        assertEquals(Value.Nil, result.value("absent", "P1"))
        assertEquals(Value.Nil, result.value("unknown", "P1"))
        number("20", result.value("known", "P1"))
        number("20", result.value("reordered", "P1"))
        number("0", result.value("absent", "P2"))
        assertEquals(Value.Nil, result.view.reduce("unknown").value)
    }

    @Test
    fun `period boundaries reduce within the fixed parent scope in declared order`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/parent-boundary
              (dimension quarter {:periods {:start "2026-04-01" :unit :quarter :count 2}})
              (dimension month {:periods {:start "2026-04-01" :unit :month :count 6} :parent quarter})
              (line balance "Balance" (+ month.index 10) {:per month :aggregate {:last month} :op :info})
              (line first-balance "Opening" (dim/rollup all.balance relation_month quarter.key :first periods.month.keys) {:per quarter :op :info})
              (line last-balance "Closing" (dim/rollup all.balance relation_month quarter.key :last periods.month.keys) {:per quarter :op :info}))
        """,
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        number("12", result.view.reduce("balance", mapOf("quarter" to "P1")).value)
        number("15", result.view.reduce("balance", mapOf("quarter" to "P2")).value)
        val trace = result.view.reduce("balance", mapOf("quarter" to "P1")).trace as BoundaryAggregateTrace
        assertEquals(listOf("P1", "P2", "P3"), trace.periodKeys)
        assertEquals(mapOf("quarter" to "P1"), trace.fixed)
        number("10", result.value("first-balance", "P1"))
        number("13", result.value("first-balance", "P2"))
        number("12", result.value("last-balance", "P1"))
        number("15", result.value("last-balance", "P2"))
    }

    @Test
    fun `monthly periods advance from the original month-end anchor`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/month-end
              (dimension month {:periods {:start "2026-01-31" :unit :month :count 3}}))
        """,
            ),
        )
        val members = result.members.getValue("month")
        assertEquals(listOf("P1", "P2", "P3"), members.map { it.key })
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31"),
            members.map {
                (it.record.getValue("start") as Value.Date).value.toString()
            },
        )
        assertEquals(Value.Date(LocalDate.parse("2026-04-30")), members.last().record["end-exclusive"])
        assertEquals(Value.Nil, members.first().record["previous-key"])
        assertEquals(Value.Kw("P2"), members.last().record["previous-key"])
        assertEquals(listOf(0, 1, 2), members.map { it.index })
    }

    @Test
    fun `static noncalendar financial years retain declared keys and labels`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/fiscal
              (dimension year {:periods [
                {:key :FY2026 :start "2026-07-01" :end "2027-07-01" :label "FY 2026/27"}
                {:key :FY2027 :start "2027-07-01" :end "2028-07-01"}]}))
        """,
            ),
        )
        assertEquals("FY 2026/27", result.members.getValue("year").first().label)
        assertEquals(Value.Kw("FY2026"), result.members.getValue("year").last().record["previous-key"])
    }

    @Test
    fun `gaps overlap reverse order and duplicate static keys are hard errors`() {
        listOf(
            "{:key :A :start \"2026-01-01\" :end \"2027-01-01\"} {:key :B :start \"2027-02-01\" :end \"2028-01-01\"}",
            "{:key :A :start \"2026-01-01\" :end \"2027-02-01\"} {:key :B :start \"2027-01-01\" :end \"2028-01-01\"}",
            "{:key :A :start \"2027-01-01\" :end \"2028-01-01\"} {:key :B :start \"2026-01-01\" :end \"2027-01-01\"}",
            "{:key :A :start \"2026-01-01\" :end \"2027-01-01\"} {:key :A :start \"2027-01-01\" :end \"2028-01-01\"}",
        ).forEach { entries ->
            val failure = assertFailsWith<MantraException> {
                Mantra.calculate(schema("(schema test/bad (dimension year {:periods [$entries]}))"))
            }
            assertTrue(failure.diagnostics.any { it.code in setOf("MANTRA-PERIOD-CONTINUITY", "MANTRA-PERIOD-KEY") })
        }
    }

    @Test
    fun `period activity and table membership cannot remove intermediate periods`() {
        listOf(":when true", ":from rows", ":members [:A]").forEach { extra ->
            assertFailsWith<MantraException> {
                schema(
                    "(schema test/mixed (dimension year {:periods {:start \"2026-01-01\" :unit :year :count 2} $extra}))",
                )
            }
        }
        assertFailsWith<MantraException> {
            schema(
                """(schema test/when (dimension year {:periods [{:key :A :start "2026-01-01" :end "2027-01-01" :when true}]}))""",
            )
        }
    }

    @Test
    fun `period hierarchy binds by complete date containment and exposes the existing relation root`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/hierarchy
              (dimension quarter {:periods {:start "2026-04-01" :unit :quarter :count 2}})
              (dimension month {:periods {:start "2026-04-01" :unit :month :count 6} :parent quarter})
              (line amount "Amount" 1 {:per month :op :info})
              (line quarterly "Quarterly" (dim/rollup all.amount relation_month quarter.key) {:per quarter}))
        """,
            ),
        )
        assertTrue(result.succeeded)
        number("3", result.value("quarterly", "P1"))
        number("3", result.value("quarterly", "P2"))
        assertEquals(
            listOf("P1", "P1", "P1", "P2", "P2", "P2"),
            result.members.getValue("month").map {
                (it.record["parent-key"] as Value.Kw).name
            },
        )
        assertFailsWith<MantraException> {
            Mantra.calculate(
                schema(
                    """
                (schema test/cross-boundary
                  (dimension quarter {:periods {:start "2026-01-01" :unit :quarter :count 2}})
                  (dimension child {:periods [{:key :A :start "2026-03-01" :end "2026-05-01"}] :parent quarter}))
            """,
                ),
            )
        }
    }

    private fun stockSchema() = schema(
        """
        (schema test/stock
          (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
          (dimension asset {:members [:A :B]})
          (input balance :decimal {:per [year asset] :aggregate {:first year}})
          (line opening "Opening" balance {:per [year asset] :aggregate {:first year} :op :info})
          (choice chosen "Chosen" {:per [year asset] :rule :max :aggregate {:last year} :op :info}
            (option :one "One" balance) (option :two "Two" (- balance 1)))
          (section assets "Assets" {:per [year asset]}
            (line current "Current" balance)
            (total closing "Closing" {:aggregate {:last year}}))
          (total combined "Combined"))
    """,
    )
    private fun stockCase() =
        case("""(case c (inputs {:balance {:P1 {:A 100 :B 200} :P2 {:A 50 :B 150} :P3 {:A 20 :B 80}}}))""")

    @Test
    fun `first and last apply only to the folded period axis and other dimensions sum`() {
        val result = Mantra.calculate(stockSchema(), stockCase())
        number("300", result.view.reduce("balance").value)
        number("300", result.view.reduce("opening").value)
        number("100", result.view.reduce("closing").value)
        number("100", result.view.reduce("chosen").value)
        number("200", result.view.reduce("closing", mapOf("year" to "P2")).value)
        number("20", result.view.reduce("closing", mapOf("asset" to "A")).value)
        number("100", result.value("combined"))
        val trace = result.view.reduce("closing").trace as BoundaryAggregateTrace
        assertEquals(2, trace.selectionCount)
        assertEquals(listOf(listOf("P3", "A"), listOf("P3", "B")), trace.selected.map { it.coord })
        assertFailsWith<UnsupportedOperationException> { (trace.selected as MutableList).clear() }
    }

    @Test
    fun `an inactive terminal period is not replaced by an earlier active balance`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/inactive-last
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (line closing "Closing" 100 {:per year :when (< year.index 2) :aggregate {:last year}}))
        """,
            ),
        )
        number("0", result.view.reduce("closing").value)
        assertFalse((result.view.reduce("closing").trace as BoundaryAggregateTrace).selected.single().active)
    }

    @Test
    fun `an active undefined stock remains undefined in reductions and parent totals`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/undefined-stock
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (section detail "Detail" {:per year}
                (line closing "Closing" (decimal/divide 1 0) {:aggregate {:last year}}))
              (total combined "Combined"))
        """,
            ),
        )
        assertTrue(result.succeeded)
        assertEquals(Value.Nil, result.view.reduce("closing").value)
        assertEquals(Value.Nil, result.value("combined"))
        assertNull(result.node("closing").crossTotal())
    }
}
