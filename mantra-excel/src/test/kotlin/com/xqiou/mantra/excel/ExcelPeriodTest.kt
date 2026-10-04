package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelPeriodTest {
    private fun schema(source: String) =
        Mantra.loadSchema(SourceText("period.mantra", source.trimIndent()), SourceResolver { _, _ -> null })
    private fun cell(export: ExcelWorkbook, id: String, vararg coordinate: String): XSSFCell {
        val reference = CellReference(assertNotNull(export.address(id, coordinate.toList())))
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }
    private fun layout(style: String, columns: String, fixed: String = "") = LayoutReader.read(
        SourceText(
            "layout.mantra",
            """
        (layout test/period {:language :en :hide-zero false}
          (table bridge {:style :$style :row-dimension asset $fixed} $columns))
            """.trimIndent(),
        ),
    )

    private val carried =
        schema(
            """
        (schema test/carry
          (dimension asset {:members [:A :B]})
          (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
          (input seed :decimal {:per asset})
          (section bridge "Bridge" {:per [asset year]}
            (line opening "Opening" (prev closing seed) {:op :info :aggregate {:first year}})
            (line movement "Movement" 20 {:op :info})
            (line closing "Closing" (+ opening movement) {:op :info :aggregate {:last year}})))
    """,
        )

    @Test
    fun `matrix and fixed transpose recompute previous hidden coordinates and boundary totals`() {
        val result = Mantra.calculateForAudit(
            carried,
            Mantra.loadCase(SourceText("case.mantra", "(case c (inputs {:seed {:A 100 :B 200}}))")),
        )
        listOf(
            layout("matrix", ":label (members year) :cross-total"),
            layout("transpose", ":label (node closing)", ":fixed {:year :P2}"),
        ).forEach { spec ->
            ExcelExport.workbook(result, spec).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                assertEquals(160.0, cell(export, "closing", "A", "P3").numericCellValue)
                assertEquals(260.0, cell(export, "closing", "B", "P3").numericCellValue)
                assertEquals(300.0, cell(export, "aggregate.opening").numericCellValue)
                assertEquals(120.0, cell(export, "aggregate.movement").numericCellValue)
                assertEquals(420.0, cell(export, "aggregate.closing").numericCellValue)
                cell(export, "seed", "A").setCellValue(150.0)
                export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
                assertEquals(210.0, cell(export, "closing", "A", "P3").numericCellValue)
                assertEquals(470.0, cell(export, "aggregate.closing").numericCellValue)
            }
        }
    }

    @Test
    fun `period dates and edited date inputs retain whole month and leap year semantics`() {
        val model =
            schema(
                """
            (schema test/dates
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (input available :date)
              (section dates "Dates" {:per year}
                (line months "Months" (date/months-between available year.end-exclusive) {:op :info})
                (line days "Days" (date/days-between year.start year.end-exclusive) {:op :info})
                (line shifted "Shift" (date/plus-months available 1) {:type :date :op :info})))
        """,
            )
        val result = Mantra.calculateForAudit(
            model,
            Mantra.loadCase(SourceText("dates-case.mantra", "(case c (inputs {:available \"2026-01-31\"}))")),
        )
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(11.0, cell(export, "months", "P1").numericCellValue)
            assertEquals(366.0, cell(export, "days", "P3").numericCellValue)
            assertEquals(
                excelDate(java.time.LocalDate.of(2026, 2, 28)).text.toDouble(),
                cell(export, "shifted", "P1").numericCellValue,
            )
            cell(export, "available").setCellValue(java.time.LocalDate.of(2026, 2, 1).atStartOfDay())
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(11.0, cell(export, "months", "P1").numericCellValue)
            assertEquals(
                excelDate(java.time.LocalDate.of(2026, 3, 1)).text.toDouble(),
                cell(export, "shifted", "P1").numericCellValue,
            )
        }
    }

    @Test
    fun `previous nil propagates through rounding and totals while zero and local shadowing stay numeric`() {
        val model =
            schema(
                """
            (schema test/nil
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (input seed :decimal {:default 8})
              (defn initial [] (+ seed 1))
              (line zero-carry "Zero" (prev zero-carry 0) {:per year :op :info})
              (line named-carry "Named" (prev named-carry (initial)) {:per year :op :info})
              (line shadow "Shadow" (let [x seed prev (fn [^Decimal y] (+ x y))] (prev 4))
                {:per year :op :info})
              (section values "Values" {:per year}
                (line unknown "Unknown" (prev unknown (decimal/divide 1 0))
                  {:aggregate {:last year} :round [2 :half-up]})
                (total inner "Inner" {:aggregate {:last year}}))
              (total outer "Outer"))
        """,
            )
        val result = Mantra.calculateForAudit(model)
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            listOf("P1", "P2", "P3").forEach { key ->
                assertEquals(0.0, cell(export, "zero-carry", key).numericCellValue)
                assertEquals(9.0, cell(export, "named-carry", key).numericCellValue)
                assertEquals(12.0, cell(export, "shadow", key).numericCellValue)
                assertEquals(CellType.STRING, cell(export, "unknown", key).cachedFormulaResultType)
                assertEquals("", cell(export, "unknown", key).stringCellValue)
            }
            assertEquals("", cell(export, "inner", "P3").stringCellValue)
            assertEquals("", cell(export, "outer").stringCellValue)
            cell(export, "seed").setCellValue(10.0)
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(11.0, cell(export, "named-carry", "P3").numericCellValue)
            assertEquals(14.0, cell(export, "shadow", "P3").numericCellValue)
        }
    }

    @Test
    fun `period parents boundary rollup and parent scoped totals retain selected missing values`() {
        val model =
            schema(
                """
            (schema test/hierarchy
              (dimension quarter {:periods {:start "2026-04-01" :unit :quarter :count 2}})
              (dimension month {:periods {:start "2026-04-01" :unit :month :count 6} :parent quarter})
              (section monthly "Monthly" {:per month}
                (line balance "Balance" (+ month.index 10) {:aggregate {:last month}}))
              (line roll-first "First" (dim/rollup all.balance relation_month quarter.key :first periods.month.keys)
                {:per quarter :op :info})
              (line roll-last "Last" (dim/rollup all.balance relation_month quarter.key :last periods.month.keys)
                {:per quarter :op :info})
              (line missing "Missing" (dim/rollup {:P2 20.0} {:P1 :P1 :P2 :P1} quarter.key :first [:P1 :P2])
                {:per quarter :aggregate {:last quarter} :op :info})
              (line absent "Absent" (dim/rollup {:P1 10.0} {:P1 :unused} quarter.key :last [:P1])
                {:per quarter :op :info}))
        """,
            )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(10.0, cell(export, "roll-first", "P1").numericCellValue)
            assertEquals(13.0, cell(export, "roll-first", "P2").numericCellValue)
            assertEquals(12.0, cell(export, "roll-last", "P1").numericCellValue)
            assertEquals(15.0, cell(export, "roll-last", "P2").numericCellValue)
            assertEquals("", cell(export, "missing", "P1").stringCellValue)
            assertEquals(0.0, cell(export, "missing", "P2").numericCellValue)
            assertEquals(0.0, cell(export, "absent", "P1").numericCellValue)
        }
        listOf("P1" to 12.0, "P2" to 15.0).forEach { (key, expected) ->
            val spec = LayoutReader.read(
                SourceText(
                    "scoped.mantra",
                    """
                (layout test/scoped {:language :en}
                  (table monthly {:style :transpose :row-dimension month :fixed {:quarter :$key}}
                    :label (node balance)))
                    """.trimIndent(),
                ),
            )
            ExcelExport.workbook(result, spec).use { export ->
                assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
                assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
                val address = CellReference(assertNotNull(export.aggregateAddress("balance", mapOf("quarter" to key))))
                val selected = export.workbook.getSheet(
                    address.sheetName,
                ).getRow(address.row).getCell(address.col.toInt())
                assertEquals(expected, selected.numericCellValue)
            }
        }
    }

    @Test
    fun `an inactive terminal period never selects an earlier active balance`() {
        val model =
            schema(
                """
            (schema test/inactive
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (line balance "Balance" 100 {:per year :when (< year.index 2) :aggregate {:last year}}))
        """,
            )
        ExcelExport.workbook(Mantra.calculateForAudit(model), Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(0.0, cell(export, "aggregate.balance").numericCellValue)
        }
    }

    @Test
    fun `boolean table fields retain false truthiness in records and vector closures`() {
        val model =
            schema(
                """
            (schema test/boolean-records
              (input facts :table {:columns {:id :keyword :enabled :boolean :amount :decimal}})
              (dimension member {:from facts :key :id})
              (line selected "Selected" (if member.enabled member.amount 0) {:per member :op :info})
              (line selected-vector "Vector" (sum (map (fn [entry] (if entry.enabled entry.amount 0)) facts))
                {:op :info}))
        """,
            )
        val input = Mantra.loadCase(
            SourceText(
                "boolean-case.mantra",
                """
            (case c (inputs {:facts [{:id :A :enabled false :amount 50000} {:id :B :enabled true :amount 10}]}))
                """.trimIndent(),
            ),
        )
        ExcelExport.workbook(Mantra.calculateForAudit(model, input), Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            assertEquals(0.0, cell(export, "selected", "A").numericCellValue)
            assertEquals(10.0, cell(export, "selected-vector").numericCellValue)
            val address = CellReference(assertNotNull(export.recordAddress("member", "A", "enabled")))
            export.workbook.getSheet(
                address.sheetName,
            ).getRow(address.row).getCell(address.col.toInt()).setCellValue(true)
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(50000.0, cell(export, "selected", "A").numericCellValue)
            assertEquals(50010.0, cell(export, "selected-vector").numericCellValue)
        }
    }
}
