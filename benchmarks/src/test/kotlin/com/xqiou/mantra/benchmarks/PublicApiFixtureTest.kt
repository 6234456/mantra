package com.xqiou.mantra.benchmarks

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PublicApiFixtureTest {
    @Test
    fun `separate consumer plans calculates explains renders and recalculates all workbook values`() {
        val fixture = SyntheticFixture(Scenario("public-api-fixture", 4, 3, 6))
        val inspected = Mantra.inspect(fixture.schema, fixture.case)
        assertTrue(inspected.succeeded)
        assertTrue(inspected.node("step3").values.isEmpty())
        val result = Mantra.calculate(fixture.schema, fixture.case)
        fixture.verify(result)
        assertEquals(0, BigDecimal("37.50").compareTo(result.decimal("answer")))
        val explained = Mantra.calculateForExplain(
            fixture.schema,
            fixture.case,
            emptyList(),
            fixture.explainNode,
            fixture.explainCoord,
        )
        fixture.verify(explained)
        assertNotNull(explained.explainTrace)
        assertTrue(Render.text(result, fixture.layout, includeAudit = true).contains("Combined answer"))
        assertTrue(Render.html(result, fixture.layout).contains("Combined answer"))
        ExcelExport.workbook(result, fixture.layout).use { workbook ->
            fixture.verify(workbook)
            assertTrue(workbook.bytes().size > 0)
        }
    }
}
