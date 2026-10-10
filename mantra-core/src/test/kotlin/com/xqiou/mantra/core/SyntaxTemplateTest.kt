package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.NodeTrace
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Expected arithmetic is independently locked in docs/templates/README.md. */
class SyntaxTemplateTest {
    private fun calculate(template: String): CalculationResult {
        val directory = Path.of("docs/templates", template)
        val result = Mantra.calculate(
            Mantra.loadSchema(directory.resolve("schema.mantra")),
            Mantra.loadCase(directory.resolve("case-demo.mantra")),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertTrue(result.validationPassed, result.diagnostics.toString())
        return result
    }

    private fun number(expected: String, actual: Value?) {
        assertEquals(0, BigDecimal(expected).compareTo(assertIs<Value.Num>(actual).value))
    }

    @Test
    fun `ratio template divides aggregate components with independently declared rounding`() {
        val result = calculate("ratio")
        number("0.3333", result.value("rate", "A"))
        number("0.1667", result.value("rate", "B"))
        number("0.222222", result.view.reduce("rate").value)
        number("2", result.view.reduce("charge").value)
        number("9", result.view.reduce("units").value)
        assertEquals(4, assertIs<Value.Num>(result.value("rate", "A")).value.scale())
        assertEquals(6, assertIs<Value.Num>(result.view.reduce("rate").value).value.scale())
    }

    @Test
    fun `allocation template distributes rounding residue and reconciles the total`() {
        val result = calculate("allocation")
        number("16.67", result.value("allocated", "A"))
        number("33.34", result.value("allocated", "B"))
        number("50.00", result.value("allocated", "C"))
        number("100.01", result.value("allocation-total"))
        number("100.01", result.view.reduce("allocated").value)
    }

    @Test
    fun `roll forward template distinguishes period stocks and total flow including a zero closing`() {
        val result = calculate("roll-forward")
        listOf("120", "115", "125").forEachIndexed { index, expected ->
            number(expected, result.value("closing", "A", "P${index + 1}"))
        }
        listOf("40", "45", "0").forEachIndexed { index, expected ->
            number(expected, result.value("closing", "B", "P${index + 1}"))
        }
        number("150", result.view.reduce("opening").value)
        number("-25", result.view.reduce("movement").value)
        number("125", result.view.reduce("closing").value)
        number("0", result.view.reduce("closing", mapOf("entity" to "B")).value)
        number("1", result.value("movement-count", "A", "P1"))
    }

    @Test
    fun `named rules template preserves eligibility chosen option and contribution signs`() {
        val result = calculate("rules")
        number("125.00", result.value("gross"))
        number("30", result.value("selected-discount"))
        number("30", result.value("applied-discount"))
        number("95.00", result.value("net"))
        number("125.00", result.value("reference-value"))
        val choice = assertIs<NodeTrace.Choice>(result.node("selected-discount").trace())
        assertEquals("requested", choice.selected)
        assertFalse(choice.options.single { it.key == "alternative" }.available)
    }
}
