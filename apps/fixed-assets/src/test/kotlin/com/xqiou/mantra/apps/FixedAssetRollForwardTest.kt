package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedAssetRollForwardTest {
    private val dir = Path.of("apps/fixed-assets")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private fun calculate(case: String = "demo") = Mantra.calculateForAudit(
        schema,
        Mantra.loadCase(dir.resolve("case-$case.mantra")),
    )

    @Test
    fun `opening stocks carry from the prior close and disposal removes both gross and accumulated cost`() {
        val result = calculate()
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertTrue(result.validationPassed)
        assertEquals(0, BigDecimal("144000").compareTo(result.decimal("period-carrying-closing", "P2")))
        assertEquals(0, BigDecimal("20000").compareTo(result.decimal("carrying-disposals", "Equipment", "P3")))
        assertEquals(0, BigDecimal("60000").compareTo(result.decimal("gross-disposals", "Equipment", "P3")))
        assertEquals(0, BigDecimal("40000").compareTo(result.decimal("accumulated-disposals", "Equipment", "P3")))
        for (period in listOf("P4", "P5")) {
            assertEquals(0, result.decimal("carrying-closing", "Equipment", period).signum())
            assertEquals(0, result.decimal("depreciation", "Equipment", period).signum())
        }
        val opening = result.value("gross-opening", "Machine", "P2")
        assertEquals(result.value("gross-closing", "Machine", "P1"), opening)
        assertEquals(
            0,
            BigDecimal("60000").compareTo((result.view.reduce("carrying-closing").value as Value.Num).value),
        )
        assertEquals(
            0,
            BigDecimal("148000").compareTo((result.view.reduce("carrying-opening").value as Value.Num).value),
        )
        assertEquals(0, BigDecimal("128000").compareTo((result.view.reduce("depreciation").value as Value.Num).value))
    }

    @Test
    fun `monthly overlap and terminal currency residue preserve the residual floor`() {
        val partial = calculate("partial-year")
        assertTrue(partial.succeeded)
        assertEquals(
            listOf("10000", "20000", "20000", "10000", "0"),
            (1..5).map {
                partial.decimal("depreciation", "Equipment", "P$it").stripTrailingZeros().toPlainString()
            },
        )
        val rounded = calculate("rounding-residue")
        assertEquals(0, BigDecimal("33.34").compareTo(rounded.decimal("depreciation", "Tool", "P3")))
        val precise = Mantra.calculate(
            schema,
            Mantra.loadCase(dir.resolve("case-rounding-residue.mantra")),
            listOf(Mantra.loadParameters(dir.resolve("params-precision.mantra"))),
        )
        assertEquals(0, BigDecimal("33.3334").compareTo(precise.decimal("depreciation", "Tool", "P3")))
        val floor = calculate("residual-floor")
        assertEquals(0, BigDecimal("20").compareTo(floor.decimal("carrying-closing", "Tool", "P5")))
        assertEquals(0, (floor.view.reduce("depreciation").value as Value.Num).value.signum())
    }

    @Test
    fun `an independently reported mismatch does not change the computed asset balances`() {
        val failed = calculate("unreconciled")
        assertTrue(failed.succeeded)
        assertFalse(failed.validationPassed)
        assertEquals(0, BigDecimal("144000").compareTo(failed.decimal("period-carrying-closing", "P2")))
        assertEquals(0, BigDecimal("-0.02").compareTo(failed.decimal("period-carrying-reconciliation", "P2")))
        assertEquals("MANTRA-RECONCILE-FAILED", failed.diagnostics.single().code)
    }

    @Test
    fun `changing an opening cost in Excel updates later periods through real formulas`() {
        val result = calculate("rounding-residue")
        ExcelExport.workbook(result, Render.loadLayout(dir.resolve("layout.mantra"))).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            assertEquals(emptyList(), export.report.evaluationErrors)
            val input = CellReference(checkNotNull(export.tableAddress("assets", 0, "opening-cost")))
            export.workbook.getSheet(input.sheetName).getRow(input.row).getCell(input.col.toInt()).setCellValue(120.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.clearAllCachedResultValues()
            val address = CellReference(checkNotNull(export.address("depreciation", listOf("Tool", "P3"))))
            val value = evaluator.evaluate(
                export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt()),
            )
            assertEquals(CellType.NUMERIC, value.cellType)
            assertEquals(40.0, value.numberValue, 1e-9)
        }
    }

    @Test
    fun `visible full-portfolio closing crossfoot selects the last year within every asset`() {
        ExcelExport.workbook(calculate(), Render.loadLayout(dir.resolve("layout.mantra"))).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            val address = CellReference(checkNotNull(export.address("aggregate.carrying-closing")))
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            val value = evaluator.evaluate(
                export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt()),
            )
            assertEquals(CellType.NUMERIC, value.cellType)
            assertEquals(60000.0, value.numberValue, 1e-9)
            // Summing five annual closing stocks would incorrectly produce 472000.
        }
    }
}
