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

class ExcelFinancialFormulaSizeTest {
    private fun schema(lazy: Boolean) = Mantra.loadSchema(
        SourceText(
            "financial-schedule.mantra",
            """
            (schema test/financial-formula-size
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (input contract-records :table {:columns {:id :keyword :annual-rate :decimal}})
              (dimension contract {:from contract-records :key :id})
              (input cash-flow-items :table
                {:columns {:contract-key :keyword :period-key :keyword :unrounded-payment :decimal}})
              (input divisor :decimal {:default 2})
              (input gate :boolean {:default true})
              (section valuation "Financial valuation" {:per contract}
                (line present-value "Present value"
                  ${if (lazy) "(if gate" else ""}
                  (fin/npv contract.annual-rate
                    (vec (map (fn [period-key]
                      (let [cash-flow (sum (map (fn [entry]
                        (if (and (= entry.contract-key contract.key) (= entry.period-key period-key))
                          ${if (lazy) "(/ entry.unrounded-payment divisor)" else "entry.unrounded-payment"} 0))
                        cash-flow-items))]
                        (if (nil? cash-flow) 0 cash-flow))) periods.year.keys)) 2)
                  ${if (lazy) "17)" else ""})))
            """.trimIndent(),
        ),
        SourceResolver { _, _ -> null },
    )

    private fun facts(lazy: Boolean, gate: Boolean = true, divisor: Int = 2) = Mantra.loadCase(
        SourceText(
            "financial-facts.mantra",
            """
            (case original
              (inputs {:contract-records [{:id :A :annual-rate 0.10}]
                       :cash-flow-items [{:contract-key :A :period-key :P3 :unrounded-payment ${if (lazy) 600 else 300}}
                                         {:contract-key :A :period-key :P1 :unrounded-payment ${if (lazy) 200 else 100}}
                                         {:contract-key :A :period-key :P2 :unrounded-payment ${if (lazy) 400 else 200}}
                                         {:contract-key :Other :period-key :P2 :unrounded-payment 999}]
                       :gate $gate :divisor $divisor}))
            """.trimIndent(),
        ),
    )

    private fun cell(export: ExcelWorkbook, address: String?): XSSFCell {
        val reference = CellReference(assertNotNull(address))
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    private fun noFormulaErrors(export: ExcelWorkbook) {
        for (sheet in export.workbook) {
            for (row in sheet) {
                for (cell in row) {
                    assertFalse(
                        cell.cellType == CellType.FORMULA && cell.cachedFormulaResultType == CellType.ERROR,
                        "${sheet.sheetName}!${cell.address}",
                    )
                }
            }
        }
    }

    @Test
    fun `mapped financial flows retain bounded live formulas and independently reproduce payment edits`() {
        val result = Mantra.calculateForAudit(schema(false), facts(false))
        assertTrue(result.succeeded, result.diagnostics.toString())
        // 100/1.1 + 200/1.1^2 + 300/1.1^3, independently rounded to two places.
        assertEquals(Value.num("481.59"), result.value("present-value", "A"))
        listOf(true, false).forEach { useNames ->
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, ExcelOptions(useNames = useNames)).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                val observed = cell(export, export.address("present-value", listOf("A")))
                assertEquals(CellType.FORMULA, observed.cellType)
                assertEquals(481.59, observed.numericCellValue)
                for (sheet in export.workbook) {
                    for (row in sheet) {
                        for (value in row) {
                            if (value.cellType == CellType.FORMULA) assertTrue(value.cellFormula.length <= 8192)
                        }
                    }
                }
                cell(export, export.tableAddress("cash-flow-items", 2, "unrounded-payment")).setCellValue(500.0)
                val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
                evaluator.evaluateAll()
                // 100/1.1 + 500/1.1^2 + 300/1.1^3.
                assertEquals(729.53, observed.numericCellValue)
                cell(export, export.recordAddress("contract", "A", "annual-rate")).setCellValue(0.0)
                evaluator.clearAllCachedResultValues()
                evaluator.evaluateAll()
                assertEquals(900.0, observed.numericCellValue)
                noFormulaErrors(export)
            }
        }
    }

    @Test
    fun `materialized financial flows remain asleep until selected and preserve divide errors and recovery`() {
        val model = schema(true)
        val result = Mantra.calculateForAudit(model, facts(true, gate = false, divisor = 0))
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(17), result.value("present-value", "A"))
        val failed = Mantra.calculateForAudit(model, facts(true, gate = true, divisor = 0))
        assertFalse(failed.succeeded)
        assertTrue(failed.diagnostics.any { it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO") })
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            val observed = cell(export, export.address("present-value", listOf("A")))
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.evaluateAll()
            assertEquals(17.0, observed.numericCellValue)
            noFormulaErrors(export)
            cell(export, export.address("gate")).setCellValue(true)
            evaluator.clearAllCachedResultValues()
            assertEquals(FormulaError.DIV0.code, evaluator.evaluate(observed).errorValue)
            cell(export, export.address("divisor")).setCellValue(2.0)
            evaluator.clearAllCachedResultValues()
            evaluator.evaluateAll()
            assertEquals(481.59, observed.numericCellValue)
            noFormulaErrors(export)
            cell(export, export.address("gate")).setCellValue(false)
            cell(export, export.address("divisor")).setCellValue(0.0)
            evaluator.clearAllCachedResultValues()
            evaluator.evaluateAll()
            assertEquals(17.0, observed.numericCellValue)
            noFormulaErrors(export)
        }
    }
}
