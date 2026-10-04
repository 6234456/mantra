package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessValidationTest {
    private val dir = Path.of("apps/cost-accounting")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private val demo = Mantra.loadCase(dir.resolve("case-demo.mantra"))

    @Test
    fun `a direct posting without an order is diagnosed at the original table cell`() {
        val rows = (demo.inputs.getValue("primary-postings") as Value.Vec).items.mapIndexed { index, row ->
            if (index == 0) Value.MapV((row as Value.MapV).entries - Value.Kw("order-id")) else row
        }
        val missing = Mantra.calculate(
            schema,
            demo.copy(inputs = demo.inputs + ("primary-postings" to Value.Vec(rows))),
        )
        assertTrue(missing.succeeded)
        assertFalse(missing.validationPassed)
        val finding = missing.diagnostics.single { it.code == "MANTRA-INPUT-REQUIRED" }
        assertEquals("primary-postings", finding.nodeId)
        assertEquals(0, finding.rowIndex)
        assertEquals("order-id", finding.column)
        assertEquals(0, BigDecimal("4000").compareTo(missing.decimal("unassigned-cost")))
        assertTrue(missing.diagnostics.any { it.code == "MANTRA-RECONCILE-FAILED" && it.nodeId == "unassigned-cost" })
    }

    @Test
    fun `a negative order base is a member finding while source totals remain exact`() {
        val rows = (demo.inputs.getValue("orders") as Value.Vec).items.mapIndexed { index, row ->
            if (index == 0) {
                Value.MapV((row as Value.MapV).entries + (Value.Kw("allocation-base") to Value.num(-10)))
            } else {
                row
            }
        }
        val negative = Mantra.calculate(schema, demo.copy(inputs = demo.inputs + ("orders" to Value.Vec(rows))))
        assertTrue(negative.succeeded)
        assertFalse(negative.validationPassed)
        val finding = negative.diagnostics.single { it.code == "MANTRA-CHECK-FAILED" }
        assertEquals("order-base-nonnegative", finding.nodeId)
        assertEquals(listOf("O100"), finding.coord)
        assertEquals(0, BigDecimal("15100").compareTo(negative.decimal("source-cost-total")))
    }

    @Test
    fun `unit precision parameters change member rounding and retain weighted cross-total rounding`() {
        val precise = Mantra.calculate(
            schema,
            demo,
            listOf(Mantra.loadParameters(dir.resolve("params-unit-precision.mantra"))),
        )
        assertTrue(precise.succeeded)
        assertTrue(precise.validationPassed)
        assertEquals("cost.accounting/unit-precision", precise.node("unit-scale").parameterSource)
        assertEquals(0, BigDecimal("65.333333").compareTo(precise.decimal("actual-weighted-unit", "A")))
        assertEquals(
            0,
            BigDecimal("65.6522").compareTo(checkNotNull(precise.node("actual-weighted-unit").crossTotal())),
        )
    }
}
