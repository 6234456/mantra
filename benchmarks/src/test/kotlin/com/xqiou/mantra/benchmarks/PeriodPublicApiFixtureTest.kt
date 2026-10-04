package com.xqiou.mantra.benchmarks

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PeriodPublicApiFixtureTest {
    @Test
    fun `public period engine reducers both layouts and XLSX match independent closed-form values`() {
        val fixture = PeriodFixture(3, 3)
        val result = Mantra.calculateForAudit(fixture.schema, fixture.case)
        fixture.verify(result)
        assertEquals(0, BigDecimal("654").compareTo(fixture.expectedReduction("closing")))
        val explained = Mantra.calculateForExplain(
            fixture.schema,
            fixture.case,
            emptyList(),
            fixture.explainNode,
            fixture.explainCoord,
        )
        fixture.verify(explained)
        assertNotNull(explained.explainTrace)
        fixture.layouts.forEach { (_, layout) ->
            val paper = Render.paper(result, layout)
            assertTrue(fixture.verify(paper) > 0)
            ExcelExport.workbook(result, layout).use { workbook ->
                assertTrue(fixture.verify(workbook, Render.completePaper(result, layout)) > 0)
            }
        }
    }

    @Test
    fun `editing one seed recomputes its forward series while unchanged sessions reuse all tasks`() {
        val fixture = PeriodFixture(3, 3)
        Mantra.openSession(fixture.schema, fixture.case).use { session ->
            fixture.verify(session.result)
            val changed = fixture.changedCase(10)
            fixture.verify(session.recalculate(changed), 10)
            assertTrue(session.lastRun.reusedTasks > 0)
            assertTrue(session.lastRun.evaluatedTasks > 0)
            assertTrue(!session.lastRun.fullRebuild)
            fixture.verify(session.recalculate(changed), 10)
            assertEquals(0, session.lastRun.evaluatedTasks)
            assertEquals(0, session.lastRun.formulaEvaluations)
        }
    }
}
