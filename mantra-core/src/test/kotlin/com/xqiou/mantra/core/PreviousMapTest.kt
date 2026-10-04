package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.TraceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviousMapTest {
    @Test
    fun `previous member maps retain nullable leaves and expose their exact prior fixed scope`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "map.mantra",
                """
            (schema test/prev-map
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (dimension asset {:members [:A :B]})
              (line closing "Closing" (prev closing (decimal/divide 1 0))
                {:per [year asset] :aggregate {:last year} :op :info})
              (line carried "Carried" (get (prev closing {:A (if (= year.index 0) 1.0 (/ 1 0)) :B 2.0}) :A)
                {:per year :aggregate {:last year}}))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val result = Mantra.calculateForAudit(schema)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(0, result.decimal("carried", "P1").compareTo(java.math.BigDecimal.ONE))
        assertEquals(Value.Nil, result.value("carried", "P2"))
        val trace = result.node("carried").trace(listOf("P2")) as NodeTrace.Computed
        val reference = trace.references.single { it.kind == TraceRef.Kind.PREVIOUS }
        assertNull(reference.coord)
        assertEquals(mapOf("year" to "P1"), reference.fixed)
        assertEquals(Value.MapV(linkedMapOf(Value.Kw("A") to Value.Nil, Value.Kw("B") to Value.Nil)), reference.value)
        assertTrue(trace.explanation!!.steps.none { it.text == "(/ 1 0)" })
    }
}
