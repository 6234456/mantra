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
    private val dir = Path.of("apps/de-est")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private val demo = Mantra.loadCase(dir.resolve("case-mustermann.mantra"))

    @Test
    fun `inconsistent assessment flags are business findings without stopping tax calculation`() {
        val inconsistent = Mantra.calculate(
            schema,
            demo.copy(inputs = demo.inputs + ("alleinerziehend" to Value.Bool(true))),
        )
        assertTrue(inconsistent.succeeded)
        assertFalse(inconsistent.validationPassed)
        assertEquals("veranlagung-konsistent", inconsistent.diagnostics.single().nodeId)
        assertEquals(0, BigDecimal("17996").compareTo(inconsistent.decimal("festzusetzende-est")))
    }

    @Test
    fun `positive wages require explicitly supplied withholding while zero withholding is valid`() {
        val withholding = demo.inputs.getValue("lohnsteuer") as Value.MapV
        val missing = Mantra.calculate(
            schema,
            demo.copy(inputs = demo.inputs + ("lohnsteuer" to Value.MapV(withholding.entries - Value.Kw("A")))),
        )
        assertTrue(missing.succeeded)
        assertFalse(missing.validationPassed)
        val finding = missing.diagnostics.single { it.code == "MANTRA-INPUT-REQUIRED" }
        assertEquals("lohnsteuer", finding.nodeId)
        assertEquals(listOf("A"), finding.coord)
        val zero = Mantra.calculate(schema, Mantra.loadCase(dir.resolve("case-single-tax-free.mantra")))
        assertTrue(zero.succeeded)
        assertTrue(zero.validationPassed)
        assertEquals(Value.num(0), zero.value("lohnsteuer", "A"))
    }
}
