package com.xqiou.mantra.core

import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RatioAggregateEvidenceTest {
    private fun schema(source: String) = Mantra.loadSchema(
        SourceText("ratio.mantra", source.trimIndent()),
        SourceResolver { _, _ -> null },
    )

    private fun case(source: String) = Mantra.loadCase(SourceText("ratio-case.mantra", source))

    private fun decimal(expected: String, actual: BigDecimal?) {
        assertEquals(0, BigDecimal(expected).compareTo(checkNotNull(actual)))
    }

    @Test
    fun `aggregate evidence retains the authoritative active mask and is deeply immutable`() {
        val result = Mantra.calculateForAudit(
            schema(
                """
                (schema test/weighted
                  (dimension member {:members [:A :B]})
                  (input charge :decimal {:per member})
                  (input units :decimal {:per member})
                  (section detail "Detail" {:per member}
                    (line ratio "Ratio" (decimal/divide charge units 8)
                      {:op :info :when (= member.key :A)
                       :aggregate {:ratio [charge units] :round [8 :half-up]}})))
                """,
            ),
            case("(case c (inputs {:charge {:A 20 :B 800} :units {:A 100 :B 100}}))"),
        )
        val evidence = assertNotNull(result.node("ratio").aggregateTrace)
        assertEquals("charge", evidence.numeratorId)
        assertEquals("units", evidence.denominatorId)
        assertEquals(2, evidence.memberCount)
        assertEquals(1, evidence.activeMemberCount)
        assertEquals(listOf(true, false), evidence.members.map { it.active })
        decimal("20", evidence.numeratorTotal)
        decimal("100", evidence.denominatorTotal)
        decimal("0.2", evidence.result)
        assertEquals(result.node("ratio").crossTotal(), evidence.result)
        assertEquals(8, evidence.result!!.scale())
        assertNull(evidence.undefinedReason)
        assertFalse(evidence.truncated)
        assertFailsWith<UnsupportedOperationException> { (evidence.members as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (evidence.members.first().coord as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (evidence.dimensions as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (evidence.fixed as MutableMap<*, *>).clear() }
    }

    @Test
    fun `partial cross footing records its fixed scope and all inactive contributes neutral zero`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/partial
                  (dimension bucket {:members [:X :Y]})
                  (dimension member {:members [:A :B]})
                  (input charge :decimal {:per [bucket member]})
                  (input units :decimal {:per [bucket member]})
                  (section grouped "Grouped" {:per bucket}
                    (section detail "Detail" {:per [bucket member]}
                      (line ratio "Ratio" (decimal/divide charge units 8)
                        {:when (and (= bucket.key :X) (= member.key :A))
                         :aggregate {:ratio [charge units] :round [8 :half-up]}}))
                    (total checkpoint "Checkpoint")))
                """,
            ),
            case(
                "(case c (inputs {:charge {:X {:A 20 :B 30} :Y {:A 70 :B 10}} " +
                    ":units {:X {:A 100 :B 100} :Y {:A 100 :B 300}}}))",
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        val x =
            assertNotNull(
                assertIs<NodeTrace.Sum>(result.node("checkpoint").trace(listOf("X"))).parts.single().aggregate,
            )
        assertEquals(mapOf("bucket" to "X"), x.fixed)
        assertEquals(listOf(listOf("X", "A"), listOf("X", "B")), x.members.map { it.coord })
        decimal("0.2", x.result)
        val yPart = assertIs<NodeTrace.Sum>(result.node("checkpoint").trace(listOf("Y"))).parts.single()
        val y = assertNotNull(yPart.aggregate)
        assertEquals(mapOf("bucket" to "Y"), y.fixed)
        assertEquals(0, y.activeMemberCount)
        assertEquals("no-active-members", y.undefinedReason)
        assertNull(y.result)
        decimal("0", yPart.value)
        decimal("0", result.decimal("checkpoint", "Y"))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `undefined evidence distinguishes zero denominator from required rounding`() {
        val model = schema(
            """
            (schema test/undefined
              (dimension member {:members [:A :B]})
              (input charge :decimal {:per member})
              (input units :decimal {:per member})
              (line ratio "Ratio" (if (zero? units) nil (decimal/divide charge units 8))
                {:per member :op :info :aggregate {:ratio [charge units]}}))
            """,
        )
        val zero = Mantra.calculate(model, case("(case c (inputs {:charge {:A 0 :B 0} :units {:A 0 :B 0}}))"))
        assertTrue(zero.succeeded)
        assertEquals("zero-denominator", assertNotNull(zero.node("ratio").aggregateTrace).undefinedReason)
        val repeating = Mantra.calculate(model, case("(case c (inputs {:charge {:A 1 :B 1} :units {:A 3 :B 3}}))"))
        assertFalse(repeating.succeeded)
        val trace = assertNotNull(repeating.node("ratio").aggregateTrace)
        decimal("2", trace.numeratorTotal)
        decimal("6", trace.denominatorTotal)
        assertNull(trace.result)
        assertEquals("rounding-required", trace.undefinedReason)
    }

    @Test
    fun `bounded member evidence keeps full totals and exposes truncation without duplicate warnings`() {
        val members = (1..70).map { "M$it" }
        val result = Mantra.calculate(
            schema(
                """
                (schema test/bounded
                  (dimension member {:members [${members.joinToString(" ") { ":$it" }}]})
                  (input charge :decimal {:per member})
                  (input units :decimal {:per member})
                  (section detail "Detail" {:per member}
                    (line ratio "Ratio" (decimal/divide charge units 8)
                      {:aggregate {:ratio [charge units] :round [8 :half-up]}}))
                  (total checkpoint "Checkpoint"))
                """,
            ),
            case(
                "(case c (inputs {:charge {${members.joinToString(" ") { ":$it 1" }}} " +
                    ":units {${members.joinToString(" ") { ":$it 2" }}}}))",
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        val trace = assertNotNull(result.node("ratio").aggregateTrace)
        assertEquals(64, trace.members.size)
        assertEquals(70, trace.memberCount)
        assertEquals(70, trace.activeMemberCount)
        decimal("70", trace.numeratorTotal)
        decimal("140", trace.denominatorTotal)
        decimal("0.5", trace.result)
        decimal("0.5", result.decimal("checkpoint"))
        assertTrue(trace.truncated)
        val diagnostic = result.diagnostics.single()
        assertEquals("MANTRA-AUDIT-TRUNCATED", diagnostic.code)
        assertEquals(DiagnosticCategory.EVALUATION, diagnostic.category)
        val part = assertIs<NodeTrace.Sum>(result.node("checkpoint").trace()).parts.single()
        assertSame(trace, part.aggregate)
    }
}
