package com.xqiou.mantra.apps.energy

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

/** All numeric expectations were independently frozen before this schema was authored. */
class EnergyBudgetTest {
    private val directory = Path.of("apps/energy-budget")
    private val schema = Mantra.loadSchema(directory.resolve("schema.mantra"))
    private val parameters = listOf(Mantra.loadParameters(directory.resolve("params.mantra")))
    private fun result(name: String) =
        Mantra.calculate(schema, Mantra.loadCase(directory.resolve("case-$name.mantra")), parameters)
    private fun reference(name: String, suffix: String) =
        Json.parse(Files.readString(directory.resolve("independent/references/$name-$suffix.json"))) as Value.MapV

    @Test
    fun `all independent period coordinates global and partial reductions retain exact stocks and flows`() {
        listOf(
            "demo",
            "zero-capacity",
            "single-site",
            "all-zero-demand",
            "fractional-measurements",
            "unreconciled",
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
                assertTrue(value is Value.Num, "$name/$address must be numeric, not ${value?.javaClass?.simpleName}")
                assertEquals(0, (expected as Value.Text).value.toBigDecimal().compareTo(value.value), "$name/$address")
            }
            (summary.entries.getValue(Value.Kw("undefined")) as Value.Vec).items.forEach { key ->
                val value = read(actual, (key as Value.Text).value)
                assertTrue(value == null || value == Value.Nil, "$name/${key.value}: undefined must not become zero")
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
    fun `storage carries forward with boundary and weighted reductions`() {
        val actual = result("demo")
        assertEquals(0, BigDecimal("20").compareTo(actual.decimal("closing-storage", "A", "P2")))
        assertEquals(0, BigDecimal("20").compareTo(actual.decimal("opening-storage", "A", "P3")))
        assertEquals(Value.num(12), actual.view.reduce("opening-storage").value)
        assertEquals(Value.num(0), actual.view.reduce("closing-storage").value)
        assertEquals(Value.num(128), actual.view.reduce("grid-import").value)
        assertEquals(
            0,
            BigDecimal("0.663158").compareTo((actual.view.reduce("local-coverage").value as Value.Num).value),
        )
        val failed = result("unreconciled")
        assertTrue(failed.succeeded)
        assertFalse(failed.validationPassed)
        assertEquals(listOf("A", "P2"), failed.diagnostics.single().coord)
        assertEquals(
            0,
            actual.decimal("closing-storage", "A", "P2").compareTo(failed.decimal("closing-storage", "A", "P2")),
        )
        assertEquals(null, failed.view.reduce("energy-reconciliation").value)
        assertEquals(0, BigDecimal("-1").compareTo((failed.view.reduce("energy-difference").value as Value.Num).value))
        assertEquals(
            0,
            BigDecimal(
                "-1",
            ).compareTo(
                (failed.view.reduce("energy-difference", mapOf("budget-month" to "P2")).value as Value.Num).value,
            ),
        )
    }

    private fun read(result: CalculationResult, address: String): Value? {
        val id = address.substringBefore('@')
        val suffix = address.substringAfter('@', "")
        val node = result.node(id)
        if (suffix == "*") return result.view.reduce(id).value
        if ('*' in
            suffix
        ) {
            return result.view.reduce(
                id,
                node.dims.zip(suffix.split('/')).filter {
                    it.second != "*"
                }.toMap(),
            ).value
        }
        return node.value(if (suffix.isEmpty()) emptyList() else suffix.split('/'))
    }
}
