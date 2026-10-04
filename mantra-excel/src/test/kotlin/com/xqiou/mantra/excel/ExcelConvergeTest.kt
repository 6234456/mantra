package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual locked-kernel results and independently recalculated editable workbooks. */
class ExcelConvergeTest {
    private fun schema(body: String) = Mantra.loadSchema(
        SourceText("converge.mantra", "(schema test/converge {} $body)"),
        SourceResolver { _, _ -> null },
    )

    private fun cell(export: ExcelWorkbook, id: String, coord: List<String> = emptyList()): XSSFCell {
        val address = CellReference(assertNotNull(export.address(id, coord)))
        return export.workbook.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt())
    }

    @Test
    fun `named callback recomputes live inputs and keeps the seed-specific rounded fixed point`() {
        val model = schema(
            """
            (input seed :decimal {:default 1000})
            (input principal :decimal {:default 1000})
            (defn advance [^Decimal current] (decimal/round (+ principal (* 0.25 current)) 2))
            (section main "Main" (line gross "Gross" (calc/converge advance seed 30 0)))
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num("1333.33"), result.value("gross"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(1333.33, cell(export, "gross").numericCellValue)
            cell(export, "seed").setCellValue(2000.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.evaluateAll()
            assertEquals(1333.34, cell(export, "gross").numericCellValue)
            cell(export, "principal").setCellValue(0.0)
            evaluator.clearAllCachedResultValues()
            evaluator.evaluateAll()
            assertEquals(0.0, cell(export, "gross").numericCellValue)
        }
    }

    @Test
    fun `first-step stop prevents all later callback and unselected branch helpers from executing`() {
        val model = schema(
            """
            (section main "Main"
              (line stopped "Stopped"
                (calc/converge (fn [^Decimal x]
                  (if (= x 1) 0
                    (let [share (get (alloc/capped (/ 1 x) {:A 1 :B 1} {:A 2} 2) :A)]
                      (if (nil? share) 0 share)))) 1 3 1)))
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(0), result.value("stopped"))
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(0.0, cell(export, "stopped").numericCellValue)
            for (sheet in export.workbook) {
                for (row in sheet) {
                    for (value in row) {
                        assertFalse(
                            value.cellType == CellType.FORMULA && value.cachedFormulaResultType == CellType.ERROR,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `live tolerance rejects a non-converged approximation`() {
        val model = schema(
            """
            (input tolerance :decimal {:default 0.01})
            (section main "Main"
              (line penny "Penny"
                (calc/converge (fn [x] (decimal/round (* 0.5 (- 0.01 x)) 2)) 0 6 tolerance)))
            """.trimIndent(),
        )
        val result = Mantra.calculate(model)
        assertEquals(Value.num("0.01"), result.value("penny"))
        val exact = Mantra.loadCase(SourceText("exact.mantra", "(case exact (inputs {:tolerance 0}))"))
        val failed = Mantra.calculate(model, exact)
        assertFalse(failed.succeeded)
        assertTrue(failed.diagnostics.any { it.message.contains("DSL-MANTRA-CALC-NOT-CONVERGED") })
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            cell(export, "tolerance").setCellValue(0.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            val error = evaluator.evaluate(cell(export, "penny"))
            assertEquals(CellType.ERROR, error.cellType)
            assertEquals(FormulaError.NA.code, error.errorValue)
            cell(export, "tolerance").setCellValue(0.01)
            evaluator.clearAllCachedResultValues()
            assertEquals(0.01, evaluator.evaluate(cell(export, "penny")).numberValue)
        }
    }

    @Test
    fun `dynamic bound reserves the hard ceiling and rejects invalid edited limits without approximation`() {
        val model = schema(
            """
            (input limit :decimal {:default 2})
            (section main "Main" (line value "Value" (calc/converge (fn [x] 7) 0 limit 0)))
            """.trimIndent(),
        )
        val result = Mantra.calculate(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertFailsWith<ExcelExportLimitException> {
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, ExcelOptions(maxConvergenceSteps = 999))
        }
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, ExcelOptions(maxConvergenceSteps = 1000)).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            val table = (0 until export.workbook.numberOfSheets).first {
                export.workbook.getSheetName(it).startsWith("Convergence")
            }
            assertTrue(export.workbook.getSheetAt(table).lastRowNum >= 1002)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            cell(export, "limit").setCellValue(1.0)
            assertEquals(FormulaError.NA.code, evaluator.evaluate(cell(export, "value")).errorValue)
            cell(export, "limit").setCellValue(1.5)
            evaluator.clearAllCachedResultValues()
            assertEquals(FormulaError.VALUE.code, evaluator.evaluate(cell(export, "value")).errorValue)
            cell(export, "limit").setCellValue(2.0)
            evaluator.clearAllCachedResultValues()
            evaluator.evaluateAll()
            assertEquals(7.0, cell(export, "value").numericCellValue)
        }
    }

    @Test
    fun `callback input errors retain division identity through the iteration cells and recover`() {
        val model = schema(
            """
            (input divisor :decimal {:default 2})
            (section main "Main" (line value "Value" (calc/converge (fn [x] (/ 1 divisor)) 0 3 0)))
            """.trimIndent(),
        )
        val result = Mantra.calculate(model)
        assertEquals(Value.num("0.5"), result.value("value"))
        val zero = Mantra.loadCase(SourceText("zero.mantra", "(case zero (inputs {:divisor 0}))"))
        val failed = Mantra.calculate(model, zero)
        assertFalse(failed.succeeded)
        assertTrue(failed.diagnostics.any { it.message.contains("DSL-RUNTIME-DIVIDE-BY-ZERO") })
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            cell(export, "divisor").setCellValue(0.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            assertEquals(FormulaError.DIV0.code, evaluator.evaluate(cell(export, "value")).errorValue)
            cell(export, "divisor").setCellValue(2.0)
            evaluator.clearAllCachedResultValues()
            assertEquals(0.5, evaluator.evaluate(cell(export, "value")).numberValue)
        }
    }

    @Test
    fun `unsupported callback fails export rather than substituting a generation-time answer`() {
        val model = schema(
            """
            (section main "Main" (line value "Value" (calc/converge (fn [x] (mod 7 2)) 1 2 0)))
            """.trimIndent(),
        )
        val result = Mantra.calculate(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(1), result.value("value"))
        assertFailsWith<ExcelExportLimitException> {
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE)
        }
    }

    @Test
    fun `previous-period callback stays at the real node coordinate while iteration changes`() {
        val model = schema(
            """
            (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
            (input seed :decimal {:default 0})
            (section main "Main" {:per [year]}
              (line closing "Closing"
                (calc/converge (fn [^Decimal current]
                  (let [prior (prev closing seed)] (if (nil? prior) 0 (+ prior 1)))) 0 3 0)))
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            listOf("P1", "P2", "P3").forEachIndexed { index, period ->
                assertEquals(Value.num((index + 1).toLong()), result.value("closing", period))
                assertEquals((index + 1).toDouble(), cell(export, "closing", listOf(period)).numericCellValue)
            }
            cell(export, "seed").setCellValue(2.0)
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            listOf("P1", "P2", "P3").forEachIndexed { index, period ->
                assertEquals((index + 3).toDouble(), cell(export, "closing", listOf(period)).numericCellValue)
            }
        }
    }

    @Test
    fun `lexical callbacks retain two-axis context while previous periods advance independently`() {
        val model =
            schema(
                """
            (dimension asset {:members [:A :B]})
            (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
            (input seed :decimal {:per asset})
            (section bridge "Bridge" {:per [asset year]}
              (line closing "Closing"
                (calc/converge
                  (let [step (+ asset.index 1) prior (prev closing seed)]
                    (fn [^Decimal current] (if (nil? prior) 0 (+ prior step)))) 0 3 0)))
                """.trimIndent(),
            )
        val case = Mantra.loadCase(
            SourceText(
                "two-axis.mantra",
                "(case c (inputs {:seed {:A 100 :B 200}}))",
            ),
        )
        val result = Mantra.calculateForAudit(model, case)
        assertTrue(result.succeeded, result.diagnostics.toString())
        val layout = LayoutReader.read(
            SourceText(
                "layout.mantra",
                """
            (layout test/converge {:language :en :hide-zero false}
              (table bridge {:style :matrix :row-dimension asset}
                :label (members year) :cross-total))
                """.trimIndent(),
            ),
        )
        ExcelExport.workbook(result, layout).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            for (period in 1..3) {
                val expectedA = (100 + period).toDouble()
                assertEquals(expectedA, cell(export, "closing", listOf("A", "P$period")).numericCellValue)
                val expectedB = (200 + period * 2).toDouble()
                assertEquals(expectedB, cell(export, "closing", listOf("B", "P$period")).numericCellValue)
            }
            cell(export, "seed", listOf("A")).setCellValue(150.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.evaluateAll()
            for (period in 1..3) {
                val expectedA = (150 + period).toDouble()
                assertEquals(expectedA, cell(export, "closing", listOf("A", "P$period")).numericCellValue)
                val expectedB = (200 + period * 2).toDouble()
                assertEquals(expectedB, cell(export, "closing", listOf("B", "P$period")).numericCellValue)
            }
        }
    }

    @Test
    fun `nested convergence shares the export expansion budget without overlapping blocks`() {
        val model =
            schema(
                """
            (section main "Main" (line observed "Observed"
              (calc/converge (fn [x] (calc/converge (fn [y] 7) 0 2 0)) 0 2 0)))
                """.trimIndent(),
            )
        val result = Mantra.calculate(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(7), result.value("observed"))
        assertFailsWith<ExcelExportLimitException> {
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, ExcelOptions(maxConvergenceSteps = 5))
        }
        assertFailsWith<ExcelExportLimitException> {
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, ExcelOptions(maxCells = 20))
        }
        assertFailsWith<ExcelExportLimitException> {
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, ExcelOptions(maxSheets = 1))
        }
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, ExcelOptions(maxConvergenceSteps = 6)).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(7.0, cell(export, "observed").numericCellValue)
            val sheet = export.workbook.first { it.sheetName.startsWith("Convergence") }
            assertEquals(
                3,
                sheet.count {
                    val first = it.getCell(0)
                    first?.cellType == CellType.STRING && first.stringCellValue == "Iteration"
                },
            )
        }
    }
}
