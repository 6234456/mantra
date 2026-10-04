package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.BoundaryAggregateTrace
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.RatioAggregateTrace
import com.xqiou.mantra.core.view.SumAggregateTrace
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailedReductionTest {
    private fun schema(text: String) = Mantra.loadSchema(
        SourceText("schema.mantra", text.trimIndent()),
        SourceResolver { _, _ -> null },
    )
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text))
    private fun number(expected: String, actual: Value?) =
        assertEquals(0, BigDecimal(expected).compareTo(assertIs<Value.Num>(actual).value))

    @Test
    fun `failed formula remains undefined in partial cross footing and subsequent totals`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/failed-sum
                  (dimension bucket {:members [:X :Y]})
                  (dimension member {:members [:A :B]})
                  (section grouped "Grouped" {:per bucket}
                    (line detail "Detail"
                      (if (and (= bucket.key :X) (= member.key :A)) (/ 1 0) 7)
                      {:per [bucket member]})
                    (total checkpoint "Checkpoint"))
                  (total overall "Overall"))
                """,
            ),
        )
        assertFalse(result.succeeded)
        assertEquals(Value.Nil, result.value("detail", "X", "A"))
        assertIs<NodeTrace.Failed>(result.node("detail").trace(listOf("X", "A")))
        assertTrue(result.node("detail").isActive(listOf("X", "A")))
        assertEquals(Value.Nil, result.value("checkpoint", "X"))
        number("14", result.value("checkpoint", "Y"))
        assertEquals(Value.Nil, result.value("overall"))
        assertNull(result.node("detail").crossTotal())
        val partial = result.view.reduce("detail", mapOf("bucket" to "X"))
        assertEquals(Value.Nil, partial.value)
        assertEquals("selected-value-undefined", assertIs<SumAggregateTrace>(partial.trace).undefinedReason)
        val total = assertIs<NodeTrace.Sum>(result.node("checkpoint").trace(listOf("X")))
        assertNull(total.parts.single().value)
        assertEquals("selected-value-undefined", total.parts.single().reduction?.undefinedReason)
    }

    @Test
    fun `failed available choice is not skipped while optional nil and unavailable options remain neutral`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/failed-choice
                  (dimension member {:members [:A :B]})
                  (input optional-value :decimal {:per member :optional true})
                  (choice broken "Broken" {:per member :rule :min}
                    (option :a "A" (/ 1 0)) (option :b "B" 8))
                  (total overall "Overall")
                  (choice excluded "Excluded" {:per member :rule :min :op :info}
                    (option :a "A" (/ 1 0) {:when false}) (option :b "B" 8 {:when false}))
                  (choice known "Known" {:per member :rule :min :op :info}
                    (option :a "A" (/ 1 0) {:when false}) (option :b "B" 8)))
                """,
            ),
        )
        assertFalse(result.succeeded)
        assertIs<NodeTrace.Failed>(result.node("broken").trace(listOf("A")))
        assertTrue(result.node("broken").isActive(listOf("A")))
        assertEquals(Value.Nil, result.view.reduce("broken").value)
        assertEquals(Value.Nil, result.value("overall"))
        assertEquals(Value.Nil, result.value("optional-value", "A"))
        number("0", result.view.reduce("optional-value").value)
        number("0", result.view.reduce("excluded").value)
        number("16", result.view.reduce("known").value)
    }

    @Test
    fun `stock boundary preserves selected failure and does not skip inactive or failed earlier periods`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/failed-boundary
                  (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
                  (line broken "Broken" (if (= year.index 1) (/ 1 0) 10)
                    {:per year :aggregate {:last year} :op :info})
                  (line known "Known" (if (= year.index 0) (/ 1 0) 10)
                    {:per year :aggregate {:last year} :op :info})
                  (line excluded "Excluded" 10
                    {:per year :when (= year.index 0) :aggregate {:last year} :op :info}))
                """,
            ),
        )
        assertFalse(result.succeeded)
        val boundary = result.view.reduce("broken")
        assertEquals(Value.Nil, boundary.value)
        val trace = assertIs<BoundaryAggregateTrace>(boundary.trace)
        assertEquals("selected-value-undefined", trace.undefinedReason)
        assertEquals(listOf("P2"), trace.selected.single().coord)
        assertTrue(trace.selected.single().active)
        number("10", result.view.reduce("known").value)
        assertIs<NodeTrace.Inactive>(result.node("excluded").trace(listOf("P2")))
        number("0", result.view.reduce("excluded").value)
    }

    @Test
    fun `failed own and inherited conditions differ from a condition that is false`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/failed-condition
                  (dimension member {:members [:A :B]})
                  (input divisor :decimal {:per member})
                  (section scoped "Scoped" {:per member :when (> (/ 1 divisor) 0)}
                    (line inherited "Inherited" 4))
                  (line own "Own" 4 {:per member :when (> (/ 1 divisor) 0) :op :info})
                  (line excluded "Excluded" (/ 1 0) {:per member :when false :op :info}))
                """,
            ),
            case("(case c (inputs {:divisor {:A 0 :B 1}}))"),
        )
        assertFalse(result.succeeded)
        listOf("inherited", "own").forEach { id ->
            assertEquals(Value.Nil, result.value(id, "A"))
            assertIs<NodeTrace.Failed>(result.node(id).trace(listOf("A")))
            assertTrue(result.node(id).isActive(listOf("A")))
            number("4", result.value(id, "B"))
            assertEquals(Value.Nil, result.view.reduce(id).value)
        }
        assertIs<NodeTrace.Inactive>(result.node("excluded").trace(listOf("A")))
        assertFalse(result.node("excluded").isActive(listOf("A")))
        number("0", result.view.reduce("excluded").value)
    }

    @Test
    fun `ratio cannot report a finite weighted value when its own formula failed`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/failed-ratio
                  (dimension member {:members [:A :B]})
                  (input charge :decimal {:per member}) (input units :decimal {:per member})
                  (line measure "Measure" (if (= member.key :A) (/ 1 0) 1)
                    {:per member :aggregate {:ratio [charge units]}})
                  (total overall "Overall"))
                """,
            ),
            case("(case c (inputs {:charge {:A 20 :B 80} :units {:A 100 :B 100}}))"),
        )
        assertFalse(result.succeeded)
        assertEquals(Value.Nil, result.value("overall"))
        assertNull(result.node("measure").crossTotal())
        val reduced = result.view.reduce("measure")
        assertEquals(Value.Nil, reduced.value)
        val trace = assertIs<RatioAggregateTrace>(reduced.trace)
        assertNull(trace.result)
        assertEquals("selected-value-undefined", trace.undefinedReason)
        assertEquals(2, trace.activeMemberCount)
        assertTrue(result.diagnostics.none { it.code == "MANTRA-AGGREGATE-ZERO-DENOMINATOR" })
    }

    @Test
    fun `ratio rejects failed components but permits optional nil and excludes inactive failure scopes`() {
        listOf("charge", "units").forEach { failing ->
            val result = Mantra.calculate(
                schema(
                    """
                    (schema test/failed-ratio-component
                      (dimension member {:members [:A :B]})
                      (line charge "Charge" ${if (failing == "charge") "(if (= member.key :A) (/ 1 0) 20)" else "20"}
                        {:per member :op :info})
                      (line units "Units" ${if (failing == "units") "(if (= member.key :A) (/ 1 0) 100)" else "100"}
                        {:per member :op :info})
                      (input optional-value :decimal {:per member :optional true})
                      (line measure "Measure" 1 {:per member :aggregate {:ratio [charge units]} :op :info})
                      (line masked "Masked" 1
                        {:per member :when (= member.key :B) :aggregate {:ratio [charge units]} :op :info})
                      (line optional-ratio "Optional ratio" 1
                        {:per member :when (= member.key :B) :aggregate {:ratio [optional-value units]} :op :info}))
                    """,
                ),
            )
            assertFalse(result.succeeded)
            val reduced = result.view.reduce("measure")
            assertEquals(Value.Nil, reduced.value)
            assertEquals("selected-value-undefined", assertIs<RatioAggregateTrace>(reduced.trace).undefinedReason)
            assertNull(result.node("measure").crossTotal())
            number("0.2", result.view.reduce("masked").value)
            number("0", result.view.reduce("optional-ratio").value)
            assertTrue(result.diagnostics.none { it.code == "MANTRA-AGGREGATE-ZERO-DENOMINATOR" })
        }
    }

    @Test
    fun `a valid undefined member ratio can still participate in a defined weighted ratio`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/weighted-nil
                  (dimension member {:members [:A :B]})
                  (input charge :decimal {:per member}) (input units :decimal {:per member})
                  (line measure "Measure" (decimal/divide charge units 2)
                    {:per member :aggregate {:ratio [charge units]}})
                  (total overall "Overall"))
                """,
            ),
            case("(case c (inputs {:charge {:A 0 :B 20} :units {:A 0 :B 100}}))"),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.Nil, result.value("measure", "A"))
        assertIs<NodeTrace.Computed>(result.node("measure").trace(listOf("A")))
        number("0.2", result.view.reduce("measure").value)
        number("0.2", result.value("overall"))
        assertNull(assertIs<RatioAggregateTrace>(result.view.reduce("measure").trace).undefinedReason)
    }

    @Test
    fun `incremental guard recovery replaces failure with applicable then inactive values`() {
        val template = schema(
            """
            (schema test/guard-recovery
              (dimension member {:members [:A :B]})
              (input divisor :decimal {:per member})
              (section scoped "Scoped" {:per member :when (> (/ 1 divisor) 0)}
                (line detail "Detail" 4))
              (total overall "Overall"))
            """,
        )
        Mantra.openSession(template, case("(case c (inputs {:divisor {:A 0 :B 1}}))")).use { session ->
            val initial = session.result
            assertFalse(initial.succeeded)
            assertEquals(Value.Nil, initial.value("overall"))
            val recovered = session.recalculate(case("(case c (inputs {:divisor {:A 1 :B 1}}))"))
            assertTrue(recovered.succeeded, recovered.diagnostics.toString())
            assertFalse(session.lastRun.fullRebuild)
            number("8", recovered.value("overall"))
            val excluded = session.recalculate(case("(case c (inputs {:divisor {:A -1 :B 1}}))"))
            assertTrue(excluded.succeeded, excluded.diagnostics.toString())
            assertIs<NodeTrace.Inactive>(excluded.node("detail").trace(listOf("A")))
            number("4", excluded.value("overall"))
            assertEquals(Value.Nil, initial.value("overall"))
        }
    }
}
