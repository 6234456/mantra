package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.normein.dsl.form.DslFormReadResult
import com.xqiou.normein.dsl.form.DslFormReader
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelHigherOrderTest {
    private fun cell(export: ExcelWorkbook, address: String?): XSSFCell {
        val reference = CellReference(assertNotNull(address))
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    @Test
    fun `ordered period map materializes a vector before discounted cash flows and recomputes edits`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "cash-flow.mantra",
                """
                (schema test/cash-flow
                  (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
                  (input rate :decimal {:default 0.10})
                  (input payments :table {:columns {:period :keyword :amount :decimal}})
                  (section valuation "Valuation"
                    (line present-value "Present value"
                      (fin/npv rate
                        (vec (map (fn [period-key]
                          (let [amount (sum (map (fn [entry]
                            (if (= entry.period period-key) entry.amount 0)) payments))]
                            (if (nil? amount) 0 amount)))
                          periods.year.keys)) 2))))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val input = Mantra.loadCase(
            SourceText(
                "cash-flow-case.mantra",
                """
                (case c (inputs {:payments [{:period :P3 :amount 300}
                                           {:period :P1 :amount 100}
                                           {:period :P2 :amount 200}]}))
                """.trimIndent(),
            ),
        )
        val result = Mantra.calculateForAudit(schema, input)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals("481.59", result.decimal("present-value").toPlainString())
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            val discounted = cell(export, export.address("present-value"))
            assertEquals(481.59, discounted.numericCellValue)
            cell(export, export.tableAddress("payments", 2, "amount")).setCellValue(500.0)
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(729.53, discounted.numericCellValue)
            cell(export, export.address("rate")).setCellValue(0.0)
            export.workbook.creationHelper.createFormulaEvaluator().evaluateAll()
            assertEquals(900.0, discounted.numericCellValue)
        }
    }

    @Test
    fun `vector conversion preserves sequence order and rejects unsupported scalar and map shapes`() {
        val resolver = object : ExcelResolver {
            override fun reference(nodeId: String, contextDims: List<String>, contextCoord: Coord): X? = null
            override fun record(dim: String, key: String, field: String): X? = null
            override fun isNode(nodeId: String) = false
            override fun isDimension(name: String) = false
        }
        val translator = FormulaTranslator(resolver, emptyList())
        fun translate(source: String): X {
            val parsed = assertIs<DslFormReadResult.Success>(DslFormReader().readDocument(source))
            return translator.translate(parsed.document.root, FormulaTranslator.Ctx(emptyList(), emptyList()))
        }
        assertEquals(
            listOf("3", "1", "2"),
            assertIs<X.Vec>(translate("(vec [3 1 2])")).items.map {
                assertIs<X.Scalar>(it).text
            },
        )
        assertEquals(emptyList(), assertIs<X.Vec>(translate("(vec nil)")).items)
        listOf("(vec 1)", "(vec true)", "(vec \"abc\")", "(vec {:A 1 :B 2})").forEach { source ->
            assertFailsWith<Untranslatable>(source) { translate(source) }
        }
    }
}
