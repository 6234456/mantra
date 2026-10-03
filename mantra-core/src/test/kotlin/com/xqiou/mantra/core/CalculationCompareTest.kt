package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.CalculationCompare
import com.xqiou.mantra.core.view.CalculationView
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalculationCompareTest {
    private val noIncludes = SourceResolver { _, _ -> null }

    @Test
    fun `compares member coordinates nonnumeric values and decimal scale exactly`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "schema.mantra",
                """
            (schema app/compare {:mainline [panel]}
              (param factor 1.0)
              (input include-b :boolean {:default true})
              (input input-quantity :decimal {:per member})
              (dimension member {:members [{:key :A} {:key :B :when include-b}]})
              (section root "Root"
                (section panel "Panel" {:display :schedule :per member}
                  (line result "Result" (+ input-quantity factor))
                  (total total "Total"))))
                """.trimIndent(),
            ),
            noIncludes,
        )
        val baseCase = Mantra.loadCase(SourceText("base.mantra", "(case base (inputs {:input-quantity {:A 2 :B 3}}))"))
        val variantCase = Mantra.loadCase(
            SourceText(
                "variant.mantra",
                """
            (case variant (inputs {:input-quantity {:A 4 :B 3} :include-b false}) (params {:factor 1.00}))
                """.trimIndent(),
            ),
        )
        val base = CalculationView.of(Mantra.calculate(schema, baseCase))
        val variant = CalculationView.of(Mantra.calculate(schema, variantCase))
        val diff = CalculationCompare.between(base, variant)
        assertTrue(diff.parameterChanges.isEmpty(), "1.0 and 1.00 have the same numeric value")
        val mainline = diff.mainline.associateBy { it.value.coord }
        assertEquals(setOf(listOf("A"), listOf("B")), mainline.keys)
        assertEquals(0, BigDecimal("2").compareTo(requireNotNull(mainline.getValue(listOf("A")).value.delta)))
        val missing = mainline.getValue(listOf("B")).value
        assertTrue(missing.basePresent)
        assertTrue(!missing.variantPresent)
        assertNull(missing.delta)
        val boolean = diff.changes.flatMap { it.items }.single { it.node == "include-b" }
        assertEquals(Value.Bool(true), boolean.base)
        assertEquals(Value.Bool(false), boolean.variant)
        assertNull(boolean.delta)
    }
}
