package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkbenchJsonTest {
    @Test
    fun `value encoding preserves decimal scale and ordered map keys`() {
        val value = Value.MapV(
            linkedMapOf(
                Value.Kw("amount") to Value.Num(BigDecimal("83217.90")),
                Value.Num(BigDecimal("1.0")) to Value.Vec(listOf(Value.Date(LocalDate.parse("2025-12-31")), Value.Nil)),
            ),
        )
        assertEquals(
            """{"map":[[{"kw":"amount"},{"n":"83217.90"}],[{"n":"1.0"},[{"date":"2025-12-31"},null]]]}""",
            WorkbenchJson.write(WorkbenchJson.value(value)),
        )
    }

    @Test
    fun `writer escapes text and refuses unencoded decimals`() {
        assertEquals("\"a\\n\\\"b\\\"\"", WorkbenchJson.write("a\n\"b\""))
        assertFailsWith<IllegalStateException> { WorkbenchJson.write(BigDecimal("1.25")) }
    }
}
