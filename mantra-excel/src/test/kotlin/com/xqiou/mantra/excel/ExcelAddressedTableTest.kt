package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelAddressedTableTest {
    private fun cell(export: ExcelWorkbook, id: String, vararg coordinate: String): XSSFCell {
        val address = CellReference(assertNotNull(export.address(id, coordinate.toList())))
        return export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt())
    }

    @Test
    fun `transpose node columns keep independent signs addresses and editable raw amounts`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "transpose.mantra",
                """
                (schema test/transpose
                  (dimension asset {:members [:A :B]})
                  (input seed :decimal {:per asset :default 10})
                  (section bridge "Bridge" {:per asset}
                    (line additions "Additions" seed)
                    (line deductions "Deductions" 3 {:op :minus})
                    (total balance "Balance")))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val result = Mantra.calculateForAudit(schema)
        assertTrue(result.succeeded, result.diagnostics.toString())
        listOf(true, false).forEach { signed ->
            val layout = LayoutReader.read(
                SourceText(
                    "transpose-layout.mantra",
                    """
                    (layout test/transpose {:language :en :signed $signed :hide-zero false}
                      (table bridge {:style :transpose :row-dimension asset}
                        :label (node additions) (node deductions) (node balance)))
                    """.trimIndent(),
                ),
            )
            ExcelExport.workbook(result, layout).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                val additions = cell(export, "additions", "A")
                val deductions = cell(export, "deductions", "A")
                val balance = cell(export, "balance", "A")
                assertEquals(10.0, additions.numericCellValue)
                assertEquals(3.0, deductions.numericCellValue)
                assertEquals(7.0, balance.numericCellValue)
                assertEquals(additions.rowIndex, deductions.rowIndex)
                assertEquals(additions.columnIndex + 1, deductions.columnIndex)
                assertEquals(deductions.columnIndex + 1, balance.columnIndex)
                assertFalse(additions.cellStyle.dataFormatString.substringBefore(';').startsWith('('))
                assertEquals(signed, deductions.cellStyle.dataFormatString.substringBefore(';').startsWith('('))
                cell(export, "seed", "A").setCellValue(20.0)
                export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
                assertEquals(20.0, additions.numericCellValue)
                assertEquals(3.0, deductions.numericCellValue)
                assertEquals(17.0, balance.numericCellValue)
                assertEquals(7.0, cell(export, "balance", "B").numericCellValue)
            }
        }
    }
}
