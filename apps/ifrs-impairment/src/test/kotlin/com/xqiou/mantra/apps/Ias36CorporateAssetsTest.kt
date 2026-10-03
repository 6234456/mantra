package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.render.Render
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independently authored fictional inputs; expected amounts are also checked by verify_expected.py. */
class Ias36CorporateAssetsTest {
    private val dir = Path.of("apps/ifrs-impairment")
    private val result = Mantra.calculate(
        Mantra.loadSchema(dir.resolve("schema.mantra")),
        Mantra.loadCase(dir.resolve("case-demo.mantra")),
    )

    private fun assertAmount(expected: Int, id: String, vararg coord: String) = assertEquals(
        0,
        BigDecimal(expected).compareTo(result.decimal(id, *coord)),
        "$id${coord.toList()} = ${result.decimal(id, *coord).toPlainString()}",
    )

    @Test
    fun `calculates the fictional shared-facility impairment case`() {
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        // Shared facilities use weights 1 : 2 : 3, with conserved whole-unit allocation.
        listOf("A" to 120, "B" to 360, "C" to 780).forEach { (cgu, weighted) ->
            assertAmount(weighted, "weighted-amount", cgu)
        }
        listOf("A" to 20, "B" to 60, "C" to 131).forEach { (cgu, share) -> assertAmount(share, "corporate-share", cgu) }
        listOf("A" to 140, "B" to 240, "C" to 391).forEach { (cgu, amount) ->
            assertAmount(amount, "carrying-amount-allocated", cgu)
        }
        // Schedule 3 – impairment test per CGU
        listOf("A" to 180, "B" to 210, "C" to 300).forEach { (cgu, ra) -> assertAmount(ra, "recoverable-amount", cgu) }
        listOf("A" to 0, "B" to 30, "C" to 91).forEach { (cgu, loss) -> assertAmount(loss, "impairment-loss", cgu) }
        assertEquals("viu", (result.node("recoverable-amount").trace(listOf("B")) as NodeTrace.Choice).selected)
        // The loss split also conserves the total after rounding.
        assertAmount(8, "loss-to-corporate", "B")
        assertAmount(22, "loss-to-own-assets", "B")
        assertAmount(30, "loss-to-corporate", "C")
        assertAmount(61, "loss-to-own-assets", "C")
        // Schedule 5 – smallest group of CGUs
        assertAmount(844, "group-before")
        assertAmount(723, "group-carrying-amount")
        assertAmount(0, "group-loss")
        assertAmount(121, "total-impairment")
    }

    @Test
    fun `renders an IFRS schedule working paper`() {
        val layout = Render.loadLayout(dir.resolve("layout.mantra"))
        val text = Render.text(result, layout, includeAudit = true)
        val html = Render.html(result, layout)
        val out = Path.of("apps/ifrs-impairment/build/out").also(Files::createDirectories)
        Files.writeString(out.resolve("ias36-demo.txt"), text)
        Files.writeString(out.resolve("ias36-demo.html"), html)
        println(text)
        assertTrue("Allocated shared facilities" in text)
        assertTrue("121" in text)
        assertTrue("<table class=\"calc matrix\">" in html)
    }

    @Test
    fun `case DSL can bind an application declared asset weighting formula`() {
        val customized = Mantra.calculate(result.schema, Mantra.loadCase(dir.resolve("case-custom-weight.mantra")))
        assertTrue(customized.succeeded, customized.diagnostics.joinToString("\n"))
        assertEquals(0, BigDecimal("36").compareTo(customized.decimal("weighting", "A")))
        assertEquals(0, BigDecimal("144").compareTo(customized.decimal("weighting", "B")))
        assertEquals(0, BigDecimal("8").compareTo(customized.decimal("corporate-share", "A")))
        assertEquals(0, BigDecimal("48").compareTo(customized.decimal("corporate-share", "B")))
        assertEquals(0, BigDecimal("155").compareTo(customized.decimal("corporate-share", "C")))
    }
}
