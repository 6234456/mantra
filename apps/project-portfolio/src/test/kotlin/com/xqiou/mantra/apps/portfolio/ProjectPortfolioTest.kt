package com.xqiou.mantra.apps.portfolio

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Expected proposals and ratios come from the separately authored Decimal/Fraction source. */
class ProjectPortfolioTest {
    private val directory = Path.of("apps/project-portfolio")
    private val schema = Mantra.loadSchema(directory.resolve("schema.mantra"))
    private val parameters = listOf(Mantra.loadParameters(directory.resolve("params.mantra")))
    private fun result(name: String) =
        Mantra.calculate(schema, Mantra.loadCase(directory.resolve("case-$name.mantra")), parameters)
    private fun reference(name: String, suffix: String) =
        Json.parse(Files.readString(directory.resolve("independent/references/$name-$suffix.json"))) as Value.MapV

    @Test
    fun `all independent selected proposal department and global amounts including currency boundaries match`() {
        listOf(
            "demo",
            "none-selected",
            "over-budget",
            "fractional-currency",
            "missing-approval",
            "zero-cost",
        ).forEach { name ->
            val actual = result(name)
            assertTrue(actual.succeeded, "$name: ${actual.diagnostics}")
            val summary = reference(name, "summary")
            assertEquals(
                (summary.entries.getValue(Value.Kw("validationPassed")) as Value.Bool).value,
                actual.validationPassed,
            )
            reference(name, "values").entries.forEach { (key, expected) ->
                val address = (key as Value.Kw).name
                val value = read(actual, address)
                assertTrue(value is Value.Num, "$name/$address must really be numeric")
                assertEquals(0, (expected as Value.Text).value.toBigDecimal().compareTo(value.value), "$name/$address")
            }
            (summary.entries.getValue(Value.Kw("undefined")) as Value.Vec).items.forEach { key ->
                val value = read(actual, (key as Value.Text).value)
                assertTrue(value == null || value == Value.Nil, "$name/${key.value}: preserve undefined")
            }
            val expected = (summary.entries.getValue(Value.Kw("expectedBusiness")) as Value.Vec).items.map { item ->
                val entry = item as Value.MapV
                (entry.entries.getValue(Value.Kw("code")) as Value.Text).value to
                    (entry.entries.getValue(Value.Kw("node")) as Value.Text).value
            }.sortedBy { it.toString() }
            assertEquals(
                expected,
                actual.diagnostics.filter { it.category == DiagnosticCategory.BUSINESS }.map {
                    it.code to
                        it.nodeId
                }.sortedBy { it.toString() },
            )
        }
    }

    @Test
    fun `supplied false stays false and no selection or free proposals retain undefined weighted ratios`() {
        val demo = result("demo")
        assertEquals(Value.Bool(false), demo.value("proposal-selected", "B"))
        assertEquals(Value.num(0), demo.value("selected-cost", "B"))
        assertEquals(0, BigDecimal("120").compareTo(demo.decimal("portfolio-cost")))
        assertEquals(
            0,
            BigDecimal("1.733333").compareTo((demo.view.reduce("project-benefit-cost").value as Value.Num).value),
        )
        listOf("none-selected", "zero-cost").forEach { name ->
            val actual = result(name)
            assertTrue(actual.succeeded && actual.validationPassed)
            assertEquals(null, actual.node("project-benefit-cost").crossTotal())
            assertEquals(2, actual.diagnostics.count { it.code == "MANTRA-AGGREGATE-ZERO-DENOMINATOR" })
        }
    }

    @Test
    fun `missing selected note gives the actual zero based row column while overrun remains a business outcome`() {
        val missing = result("missing-approval")
        assertTrue(missing.succeeded)
        assertFalse(missing.validationPassed)
        val finding = missing.diagnostics.single()
        assertEquals("MANTRA-INPUT-REQUIRED", finding.code)
        assertEquals("projects", finding.nodeId)
        assertEquals(0, finding.rowIndex)
        assertEquals("approval-note", finding.column)
        val over = result("over-budget")
        assertTrue(over.succeeded)
        assertFalse(over.validationPassed)
        assertEquals(0, BigDecimal("-50").compareTo(over.decimal("budget-remaining")))
        assertEquals(0, BigDecimal("-40").compareTo(over.decimal("capacity-remaining")))
    }

    private fun read(result: CalculationResult, address: String): Value? {
        val id = address.substringBefore('@')
        val suffix = address.substringAfter('@', "")
        return if (suffix ==
            "*"
        ) {
            result.view.reduce(id).value
        } else {
            result.node(id).value(if (suffix.isEmpty()) emptyList() else suffix.split('/'))
        }
    }
}
