package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual locked-kernel outcomes compared with independently recalculated editable workbooks. */
class ExcelLetParityTest {
    private fun model(expression: String, extra: String = "") = Mantra.loadSchema(
        SourceText(
            "let.mantra",
            """
            (schema test/let-parity
              (input divisor :decimal {:default 2})
              $extra
              (section main "Main" (line observed "Observed" $expression)))
            """.trimIndent(),
        ),
        SourceResolver { _, _ -> null },
    )

    private fun zero() = Mantra.loadCase(SourceText("zero.mantra", "(case zero (inputs {:divisor 0}))"))

    private fun cell(export: ExcelWorkbook, id: String): XSSFCell {
        val address = CellReference(assertNotNull(export.address(id)))
        return export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt())
    }

    private fun noFormulaErrors(export: ExcelWorkbook) {
        for (sheet in export.workbook) {
            for (row in sheet) {
                for (value in row) {
                    assertFalse(
                        value.cellType == CellType.FORMULA && value.cachedFormulaResultType == CellType.ERROR,
                        "${sheet.sheetName}!${value.address}",
                    )
                }
            }
        }
    }

    @Test
    fun `unused eager bindings preserve real divide error and workbook recovery`() {
        val expressions = listOf(
            "(let [unused (/ 1 divisor)] 5)",
            "(let [unused [(/ 1 divisor)]] 5)",
            "(let [unused {:amount (/ 1 divisor)}] 5)",
            "(let [unused (let [hidden (/ 1 divisor)] (fn [] 7))] 5)",
            "(let [unused (map (fn [entry] entry) [(/ 1 divisor)])] 5)",
            "(fin/npv 0 (let [unused (/ 1 divisor)] [2 3]) 2)",
        )
        expressions.forEach { expression ->
            val schema = model(expression)
            val result = Mantra.calculateForAudit(schema)
            assertTrue(result.succeeded, result.diagnostics.toString())
            assertEquals(0, BigDecimal.valueOf(5).compareTo(result.decimal("observed")))
            val failed = Mantra.calculateForAudit(schema, zero())
            assertFalse(failed.succeeded, expression)
            assertTrue(failed.diagnostics.any { it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO") })
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                cell(export, "divisor").setCellValue(0.0)
                val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
                val error = evaluator.evaluate(cell(export, "observed"))
                assertEquals(CellType.ERROR, error.cellType, expression)
                assertEquals(FormulaError.DIV0.code, error.errorValue, expression)
                cell(export, "divisor").setCellValue(2.0)
                evaluator.clearAllCachedResultValues()
                assertEquals(5.0, evaluator.evaluate(cell(export, "observed")).numberValue)
            }
        }
    }

    @Test
    fun `unused function and producer bodies remain unexecuted while constructors are eager`() {
        val expressions = listOf(
            "(let [unused (fn [] (/ 1 divisor))] 5)" to 5L,
            "(let [unused (map (fn [entry] (/ 1 divisor)) [1 2])] 5)" to 5L,
            "(sum [(map (fn [entry] (/ 1 divisor)) [1]) 3])" to 3L,
        )
        expressions.forEach { (expression, expected) ->
            val result = Mantra.calculateForAudit(model(expression), zero())
            assertTrue(result.succeeded, result.diagnostics.toString())
            assertEquals(Value.num(expected), result.value("observed"))
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
                assertEquals(expected.toDouble(), cell(export, "observed").numericCellValue)
                noFormulaErrors(export)
            }
        }
    }

    @Test
    fun `consuming a producer executes the mapped body and retains a used source construction error`() {
        val expressions = listOf(
            "(count (map (fn [entry] (/ 1 divisor)) [1 2]))" to 2L,
            "(nth (map (fn [entry] (/ 1 divisor)) [1 2]) 1)" to 0L,
        )
        expressions.forEach { (expression, count) ->
            val schema = model(expression)
            val result = Mantra.calculate(schema)
            assertTrue(result.succeeded, result.diagnostics.toString())
            val expected = if (count == 0L) 0.5 else count.toDouble()
            val failed = Mantra.calculate(schema, zero())
            assertFalse(failed.succeeded)
            assertTrue(failed.diagnostics.any { it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO") })
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertEquals(expected, cell(export, "observed").numericCellValue)
                cell(export, "divisor").setCellValue(0.0)
                val value = export.workbook.creationHelper.createFormulaEvaluator().evaluate(cell(export, "observed"))
                assertEquals(FormulaError.DIV0.code, value.errorValue)
            }
        }
    }

    @Test
    fun `convergence callback unused binding errors do not disappear behind rounded next`() {
        val expression = """
            (calc/converge (fn [^Decimal current]
              (let [unused (/ 1 divisor)] (decimal/round (+ 1000 (* 0.25 current)) 2))) 1000 30 0)
        """.trimIndent()
        val schema = model(expression)
        val result = Mantra.calculateForAudit(schema)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num("1333.33"), result.value("observed"))
        val failed = Mantra.calculateForAudit(schema, zero())
        assertFalse(failed.succeeded)
        assertTrue(failed.diagnostics.any { it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO") })
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            cell(export, "divisor").setCellValue(0.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            assertEquals(FormulaError.DIV0.code, evaluator.evaluate(cell(export, "observed")).errorValue)
            cell(export, "divisor").setCellValue(2.0)
            evaluator.clearAllCachedResultValues()
            evaluator.evaluateAll()
            assertEquals(1333.33, cell(export, "observed").numericCellValue)
            noFormulaErrors(export)
        }
    }

    @Test
    fun `outer lazy if keeps unused binding and every callback helper asleep until selected`() {
        val schema = model(
            """
            (if gate (calc/converge (fn [x] (let [unused (/ 1 divisor)] 7)) 0 3 0) 17)
            """.trimIndent(),
            "(input gate :boolean {:default false})",
        )
        val result = Mantra.calculateForAudit(schema, zero())
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(17), result.value("observed"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.evaluateAll()
            noFormulaErrors(export)
            cell(export, "gate").setCellValue(true)
            evaluator.clearAllCachedResultValues()
            assertEquals(FormulaError.DIV0.code, evaluator.evaluate(cell(export, "observed")).errorValue)
            cell(export, "divisor").setCellValue(2.0)
            evaluator.clearAllCachedResultValues()
            evaluator.evaluateAll()
            assertEquals(7.0, cell(export, "observed").numericCellValue)
            cell(export, "gate").setCellValue(false)
            cell(export, "divisor").setCellValue(0.0)
            evaluator.clearAllCachedResultValues()
            evaluator.evaluateAll()
            assertEquals(17.0, cell(export, "observed").numericCellValue)
            noFormulaErrors(export)
        }
    }

    @Test
    fun `stopping suppresses later eager binding helpers without making the first binding lazy`() {
        val schema =
            model(
                """
            (calc/converge (fn [^Decimal x]
              (let [unused (if (= x 1) 0
                (get (alloc/capped (/ 1 x) {:A 1 :B 1} {:A 2} 2) :A))] 0)) 1 3 1)
                """.trimIndent(),
            )
        val result = Mantra.calculateForAudit(schema)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(0), result.value("observed"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(0.0, cell(export, "observed").numericCellValue)
            noFormulaErrors(export)
        }
    }

    @Test
    fun `eager callback creation error precedes an invalid live iteration limit`() {
        val schema =
            model(
                """
            (calc/converge (let [unused (/ 1 divisor)] (fn [x] 7)) 0 limit 0)
                """.trimIndent(),
                "(input limit :decimal {:default 2})",
            )
        val result = Mantra.calculateForAudit(schema)
        assertTrue(result.succeeded, result.diagnostics.toString())
        val invalid = Mantra.loadCase(
            SourceText(
                "invalid.mantra",
                "(case invalid (inputs {:divisor 0 :limit 1.5}))",
            ),
        )
        val failed = Mantra.calculateForAudit(schema, invalid)
        assertFalse(failed.succeeded)
        assertTrue(failed.diagnostics.any { it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO") })
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            cell(export, "divisor").setCellValue(0.0)
            cell(export, "limit").setCellValue(1.5)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            assertEquals(FormulaError.DIV0.code, evaluator.evaluate(cell(export, "observed")).errorValue)
            cell(export, "divisor").setCellValue(2.0)
            cell(export, "limit").setCellValue(2.0)
            evaluator.clearAllCachedResultValues()
            evaluator.evaluateAll()
            assertEquals(7.0, cell(export, "observed").numericCellValue)
            noFormulaErrors(export)
        }
    }
}
