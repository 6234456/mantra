package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.mantra.core.view.NodeTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuditTraceTest {
    private fun schema(body: String) = Mantra.loadSchema(
        SourceText("audit.mantra", "(schema t/audit {} $body)"),
        SourceResolver { _, _ -> null },
    )

    private fun assertSameSemantics(expected: ExplainTrace, actual: ExplainTrace) {
        // Event identities belong to their own executions; source evidence and invocation order agree.
        fun semantics(trace: ExplainTrace) = trace.copy(
            steps = trace.steps.map { it.copy(eventId = null) },
            branches = trace.branches.map { it.copy(eventId = null) },
        )
        assertEquals(semantics(expected), semantics(actual))
        listOf(expected, actual).forEach { trace ->
            trace.steps.forEach { step ->
                assertTrue(assertNotNull(step.eventId).isNotBlank())
                assertNotNull(step.invocationIndex)
            }
            trace.branches.forEach { branch ->
                val eventId = assertNotNull(branch.eventId)
                val step = trace.steps.single { it.eventId == eventId }
                assertEquals(step.text, branch.text)
                assertEquals(step.location, branch.location)
                assertEquals(step.invocationIndex, branch.invocationIndex)
            }
        }
    }

    @Test
    fun `audit and Explain retain the same semantics and genuine execution event identities`() {
        val schema = schema(
            """
            (defn twice [^Decimal x] (if (> x 4) (* x 2) (- x 1)))
            (section main "Main" (line amount "Amount" (twice (+ 2 3))))
            """.trimIndent(),
        )
        val audit = Mantra.calculateForAudit(schema)
        val explained = Mantra.calculateForExplain(schema, audit.case, emptyList(), "amount")
        val captured = assertNotNull((audit.node("amount").trace() as NodeTrace.Computed).explanation)
        assertSameSemantics(assertNotNull(explained.explainTrace), captured)
        assertEquals(Value.num(10), audit.value("amount"))
        assertFalse(captured.truncated)
        assertTrue(captured.steps.any { it.text == "(* x 2)" && it.value == Value.num(10) })
        assertTrue(captured.branches.any { it.text == "(* x 2)" && it.selected })
        assertFalse(captured.steps.any { it.text == "(- x 1)" })
        assertTrue(captured.steps.indexOfFirst { it.text == "(+ 2 3)" } < captured.steps.lastIndex)
        assertEquals("(twice (+ 2 3))", captured.steps.last().text)
    }

    @Test
    fun `non scalar summaries retain genuine structured values and visible kernel truncation`() {
        val schema = schema(
            """
            (section main "Main"
              (line record "Record" (if true {:amount (+ 2 3) :label "A"} {}) {:type :any})
              (line many "Many" (vec (range 70)) {:type :any}))
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(schema)
        val record = assertNotNull((result.node("record").trace() as NodeTrace.Computed).explanation)
        assertEquals(result.value("record"), record.steps.last().value)
        assertTrue(assertNotNull(record.steps.last().rendered).contains(":amount 5"))
        assertFailsWith<UnsupportedOperationException> { (record.steps as MutableList<*>).clear() }
        val entries = (record.steps.last().value as Value.MapV).entries
        assertFailsWith<UnsupportedOperationException> { (entries as MutableMap<*, *>).clear() }
        val many = assertNotNull((result.node("many").trace() as NodeTrace.Computed).explanation)
        assertTrue(many.truncated)
        assertEquals(70, (result.value("many") as Value.Vec).items.size)
        assertTrue(result.diagnostics.any { it.code == "MANTRA-AUDIT-TRUNCATED" && it.severity == Severity.WARNING })
        assertTrue(result.succeeded)
    }

    @Test
    fun `run budget marks every omitted coordinate and never changes calculated values`() {
        val schema = schema(
            "(section main \"Main\" (line alpha \"First\" (+ 1 2)) (line beta \"Second\" (* alpha 2)))",
        )
        val result = Mantra.calculateForAudit(schema, options = AuditOptions(maxFormulas = 1))
        assertEquals(Mantra.calculate(schema).value("beta"), result.value("beta"))
        val first = assertNotNull((result.node("alpha").trace() as NodeTrace.Computed).explanation)
        val second = assertNotNull((result.node("beta").trace() as NodeTrace.Computed).explanation)
        assertFalse(first.truncated)
        assertTrue(second.truncated)
        assertTrue(second.steps.isEmpty())
        assertEquals(1, result.diagnostics.count { it.code == "MANTRA-AUDIT-TRUNCATED" })
        assertTrue(result.succeeded)
    }

    @Test
    fun `case formula source and exact decimals are retained without reevaluation`() {
        val schema = schema("(input base :decimal) (section main \"Main\" (formula-slot hook \"Hook\" (+ base 1)))")
        val case = Mantra.loadCase(
            SourceText("custom-case.mantra", "(case custom (inputs {:base 1.123456789}) (bind hook (* base 2)))"),
        )
        val result = Mantra.calculateForAudit(schema, case)
        val trace = assertNotNull((result.node("hook").trace() as NodeTrace.Computed).explanation)
        assertEquals("(* base 2)", trace.steps.last().text)
        assertEquals("custom-case.mantra", trace.steps.last().location.source)
        assertEquals("2.246913578", trace.steps.last().rendered)
        assertEquals(result.value("hook"), trace.steps.last().value)
    }

    @Test
    fun `spread coordinates share one detached immutable trace within the run budget`() {
        val schema = schema(
            """
            (dimension member {:members [:A :B]})
            (section main "Main" {:per member}
              (line portions "Portions" (alloc/pro-rata 10 {:A 1 :B 1} 0) {:spread true}))
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(schema, options = AuditOptions(maxFormulas = 1))
        val a = assertNotNull((result.node("portions").trace(listOf("A")) as NodeTrace.Computed).explanation)
        val b = assertNotNull((result.node("portions").trace(listOf("B")) as NodeTrace.Computed).explanation)
        assertSame(a, b)
        assertFalse(a.truncated)
        assertEquals(Value.num(5), result.value("portions", "A"))
        assertEquals(Value.num(5), result.value("portions", "B"))
        assertFailsWith<UnsupportedOperationException> { (a.steps as MutableList<*>).clear() }
        val explained = Mantra.calculateForExplain(schema, result.case, emptyList(), "portions", listOf("B"))
        assertSameSemantics(a, assertNotNull(explained.explainTrace))
    }
}
