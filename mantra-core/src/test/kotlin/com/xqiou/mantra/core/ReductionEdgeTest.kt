package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReductionEdgeTest {
    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text.trimIndent()), SourceResolver { _, _ -> null })

    @Test
    fun `unsupported reductions do not turn a numeric total into undefined`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/unsupported
              (dimension member {:members [:A :B]})
              (line text "Text" "Note" {:type :text})
              (line metric "Nonadditive" 7 {:per member :aggregate :none})
              (line amount "Amount" 4)
              (total combined "Combined"))
        """,
            ),
        )
        assertTrue(result.succeeded)
        assertEquals(Value.num(4), result.value("combined"))
        assertNull(result.view.reduce("text").value)
        assertNull(result.view.reduce("metric").value)
        assertEquals(Value.num(7), result.view.reduce("metric", mapOf("member" to "A")).value)
        val trace = result.node("combined").trace() as NodeTrace.Sum
        assertEquals(listOf("0", "0", "4"), trace.parts.map { it.value!!.toPlainString() })
    }

    @Test
    fun `real previous nil propagates through additive reductions and enclosing totals`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/undefined-flow
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (line unknown "Unknown" (prev unknown (decimal/divide 1 0)) {:per year})
              (total combined "Combined"))
        """,
            ),
        )
        assertTrue(result.succeeded)
        assertEquals(Value.Nil, result.value("unknown", "P2"))
        assertEquals(Value.Nil, result.view.reduce("unknown").value)
        assertEquals(Value.Nil, result.value("combined"))
        assertNull(result.node("combined").crossTotal())
    }

    @Test
    fun `undefined stock choice differs from a choice with no applicable options`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/choice-nil
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (choice unknown "Unknown" {:rule :min :per year :aggregate {:last year} :op :info}
                (option :a "A" (decimal/divide 1 0))
                (option :b "B" (decimal/divide 2 0)))
              (choice excluded "Excluded" {:rule :min :per year :aggregate {:last year} :op :info}
                (option :a "A" (decimal/divide 1 0) {:when false})
                (option :b "B" (decimal/divide 2 0) {:when false}))
              (choice known "Known" {:rule :min :per year :aggregate {:last year} :op :info}
                (option :a "A" (decimal/divide 1 0))
                (option :b "B" 8)))
        """,
            ),
        )
        assertTrue(result.succeeded)
        assertEquals(Value.Nil, result.value("unknown", "P2"))
        assertEquals(Value.Nil, result.view.reduce("unknown").value)
        assertEquals(Value.ZERO, result.view.reduce("excluded").value)
        assertEquals(Value.num(8), result.view.reduce("known").value)
    }

    @Test
    fun `complete fixed coordinates obey ancestor membership before accessing values`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/ancestor-coordinate
              (dimension quarter {:periods {:start "2026-01-01" :unit :quarter :count 2}})
              (dimension month {:periods {:start "2026-01-01" :unit :month :count 6} :parent quarter})
              (line closing "Closing" (+ month.index 1) {:per month :aggregate {:last month} :op :info})
              (check valid "Valid" true {:per month}))
        """,
            ),
        )
        val coherent = mapOf("quarter" to "P1", "month" to "P2")
        val contradictory = mapOf("quarter" to "P2", "month" to "P2")
        assertEquals(listOf("P2"), result.view.coordinate("closing", coherent))
        assertEquals(listOf("P2"), result.view.coordinate("valid", coherent))
        assertNull(result.view.coordinate("closing", mapOf("quarter" to "P1")))
        assertNull(result.view.coordinate("valid", contradictory))
        assertEquals(Value.ZERO, result.view.reduce("closing", contradictory).value)
        assertEquals(Value.num(2), result.view.reduce("closing", coherent).value)
        assertEquals(
            listOf(listOf("P1"), listOf("P2"), listOf("P3")),
            result.view.coordinates("closing", mapOf("quarter" to "P1")),
        )
        assertEquals(emptyList(), result.view.coordinates("valid", contradictory))
        assertEquals(result.node("closing").values.keys.toList(), result.view.coordinates("closing"))
    }
}
