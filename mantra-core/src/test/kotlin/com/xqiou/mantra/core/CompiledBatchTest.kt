package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.BatchFailureKind
import com.xqiou.mantra.core.api.BatchLimits
import com.xqiou.mantra.core.api.BatchOptions
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellationSource
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompiledBatchTest {
    private fun schema(text: String) = Mantra.loadSchema(
        SourceText("schema.mantra", text.trimIndent()),
        SourceResolver { _, _ -> null },
    )
    private fun scalar(id: String, value: Long): CaseData =
        CaseData.empty(id).copy(inputs = mapOf("base" to Value.num(value)))
    private fun number(expected: String, actual: Value) =
        assertEquals(0, BigDecimal(expected).compareTo((actual as Value.Num).value))

    @Test
    fun `batch consumes exactly one iterator and delivers detached values for genuinely different IDs`() {
        val compiled = Mantra.compile(
            schema("""(schema test/stream (input base :decimal) (line answer "Answer" (* base 2)))"""),
        )
        var iteratorCalls = 0
        var inputReads = 0
        var callbacks = 0L
        var old: com.xqiou.mantra.core.api.CalculationResult? = null
        val stream = Iterable {
            assertEquals(0, iteratorCalls++)
            object : Iterator<CaseData> {
                override fun hasNext() = inputReads < 30
                override fun next(): CaseData {
                    inputReads++
                    return scalar("case-$inputReads", inputReads.toLong())
                }
            }
        }
        val summary = compiled.forEach(stream) { item ->
            callbacks++
            assertEquals(callbacks - 1, item.index)
            assertEquals("case-$callbacks", item.caseId)
            val result = assertNotNull(item.result)
            assertEquals(item.caseId, result.case.id)
            number((callbacks * 2).toString(), result.value("answer"))
            if (old == null) old = result else number("2", old!!.value("answer"))
            assertTrue(item.succeeded)
            assertNotNull(item.usage)
        }
        assertEquals(1, iteratorCalls)
        assertEquals(30L, summary.completedCases)
        assertEquals(30L, callbacks)
        assertEquals(30L, summary.succeededCases)
        assertEquals(0L, summary.technicalFailedCases)
        assertEquals(0L, summary.validationFailedCases)
        assertNull(summary.failure)
        assertEquals(1L, summary.statistics.sessionOpens)
        assertEquals(1L, summary.statistics.sessionCloseAttempts)
        assertEquals(1L, summary.statistics.successfulSessionCloses)
        assertEquals(30L, summary.statistics.rowCycles)
        assertEquals(30L, summary.statistics.physicalRowPreparations)
        assertEquals(0L, summary.statistics.executionPlanCompilations)
        assertEquals(0L, summary.statistics.valueOnlyEvidenceMaterializations)
        assertEquals(1L, compiled.compilation.executionPlanCompilations)
        number("2", old!!.value("answer"))
    }

    @Test
    fun `business current values technical Nil and rejected bindings remain separate batch outcomes`() {
        val compiled = Mantra.compile(
            schema(
                """
            (schema test/outcomes
              (input divisor :decimal {:min 0})
              (line quotient "Quotient" (/ 10 divisor)))
        """,
            ),
        )
        fun facts(id: String, divisor: Long) = CaseData.empty(id).copy(inputs = mapOf("divisor" to Value.num(divisor)))
        val cases = listOf(
            facts("good", 2),
            facts("business", -2),
            facts("technical", 0),
            facts("binding", 3).copy(params = mapOf("unknown" to Value.num(1))),
            facts("recovered", 5),
        )
        var seen = 0
        val summary = compiled.forEach(cases) { item ->
            assertEquals(cases[seen++].id, item.caseId)
            when (item.caseId) {
                "good" -> number("5", item.result!!.value("quotient"))
                "business" -> {
                    assertTrue(item.succeeded)
                    assertFalse(item.validationPassed)
                    number("-5", item.result!!.value("quotient"))
                    assertTrue(item.diagnostics.any { it.category == DiagnosticCategory.BUSINESS })
                }
                "technical" -> {
                    assertFalse(item.succeeded)
                    assertEquals(Value.Nil, item.result!!.value("quotient"))
                    assertTrue(item.diagnostics.any { it.category == DiagnosticCategory.EVALUATION })
                }
                "binding" -> {
                    assertNull(item.result)
                    assertFalse(item.succeeded)
                    assertTrue(item.diagnostics.any { it.code == "MANTRA-CASE-PARAM-UNKNOWN" })
                }
                "recovered" -> {
                    assertTrue(item.succeeded)
                    number("2", item.result!!.value("quotient"))
                    assertTrue(item.diagnostics.isEmpty())
                }
            }
        }
        assertEquals(5L, summary.completedCases)
        assertEquals(3L, summary.succeededCases)
        assertEquals(2L, summary.technicalFailedCases)
        assertEquals(1L, summary.validationFailedCases)
        assertNull(summary.failure)
        assertEquals(1L, summary.statistics.sessionOpens)
        assertEquals(1L, summary.statistics.sessionCloseAttempts)
        assertEquals(4L, summary.statistics.rowCycles)
    }

    @Test
    fun `batch count limit rejects before reading an extra case and closes the owned worker`() {
        val compiled = Mantra.compile(
            schema("""(schema test/count (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        var reads = 0
        val stream = Iterable {
            object : Iterator<CaseData> {
                override fun hasNext() = true
                override fun next(): CaseData = scalar("case-${++reads}", reads.toLong())
            }
        }
        val summary = compiled.forEach(stream, options = BatchOptions(limits = BatchLimits(maxCases = 2))) { }
        assertEquals(2, reads)
        assertEquals(2L, summary.completedCases)
        assertEquals(BatchFailureKind.LIMIT, summary.failure?.kind)
        assertEquals(2L, summary.failure?.completedCases)
        assertEquals(1L, summary.statistics.sessionCloseAttempts)
    }

    @Test
    fun `callback cancellation stops before the next input and delivers no incomplete case`() {
        val compiled = Mantra.compile(
            schema("""(schema test/stop (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        var reads = 0
        val stream = Iterable {
            object : Iterator<CaseData> {
                override fun hasNext() = true
                override fun next() = scalar("case-${++reads}", reads.toLong())
            }
        }
        val cancellation = RunCancellationSource()
        var callbacks = 0
        val summary = compiled.forEach(stream, options = BatchOptions(control = RunControl(cancellation))) { item ->
            callbacks++
            number("2", item.result!!.value("answer"))
            cancellation.cancel()
        }
        assertEquals(1, reads)
        assertEquals(1, callbacks)
        assertEquals(1L, summary.completedCases)
        assertEquals(BatchFailureKind.CANCELLED, summary.failure?.kind)
        assertEquals(1L, summary.statistics.sessionOpens)
        assertEquals(1L, summary.statistics.sessionCloseAttempts)
        number("4", compiled.calculate(scalar("next-request", 3)).value("answer"))
    }

    @Test
    fun `pre cancellation deadline and zero capacity do not request an input or execute a formula`() {
        val compiled = Mantra.compile(
            schema("""(schema test/preflight (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        val cancelled = RunCancellationSource().also { it.cancel() }
        val untouched = Iterable<CaseData> { error("Cancelled batch must not create an input iterator") }
        val cancellation = compiled.forEach(untouched, options = BatchOptions(control = RunControl(cancelled))) {
            error("No callback")
        }
        assertEquals(BatchFailureKind.CANCELLED, cancellation.failure?.kind)
        val deadline = compiled.forEach(
            untouched,
            options = BatchOptions(control = RunControl(deadline = Instant.EPOCH)),
        ) {
            error("No callback")
        }
        assertEquals(BatchFailureKind.DEADLINE, deadline.failure?.kind)
        val zero = compiled.forEach(
            listOf(scalar("unused", 1)),
            options = BatchOptions(limits = BatchLimits(maxCases = 0)),
        ) {
            error("No callback")
        }
        assertEquals(BatchFailureKind.LIMIT, zero.failure?.kind)
        listOf(cancellation, deadline, zero).forEach {
            assertEquals(0L, it.completedCases)
            assertEquals(0L, it.statistics.sessionOpens)
            assertEquals(0L, it.statistics.rowCycles)
            assertEquals(0L, it.statistics.sessionCloseAttempts)
        }
    }

    @Test
    fun `each case gets a fresh M3 epoch and rejected cases cannot borrow a prior successful result`() {
        val compiled = Mantra.compile(
            schema(
                """
            (schema test/epochs
              (input rows :table {:columns {:id :keyword}})
              (dimension member {:from rows :key :id})
              (line answer "Answer" 1 {:per member}))
        """,
            ),
        )
        fun rows(id: String, count: Int) = CaseData.empty(id).copy(
            inputs = mapOf(
                "rows" to Value.Vec((1..count).map { Value.MapV(mapOf(Value.Kw("id") to Value.Kw("M$it"))) }),
            ),
        )
        val cases = listOf(rows("a", 1), rows("too-many", 3), rows("b", 1))
        var callbacks = 0
        val summary = compiled.forEach(
            cases,
            options = BatchOptions(calculation = CalculationOptions(limits = RunLimits(maxFormulaExecutions = 1))),
        ) { item ->
            callbacks++
            if (item.caseId == "too-many") {
                assertNull(item.result)
                assertNotNull(item.runFailure)
            } else {
                assertTrue(item.succeeded)
                assertEquals(1L, item.usage!![com.xqiou.mantra.core.api.RunCounter.FORMULA_EXECUTIONS])
                number("1", item.result!!.value("answer", "M1"))
            }
        }
        assertEquals(3, callbacks)
        assertEquals(2L, summary.succeededCases)
        assertEquals(1L, summary.technicalFailedCases)
        assertEquals(0L, summary.statistics.executionPlanCompilations)
        assertEquals(summary.statistics.sessionOpens, summary.statistics.sessionCloseAttempts)
        assertEquals(summary.statistics.sessionOpens, summary.statistics.successfulSessionCloses)
    }

    @Test
    fun `consumer exception escapes without becoming a stale result or poisoning the immutable template`() {
        val compiled = Mantra.compile(
            schema("""(schema test/callback (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        val expected = IllegalStateException("consumer failed")
        val actual = assertFailsWith<IllegalStateException> {
            compiled.forEach(listOf(scalar("first", 1), scalar("never-delivered", 2))) { throw expected }
        }
        assertSame(expected, actual)
        number("5", compiled.calculate(scalar("new-batch", 4)).value("answer"))
        assertEquals(1L, compiled.compilation.executionPlanCompilations)
    }
}
