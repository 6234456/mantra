package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessValidationTest {
    private val dir = Path.of("apps/ifrs-impairment")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private val demo = Mantra.loadCase(dir.resolve("case-demo.mantra"))

    @Test
    fun `negative carrying amount is diagnosed at its CGU and loss calculations remain available`() {
        val rows = (demo.inputs.getValue("cgus") as Value.Vec).items.mapIndexed { index, row ->
            if (index == 0) {
                Value.MapV((row as Value.MapV).entries + (Value.Kw("carrying-amount") to Value.num(-120)))
            } else {
                row
            }
        }
        val invalid = Mantra.calculate(schema, demo.copy(inputs = demo.inputs + ("cgus" to Value.Vec(rows))))
        assertTrue(invalid.succeeded)
        assertFalse(invalid.validationPassed)
        val finding = invalid.diagnostics.single { it.code == "MANTRA-CHECK-FAILED" }
        assertEquals("cgu-carrying-nonnegative", finding.nodeId)
        assertEquals(listOf("A"), finding.coord)
        assertTrue(invalid.node("total-impairment").anyActive)
    }

    @Test
    fun `an empty CGU table is a minimum-row finding without deleting the group calculation`() {
        val empty = Mantra.calculate(schema, demo.copy(inputs = demo.inputs + ("cgus" to Value.Vec(emptyList()))))
        assertTrue(empty.succeeded)
        assertFalse(empty.validationPassed)
        assertEquals("MANTRA-INPUT-MIN-ROWS", empty.diagnostics.single().code)
        assertEquals("cgus", empty.diagnostics.single().nodeId)
        assertEquals(Value.num(0), empty.value("total-impairment"))
    }
}
