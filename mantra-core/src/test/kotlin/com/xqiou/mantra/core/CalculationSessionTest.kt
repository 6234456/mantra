package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import java.math.BigDecimal
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CalculationSessionTest {
    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text.trimIndent()), SourceResolver { _, _ -> null })
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text.trimIndent()))
    private fun number(expected: String, actual: Value) =
        assertEquals(0, BigDecimal(expected).compareTo((actual as Value.Num).value))

    @Test
    fun `wrong thread lifecycle calls leave the owner runtime usable and its snapshot unchanged`() {
        val template = schema("(schema test/thread-owned (input base :decimal) (line answer \"Answer\" (+ base 1)))")
        val first = case("(case c (inputs {:base 2}))")
        val changed = first.copy(inputs = mapOf("base" to Value.num(3)))
        val session = Mantra.openSession(template, first)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val initial = session.result
            val initialStats = session.lastRun
            worker.submit(
                Callable {
                    assertTrue(
                        assertFailsWith<IllegalStateException> { session.recalculate(changed) }
                            .message.orEmpty().contains("opening thread"),
                    )
                    assertTrue(
                        assertFailsWith<IllegalStateException> { session.close() }
                            .message.orEmpty().contains("opening thread"),
                    )
                    number("3", initial.value("answer"))
                },
            ).get(10, TimeUnit.SECONDS)
            assertSame(initial, session.result)
            assertEquals(initialStats, session.lastRun)
            val next = session.recalculate(changed)
            assertTrue(next.succeeded, next.diagnostics.joinToString("\n"))
            assertFalse(session.lastRun.fullRebuild)
            number("4", next.value("answer"))
            session.close()
            session.close()
            worker.submit(Callable { number("4", next.value("answer")) }).get(10, TimeUnit.SECONDS)
        } finally {
            session.close()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `editing one series invalidates its carried balances and reuses unrelated members`() {
        val template =
            schema(
                """
            (schema test/incremental
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (dimension asset {:members [:A :B]})
              (input seed :decimal {:per asset})
              (line closing "Closing" (+ (prev closing seed) 1) {:per [year asset] :aggregate {:last year} :op :info})
              (line fixed "Unchanged" 5 {:per [year asset] :op :info}))
        """,
            )
        val initial = case("(case c (inputs {:seed {:A 10 :B 20}}))")
        Mantra.openSession(template, initial).use { session ->
            val old = session.result
            assertTrue(session.lastRun.fullRebuild)
            assertEquals(12, session.lastRun.formulaEvaluations)
            assertEquals(2, session.lastRun.executionSessions)
            val nextCase = initial.copy(
                inputs = mapOf(
                    "seed" to Value.MapV(linkedMapOf(Value.Kw("A") to Value.num(100), Value.Kw("B") to Value.num(20))),
                ),
            )
            val next = session.recalculate(nextCase)
            number("103", next.value("closing", "P3", "A"))
            number("23", next.value("closing", "P3", "B"))
            number("13", old.value("closing", "P3", "A"))
            number("126", next.view.reduce("closing").value!!)
            assertFalse(session.lastRun.fullRebuild)
            assertEquals(3, session.lastRun.formulaEvaluations)
            assertTrue(session.lastRun.reusedTasks > 0)
            assertTrue(session.lastRun.invalidatedTasks > 3)
            assertEquals(2, session.lastRun.executionSessions)
            assertNull((next.node("closing").trace(listOf("P3", "A")) as NodeTrace.Computed).explanation)
            session.recalculate(nextCase)
            assertEquals(0, session.lastRun.evaluatedTasks)
            assertEquals(0, session.lastRun.formulaEvaluations)
            assertEquals(0, session.lastRun.invalidatedTasks)
            assertTrue(session.lastRun.reusedTasks > 0)
        }
    }

    @Test
    fun `input fixes replace owned diagnostics and source position changes rebind findings`() {
        val template =
            schema(
                """
            (schema test/findings
              (input enabled :boolean {:default true})
              (input supplied :decimal {:default 0 :required-when enabled :min 0})
              (input other :decimal {:min 0})
              (line answer "Answer" (+ supplied other)))
        """,
            )
        val first = case("(case c (inputs {:supplied -1 :other -2}))")
        Mantra.openSession(template, first).use { session ->
            assertFalse(session.result.validationPassed)
            assertEquals(2, session.result.diagnostics.size)
            val fixed = first.copy(inputs = first.inputs + ("supplied" to Value.ZERO))
            val result = session.recalculate(fixed)
            assertEquals(listOf("other"), result.diagnostics.map { it.nodeId })
            val moved = fixed.copy(
                inputLocations =
                fixed.inputLocations + ("other" to SourceLocation("edited.mantra", 42, 7)),
                inputCells =
                fixed.inputCells - "other",
            )
            val positioned = session.recalculate(moved)
            assertEquals(SourceLocation("edited.mantra", 42, 7), positioned.diagnostics.single().location)
            val cleared = session.recalculate(moved.copy(inputs = moved.inputs + ("other" to Value.ZERO)))
            assertTrue(cleared.validationPassed)
            assertTrue(cleared.diagnostics.isEmpty())
        }
    }

    @Test
    fun `changed derived membership removes obsolete coordinates and initializes new members`() {
        val template =
            schema(
                """
            (schema test/domain-edit
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (input rows :table {:columns {:id :keyword :name :text}})
              (dimension asset {:from rows :key :id :title :name})
              (line closing "Closing" (+ (prev closing 10) 1) {:per [year asset]}))
        """,
            )
        val first = case("(case c (inputs {:rows [{:id :A :name \"A\"} {:id :B :name \"B\"}]}))")
        val replacement = case("(case c (inputs {:rows [{:id :A :name \"A\"} {:id :C :name \"C\"}]}))")
        Mantra.openSession(template, first).use { session ->
            val old = session.result
            assertEquals(
                listOf(listOf("P1", "B"), listOf("P2", "B")),
                old.view.coordinates("closing", mapOf("asset" to "B")),
            )
            number("23", old.view.reduce("closing", mapOf("asset" to "B")).value!!)
            val next = session.recalculate(replacement)
            assertEquals(listOf("A", "C"), next.members.getValue("asset").map { it.key })
            assertEquals(Value.Nil, next.value("closing", "P2", "B"))
            number("12", next.value("closing", "P2", "C"))
            number("12", old.value("closing", "P2", "B"))
            assertEquals(
                listOf(listOf("P1", "C"), listOf("P2", "C")),
                next.view.coordinates("closing", mapOf("asset" to "C")),
            )
            number("23", next.view.reduce("closing", mapOf("asset" to "C")).value!!)
            assertFailsWith<IllegalArgumentException> { next.view.coordinates("closing", mapOf("asset" to "B")) }
            number("23", old.view.reduce("closing", mapOf("asset" to "B")).value!!)
            assertTrue(session.lastRun.invalidatedTasks > 0)
            assertFalse(session.lastRun.fullRebuild)
        }
    }

    @Test
    fun `same type parameter edits reuse sessions and formula edits rebuild checked plans`() {
        val template =
            schema(
                """
            (schema test/parameter-edit
              (param multiplier 2)
              (input base :decimal)
              (formula-slot answer "Answer" (* base multiplier)))
        """,
            )
        val first = case("(case c (inputs {:base 4}))")
        Mantra.openSession(template, first).use { session ->
            val next = session.recalculate(first.copy(params = mapOf("multiplier" to Value.num(3))))
            number("12", next.value("answer"))
            assertFalse(session.lastRun.fullRebuild)
            assertEquals(1, session.lastRun.formulaEvaluations)
            val custom = case("(case c (inputs {:base 4}) (params {:multiplier 3}) (bind answer (+ base multiplier)))")
            number("7", session.recalculate(custom).value("answer"))
            assertTrue(session.lastRun.fullRebuild)
        }
    }

    @Test
    fun `recoverable evaluation errors are removed when their input producer changes`() {
        val template =
            schema(
                """
            (schema test/error-edit
              (input divisor :decimal)
              (line quotient "Quotient" (/ 10 divisor)))
        """,
            )
        val first = case("(case c (inputs {:divisor 0}))")
        Mantra.openSession(template, first).use { session ->
            assertFalse(session.result.succeeded)
            val result = session.recalculate(first.copy(inputs = mapOf("divisor" to Value.num(2))))
            assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
            assertTrue(result.diagnostics.isEmpty())
            number("5", result.value("quotient"))
        }
    }

    @Test
    fun `close is idempotent and rejected edits preserve the last readable result`() {
        val template = schema("(schema test/closed (input base :decimal) (line answer \"Answer\" (+ base 1)))")
        val first = case("(case c (inputs {:base 2}))")
        val session = Mantra.openSession(template, first)
        assertFailsWith<MantraException> {
            session.recalculate(
                first.copy(
                    inputs =
                    first.inputs + ("unknown" to Value.ZERO),
                ),
            )
        }
        number("3", session.result.value("answer"))
        number("4", session.recalculate(first.copy(inputs = mapOf("base" to Value.num(3)))).value("answer"))
        session.close()
        session.close()
        assertFailsWith<IllegalStateException> { session.recalculate(first) }
        number("4", session.result.value("answer"))
    }
}
