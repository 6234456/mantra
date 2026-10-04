package com.xqiou.mantra.excel

import com.xqiou.mantra.core.view.Coord
import com.xqiou.normein.dsl.form.DslFormReadResult
import com.xqiou.normein.dsl.form.DslFormReader
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ExcelDatesTest {
    private fun translator() = FormulaTranslator(
        object : ExcelResolver {
            override fun reference(nodeId: String, contextDims: List<String>, contextCoord: Coord): X? = when (nodeId) {
                "raw" -> Ex.atom("Dates!A1", XKind.TEXT)
                "current" -> Ex.atom("Dates!A2", XKind.DATE)
                else -> null
            }
            override fun record(dim: String, key: String, field: String): X? = null
            override fun isNode(nodeId: String) = nodeId in setOf("raw", "current")
            override fun isDimension(name: String) = false
        },
        emptyList(),
    )

    @Test
    fun `editable date parsing matches strict ISO and supplied SMART patterns`() {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Dates")
            sheet.createRow(0).createCell(0).setCellValue("30.02.2026")
            sheet.createRow(1).createCell(0).setCellValue(LocalDate.of(2021, 1, 1).atStartOfDay())
            val translator = translator()
            fun formula(source: String, row: Int) = sheet.createRow(row).createCell(0).also {
                val form = (DslFormReader().readDocument(source) as DslFormReadResult.Success).document.root
                val expression = translator.scalar(form, FormulaTranslator.Ctx(emptyList(), emptyList()))
                Ex.validateFormula(expression.text)
                it.cellFormula = expression.text
            }
            val supplied = formula("(date/parse raw \"dd.MM.uuuu\")", 3)
            val strict = formula("(date/parse raw)", 4)
            val typed = formula("(date? (date/parse raw))", 5)
            val missing = formula("(nil? (date/parse raw))", 6)
            val present = formula("(some? current)", 7)
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            assertEquals(excelDate(LocalDate.of(2026, 2, 28)).text.toDouble(), evaluator.evaluate(supplied).numberValue)
            assertEquals("", evaluator.evaluate(strict).stringValue)
            assertEquals(false, evaluator.evaluate(typed).booleanValue)
            assertEquals(true, evaluator.evaluate(missing).booleanValue)
            assertEquals(true, evaluator.evaluate(present).booleanValue)
            sheet.getRow(0).getCell(0).setCellValue(" 2026-02-28 ")
            evaluator.clearAllCachedResultValues()
            assertEquals(excelDate(LocalDate.of(2026, 2, 28)).text.toDouble(), evaluator.evaluate(strict).numberValue)
            assertEquals(true, evaluator.evaluate(typed).booleanValue)
            assertEquals(false, evaluator.evaluate(missing).booleanValue)
            sheet.getRow(0).getCell(0).setCellValue("2026-02-30")
            evaluator.clearAllCachedResultValues()
            assertEquals(CellType.STRING, evaluator.evaluate(strict).cellType)
            assertEquals("", evaluator.evaluate(strict).stringValue)
            assertEquals(false, evaluator.evaluate(typed).booleanValue)
            assertEquals(true, evaluator.evaluate(missing).booleanValue)
            sheet.getRow(1).getCell(0).setBlank()
            evaluator.clearAllCachedResultValues()
            assertEquals(false, evaluator.evaluate(present).booleanValue)
        }
    }

    @Test
    fun `ISO weeks and calendar boundary shifts recompute without locale week functions`() {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Dates")
            sheet.createRow(0).createCell(0).setCellValue("")
            val input = sheet.createRow(1).createCell(0).also {
                it.setCellValue(LocalDate.of(2021, 1, 1).atStartOfDay())
            }
            val translator = translator()
            fun formula(source: String, row: Int) = sheet.createRow(row).createCell(0).also {
                val form = (DslFormReader().readDocument(source) as DslFormReadResult.Success).document.root
                it.cellFormula = translator.scalar(form, FormulaTranslator.Ctx(emptyList(), emptyList())).text
            }
            val week = formula("(date/iso-week current)", 3)
            val year = formula("(date/iso-week-year current)", 4)
            val shifted = formula("(date/plus-years current 1)", 5)
            val quarter = formula("(date/end-of-quarter current)", 6)
            val reversed = formula("(date/months-between (date/plus-months current 1) current)", 7)
            val fractional = formula("(date/plus-months current 1.5)", 8)
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            assertEquals(53.0, evaluator.evaluate(week).numberValue)
            assertEquals(2020.0, evaluator.evaluate(year).numberValue)
            input.setCellValue(LocalDate.of(2021, 1, 4).atStartOfDay())
            evaluator.clearAllCachedResultValues()
            assertEquals(1.0, evaluator.evaluate(week).numberValue)
            assertEquals(2021.0, evaluator.evaluate(year).numberValue)
            input.setCellValue(LocalDate.of(2024, 2, 29).atStartOfDay())
            evaluator.clearAllCachedResultValues()
            assertEquals(excelDate(LocalDate.of(2025, 2, 28)).text.toDouble(), evaluator.evaluate(shifted).numberValue)
            assertEquals(excelDate(LocalDate.of(2024, 3, 31)).text.toDouble(), evaluator.evaluate(quarter).numberValue)
            assertEquals(-1.0, evaluator.evaluate(reversed).numberValue)
            assertEquals("", evaluator.evaluate(fractional).stringValue)
        }
    }
}
