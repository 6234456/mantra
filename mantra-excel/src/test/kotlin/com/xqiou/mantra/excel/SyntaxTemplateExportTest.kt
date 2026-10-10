package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.render.Render
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyntaxTemplateExportTest {
    @Test
    fun `copyable patterns retain independent amounts in papers and live workbook formulas`() {
        // Hand arithmetic is recorded in docs/templates/README.md, independently of the engine.
        val references = mapOf(
            "ratio" to mapOf("rate@A" to "0.3333", "rate@B" to "0.1667", "rate@*" to "0.222222"),
            "allocation" to
                mapOf(
                    "allocated@A" to "16.67",
                    "allocated@B" to "33.34",
                    "allocated@C" to "50.00",
                    "allocation-total" to "100.01",
                ),
            "roll-forward" to
                mapOf(
                    "closing@A/P1" to "120",
                    "closing@A/P2" to "115",
                    "closing@A/P3" to "125",
                    "closing@B/P3" to "0",
                    "opening@*" to "150",
                    "movement@*" to "-25",
                    "closing@*" to "125",
                ),
            "rules" to
                mapOf(
                    "gross" to "125.00",
                    "selected-discount" to "30",
                    "net" to "95.00",
                    "reference-value" to "125.00",
                ),
        )
        references.forEach { (name, amounts) ->
            val directory = Path.of("docs/templates", name)
            val result = Mantra.calculateForAudit(
                Mantra.loadSchema(directory.resolve("schema.mantra")),
                Mantra.loadCase(directory.resolve("case-demo.mantra")),
            )
            assertTrue(result.succeeded, "$name: ${result.diagnostics}")
            assertTrue(result.validationPassed, "$name: ${result.diagnostics}")
            val layout = Render.loadLayout(directory.resolve("layout.mantra"))
            assertTrue(Render.html(result, layout).isNotBlank())
            assertTrue(Render.text(result, layout, includeAudit = true).isNotBlank())
            ExcelExport.workbook(result, layout).use { export ->
                assertEquals(emptyList(), export.report.fallbacks, name)
                assertEquals(emptyList(), export.report.evaluationErrors, name)
                val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
                amounts.forEach { (address, expected) ->
                    val id = address.substringBefore('@')
                    val suffix = address.substringAfter('@', "")
                    val coords = if (suffix.isEmpty() || suffix == "*") emptyList() else suffix.split('/')
                    val value = if (suffix == "*") result.view.reduce(id).value else result.node(id).value(coords)
                    assertTrue(value is Value.Num, "$name/$address: $value")
                    assertEquals(0, expected.toBigDecimal().compareTo(value.value), "$name/$address")
                    val target = if (suffix == "*") export.aggregateAddress(id) else export.address(id, coords)
                    val reference = CellReference(assertNotNull(target, "$name/$address"))
                    val cell = export.workbook.getSheet(
                        reference.sheetName,
                    ).getRow(reference.row).getCell(reference.col.toInt())
                    assertEquals(CellType.FORMULA, cell.cellType, "$name/$address retains a formula")
                    val calculated = assertNotNull(evaluator.evaluate(cell), "$name/$address")
                    assertEquals(CellType.NUMERIC, calculated.cellType, "$name/$address")
                    assertEquals(expected.toDouble(), calculated.numberValue, 1e-8, "$name/$address")
                }
            }
        }
    }
}
