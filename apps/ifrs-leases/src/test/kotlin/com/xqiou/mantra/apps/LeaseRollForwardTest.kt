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

class LeaseRollForwardTest {
    private val dir = Path.of("apps/ifrs-leases")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private fun calculate(case: String = "demo") = Mantra.calculateForAudit(
        schema,
        Mantra.loadCase(dir.resolve("case-$case.mantra")),
    )

    @Test
    fun `discounted unpaid payments carry through interest and supplied payments`() {
        val result = calculate()
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertTrue(result.validationPassed)
        assertEquals(0, BigDecimal("27232.48").compareTo(result.decimal("initial-liability", "Office")))
        assertEquals(0, BigDecimal("18594.10").compareTo(result.decimal("liability-closing", "Office", "P1")))
        assertEquals(
            result.value("liability-closing", "Office", "P1"),
            result.value("liability-opening", "Office", "P2"),
        )
        assertEquals(0, BigDecimal("9077.50").compareTo(result.decimal("rou-depreciation", "Office", "P3")))
        assertEquals(
            0,
            BigDecimal("45232.48").compareTo((result.view.reduce("liability-opening").value as Value.Num).value),
        )
        assertEquals(0, (result.view.reduce("liability-closing").value as Value.Num).value.signum())
        assertEquals(0, BigDecimal("2767.52").compareTo((result.view.reduce("interest").value as Value.Num).value))
        assertEquals(0, BigDecimal("48000").compareTo((result.view.reduce("payment").value as Value.Num).value))
        assertEquals(0, BigDecimal("1361.62").compareTo(result.decimal("interest", "Office", "P1")))
        assertEquals(0, result.decimal("interest", "Storage", "P1").signum())
    }

    @Test
    fun `commencement cash belongs to right-of-use cost and a one-year term retains later zero coordinates`() {
        val prepaid = calculate("commencement-payment")
        assertTrue(prepaid.succeeded)
        assertEquals(0, BigDecimal("18594.10").compareTo(prepaid.decimal("initial-liability", "Office")))
        assertEquals(0, BigDecimal("28594.10").compareTo(prepaid.decimal("initial-rou-cost", "Office")))
        assertEquals(0, BigDecimal("9531.36").compareTo(prepaid.decimal("rou-depreciation", "Office", "P3")))
        val single = calculate("single-period")
        assertTrue(single.validationPassed)
        assertEquals(0, BigDecimal("8000").compareTo(single.decimal("initial-liability", "Dock")))
        assertEquals(0, BigDecimal("400").compareTo(single.decimal("interest", "Dock", "P1")))
        for (period in listOf("P2", "P3")) {
            assertEquals(0, single.decimal("liability-opening", "Dock", period).signum())
            assertEquals(0, single.decimal("rou-depreciation", "Dock", period).signum())
        }
    }

    @Test
    fun `business scope and reported-balance failures preserve the supplied payment and signed interest`() {
        val failed = calculate("unreconciled")
        assertTrue(failed.succeeded)
        assertFalse(failed.validationPassed)
        assertEquals(0, BigDecimal("-0.02").compareTo(failed.decimal("liability-reconciliation", "Office", "P2")))
        val payment = calculate("negative-payment")
        assertTrue(payment.succeeded)
        assertFalse(payment.validationPassed)
        assertEquals(0, BigDecimal("-1000").compareTo(payment.decimal("payment", "Office", "P2")))
        assertEquals(0, BigDecimal("0.01").compareTo(payment.decimal("liability-closing", "Office", "P3")))
        assertEquals("payment-nonnegative", payment.diagnostics.single().nodeId)
        val negativeRate = calculate("negative-rate-outside-demo")
        assertTrue(negativeRate.succeeded)
        assertTrue(negativeRate.decimal("interest", "Office", "P1").signum() < 0)
        assertEquals("lease-rate-supported", negativeRate.diagnostics.single().nodeId)
    }

    @Test
    fun `editing an annual payment recalculates measurement and later liability formulas in Excel`() {
        ExcelExport.workbook(calculate(), Render.loadLayout(dir.resolve("layout.mantra"))).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            assertEquals(emptyList(), export.report.evaluationErrors)
            val input = CellReference(checkNotNull(export.tableAddress("lease-payments", 0, "payment")))
            export.workbook.getSheet(input.sheetName).getRow(input.row).getCell(input.col.toInt()).setCellValue(11000.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.clearAllCachedResultValues()
            val address = CellReference(checkNotNull(export.address("liability-opening", listOf("Office", "P2"))))
            val value = evaluator.evaluate(
                export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt()),
            )
            assertEquals(CellType.NUMERIC, value.cellType)
            // The edited first payment also changes commencement PV; its incremental debt is repaid in P1.
            assertEquals(18594.10, value.numberValue, 1e-9)
        }
    }
}
