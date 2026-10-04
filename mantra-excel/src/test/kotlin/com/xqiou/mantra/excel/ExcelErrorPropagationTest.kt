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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelErrorPropagationTest {
    private data class Scenario(val expression: String, val expected: Value)

    private fun cell(export: ExcelWorkbook, id: String): XSSFCell {
        val address = CellReference(assertNotNull(export.address(id)))
        return export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt())
    }

    private fun schema(expression: String, boolean: Boolean = false) = Mantra.loadSchema(
        SourceText(
            "errors.mantra",
            """
            (schema test/errors
              (input divisor :decimal {:default 2})
              (input gate :boolean {:default false})
              (line observed "Observed" $expression ${if (boolean) "{:type :boolean :op :info}" else "{:op :info}"}))
            """.trimIndent(),
        ),
        SourceResolver { _, _ -> null },
    )

    @Test
    fun `selected errors in predicates and excluded sum items match locked kernel failures`() {
        val date = "(date/plus-days (date/parse \"2026-01-01\") (/ 2 divisor))"
        val scenarios = listOf(
            Scenario("(if (/ 2 divisor) 7 9)", Value.num(7)),
            Scenario("(nil? (/ 2 divisor))", Value.Bool(false)),
            Scenario("(some? (/ 2 divisor))", Value.Bool(true)),
            Scenario("(or (/ 2 divisor) 7)", Value.num(1)),
            Scenario("(and (/ 2 divisor) 7)", Value.num(7)),
            Scenario("(sum [(< (/ 2 divisor) 2) 3])", Value.num(3)),
            Scenario("(nil? (sum [(< (/ 2 divisor) 2)]))", Value.Bool(true)),
            Scenario("(sum [[$date] 3])", Value.num(3)),
            Scenario("(sum [$date 3])", Value.num(3)),
            Scenario("(sum [[(/ 2 divisor)] 3])", Value.num(3)),
            Scenario("(sum [{:amount (/ 2 divisor)} 3])", Value.num(3)),
            Scenario("(sum [[(str \"ignored\" (/ 2 divisor))] 3])", Value.num(3)),
            Scenario("(nil? (sum {:amount (/ 2 divisor)}))", Value.Bool(true)),
            Scenario("(nil? $date)", Value.Bool(false)),
            Scenario("(some? $date)", Value.Bool(true)),
            Scenario("(date? $date)", Value.Bool(true)),
            Scenario("(date? (/ 2 divisor))", Value.Bool(false)),
            Scenario("(nil? (date/parse (str (/ 2 divisor))))", Value.Bool(true)),
        )
        scenarios.forEach { scenario ->
            val model = schema(scenario.expression, scenario.expected is Value.Bool)
            val result = Mantra.calculate(model)
            assertTrue(result.succeeded, result.diagnostics.toString())
            assertEquals(scenario.expected, result.value("observed"), scenario.expression)
            val failed = Mantra.calculate(
                model,
                Mantra.loadCase(SourceText("zero.mantra", "(case zero (inputs {:divisor 0}))")),
            )
            assertFalse(failed.succeeded, scenario.expression)
            assertTrue(
                failed.diagnostics.any {
                    it.nodeId == "observed" && it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO")
                },
                failed.diagnostics.toString(),
            )
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                cell(export, "divisor").setCellValue(0.0)
                val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
                val value = evaluator.evaluate(cell(export, "observed"))
                assertEquals(CellType.ERROR, value.cellType, scenario.expression)
                assertEquals(FormulaError.DIV0.code, value.errorValue, scenario.expression)
                cell(export, "divisor").setCellValue(2.0)
                evaluator.clearAllCachedResultValues()
                val restored = evaluator.evaluate(cell(export, "observed"))
                when (val expected = scenario.expected) {
                    is Value.Num -> assertEquals(expected.value.toDouble(), restored.numberValue, scenario.expression)
                    is Value.Bool -> assertEquals(expected.value, restored.booleanValue, scenario.expression)
                    else -> error("unexpected fixture type")
                }
            }
        }
    }

    @Test
    fun `excluded conditional items remain lazy when the containing sum is skipped`() {
        val expression = "(if gate (sum [(if gate [(/ 2 divisor)] {:amount 3}) 3]) 17)"
        val model = schema(expression)
        val result = Mantra.calculate(
            model,
            Mantra.loadCase(SourceText("zero.mantra", "(case zero (inputs {:divisor 0}))")),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(17), result.value("observed"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.evaluateAll()
            assertEquals(17.0, cell(export, "observed").numericCellValue)
            cell(export, "gate").setCellValue(true)
            evaluator.clearAllCachedResultValues()
            assertEquals(FormulaError.DIV0.code, evaluator.evaluate(cell(export, "observed")).errorValue)
            cell(export, "divisor").setCellValue(2.0)
            evaluator.clearAllCachedResultValues()
            assertEquals(3.0, evaluator.evaluate(cell(export, "observed")).numberValue)
        }
    }

    @Test
    fun `only complete escaped text literals are safe to omit`() {
        assertTrue(Ex.isTextLiteral(Ex.text("")))
        assertTrue(Ex.isTextLiteral(Ex.text("a \"quoted\" value")))
        assertFalse(Ex.isTextLiteral(Ex.atom("\"x\"&1/0", XKind.TEXT)))
        assertFalse(Ex.isTextLiteral(Ex.EMPTY))
    }
}
