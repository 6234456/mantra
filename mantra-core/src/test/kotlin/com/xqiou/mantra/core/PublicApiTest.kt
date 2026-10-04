package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.FunctionCatalog
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.NodeTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PublicApiTest {
    @Test
    fun `inspection compiles a schema without evaluating its formulas`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "schema.mantra",
                """
            (schema test/inspection {}
              (input denominator :decimal {:default 0})
              (section body "Body"
                (line quotient "Quotient" (/ 1 denominator))))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val inspected = Mantra.inspect(schema)
        assertTrue(inspected.succeeded)
        assertEquals(NodeKind.LINE, inspected.node("quotient").kind)
        assertTrue(inspected.node("quotient").values.isEmpty())
        assertTrue(inspected.dependencyCount > 0)

        val calculated = Mantra.calculate(schema)
        assertFalse(calculated.succeeded)
        assertIs<NodeTrace.Failed>(calculated.node("quotient").trace())
        assertSame(calculated.view, CalculationView.of(calculated))
        assertSame(calculated.view.node("quotient"), calculated.node("quotient"))
    }

    @Test
    fun `function catalog exposes documentation for the calculation primitives`() {
        assertEquals("mantra.calc", FunctionCatalog.libraryId)
        assertEquals("2", FunctionCatalog.semanticsVersion)
        val functions = FunctionCatalog.functions
        assertEquals(functions.size, functions.map { it.name }.toSet().size)
        assertTrue(functions.all { it.summary.isNotBlank() })
        assertTrue(
            setOf("alloc/pro-rata", "calc/converge", "dim/rollup", "fin/npv").all { name ->
                functions.any {
                    it.name ==
                        name
                }
            },
        )
        assertTrue(FunctionCatalog.callableCount >= functions.size)
    }
}
