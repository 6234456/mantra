package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellation
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live workbook mutations are checked against independent expected values, without rerunning Mantra. */
class ExcelDynamicHardeningTest {
    private fun export(
        schema: String,
        facts: String,
        layout: String? = null,
        reading: CalculationOptions = CalculationOptions(),
    ): ExcelWorkbook {
        val result = Mantra.calculateForAudit(
            Mantra.loadSchema(SourceText("dynamic.mantra", schema.trimIndent()), SourceResolver { _, _ -> null }),
            Mantra.loadCase(SourceText("facts.mantra", "(case current (inputs $facts))")),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        val output = ExcelExport.workbook(
            result,
            layout?.let { LayoutReader.read(SourceText("layout.mantra", it)) } ?: Presets.IFRS_SCHEDULE,
            ExcelOptions(dynamicTableCapacities = mapOf("records" to 3), reading = reading),
        )
        assertEquals(emptyList(), output.report.fallbacks)
        assertEquals(emptyList(), output.report.evaluationErrors)
        return output
    }
    private fun cell(output: ExcelWorkbook, address: String): XSSFCell = CellReference(address).let {
        output.workbook.getSheet(it.sheetName).getRow(it.row).getCell(it.col.toInt())
    }
    private fun recalc(output: ExcelWorkbook) {
        output.workbook.creationHelper.createFormulaEvaluator().apply {
            clearAllCachedResultValues()
            evaluateAll()
        }
    }
    private fun value(output: ExcelWorkbook, node: String, vararg coord: String) =
        cell(output, assertNotNull(output.address(node, coord.toList()))).numericCellValue
    private fun record(id: String, base: Int, cap: Int = 0, note: String? = null): Map<String, Value> = mapOf(
        "id" to Value.Kw(id),
        "base" to Value.Num(base.toBigDecimal()),
        "cap" to Value.Num(cap.toBigDecimal()),
        "note" to (note?.let { Value.Text(it) } ?: Value.Nil),
    )
    private fun simpleRecord(id: String, base: Int) = mapOf(
        "id" to Value.Kw(id),
        "base" to Value.Num(base.toBigDecimal()),
    )
    private fun decision(output: ExcelWorkbook, caption: String): XSSFCell =
        assertNotNull(output.workbook.getSheet("Checks")).first { row ->
            val title = row.getCell(0)
            title?.cellType == CellType.STRING && title.stringCellValue == caption
        }.getCell(2) as XSSFCell

    @Test
    fun `fixed public member scope continues selecting A after rows move`() {
        val schema = """
            (schema test/dynamic-fixed {}
              (input records :table {:columns {:id :keyword :base :decimal}})
              (dimension item {:from records :key :id})
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (section bridge "Bridge" {:per [item year]}
                (line opening "Opening" (prev closing item.base) {:op :info :aggregate {:first year}})
                (line movement "Movement" (if (= year.key :P1) 10 20) {:op :info})
                (line closing "Closing" (+ opening movement) {:aggregate {:last year}})))
        """
        export(
            schema,
            "{:records [{:id :A :base 100} {:id :B :base 50}]}",
            "(layout fixed {:language :en} (table bridge {:style :transpose :row-dimension year :fixed {:item :A}} :label (node closing)))",
        ).use { output ->
            val fixed = assertNotNull(output.aggregateAddress("closing", mapOf("item" to "A")))
            assertEquals(130.0, cell(output, fixed).numericCellValue)
            val originalA = assertNotNull(output.address("closing", listOf("A", "P2")))
            output.replaceDynamicTableRows("records", listOf(simpleRecord("B", 50), simpleRecord("A", 100)))
            recalc(output)
            assertEquals(130.0, cell(output, fixed).numericCellValue)
            assertEquals(130.0, value(output, "closing", "A", "P2"))
            assertNotEquals(originalA, output.address("closing", listOf("A", "P2")))
            output.removeDynamicTableRow("records", 1)
            recalc(output)
            assertNull(output.address("closing", listOf("A", "P2")))
            assertEquals(0.0, cell(output, fixed).numericCellValue)
        }
    }

    @Test
    fun `normal member labels move with their physical values`() {
        val schema = """
            (schema test/dynamic-labels {}
              (input records :table {:columns {:id :keyword :base :decimal}})
              (dimension item {:from records :key :id})
              (section detail "Detail" {:per item} (line displayed "Displayed" item.base)))
        """
        export(
            schema,
            "{:records [{:id :A :base 7} {:id :B :base 9}]}",
            "(layout members {:language :en} (table detail {:style :transpose :row-dimension item} :label (node displayed)))",
        ).use { output ->
            val originalA = cell(output, assertNotNull(output.address("displayed", listOf("A"))))
            val oldLabel = originalA.row.getCell(0)
            assertEquals("A", oldLabel.stringCellValue)
            output.replaceDynamicTableRows("records", listOf(simpleRecord("B", 9), simpleRecord("A", 7)))
            recalc(output)
            assertEquals("B", oldLabel.stringCellValue)
            assertEquals(9.0, originalA.numericCellValue)
            assertEquals(7.0, value(output, "displayed", "A"))
        }
    }

    @Test
    fun `reserved slots never lower a positive minimum or raise a negative maximum`() {
        val schema = """
            (schema test/dynamic-extremes {}
              (input records :table {:columns {:id :keyword :base :decimal}})
              (dimension item {:from records :key :id})
              (section detail "Detail" {:per item} (line measured "Measured" item.base {:op :info}))
              (section checks "Checks"
                (line smallest "Smallest" (dim/min measured) {:op :info})
                (line largest "Largest" (dim/max measured) {:op :info})
                (check empty-min "Empty minimum" (nil? (dim/min measured)))))
        """
        export(schema, "{:records [{:id :A :base 7} {:id :B :base 9}]}").use { output ->
            assertEquals(7.0, value(output, "smallest"))
            assertEquals(9.0, value(output, "largest"))
            output.replaceDynamicTableRows("records", listOf(simpleRecord("A", -7), simpleRecord("B", -9)))
            recalc(output)
            assertEquals(-9.0, value(output, "smallest"))
            assertEquals(-7.0, value(output, "largest"))
            output.replaceDynamicTableRows("records", emptyList())
            recalc(output)
            assertEquals("✓", decision(output, "Empty minimum").stringCellValue)
        }
    }

    @Test
    fun `allocators preserve real keys and current waterfall priority through insertion and reorder`() {
        val schema = """
            (schema test/dynamic-allocations {}
              (input records :table {:columns {:id :keyword :base :decimal :cap :decimal :note :text?}})
              (dimension item {:from records :key :id})
              (section shares "Shares" {:per item}
                (line weights "Weights" item.base {:op :info})
                (line caps "Caps" item.cap {:op :info})
                (line pro "Pro rata" (alloc/pro-rata 30 weights 2) {:spread true :op :info})
                (line capped "Capped" (alloc/capped 30 weights {:A 3 :B 100 :C 100} 2) {:spread true :op :info})
                (line waterfall "Waterfall" (alloc/waterfall 10 caps) {:spread true :op :info}))
              (section checks "Checks"
                (line selected-a "Selected A" (get pro :A) {:op :info})))
        """
        export(schema, "{:records [{:id :A :base 1 :cap 3} {:id :B :base 2 :cap 4}]}").use { output ->
            assertEquals(10.0, value(output, "pro", "A"))
            assertEquals(20.0, value(output, "pro", "B"))
            assertEquals(3.0, value(output, "capped", "A"))
            assertEquals(27.0, value(output, "capped", "B"))
            output.insertDynamicTableRow("records", 2, record("C", 3, 5))
            recalc(output)
            assertEquals(5.0, value(output, "pro", "A"))
            assertEquals(10.0, value(output, "pro", "B"))
            assertEquals(15.0, value(output, "pro", "C"))
            assertEquals(10.8, value(output, "capped", "B"), 1e-9)
            assertEquals(16.2, value(output, "capped", "C"), 1e-9)
            assertEquals(3.0, value(output, "waterfall", "C"))
            output.replaceDynamicTableRows("records", listOf(record("C", 3, 5), record("A", 1, 3), record("B", 2, 4)))
            recalc(output)
            assertEquals(5.0, value(output, "selected-a"))
            assertEquals(3.0, value(output, "capped", "A"))
            assertEquals(5.0, value(output, "waterfall", "C"))
            assertEquals(3.0, value(output, "waterfall", "A"))
            assertEquals(2.0, value(output, "waterfall", "B"))
        }
    }

    @Test
    fun `separately keyed facts follow their member and new keys cannot borrow old facts`() {
        val schema = """
            (schema test/dynamic-facts {}
              (input records :table {:columns {:id :keyword :base :decimal}})
              (dimension item {:from records :key :id})
              (input extra :decimal {:per item :default 0 :required true})
              (section detail "Detail" {:per item} (line combined "Combined" (+ item.base extra))))
        """
        export(schema, "{:records [{:id :A :base 1} {:id :B :base 2}] :extra {:A 100 :B 200}}").use { output ->
            output.replaceDynamicTableRows("records", listOf(simpleRecord("B", 2), simpleRecord("A", 1)))
            recalc(output)
            assertEquals(101.0, value(output, "combined", "A"))
            assertEquals(202.0, value(output, "combined", "B"))
            output.replaceDynamicTableRows("records", listOf(simpleRecord("C", 3), simpleRecord("B", 2)))
            recalc(output)
            assertEquals(3.0, value(output, "combined", "C"))
            assertNull(output.address("extra", listOf("C")))
            val table = output.workbook.getSheet("Keyed extra").tables.single()
            val row = table.xssfSheet.getRow(table.startRowIndex + 3)
            row.getCell(0).setCellValue("C")
            row.getCell(1).setCellValue(300.0)
            row.getCell(2).setCellValue(true)
            recalc(output)
            assertEquals(303.0, value(output, "combined", "C"))
            assertEquals(100.0, cell(output, assertNotNull(output.address("extra", listOf("A")))).numericCellValue)
            assertNotNull(output.address("extra", listOf("C")))
            assertEquals("outdated", cell(output, assertNotNull(output.auditSnapshotStatusAddress())).stringCellValue)
        }
    }

    @Test
    fun `duplicate keys missing keys and blank holes produce live errors and can recover`() {
        val schema = """
            (schema test/dynamic-integrity {}
              (input records :table {:columns {:id :keyword :base :decimal}})
              (dimension item {:from records :key :id})
              (section detail "Detail" {:per item} (line measured "Measured" item.base)))
        """
        export(schema, "{:records [{:id :A :base 7} {:id :B :base 9}]}").use { output ->
            val total = cell(output, assertNotNull(output.aggregateAddress("measured")))
            for (bad in listOf(
                listOf(simpleRecord("A", 7), simpleRecord("A", 9)),
                listOf(mapOf("base" to Value.Num(7.toBigDecimal())), simpleRecord("B", 9)),
                listOf(simpleRecord("A", 7), emptyMap(), simpleRecord("B", 9)),
                listOf(mapOf("id" to Value.Kw("A"), "base" to Value.Text("not a decimal")), simpleRecord("B", 9)),
            )) {
                output.replaceDynamicTableRows("records", bad)
                recalc(output)
                assertEquals(CellType.ERROR, total.cachedFormulaResultType)
            }
            output.replaceDynamicTableRows("records", listOf(simpleRecord("B", 9), simpleRecord("A", 7)))
            recalc(output)
            assertEquals(16.0, total.numericCellValue)
        }
    }

    @Test
    fun `table minimum rows and conditional columns inspect newly added physical rows`() {
        val schema = """
            (schema test/dynamic-business {}
              (input records :table {:required true :min-rows 2 :columns
                {:id :keyword :base :decimal :cap :decimal :note {:type :text :optional true :required-when (> row.base 0)}}})
              (dimension item {:from records :key :id})
              (section detail "Detail" {:per item} (line measured "Measured" item.base)))
        """
        export(
            schema,
            "{:records [{:id :A :base 7 :cap 0 :note \"fact A\"} {:id :B :base 9 :cap 0 :note \"fact B\"}]}",
        ).use { output ->
            assertEquals("✓", decision(output, "records · minimum rows").stringCellValue)
            output.removeDynamicTableRow("records", 1)
            recalc(output)
            assertEquals("✗", decision(output, "records · minimum rows").stringCellValue)
            output.insertDynamicTableRow("records", 1, record("C", 3))
            recalc(output)
            assertEquals("✓", decision(output, "records · minimum rows").stringCellValue)
            assertEquals("✗", decision(output, "records [2] · note").stringCellValue)
            output.replaceDynamicTableRows("records", listOf(record("A", 7, note = "fact A"), record("C", 0)))
            recalc(output)
            assertEquals("✓", decision(output, "records [2] · note").stringCellValue)
            output.replaceDynamicTableRows("records", emptyList())
            recalc(output)
            assertEquals("✗", decision(output, "records · required").stringCellValue)
            assertEquals("", decision(output, "records [1] · note").stringCellValue)
        }
    }

    @Test
    fun `published workbook edits do not retain or call a closed read control`() {
        val polls = AtomicInteger()
        val expired = AtomicBoolean(false)
        val reading = CalculationOptions(
            control = RunControl(
                RunCancellation {
                    polls.incrementAndGet()
                    expired.get()
                },
            ),
        )
        val schema = """
            (schema test/dynamic-lifetime {}
              (input records :table {:columns {:id :keyword :base :decimal}})
              (dimension item {:from records :key :id})
              (section detail "Detail" {:per item} (line measured "Measured" item.base)))
        """
        export(schema, "{:records [{:id :A :base 7} {:id :B :base 9}]}", reading = reading).use { output ->
            val finishedPolls = polls.get()
            assertTrue(finishedPolls > 0)
            expired.set(true)
            output.insertDynamicTableRow("records", 2, simpleRecord("C", 11))
            output.replaceDynamicTableRows("records", output.dynamicTableRows("records").reversed())
            recalc(output)
            assertEquals(27.0, cell(output, assertNotNull(output.aggregateAddress("measured"))).numericCellValue)
            assertEquals(7.0, value(output, "measured", "A"))
            assertNotNull(output.bytes())
            assertEquals(finishedPolls, polls.get())
        }
    }
}
