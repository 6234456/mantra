package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.StyleFill
import com.xqiou.mantra.render.layout.StyleTone
import com.xqiou.mantra.render.layout.StyleWeight
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFColor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelStyleClassesTest {
    // Shared scenario with WorkbenchStyleClassesTest. Its independently locked bridge is
    // 12.5000 + 2.0000 - 1.2500 = 13.2500; informational rows contribute zero.
    private val schemaText = """
        (schema test/class-pipeline {:title "Class pipeline" :version "1" :mainline [main]}
          (input postings :table {:columns {:bucket :keyword :amount :decimal}})
          (input adjustment :decimal)
          (section main "Bridge" {:panel true :class :accent}
            (line base "Base" (table/sum-where postings {:bucket :base} :amount) {:class :source})
            (line additions "Additions" (table/sum-where postings {:bucket :addition} :amount)
              {:class [:accent :strong :subtle]})
            (subtract deduction "Deduction" adjustment {:class :muted})
            (total result "Result" {:class :key-result})
            (info note "Note" 0.2500 {:class [:note :future-tag]})
            (info plain "Unclassified" 5 {:class :future-tag})))
    """.trimIndent()
    private val caseText = """
        (case demo {:schema "test/class-pipeline" :layout "test/class-paper"}
          (inputs {:postings (rows [:bucket :amount] [:base 12.5000] [:addition 2.0000])
                   :adjustment 1.2500}))
    """.trimIndent()
    private val layoutText = """
        (layout test/class-paper {:preset :ifrs-schedule :locale "en-US" :precision 4
                                 :hide-zero false :style-preset [:utilities :working-paper]}
          (style-class :key-result {:weight :bold :tone :accent :fill :subtle})
          (style {:class :key-result :column :value}
            {:use [:strong :accent] :weight :normal :fill :accent})
          (table main :label :value))
    """.trimIndent()

    private fun cell(export: ExcelWorkbook, address: String?): XSSFCell {
        val reference = CellReference(assertNotNull(address))
        return export.workbook.getSheet(reference.sheetName).getRow(reference.row).getCell(reference.col.toInt())
    }

    private fun rgb(color: XSSFColor?): String = assertNotNull(assertNotNull(color).rgb)
        .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun `preset and custom classes reach HTML and XLSX without changing the exact bridge`() {
        val schema = Mantra.loadSchema(SourceText("schema.mantra", schemaText), SourceResolver { _, _ -> null })
        val facts = Mantra.loadCase(SourceText("case.mantra", caseText))
        val result = Mantra.calculateForAudit(schema, facts)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals("13.2500", result.decimal("result").toPlainString())
        val styled = LayoutReader.read(SourceText("layout.mantra", layoutText))
        val plain = LayoutReader.read(
            SourceText(
                "plain.mantra",
                "(layout test/plain {:preset :ifrs-schedule :locale \"en-US\" :precision 4 :hide-zero false}" +
                    " (table main :label :value))",
            ),
        )
        val paper = Render.paper(result, styled)
        val rows = paper.tables.single { it.id == "main" }.rows
        val total = rows.single { it.nodeId == "result" }
        assertEquals(StyleWeight.BOLD, total.style.weight)
        assertEquals(StyleTone.ACCENT, total.style.tone)
        assertEquals(StyleFill.SUBTLE, total.style.fill)
        val utilities = rows.single { it.nodeId == "additions" }
        assertEquals(StyleWeight.BOLD, utilities.style.weight)
        assertEquals(StyleTone.ACCENT, utilities.style.tone)
        assertEquals(StyleFill.SUBTLE, utilities.style.fill)
        val unknown = rows.single { it.nodeId == "plain" }
        assertEquals(listOf("future-tag"), unknown.classes)
        assertEquals(null, unknown.style.tone)
        val html = Render.html(result, styled)
        val totalHtml = assertNotNull(
            Regex("<tr class=\"[^\"]*\\bu-key-result\\b[^\"]*\"[^>]*>.*?</tr>", RegexOption.DOT_MATCHES_ALL)
                .find(html),
        ).value
        assertContains(
            totalHtml,
            "data-column=\"label\" style=\"font-weight:700;color:var(--accent);background:var(--heading)\"",
        )
        assertContains(
            totalHtml,
            "data-column=\"value\" style=\"font-weight:400;color:var(--accent);" +
                "background:color-mix(in srgb,var(--accent) 10%,var(--paper))\"",
        )
        assertContains(totalHtml, ">13.2500</td>")
        assertEquals(Render.text(result, plain), Render.text(result, styled))
        ExcelExport.workbook(result, styled).use { export ->
            assertTrue(export.report.fallbacks.isEmpty(), export.report.fallbacks.toString())
            assertTrue(export.report.evaluationErrors.isEmpty(), export.report.evaluationErrors.toString())
            val value = cell(export, export.address("result"))
            assertEquals(13.25, value.numericCellValue)
            assertFalse(value.cellStyle.font.bold)
            assertEquals("1F5FBF", rgb(value.cellStyle.font.xssfColor))
            assertEquals("EEF3FA", rgb(value.cellStyle.fillForegroundXSSFColor))
            val label = value.row.getCell(0)
            assertTrue(label.cellStyle.font.bold)
            assertEquals("1F5FBF", rgb(label.cellStyle.font.xssfColor))
            assertEquals("E7EAEE", rgb(label.cellStyle.fillForegroundXSSFColor))
            val addition = cell(export, export.address("additions"))
            assertTrue(addition.cellStyle.font.bold)
            assertEquals("1F5FBF", rgb(addition.cellStyle.font.xssfColor))
            assertEquals("E7EAEE", rgb(addition.cellStyle.fillForegroundXSSFColor))
            cell(export, export.tableAddress("postings", 1, "amount")).setCellValue(3.5)
            export.workbook.creationHelper.createFormulaEvaluator().apply {
                clearAllCachedResultValues()
                evaluateAll()
            }
            assertEquals(14.75, value.numericCellValue)
            assertFalse(value.cellStyle.font.bold)
            assertEquals("EEF3FA", rgb(value.cellStyle.fillForegroundXSSFColor))
        }
        ExcelExport.workbook(result, plain).use { export ->
            assertEquals(13.25, cell(export, export.address("result")).numericCellValue)
        }
        assertEquals(facts.inputs, result.view.case.inputs)
        assertEquals("13.2500", result.decimal("result").toPlainString())
    }
}
