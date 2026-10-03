package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.excel.ExcelWorkbook
import com.xqiou.mantra.render.Render
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Excel export must keep the logic: every calculated cell is a formula, POI's evaluation of
 * those formulas reproduces every engine value, and changing an input cell recalculates the same
 * results as a new engine run.
 */
class ExcelExportTest {
    private fun export(dir: String, case: String): Triple<CalculationResult, ExcelWorkbook, Path> {
        val base = Path.of("apps", dir)
        val schema = Mantra.loadSchema(base.resolve("schema.mantra"))
        val result = Mantra.calculate(schema, Mantra.loadCase(base.resolve(case)))
        val layout = Render.loadLayout(base.resolve("layout.mantra"))
        val workbook = ExcelExport.workbook(result, layout)
        val out = Path.of("apps/ifrs-impairment/build/out/$dir.xlsx")
        workbook.write(out)
        return Triple(result, workbook, out)
    }

    private fun excelValue(workbook: ExcelWorkbook, address: String): Any? {
        val ref = CellReference(address)
        val cell = workbook.workbook.getSheet(ref.sheetName).getRow(ref.row)?.getCell(ref.col.toInt()) ?: return null
        val evaluator = workbook.workbook.creationHelper.createFormulaEvaluator()
        val type = if (cell.cellType == CellType.FORMULA) evaluator.evaluateFormulaCell(cell) else cell.cellType
        return when (type) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.BOOLEAN -> cell.booleanCellValue
            CellType.STRING -> cell.stringCellValue.ifEmpty { null }
            CellType.BLANK -> null
            CellType.ERROR -> "#ERROR ${cell.errorCellValue}"
            else -> null
        }
    }

    /** Compares every value of every node the engine computed with the workbook. */
    private fun assertWorkbookMatches(result: CalculationResult, workbook: ExcelWorkbook) {
        workbook.workbook.creationHelper.createFormulaEvaluator().clearAllCachedResultValues()
        val mismatches = mutableListOf<String>()
        var compared = 0
        result.nodes.values.forEach { node ->
            node.values.forEach { (coord, value) ->
                val address = workbook.address(node.id, coord) ?: return@forEach
                val actual = excelValue(workbook, address)
                compared++
                val ok = when (value) {
                    is Value.Num -> (actual as? Double)?.let { abs(it - value.value.toDouble()) < 1e-6 } ?: false
                    is Value.Bool -> actual == value.value || (actual == null && !value.value)
                    is Value.Kw -> actual == value.name
                    is Value.Text -> actual == value.value
                    Value.Nil -> actual == null || actual == 0.0
                    else -> true
                }
                if (!ok) mismatches += "${node.id}$coord at $address: engine=$value excel=$actual"
            }
        }
        assertTrue(compared > 20, "compared only $compared cells")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatches:\n" + mismatches.take(25).joinToString("\n"))
    }

    private fun setInput(workbook: ExcelWorkbook, nodeId: String, coord: List<String>, value: Double) {
        val ref = CellReference(workbook.address(nodeId, coord) ?: fail("no cell for $nodeId$coord"))
        workbook.workbook.getSheet(ref.sheetName).getRow(ref.row).getCell(ref.col.toInt()).setCellValue(value)
    }

    private fun withInput(case: CaseData, id: String, value: Value): CaseData = case.copy(
        inputs =
        case.inputs + (id to value),
    )

    @Test
    fun `IAS 36 workbook is fully formula based and recalculates`() {
        val (result, workbook) = export("ifrs-impairment", "case-demo.mantra")
        assertEquals(emptyList(), workbook.report.fallbacks)
        assertEquals(emptyList(), workbook.report.evaluationErrors)
        assertTrue(workbook.report.formulaCells > 40)
        assertWorkbookMatches(result, workbook)

        // Lower CGU C's value in use in the workbook's input table (300 → 240) and compare with a new
        // engine run: the allocation of the larger loss must follow in Excel as well.
        val ref = CellReference(workbook.recordAddress("cgu", "C", "value-in-use") ?: fail("no record cell"))
        workbook.workbook.getSheet(ref.sheetName).getRow(ref.row).getCell(ref.col.toInt()).setCellValue(240.0)
        val rows = (result.case.inputs.getValue("cgus") as Value.Vec).items.map { row ->
            val map = (row as Value.MapV).entries
            if (map[Value.Kw("id")] ==
                Value.Kw("C")
            ) {
                Value.MapV(map + (Value.Kw("value-in-use") to Value.Num(BigDecimal(240))))
            } else {
                row
            }
        }
        val changed = Mantra.calculate(result.schema, withInput(result.case, "cgus", Value.Vec(rows)))
        assertEquals(0, BigDecimal(151).compareTo(changed.decimal("impairment-loss", "C")))
        assertWorkbookMatches(changed, workbook)
    }
}
