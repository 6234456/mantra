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
        val out = Path.of("apps/de-est/build/out/$dir.xlsx")
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
    fun `income tax workbook is fully formula based and recalculates`() {
        val (result, workbook) = export("de-est", "case-mustermann.mantra")
        assertEquals(emptyList(), workbook.report.fallbacks)
        assertEquals(emptyList(), workbook.report.evaluationErrors)
        assertWorkbookMatches(result, workbook)

        // Change Person A's salary directly in the workbook and compare with a fresh engine run.
        setInput(workbook, "bruttoarbeitslohn", listOf("A"), 91500.0)
        val changed = withInput(
            result.case,
            "bruttoarbeitslohn",
            Value.MapV(
                mapOf(
                    Value.Kw("A") to Value.Num(BigDecimal(91500)),
                    Value.Kw("B") to Value.Num(BigDecimal(31200)),
                ),
            ),
        )
        assertWorkbookMatches(Mantra.calculate(result.schema, changed), workbook)
    }
}
