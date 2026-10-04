package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LargeSumExportTest {
    @Test
    fun `thousand table rows and repeated references retain formulas that recalculate`() {
        val repeatedRows = List(256) { "(nth facts 0)" }.joinToString(" ")
        val schema = schema(
            """
            (schema test/large-sum {}
              (input facts :table {:columns {:amount :decimal}})
              (section totals "Totals"
                (line table-total "Table total" (sum (map (fn [row] row.amount) facts)) {:op :info})
                (line duplicate-total "Duplicate total"
                  (sum (map (fn [row] row.amount) [$repeatedRows])) {:op :info})))
            """.trimIndent(),
        )
        val rows = (1..1000).joinToString(" ") { "{:amount $it}" }
        val result = Mantra.calculate(
            schema,
            Mantra.loadCase(SourceText("large-case.mantra", "(case test (inputs {:facts [$rows]}))")),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(500500.0, result.decimal("table-total").toDouble())
        assertEquals(256.0, result.decimal("duplicate-total").toDouble())
        listOf(true, false).forEach { useNames ->
            ExcelExport.workbook(result, Presets.DE_STAFFEL_4, ExcelOptions(useNames = useNames)).use { export ->
                assertEquals(emptyList(), export.report.fallbacks)
                assertEquals(emptyList(), export.report.evaluationErrors)
                val total = cell(export, requireNotNull(export.address("table-total")))
                val duplicate = cell(export, requireNotNull(export.address("duplicate-total")))
                assertEquals(CellType.FORMULA, total.cellType)
                assertEquals(CellType.FORMULA, duplicate.cellType)
                export.workbook.forEach { sheet ->
                    sheet.forEach { row ->
                        row.filter { it.cellType == CellType.FORMULA }.forEach {
                            Ex.validateFormula(it.cellFormula)
                        }
                    }
                }
                val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
                assertEquals(500500.0, evaluator.evaluate(total).numberValue)
                assertEquals(256.0, evaluator.evaluate(duplicate).numberValue)
                cell(export, requireNotNull(export.tableAddress("facts", 0, "amount"))).setCellValue(11.0)
                evaluator.clearAllCachedResultValues()
                assertEquals(500510.0, evaluator.evaluate(total).numberValue)
                assertEquals(2816.0, evaluator.evaluate(duplicate).numberValue)
            }
        }
    }

    @Test
    fun `sparse and reversed sums preserve duplicates gaps and first error order`() {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sparse")
            val values = (0 until 300).map { index ->
                sheet.createRow(index * 2).createCell(0).setCellValue((index + 1).toDouble())
                sheet.createRow(index * 2 + 1).createCell(0).setCellValue(9999.0)
                Ex.atom("'Sparse'!\$A\$${index * 2 + 1}")
            }
            val repeated = Ex.fn("SUM", values + listOf(values.first(), values.first()))
            Ex.validateFormula(repeated.text)
            assertTrue(!repeated.text.contains(":"), repeated.text)
            val total = sheet.getRow(0).createCell(1)
            total.cellFormula = repeated.text
            val reverse = sheet.getRow(1).createCell(1)
            reverse.cellFormula = Ex.fn("SUM", values.reversed()).text
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            assertEquals(45152.0, evaluator.evaluate(total).numberValue)
            assertEquals(45150.0, evaluator.evaluate(reverse).numberValue)

            sheet.getRow(0).getCell(0).setCellErrorValue(FormulaError.DIV0.code)
            sheet.getRow(2).getCell(0).setCellErrorValue(FormulaError.VALUE.code)
            evaluator.clearAllCachedResultValues()
            assertEquals(FormulaError.DIV0.code, evaluator.evaluate(total).errorValue)
            assertEquals(FormulaError.VALUE.code, evaluator.evaluate(reverse).errorValue)
        }
        // Smaller existing exports retain their original formula text.
        val adjacent = listOf(Ex.atom("'Sparse'!\$A\$1"), Ex.atom("'Sparse'!\$A\$2"))
        assertEquals("SUM('Sparse'!\$A\$1,'Sparse'!\$A\$2)", Ex.fn("SUM", adjacent).text)
    }

    @Test
    fun `formula limits count functions but ignore grouped expressions and escaped quotes`() {
        Ex.validateFormula("0".padEnd(8192, ' '))
        assertTrue(
            assertFailsWith<Untranslatable> {
                Ex.validateFormula("0".padEnd(8193, ' '))
            }.reason.contains("8192"),
        )
        Ex.validateFormula(nested("ABS", 64, "0"))
        assertTrue(assertFailsWith<Untranslatable> { Ex.validateFormula(nested("ABS", 65, "0")) }.reason.contains("64"))
        Ex.validateFormula("(".repeat(100) + "ABS(0)" + ")".repeat(100))
        Ex.validateFormula(nested("ABS", 64, "\"SUM( \"\"escaped\"\" )\""))
        Ex.validateFormula(nested("ABS", 64, "'S''UM(foo)'!\$A\$1"))
    }

    @Test
    fun `oversized node formulas report fallback with the actual calculated value`() {
        val longText = "x".repeat(9000)
        val result = Mantra.calculate(
            schema(
                """
                (schema test/node-limits {}
                  (section results "Results"
                    (line long-text "Long text" (str "$longText") {:type :text :op :info})
                    (line deep "Deep" ${nested("abs", 65, "-7", " ")} {:op :info})))
                """.trimIndent(),
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        ExcelExport.workbook(result, Presets.DE_STAFFEL_4).use { export ->
            assertEquals(setOf("long-text", "deep"), export.report.fallbacks.map { it.nodeId }.toSet())
            assertTrue(export.report.fallbacks.first { it.nodeId == "long-text" }.reason.contains("8192"))
            assertTrue(export.report.fallbacks.first { it.nodeId == "deep" }.reason.contains("64"))
            assertEquals(longText, cell(export, requireNotNull(export.address("long-text"))).stringCellValue)
            assertEquals(7.0, cell(export, requireNotNull(export.address("deep"))).numericCellValue)
            assertEquals(emptyList(), export.report.evaluationErrors)
        }
    }

    @Test
    fun `oversized section guards reject export instead of changing active node results`() {
        val expansion = nested("double-value", 13, "basis", " ")
        val result = Mantra.calculate(
            schema(
                """
                (schema test/guard-limits {}
                  (input basis :decimal {:default 1})
                  (defn double-value [^Decimal x] (+ x x))
                  (section guarded "Guarded" {:when (> $expansion 0)}
                    (line answer "Answer" 7)))
                """.trimIndent(),
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(7.0, result.decimal("answer").toDouble())
        val error = assertFailsWith<ExcelExportLimitException> {
            ExcelExport.workbook(result, Presets.DE_STAFFEL_4)
        }
        assertTrue(error.message.orEmpty().contains("8192"), error.message)
        assertTrue(error.message.orEmpty().contains("guarded"), error.message)
    }

    @Test
    fun `oversized dimensioned choice options reject export without borrowing another node value`() {
        val expansion = nested("double-value", 13, "basis", " ")
        val result = Mantra.calculate(
            schema(
                """
                (schema test/option-limits {}
                  (input basis :decimal {:default 1})
                  (dimension unit {:members [:a :b]})
                  (defn double-value [^Decimal x] (+ x x))
                  (section per "Per unit" {:per unit}
                    (choice winner "Winner" {:rule :max}
                      (option :expanded "Expanded" $expansion)
                      (option :small "Small" 7))))
                """.trimIndent(),
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(8192.0, result.decimal("winner", "a").toDouble())
        val error = assertFailsWith<ExcelExportLimitException> {
            ExcelExport.workbook(result, Presets.DE_STAFFEL_4)
        }
        assertTrue(error.message.orEmpty().contains("8192"), error.message)
        assertTrue(error.message.orEmpty().contains("winner"), error.message)
    }

    private fun schema(text: String) = Mantra.loadSchema(
        SourceText("large-sum.mantra", text),
        SourceResolver { _, _ -> null },
    )

    private fun cell(export: ExcelWorkbook, address: String): Cell {
        val reference = CellReference(address)
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    private fun nested(function: String, depth: Int, body: String, separator: String = ""): String =
        if (separator.isEmpty()) {
            "$function(".repeat(depth) + body + ")".repeat(depth)
        } else {
            "($function$separator".repeat(depth) + body + ")".repeat(depth)
        }
}
