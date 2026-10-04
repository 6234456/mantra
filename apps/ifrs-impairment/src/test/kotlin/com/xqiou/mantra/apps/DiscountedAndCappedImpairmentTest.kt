package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
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

class DiscountedAndCappedImpairmentTest {
    private val dir = Path.of("apps/ifrs-impairment")
    private val schema = Mantra.loadSchema(dir.resolve("schema.mantra"))
    private fun calculate(case: String, cents: Boolean = false) = Mantra.calculateForAudit(
        schema,
        Mantra.loadCase(dir.resolve("case-$case.mantra")),
        if (cents) listOf(Mantra.loadParameters(dir.resolve("params-cent-precision.mantra"))) else emptyList(),
    )

    @Test
    fun `three end-of-year cash flows independently produce the previously supplied value in use`() {
        val forecast = calculate("discounted-viu")
        val legacy = calculate("demo")
        assertTrue(forecast.succeeded, forecast.diagnostics.toString())
        assertTrue(forecast.validationPassed)
        for ((member, term, viu) in listOf(
            Triple("A", "60", "180"),
            Triple("B", "70", "210"),
            Triple("C", "100", "300"),
        )) {
            for (period in listOf("P1", "P2", "P3")) {
                assertEquals(0, BigDecimal(term).compareTo(forecast.decimal("present-value", member, period)))
            }
            assertEquals(0, BigDecimal(viu).compareTo(forecast.decimal("measured-value-in-use", member)))
            assertEquals(legacy.value("impairment-loss", member), forecast.value("impairment-loss", member))
        }
        assertEquals(legacy.value("total-impairment"), forecast.value("total-impairment"))
    }

    @Test
    fun `goodwill absorbs loss first and excess from a capped machine redistributes to the other assets`() {
        val result = calculate("capped", cents = true)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertTrue(result.validationPassed)
        assertEquals(0, BigDecimal("30").compareTo(result.decimal("loss-to-goodwill", "Workshop")))
        for ((asset, amount) in listOf("Machine" to "10", "Building" to "37.50", "Equipment" to "12.50")) {
            assertEquals(0, BigDecimal(amount).compareTo(result.decimal("asset-allocated-loss", "Workshop", asset)))
        }
        assertEquals(0, BigDecimal("300").compareTo(result.decimal("own-capped-closing", "Workshop")))
        assertEquals(0, result.decimal("own-unallocated-loss", "Workshop").signum())
        assertEquals(0, BigDecimal("90").compareTo(result.decimal("total-impairment")))
    }

    @Test
    fun `whole-unit capped allocation keeps exact footing with deterministic remainder order`() {
        val result = calculate("capped")
        assertTrue(result.validationPassed)
        assertEquals(0, BigDecimal("38").compareTo(result.decimal("asset-allocated-loss", "Workshop", "Building")))
        assertEquals(0, BigDecimal("12").compareTo(result.decimal("asset-allocated-loss", "Workshop", "Equipment")))
        assertEquals(0, BigDecimal("60").compareTo(result.decimal("own-capped-loss", "Workshop")))
    }

    @Test
    fun `insufficient asset capacities retain unallocated loss and fail both explicit controls`() {
        val result = calculate("insufficient-capacity")
        assertTrue(result.succeeded)
        assertFalse(result.validationPassed)
        assertEquals(0, BigDecimal("15").compareTo(result.decimal("own-capped-loss", "Workshop")))
        assertEquals(0, BigDecimal("45").compareTo(result.decimal("own-unallocated-loss", "Workshop")))
        assertEquals(0, BigDecimal("345").compareTo(result.decimal("own-capped-closing", "Workshop")))
        assertEquals(
            setOf("capped-capacity-sufficient", "capped-loss-crossfoot"),
            result.diagnostics.map {
                it.nodeId
            }.toSet(),
        )
    }

    @Test
    fun `editing an individual floor in Excel redistributes loss through live capped formulas`() {
        ExcelExport.workbook(
            calculate("capped", cents = true),
            Render.loadLayout(dir.resolve("layout.mantra")),
        ).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            assertEquals(emptyList(), export.report.evaluationErrors)
            val input = CellReference(checkNotNull(export.tableAddress("cgu-assets", 0, "asset-value-in-use")))
            export.workbook.getSheet(input.sheetName).getRow(input.row).getCell(input.col.toInt()).setCellValue(100.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.clearAllCachedResultValues()
            // FVLCD 105 now sets the machine's floor, releasing five units of loss capacity.
            for ((asset, expected) in listOf("Machine" to 15.0, "Building" to 33.75, "Equipment" to 11.25)) {
                val address =
                    CellReference(checkNotNull(export.address("asset-allocated-loss", listOf("Workshop", asset))))
                val value = evaluator.evaluate(
                    export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt()),
                )
                assertEquals(CellType.NUMERIC, value.cellType)
                assertEquals(expected, value.numberValue, 1e-9)
            }
        }
    }
}
