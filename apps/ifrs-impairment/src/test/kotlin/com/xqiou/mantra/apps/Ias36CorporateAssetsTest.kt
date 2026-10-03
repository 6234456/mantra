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

/**
 * IAS 36 Illustrative Example 8 (IE69–IE79). All figures of the example are reproduced except the
 * split of CGU B's loss in Schedule 4: the example prints 12 (building) / 30 (own assets) although
 * 42 × 56/206 = 11.42 and 42 × 150/206 = 30.58. No single rounding rule yields both 12/30 and C's
 * 1/3, so the engine's documented largest-remainder allocation (11/31) is asserted instead.
 */
class Ias36CorporateAssetsTest {
    private val dir = Path.of("apps/ifrs-impairment")
    private val result = Mantra.calculate(Mantra.loadSchema(dir.resolve("schema.mantra")), Mantra.loadCase(dir.resolve("case-ie8.mantra")))

    private fun assertAmount(expected: Int, id: String, vararg coord: String) =
        assertEquals(0, BigDecimal(expected).compareTo(result.decimal(id, *coord)), "$id${coord.toList()} = ${result.decimal(id, *coord).toPlainString()}")

    @Test
    fun `reproduces IAS 36 illustrative example 8`() {
        assertTrue(result.succeeded, result.diagnostics.joinToString("\n"))
        // Schedule 1 – allocation of the headquarters building (weighting 1 : 2 : 2)
        listOf("A" to 100, "B" to 300, "C" to 400).forEach { (cgu, weighted) -> assertAmount(weighted, "weighted-amount", cgu) }
        listOf("A" to 19, "B" to 56, "C" to 75).forEach { (cgu, share) -> assertAmount(share, "corporate-share", cgu) }
        listOf("A" to 119, "B" to 206, "C" to 275).forEach { (cgu, amount) -> assertAmount(amount, "carrying-amount-allocated", cgu) }
        // Schedule 3 – impairment test per CGU
        listOf("A" to 199, "B" to 164, "C" to 271).forEach { (cgu, ra) -> assertAmount(ra, "recoverable-amount", cgu) }
        listOf("A" to 0, "B" to 42, "C" to 4).forEach { (cgu, loss) -> assertAmount(loss, "impairment-loss", cgu) }
        assertEquals("viu", (result.node("recoverable-amount").trace(listOf("B")) as NodeTrace.Choice).selected)
        // Schedule 4 – allocation within B and C (see class comment for B's rounding)
        assertAmount(11, "loss-to-corporate", "B")
        assertAmount(31, "loss-to-own-assets", "B")
        assertAmount(1, "loss-to-corporate", "C")
        assertAmount(3, "loss-to-own-assets", "C")
        // Schedule 5 – smallest group of CGUs
        assertAmount(650, "group-before")
        assertAmount(604, "group-carrying-amount")
        assertAmount(0, "group-loss")
        assertAmount(46, "total-impairment")
    }

    @Test
    fun `renders an IFRS schedule working paper`() {
        val layout = Render.loadLayout(dir.resolve("layout.mantra"))
        val text = Render.text(result, layout, includeAudit = true)
        val html = Render.html(result, layout)
        val out = Path.of("apps/ifrs-impairment/build/out").also(Files::createDirectories)
        Files.writeString(out.resolve("ias36-ie8.txt"), text)
        Files.writeString(out.resolve("ias36-ie8.html"), html)
        println(text)
        assertTrue("Allocation of the headquarters building" in text)
        assertTrue("(42)" in text || "42" in text)
        assertTrue("<table class=\"calc matrix\">" in html)
    }

    @Test
    fun `case DSL can bind an application declared asset weighting formula`() {
        val customized = Mantra.calculate(result.schema, Mantra.loadCase(dir.resolve("case-custom-weight.mantra")))
        assertTrue(customized.succeeded, customized.diagnostics.joinToString("\n"))
        assertEquals(0, BigDecimal("100").compareTo(customized.decimal("weighting", "A")))
        assertEquals(0, BigDecimal("400").compareTo(customized.decimal("weighting", "B")))
        assertEquals(0, BigDecimal("10").compareTo(customized.decimal("corporate-share", "A")))
        assertEquals(0, BigDecimal("60").compareTo(customized.decimal("corporate-share", "B")))
        assertEquals(0, BigDecimal("80").compareTo(customized.decimal("corporate-share", "C")))
    }
}
