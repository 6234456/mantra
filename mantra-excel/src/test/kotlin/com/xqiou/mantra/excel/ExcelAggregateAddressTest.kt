package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Visible weighted totals must remain addressable even when no running total consumes the line. */
class ExcelAggregateAddressTest {
    private fun calculate(facts: String) = Mantra.calculateForAudit(
        Mantra.loadSchema(
            SourceText(
                "weighted.mantra",
                """
                (schema test/weighted {:mainline [detail]}
                  (dimension member {:members [:A :B]})
                  (input charge :decimal {:per member})
                  (input units :decimal {:per member})
                  (section detail "Weighted rates" {:per member :display :schedule}
                    (line rate "Informational rate"
                      (if (zero? units) nil (decimal/divide charge units 8))
                      {:op :info :aggregate {:ratio [charge units] :round [8 :half-up]}})))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        ),
        Mantra.loadCase(SourceText("facts.mantra", "(case c (inputs $facts))")),
    )

    private fun cell(export: ExcelWorkbook, address: String): XSSFCell {
        val reference = CellReference(address)
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    private fun recalculate(export: ExcelWorkbook) {
        export.workbook.creationHelper.createFormulaEvaluator().apply {
            clearAllCachedResultValues()
            evaluateAll()
        }
    }

    @Test
    fun `the public aggregate address identifies the visible weighted total of an info line`() {
        val result = calculate("{:charge {:A 20 :B 90} :units {:A 100 :B 300}}")
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(0, BigDecimal("0.275").compareTo(assertNotNull(result.node("rate").crossTotal())))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            assertEquals(emptyList(), export.report.evaluationErrors)
            val address = assertNotNull(export.address("aggregate.rate"))
            assertNull(export.address("aggregate.rate", listOf("A")))
            assertNotEquals(export.address("rate", listOf("A")), address)
            assertNotEquals(export.address("rate", listOf("B")), address)
            val aggregate = cell(export, address)
            assertEquals(CellType.FORMULA, aggregate.cellType)
            assertEquals(CellType.NUMERIC, aggregate.cachedFormulaResultType)
            assertEquals(0.275, aggregate.numericCellValue, 1e-12)
            assertEquals(0.2, cell(export, assertNotNull(export.address("rate", listOf("A")))).numericCellValue, 1e-12)
            assertEquals(0.3, cell(export, assertNotNull(export.address("rate", listOf("B")))).numericCellValue, 1e-12)
            // The visible total is neither the member sum (0.5) nor their arithmetic mean (0.25).
            assertNotEquals(0.5, aggregate.numericCellValue)
            assertNotEquals(0.25, aggregate.numericCellValue)
        }
    }

    @Test
    fun `an initially undefined info aggregate keeps its address and recovers after source edits`() {
        val result = calculate("{:charge {:A 0 :B 0} :units {:A 0 :B 0}}")
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertNull(result.node("rate").crossTotal())
        assertEquals(Value.Nil, result.value("rate", "A"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            assertEquals(emptyList(), export.report.evaluationErrors)
            val address = assertNotNull(export.address("aggregate.rate"))
            val aggregate = cell(export, address)
            assertEquals(CellType.FORMULA, aggregate.cellType)
            assertEquals(CellType.STRING, aggregate.cachedFormulaResultType)
            assertEquals("", aggregate.stringCellValue)
            for ((key, charge, units) in listOf(Triple("A", 40.0, 200.0), Triple("B", 90.0, 300.0))) {
                cell(export, assertNotNull(export.address("charge", listOf(key)))).setCellValue(charge)
                cell(export, assertNotNull(export.address("units", listOf(key)))).setCellValue(units)
            }
            recalculate(export)
            assertEquals(address, export.address("aggregate.rate"))
            assertEquals(CellType.NUMERIC, aggregate.cachedFormulaResultType)
            assertEquals(0.26, aggregate.numericCellValue, 1e-12)
            for (key in listOf("A", "B")) {
                cell(export, assertNotNull(export.address("units", listOf(key)))).setCellValue(0.0)
            }
            recalculate(export)
            assertEquals(CellType.STRING, aggregate.cachedFormulaResultType)
            assertEquals("", aggregate.stringCellValue)
        }
    }
}
