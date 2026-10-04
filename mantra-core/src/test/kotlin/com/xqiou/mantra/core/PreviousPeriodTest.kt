package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.TraceRef
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreviousPeriodTest {
    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text.trimIndent()), SourceResolver { _, _ -> null })
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text.trimIndent()))
    private fun number(expected: String, actual: Value) =
        assertEquals(0, BigDecimal(expected).compareTo((actual as Value.Num).value))

    @Test
    fun `two assets carry their own previous closing through three periods`() {
        val result = Mantra.calculateForAudit(
            schema(
                """
            (schema test/carry
              (dimension year {:periods {:start "2026-07-01" :unit :year :count 3}})
              (dimension asset {:members [:A :B]})
              (input opening :decimal {:per asset})
              (input movement :decimal {:per [year asset]})
              (line closing "Closing" (+ (prev closing opening) movement)
                {:per [year asset] :aggregate {:last year}})
              (check positive "Nonnegative closing" (>= closing 0) {:per [year asset]}))
        """,
            ),
            case(
                """
            (case c (inputs {:opening {:A 100 :B 200}
              :movement {:P1 {:A 10 :B 20} :P2 {:A 5 :B -10} :P3 {:A -2 :B 3}}}))
        """,
            ),
        )
        assertTrue(result.succeeded)
        assertTrue(result.validationPassed)
        number("113", result.value("closing", "P3", "A"))
        number("213", result.value("closing", "P3", "B"))
        number("326", result.view.reduce("closing").value!!)
        val trace = result.node("closing").trace(listOf("P3", "A")) as NodeTrace.Computed
        val prior = trace.references.single { it.kind == TraceRef.Kind.PREVIOUS }
        assertEquals(listOf("P2", "A"), prior.coord)
        number("115", prior.value)
    }

    @Test
    fun `first fallback is lazy and later undefined previous values are retained`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/lazy
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (line carried "Carried" (prev carried (if (= year.index 0) 7 (/ 1 0))) {:per year})
              (line unknown "Unknown" (prev unknown (decimal/divide 1 0)) {:per year :aggregate {:last year}}))
        """,
            ),
        )
        assertTrue(result.succeeded)
        listOf("P1", "P2", "P3").forEach { number("7", result.value("carried", it)) }
        assertEquals(Value.Nil, result.value("unknown", "P2"))
        assertEquals(Value.Nil, result.value("unknown", "P3"))
    }

    @Test
    fun `ordinary self references and full future maps are coordinate cycles`() {
        listOf("(+ closing 1)", "(+ (dim/sum all.closing) 1)").forEach { formula ->
            val failure = assertFailsWith<MantraException> {
                Mantra.calculate(
                    schema(
                        """
                    (schema test/cycle
                      (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
                      (line closing "Closing" $formula {:per year}))
                """,
                    ),
                )
            }
            assertTrue(failure.diagnostics.single { it.code == "MANTRA-CYCLE" }.message.contains("year=P1"))
        }
    }

    @Test
    fun `spread evaluates once per period and shares captured evidence across output members`() {
        val result = Mantra.calculateForAudit(
            schema(
                """
            (schema test/spread
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (dimension asset {:members [:A :B]})
              (input amount :decimal {:per year})
              (input weights :decimal {:per asset})
              (line split "Split" (alloc/pro-rata mantra/amount weights 2)
                {:per [year asset] :spread true}))
        """,
            ),
            case("""(case c (inputs {:amount {:P1 100 :P2 60} :weights {:A 1 :B 1}}))"""),
        )
        assertTrue(result.succeeded)
        number("50", result.value("split", "P1", "A"))
        number("30", result.value("split", "P2", "B"))
        val one = result.node("split").trace(listOf("P1", "A")) as NodeTrace.Computed
        val two = result.node("split").trace(listOf("P1", "B")) as NodeTrace.Computed
        assertSame(one.explanation, two.explanation)
    }

    @Test
    fun `a dimension may bootstrap from derived period values without a false declaration cycle`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/bootstrap
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (dimension asset {:members [{:key :A :when (> (dim/sum funding) 0)}]})
              (line funding "Funding" 10 {:per year :op :info})
              (line holding "Holding" (prev holding 100) {:per [year asset] :aggregate {:last year}}))
        """,
            ),
        )
        assertTrue(result.succeeded)
        assertEquals(listOf("A"), result.members.getValue("asset").map { it.key })
        number("100", result.value("holding", "P2", "A"))
    }
}
