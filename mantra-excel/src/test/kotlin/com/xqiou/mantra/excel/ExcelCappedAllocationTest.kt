package com.xqiou.mantra.excel

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExcelCappedAllocationTest {
    /** POI evaluates the scratch formulas independently; no Mantra engine values enter this test. */
    private fun check(
        amount: Double,
        weights: List<Double>,
        caps: List<Double?>,
        scale: Int,
        expected: List<Double>,
        editAmount: Pair<Double, List<Double>>? = null,
    ) {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Allocation")
            sheet.createRow(0).createCell(0).setCellValue(amount)
            val keys = weights.indices.map { "K$it" }
            weights.forEachIndexed { index, weight -> sheet.createRow(index + 1).createCell(0).setCellValue(weight) }
            val expressions = weights.indices.map { Ex.atom("Allocation!A${it + 2}") }
            var nextRow = weights.size + 1
            val scratch = mutableListOf<String>()
            val result = cappedAllocation(
                Ex.atom("Allocation!A1"),
                X.MapX(keys, expressions),
                X.MapX(
                    keys.filterIndexed { index, _ ->
                        caps[index] != null
                    },
                    caps.filterNotNull().map { Ex.num(it.toBigDecimal()) },
                ),
                Ex.num(scale.toLong()),
            ) { formula ->
                Ex.validateFormula(formula.text)
                scratch += formula.text
                val row = sheet.createRow(nextRow++)
                row.createCell(0).cellFormula = formula.text
                Ex.atom("Allocation!A${row.rowNum + 1}", formula.kind)
            }
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            fun values(): List<Double> = result.values.map { expression ->
                val formula = expression as X.Scalar
                val row = formula.text.substringAfterLast('A').toInt() - 1
                val value = evaluator.evaluate(sheet.getRow(row).getCell(0))
                assertEquals(CellType.NUMERIC, value.cellType)
                value.numberValue
            }
            expected.zip(values()).forEach { (left, right) -> assertEquals(left, right, 1e-9) }
            assertTrue(scratch.all { it.length < 8192 })
            editAmount?.let { (edited, newExpected) ->
                sheet.getRow(0).getCell(0).setCellValue(edited)
                evaluator.clearAllCachedResultValues()
                newExpected.zip(values()).forEach { (left, right) -> assertEquals(left, right, 1e-9) }
            }
        }
    }

    @Test
    fun `a saturated asset redistributes its excess and whole-unit ties retain member order`() {
        check(60.0, listOf(120.0, 180.0, 60.0), listOf(10.0, 50.0, 18.0), 2, listOf(10.0, 37.5, 12.5))
        check(60.0, listOf(120.0, 180.0, 60.0), listOf(10.0, 50.0, 18.0), 0, listOf(10.0, 38.0, 12.0))
    }

    @Test
    fun `capacity shortfall remains unallocated and editing the amount recomputes the allocation`() {
        check(
            60.0,
            listOf(120.0, 180.0, 60.0),
            listOf(5.0, 5.0, 5.0),
            2,
            listOf(5.0, 5.0, 5.0),
            6.0 to listOf(2.0, 3.0, 1.0),
        )
    }

    @Test
    fun `zero or negative weights and caps are excluded while a missing cap is unlimited`() {
        check(12.0, listOf(0.0, -2.0, 1.0, 1.0), listOf(5.0, 5.0, -1.0, null), 2, listOf(0.0, 0.0, 0.0, 12.0))
        check(0.0, listOf(1.0, 1.0), listOf(5.0, 5.0), 2, listOf(0.0, 0.0))
        check(-5.0, listOf(1.0, 1.0), listOf(5.0, 5.0), 2, listOf(0.0, 0.0))
    }

    @Test
    fun `equal capacity thresholds and repeated saturation preserve exact footing`() {
        check(10.0, listOf(1.0, 2.0, 7.0), listOf(1.0, 2.0, 7.0), 2, listOf(1.0, 2.0, 7.0))
        check(20.0, listOf(8.0, 4.0, 1.0), listOf(2.0, 3.0, 100.0), 2, listOf(2.0, 3.0, 15.0))
    }
}
