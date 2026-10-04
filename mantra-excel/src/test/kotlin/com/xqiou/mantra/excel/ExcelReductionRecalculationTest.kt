package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelReductionRecalculationTest {
    private fun result(text: String, facts: String = "{}") = Mantra.calculateForAudit(
        Mantra.loadSchema(SourceText("reductions.mantra", text.trimIndent()), SourceResolver { _, _ -> null }),
        Mantra.loadCase(SourceText("facts.mantra", "(case test (inputs $facts))")),
    ).also { assertTrue(it.succeeded, it.diagnostics.toString()) }

    private fun cell(export: ExcelWorkbook, address: String): XSSFCell {
        val reference = CellReference(address)
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    private fun recalculate(export: ExcelWorkbook) {
        export.workbook.creationHelper.createFormulaEvaluator().apply {
            clearAllCachedResultValues()
            evaluateAll()
        }
    }

    @Test
    fun `complete large member sums recalculate with and without named ranges`() {
        val keys = (1..1000).joinToString(" ") { ":M$it" }
        val calculated = result(
            """
            (schema test/live-range
              (dimension member {:members [$keys]})
              (input basis :decimal {:per member :default 3})
              (section amounts "Amounts" {:per member}
                (line amount "Amount" basis)))
            """,
        )
        for (useNames in listOf(true, false)) {
            ExcelExport.workbook(calculated, options = ExcelOptions(useNames = useNames)).use { export ->
                assertEquals(emptyList(), export.report.fallbacks)
                assertEquals(emptyList(), export.report.evaluationErrors)
                val total = cell(export, assertNotNull(export.aggregateAddress("amount")))
                assertEquals(CellType.FORMULA, total.cellType)
                assertEquals(3000.0, total.numericCellValue)
                cell(export, assertNotNull(export.address("basis", listOf("M500")))).setCellValue(7.0)
                recalculate(export)
                assertEquals(3004.0, total.numericCellValue)
                cell(export, assertNotNull(export.address("basis", listOf("M500")))).setCellValue(3.0)
                recalculate(export)
                assertEquals(3000.0, total.numericCellValue)
            }
        }
    }

    @Test
    fun `member sums retain editable activation conditions`() {
        val calculated = result(
            """
            (schema test/live-guards
              (dimension member {:members [:A :B]})
              (input basis :decimal {:per member :default 10})
              (input included :boolean {:per member :default true})
              (section amounts "Amounts" {:per member}
                (line amount "Amount" basis {:when included})))
            """,
        )
        ExcelExport.workbook(calculated).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            assertEquals(emptyList(), export.report.evaluationErrors)
            val total = cell(export, assertNotNull(export.aggregateAddress("amount")))
            assertEquals(20.0, total.numericCellValue)
            cell(export, assertNotNull(export.address("included", listOf("A")))).setCellValue(false)
            recalculate(export)
            assertEquals(10.0, total.numericCellValue)
            cell(export, assertNotNull(export.address("included", listOf("A")))).setCellValue(true)
            recalculate(export)
            assertEquals(20.0, total.numericCellValue)
        }
    }

    @Test
    fun `parent scoped sums follow edited parent relation cells`() {
        val calculated = result(
            """
            (schema test/live-parent
              (input facts :table {:columns {:id :keyword :quarter :keyword}})
              (dimension quarter {:members [:Q1 :Q2]})
              (dimension month {:from facts :key :id :parent quarter :parent-key :quarter})
              (input basis :decimal {:per month :default 10})
              (section amounts "Amounts" {:per month}
                (line amount "Amount" basis)))
            """,
            "{:facts [" + (1..6).joinToString(" ") { "{:id :M$it :quarter :Q${if (it <= 3) 1 else 2}}" } + "]}",
        )
        val layout = LayoutReader.read(
            SourceText(
                "parent-layout.mantra",
                """
                (layout test/live-parent {:language :en}
                  (table amounts {:style :transpose :row-dimension month :fixed {:quarter :Q1}}
                    :label (node amount)))
                """.trimIndent(),
            ),
        )
        ExcelExport.workbook(calculated, layout).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            assertEquals(emptyList(), export.report.evaluationErrors)
            val total = cell(export, assertNotNull(export.aggregateAddress("amount", mapOf("quarter" to "Q1"))))
            assertEquals(30.0, total.numericCellValue)
            val parent = cell(export, assertNotNull(export.recordAddress("month", "M3", "quarter")))
            parent.setCellValue("Q2")
            recalculate(export)
            assertEquals(20.0, total.numericCellValue)
            parent.setCellValue("Q1")
            recalculate(export)
            assertEquals(30.0, total.numericCellValue)
        }
    }
}
