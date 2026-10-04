package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.FormulaAuthoring
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Convergence contracts exercised against the locked kernel. */
class ConvergeTest {
    private fun schema(body: String) = Mantra.loadSchema(
        SourceText("converge.mantra", "(schema test/converge {} $body)"),
        SourceResolver { _, _ -> null },
    )

    @Test
    fun `typed callback adjacent delta returns next and exposes repeated real callback source`() {
        val expression = "(decimal/round (* 0.10 (- 100000 current)) 2)"
        val model = schema(
            """
            (section main "Main"
              (line bonus "Bonus" (calc/converge (fn [^Decimal current] $expression) 0 30 0)))
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num("9090.91"), result.value("bonus"))
        val trace = assertNotNull((result.node("bonus").trace() as NodeTrace.Computed).explanation)
        val calls = trace.steps.filter { it.text == expression }
        assertTrue(calls.size > 1, trace.toString())
        assertEquals(0, (calls.first().value as Value.Num).value.compareTo(BigDecimal("10000.00")))
        assertEquals(Value.num("9090.91"), calls.last().value)
        val identities = calls.map { assertNotNull(it.eventId) }
        assertEquals(identities.size, identities.distinct().size)
        assertTrue(calls.map { assertNotNull(it.invocationIndex) }.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `decimal input supports named callback and integer next normalizes before the next invocation`() {
        val model = schema(
            """
            (defn constant [^Decimal current] 7)
            (section main "Main"
              (line answer "Answer" (calc/converge constant 0 2 0)))
            """.trimIndent(),
        )
        val result = Mantra.calculate(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(7), result.value("answer"))
    }

    @Test
    fun `callable variance accepts decimal or broader arguments and rejects integer-only callbacks`() {
        val model = schema(
            """
            (defn advance [^Decimal x] 7)
            (formula-slot answer "Answer" (calc/converge advance 0 2 0))
            """.trimIndent(),
        )
        val authoring = FormulaAuthoring.forFormulaSlot(model, CaseData.empty(), emptyList(), "answer")
        listOf(
            "(calc/converge (fn [x] 7) 0 2 0)",
            "(calc/converge (fn [^Decimal x] 7) 0 2 0)",
            "(calc/converge advance 0 2 0)",
        ).forEach { source -> assertTrue(authoring.check(source).isEmpty(), source) }
        assertTrue(authoring.check("(calc/converge (fn [^Integer x] x) 0 2 0)").isNotEmpty())
        assertTrue(authoring.check("(calc/converge (fn [x] nil) 0 2 0)").isNotEmpty())
    }

    @Test
    fun `rounded callback chooses the reachable fixed point from the actual seed`() {
        val model = schema(
            """
            (input seed :decimal {:default 1000})
            (section main "Main"
              (line gross "Gross"
                (calc/converge (fn [current] (decimal/round (+ 1000 (* 0.25 current)) 2)) seed 30 0)))
            """.trimIndent(),
        )
        assertEquals(Value.num("1333.33"), Mantra.calculate(model).value("gross"))
        val changed = Mantra.loadCase(SourceText("other.mantra", "(case other (inputs {:seed 2000}))"))
        assertEquals(Value.num("1333.34"), Mantra.calculate(model, changed).value("gross"))
    }

    @Test
    fun `oscillation fails without an approximate value and callback division keeps its own error`() {
        val oscillating = schema(
            """
            (section main "Main"
              (line penny "Penny"
                (calc/converge (fn [x] (decimal/round (* 0.5 (- 0.01 x)) 2)) 0 6 0)))
            """.trimIndent(),
        )
        val failed = Mantra.calculateForAudit(oscillating)
        assertFalse(failed.succeeded)
        assertTrue(failed.diagnostics.any { it.message.contains("DSL-MANTRA-CALC-NOT-CONVERGED") })
        assertTrue(failed.node("penny").trace() is NodeTrace.Failed)
        val arithmetic = schema(
            """
            (input divisor :decimal {:default 0})
            (section main "Main" (line value "Value" (calc/converge (fn [x] (/ 1 divisor)) 0 3 0)))
            """.trimIndent(),
        )
        val division = Mantra.calculate(arithmetic)
        assertFalse(division.succeeded)
        assertTrue(division.diagnostics.any { it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO") })
        assertFalse(division.diagnostics.any { it.message.contains("NOT-CONVERGED") })
    }
}
