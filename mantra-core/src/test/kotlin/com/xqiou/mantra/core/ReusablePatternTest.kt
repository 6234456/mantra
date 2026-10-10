package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent arithmetic was fixed before implementation in docs/patterns/README.md. */
class ReusablePatternTest {
    private fun directory(pattern: String): Path = Path.of("docs/patterns", pattern)

    private fun calculate(
        pattern: String,
        caseName: String = "case-demo.mantra",
        expectedValidation: Boolean = true,
    ): CalculationResult {
        val root = directory(pattern)
        val schema = Mantra.loadSchema(root.resolve("schema.mantra"))
        assertEquals(
            setOf("pattern-rounded-product", "pattern-rounded-ratio"),
            schema.functions.map { it.name }.toSet(),
        )
        assertTrue(schema.functions.all { it.location.source == "formulas.mantra" })
        assertTrue("formulas.mantra" in schema.sources)
        val result = Mantra.calculate(schema, Mantra.loadCase(root.resolve(caseName)))
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(expectedValidation, result.validationPassed, result.diagnostics.toString())
        return result
    }

    private fun number(expected: String, actual: Value?) {
        assertEquals(0, BigDecimal(expected).compareTo(assertIs<Value.Num>(actual).value))
    }

    @Test
    fun `comparison separates source values rounded amounts variance and remaining allowance`() {
        val result = calculate("actuals-vs-baseline")
        number("120", result.value("actual", "A"))
        number("80", result.value("actual", "B"))
        number("100", result.value("baseline", "A"))
        number("90", result.value("baseline", "B"))
        number("20", result.value("variance", "A"))
        number("-10", result.value("variance", "B"))
        number("200", result.view.reduce("actual").value)
        number("190", result.view.reduce("baseline").value)
        number("10", result.view.reduce("variance").value)
        number("50", result.view.reduce("remaining").value)
    }

    @Test
    fun `independent source controls pass for one record in each bucket`() {
        val result = calculate("source-control")
        number("1", result.value("source-count", "A"))
        number("1", result.value("source-count", "B"))
        number("40", result.value("computed", "A"))
        number("15", result.value("computed", "B"))
        number("55", result.view.reduce("computed").value)
    }

    @Test
    fun `duplicate source records preserve totals while the explicit uniqueness check fails`() {
        val result = calculate("source-control", "case-duplicate.mantra", expectedValidation = false)
        number("2", result.value("source-count", "A"))
        number("40", result.value("computed", "A"))
        number("55", result.view.reduce("computed").value)
        val finding = result.diagnostics.single()
        assertEquals("MANTRA-CHECK-FAILED", finding.code)
        assertEquals("exactly-one-source", finding.nodeId)
        assertEquals(listOf("A"), finding.coord)
        assertEquals(DiagnosticCategory.BUSINESS, finding.category)
    }

    @Test
    fun `a binding member cap redistributes the request and exposes unused capacity`() {
        val result = calculate("capped-allocation")
        number("80", result.value("request"))
        number("10", result.value("allocated", "A"))
        number("28", result.value("allocated", "B"))
        number("42", result.value("allocated", "C"))
        number("80", result.value("allocated-total"))
        number("0", result.value("unallocated"))
        number("60", result.value("unused-total-capacity"))
    }

    @Test
    fun `request above all capacities remains explicitly unallocated and conserved`() {
        val result = calculate("capped-allocation", "case-excess.mantra")
        number("200", result.value("request"))
        number("10", result.value("allocated", "A"))
        number("30", result.value("allocated", "B"))
        number("100", result.value("allocated", "C"))
        number("140", result.value("allocated-total"))
        number("60", result.value("unallocated"))
        number("0", result.value("unused-total-capacity"))
    }

    @Test
    fun `shared ratio caller preserves undefined members and distinct aggregate rounding`() {
        val result = calculate("ratio-use")
        number("0.3333", result.value("rate", "A"))
        number("0.1667", result.value("rate", "B"))
        assertEquals(Value.Nil, result.value("rate", "C"))
        number("0.222222", result.view.reduce("rate").value)
        assertEquals(4, assertIs<Value.Num>(result.value("rate", "A")).value.scale())
        assertEquals(6, assertIs<Value.Num>(result.view.reduce("rate").value).value.scale())
    }

    @Test
    fun `Explain retains the shared fragment source rather than an invented expanded document`() {
        val root = directory("actuals-vs-baseline")
        val result = Mantra.calculateForExplain(
            Mantra.loadSchema(root.resolve("schema.mantra")),
            Mantra.loadCase(root.resolve("case-demo.mantra")),
            emptyList(),
            "actual",
            listOf("A"),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        number("120", result.value("actual", "A"))
        val trace = assertNotNull(result.explainTrace)
        assertTrue(trace.steps.any { it.location.source == "formulas.mantra" && it.text.contains("decimal/round") })
    }
}
