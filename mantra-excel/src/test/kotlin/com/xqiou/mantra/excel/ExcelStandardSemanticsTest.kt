package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelStandardSemanticsTest {
    private fun schema(source: String) = Mantra.loadSchema(
        SourceText("semantics.mantra", source.trimIndent()),
        SourceResolver { _, _ -> null },
    )

    private fun cell(export: ExcelWorkbook, id: String): XSSFCell {
        val address = CellReference(assertNotNull(export.address(id)))
        return export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt())
    }

    @Test
    fun `sum and logical forms match the locked kernel values and short circuit behavior`() {
        val model = schema(
            """
            (schema test/semantics
              (line empty-nil "Empty sum" (nil? (sum [])) {:type :boolean :op :info})
              (line missing-nil "Nil sum" (nil? (sum nil)) {:type :boolean :op :info})
              (line nonnumeric-nil "Nonnumeric sum" (nil? (sum [true false nil "ignored"]))
                {:type :boolean :op :info})
              (line mixed "Mixed sum" (sum [true false nil "ignored" "1,25" 3 [8] {:n 9} :ignored])
                {:op :info})
              (line numeric-zero "Zero sum" (sum [false 0 nil]) {:op :info})
              (line dim-zero "Dimension sum" (dim/sum {:A nil}) {:op :info})
              (line false-or "All false or" (nil? (or false false)) {:type :boolean :op :info})
              (line lazy-or "Lazy or" (= (or 7 (/ 1 0)) 7) {:type :boolean :op :info})
              (line value-and "Value and" (= (and true 0) 0) {:type :boolean :op :info})
              (line empty-and "Empty and" (= (and) true) {:type :boolean :op :info})
              (line lazy-and "Lazy and" (nil? (and nil (/ 1 0))) {:type :boolean :op :info})
              (line empty-text "Empty text is truthy" (= (if "" 1 (/ 1 0)) 1) {:type :boolean :op :info}))
            """,
        )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        val booleans = listOf(
            "empty-nil", "missing-nil", "nonnumeric-nil", "false-or", "lazy-or",
            "value-and", "empty-and", "lazy-and", "empty-text",
        )
        booleans.forEach { assertEquals(Value.Bool(true), result.value(it), it) }
        assertEquals(0, result.decimal("mixed").compareTo(java.math.BigDecimal("4.25")))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            booleans.forEach { assertEquals(true, cell(export, it).booleanCellValue, it) }
            assertEquals(4.25, cell(export, "mixed").numericCellValue)
            assertEquals(0.0, cell(export, "numeric-zero").numericCellValue)
            assertEquals(0.0, cell(export, "dim-zero").numericCellValue)
        }
    }

    @Test
    fun `long aggregation under a skipped division branch adds no eager helper errors`() {
        val terms = List(60) { "(/ 1 divisor)" }.joinToString(" ")
        val model = schema(
            """
            (schema test/lazy-sum
              (input gate :boolean {:default false})
              (input divisor :decimal {:default 0})
              (line selected "Selected" (if gate (sum [$terms]) 17) {:op :info}))
            """,
        )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(0, result.decimal("selected").compareTo(java.math.BigDecimal("17")))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(17.0, cell(export, "selected").numericCellValue)
            cell(export, "divisor").setCellValue(2.0)
            cell(export, "gate").setCellValue(true)
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(30.0, cell(export, "selected").numericCellValue)
            cell(export, "gate").setCellValue(false)
            cell(export, "divisor").setCellValue(0.0)
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(17.0, cell(export, "selected").numericCellValue)
        }
    }
}
