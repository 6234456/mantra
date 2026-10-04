package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExcelDynamicTableTest {
    private val source = """
        (schema test/dynamic {:mainline [bridge]}
          (input records :table {:columns {:id :keyword :group :keyword :opening :decimal :enabled :boolean}})
          (input movements :table {:columns {:id :keyword :period :keyword :amount :decimal}})
          (dimension group {:members [:G1 :G2]})
          (dimension item {:from records :key :id :parent group :parent-key :group})
          (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
          (section bridge "Bridge" {:per [item year]}
            (line opening "Opening" (prev closing item.opening) {:op :info :aggregate {:first year} :when item.enabled})
            (line movement "Movement"
              (let [v (sum (map (fn [entry] (if (and (= entry.id item.key) (= entry.period year.key)) entry.amount 0)) movements))]
                (if (nil? v) 0 v)) {:op :info :when item.enabled})
            (line closing "Closing" (+ opening movement) {:aggregate {:last year} :when item.enabled})))
    """.trimIndent()
    private val facts = """{:records [{:id :A :group :G1 :opening 100 :enabled true} {:id :B :group :G1 :opening 50 :enabled true}]
      :movements [{:id :A :period :P1 :amount 10} {:id :A :period :P2 :amount 20}
                  {:id :B :period :P1 :amount 5} {:id :B :period :P2 :amount 5}]}"""
    private fun export() = ExcelExport.workbook(
        Mantra.calculateForAudit(
            Mantra.loadSchema(SourceText("dynamic.mantra", source), SourceResolver { _, _ -> null }),
            Mantra.loadCase(SourceText("case.mantra", "(case dynamic (inputs $facts))")),
        ).also { assertTrue(it.succeeded, it.diagnostics.toString()) },
        LayoutReader.read(
            SourceText(
                "layout.mantra",
                "(layout dynamic {:language :en :hide-zero false} (table bridge {:style :matrix :row-dimension item} :label (members year) :cross-total))",
            ),
        ),
        ExcelOptions(dynamicTableCapacities = mapOf("records" to 3, "movements" to 8)),
    )
    private fun cell(wb: XSSFWorkbook, address: String) = CellReference(address).let {
        wb.getSheet(it.sheetName).getRow(it.row).getCell(it.col.toInt())
    }
    private fun recalc(export: ExcelWorkbook) {
        export.workbook.creationHelper.createFormulaEvaluator().apply {
            clearAllCachedResultValues()
            evaluateAll()
        }
    }
    private fun total(export: ExcelWorkbook, node: String) =
        cell(export.workbook, assertNotNull(export.aggregateAddress(node))).numericCellValue
    private fun record(id: String, group: String, opening: Int, enabled: Boolean = true) = mapOf(
        "id" to Value.Kw(id),
        "group" to Value.Kw(group),
        "opening" to Value.Num(opening.toBigDecimal()),
        "enabled" to Value.Bool(enabled),
    )
    private fun movement(id: String, period: String, amount: Int) = mapOf(
        "id" to Value.Kw(id),
        "period" to Value.Kw(period),
        "amount" to Value.Num(amount.toBigDecimal()),
    )

    @Test
    fun `insert delete and reorder real members preserve rolling values and current addresses`() {
        export().use { output ->
            assertEquals(emptyList(), output.report.fallbacks)
            assertEquals(emptyList(), output.report.evaluationErrors)
            assertEquals(2, output.dynamicTable("records")!!.rows)
            assertEquals(190.0, total(output, "closing"))
            val oldA = assertNotNull(output.address("closing", listOf("A", "P2")))
            output.insertDynamicTableRow("records", 2, record("C", "G2", 30))
            output.insertDynamicTableRow("movements", 4, movement("C", "P1", -10))
            output.insertDynamicTableRow("movements", 5, movement("C", "P2", 5))
            recalc(output)
            assertEquals(3, output.dynamicTable("records")!!.rows)
            assertEquals(215.0, total(output, "closing"))
            assertEquals(
                25.0,
                cell(output.workbook, assertNotNull(output.address("closing", listOf("C", "P2")))).numericCellValue,
            )
            assertEquals(
                "outdated",
                cell(output.workbook, assertNotNull(output.auditSnapshotStatusAddress())).stringCellValue,
            )
            output.removeDynamicTableRow("records", 0)
            recalc(output)
            assertEquals(85.0, total(output, "closing"))
            assertNull(output.address("closing", listOf("A", "P2")))
            assertNull(output.recordAddress("item", "A", "opening"))
            output.replaceDynamicTableRows("records", output.dynamicTableRows("records").reversed())
            recalc(output)
            assertEquals(85.0, total(output, "closing"))
            assertEquals(oldA, output.address("closing", listOf("C", "P2")))
            assertEquals(
                25.0,
                cell(output.workbook, assertNotNull(output.address("closing", listOf("C", "P2")))).numericCellValue,
            )
            XSSFWorkbook(output.bytes().inputStream()).use { reopened ->
                reopened.creationHelper.createFormulaEvaluator().apply {
                    clearAllCachedResultValues()
                    evaluateAll()
                }
                assertEquals(85.0, cell(reopened, assertNotNull(output.aggregateAddress("closing"))).numericCellValue)
                assertNotNull(reopened.getSheet("Dynamic matrix"))
                assertNotNull(reopened.getSheet("Dynamic transpose"))
            }
        }
    }

    @Test
    fun `false zero empty and capacity overflow are live rather than cached`() {
        export().use { output ->
            output.replaceDynamicTableRows("records", listOf(record("A", "G1", 0), record("B", "G1", 50, false)))
            recalc(output)
            assertEquals(30.0, total(output, "closing"))
            output.replaceDynamicTableRows("records", emptyList())
            recalc(output)
            assertEquals(0.0, total(output, "closing"))
            output.replaceDynamicTableRows(
                "records",
                listOf(record("A", "G1", 100), record("B", "G1", 50), emptyMap(), record("C", "G2", 30)),
            )
            recalc(output)
            val total = cell(output.workbook, assertNotNull(output.aggregateAddress("closing")))
            assertEquals(CellType.ERROR, total.cachedFormulaResultType)
            output.replaceDynamicTableRows("records", listOf(record("A", "G1", 100), record("B", "G1", 50)))
            recalc(output)
            assertEquals(190.0, total(output, "closing"))
            assertEquals(
                "current",
                cell(output.workbook, assertNotNull(output.auditSnapshotStatusAddress())).stringCellValue,
            )
        }
    }
}
