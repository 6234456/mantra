package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelBusinessValidationTest {
    private fun calculate(schema: String, case: String) = Mantra.calculateForAudit(
        Mantra.loadSchema(SourceText("checks.mantra", schema.trimIndent()), SourceResolver { _, _ -> null }),
        Mantra.loadCase(SourceText("facts.mantra", case)),
    )

    private fun cell(export: ExcelWorkbook, address: String): XSSFCell {
        val reference = CellReference(address)
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    private fun recalculate(export: ExcelWorkbook) {
        export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
    }

    private fun decision(export: ExcelWorkbook, caption: String): XSSFCell =
        assertNotNull(export.workbook.getSheet("Checks")).first {
            it.getCell(0)?.takeIf { cell -> cell.cellType == CellType.STRING }?.stringCellValue == caption
        }.getCell(2) as XSSFCell

    @Test
    fun `checks and reconciliations remain live with inclusive tolerance and inactive blanks`() {
        val result = calculate(
            """
            (schema test/checks {}
              (input base-amount :decimal)
              (input enabled :boolean)
              (section main "Main"
                (line computed "Computed" (* base-amount 2))
                (check positive "Positive" (>= base-amount 0))
                (reconcile matching "Matching" computed 10 {:tolerance 0.01})
                (check conditional "Conditional" false {:when enabled})))
            """,
            "(case c (inputs {:base-amount 5 :enabled false}))",
        )
        assertTrue(result.succeeded)
        assertTrue(result.validationPassed)
        assertEquals(Value.Nil, result.value("conditional"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals("✓", decision(export, "Positive").stringCellValue)
            assertEquals("✓", decision(export, "Matching").stringCellValue)
            assertEquals("", decision(export, "Conditional").stringCellValue)
            cell(export, assertNotNull(export.address("base-amount"))).setCellValue(6.0)
            cell(export, assertNotNull(export.address("enabled"))).setCellValue(true)
            recalculate(export)
            assertEquals(12.0, cell(export, assertNotNull(export.address("computed"))).numericCellValue)
            assertEquals(2.0, cell(export, assertNotNull(export.address("matching"))).numericCellValue)
            assertEquals("✗", decision(export, "Matching").stringCellValue)
            assertEquals("✗", decision(export, "Conditional").stringCellValue)
            cell(export, assertNotNull(export.address("base-amount"))).setCellValue(-1.0)
            recalculate(export)
            assertEquals("✗", decision(export, "Positive").stringCellValue)
        }
    }

    @Test
    fun `conditional row facts distinguish missing data from supplied zero and follow row edits`() {
        val result = calculate(
            """
            (schema test/row-checks {}
              (input facts :table {:min-rows 1 :columns
                {:mode :keyword :amount {:type :decimal :optional true :required-when (= row.mode :direct)}}})
              (section main "Main"
                (line computed "Computed" (sum (map (fn [r] (or r.amount 0)) facts)))))
            """,
            "(case c (inputs {:facts [{:mode :direct :amount 0} {:mode :indirect}]}))",
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            val first = decision(export, "facts [1] · amount")
            val second = decision(export, "facts [2] · amount")
            assertEquals("✓", first.stringCellValue)
            assertEquals("✓", second.stringCellValue)
            cell(export, assertNotNull(export.tableAddress("facts", 1, "mode"))).setCellValue("direct")
            recalculate(export)
            assertEquals("✗", second.stringCellValue)
            cell(export, assertNotNull(export.tableAddress("facts", 1, "amount"))).setCellValue(0.0)
            second.row.getCell(1).setCellValue(true)
            recalculate(export)
            assertEquals("✓", second.stringCellValue)
            first.row.getCell(1).setCellValue(false)
            recalculate(export)
            assertEquals("✗", first.stringCellValue)
        }
    }

    @Test
    fun `weighted ratio checkpoints preserve undefined values and recover after workbook edits`() {
        val result = calculate(
            """
            (schema test/rate {}
              (dimension member {:members [:A :B]})
              (input charge :decimal {:per member})
              (input units :decimal {:per member})
              (section detail "Detail" {:per member}
                (line rate "Rate" (if (zero? units) nil (decimal/divide charge units 8))
                  {:aggregate {:ratio [charge units] :round [8 :half-up]}}))
              (total checkpoint "Checkpoint")
              (line addend "Addend" 7)
              (total final-value "Final"))
            """,
            "(case c (inputs {:charge {:A 0 :B 0} :units {:A 0 :B 0}}))",
        )
        assertEquals(Value.Nil, result.value("final-value"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals("", cell(export, assertNotNull(export.address("final-value"))).stringCellValue)
            for ((key, charge, units) in listOf(Triple("A", 20.0, 100.0), Triple("B", 90.0, 300.0))) {
                cell(export, assertNotNull(export.address("charge", listOf(key)))).setCellValue(charge)
                cell(export, assertNotNull(export.address("units", listOf(key)))).setCellValue(units)
            }
            recalculate(export)
            assertEquals(0.275, cell(export, assertNotNull(export.address("checkpoint"))).numericCellValue, 1e-12)
            assertEquals(7.275, cell(export, assertNotNull(export.address("final-value"))).numericCellValue, 1e-12)
        }
    }
}
