package com.xqiou.mantra.render

import com.xqiou.mantra.render.layout.Align
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.StyleFill
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.StyleTone
import com.xqiou.mantra.render.layout.StyleWeight
import com.xqiou.mantra.render.layout.TableStyle
import com.xqiou.mantra.render.layout.Texts
import com.xqiou.mantra.render.paper.AuditEntry
import com.xqiou.mantra.render.paper.PaperColumn
import com.xqiou.mantra.render.paper.PaperRow
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowKind
import com.xqiou.mantra.render.paper.WorkingPaper
import com.xqiou.mantra.render.pdf.PdfOptions
import com.xqiou.mantra.render.pdf.PdfRenderException
import com.xqiou.mantra.render.pdf.PdfRenderer
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfStyleRendererTest {
    private data class TextDraw(val text: String, val font: PDFont, val ink: Int, val x: Float, val y: Float)
    private data class Rectangle(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun covers(text: TextDraw) = text.x + 1 in x..(x + width) && text.y in y..(y + height)
    }
    private data class FillDraw(val rectangle: Rectangle, val color: Int)
    private data class PageDraw(val text: List<TextDraw>, val fills: List<FillDraw>) {
        fun cell(label: String) = text.single { it.text == label }
        fun fill(text: TextDraw) = fills.lastOrNull { it.rectangle.covers(text) }?.color
    }

    /** Inspect the emitted PDF operators, including the embedded font's actual glyph mapping. */
    private fun inspect(page: PDPage): PageDraw {
        val text = mutableListOf<TextDraw>()
        val fills = mutableListOf<FillDraw>()
        val operands = mutableListOf<COSBase>()
        val rectangles = mutableListOf<Rectangle>()
        var font: PDFont? = null
        var ink = 0
        var x = 0f
        var y = 0f
        fun number(index: Int) = (operands[index] as COSNumber).floatValue()
        for (token in PDFStreamParser(page).parse()) {
            if (token !is Operator) {
                operands += token as COSBase
                continue
            }
            when (token.name) {
                "BT" -> {
                    x = 0f
                    y = 0f
                }
                "Tf" -> font = page.resources.getFont(operands[0] as COSName)
                "cs" -> assertEquals(COSName.DEVICERGB, operands[0])
                "rg", "sc" -> {
                    assertEquals(3, operands.size)
                    ink = (0..2).fold(0) { color, index ->
                        (color shl 8) or (number(index) * 255).roundToInt()
                    }
                }
                "Td" -> {
                    x += number(0)
                    y += number(1)
                }
                "Tj" -> {
                    val current = assertNotNull(font)
                    val decoded = buildString {
                        ByteArrayInputStream((operands[0] as COSString).bytes).use { bytes ->
                            while (bytes.available() > 0) append(current.toUnicode(current.readCode(bytes)))
                        }
                    }
                    text += TextDraw(decoded, current, ink, x, y)
                }
                "re" -> rectangles += Rectangle(number(0), number(1), number(2), number(3))
                "f", "f*" -> {
                    fills += rectangles.map { FillDraw(it, ink) }
                    rectangles.clear()
                }
                "S", "n" -> rectangles.clear()
            }
            operands.clear()
        }
        return PageDraw(text, fills)
    }

    private fun paper(rows: List<PaperRow>, audit: Boolean = false) = WorkingPaper(
        "Resolved styles", null, emptyList(), emptyList(), emptyList(),
        listOf(
            PaperTable(
                "styled",
                "1",
                "Styled table",
                null,
                TableStyle.MATRIX,
                listOf(
                    PaperColumn("label", "Member label", ColumnContent.Label, Align.LEFT, null),
                    PaperColumn("amount", "Exact amount", ColumnContent.Value, Align.RIGHT, null),
                ),
                rows,
            ),
        ),
        if (audit) {
            listOf(AuditEntry("audit", "1/1", "UNIQUE-AUDIT", null, "2 + 3", "2 + 3", "5.00", null))
        } else {
            emptyList()
        },
        emptyList(), emptyList(), Texts.EN, "working-paper",
    )

    @Test
    fun `row and scoped cell styles control actual PDF font ink and fill with explicit resets`() {
        val rowStyle = StyleSpec(StyleWeight.BOLD, StyleTone.MUTED, StyleFill.SUBTLE)
        val rows = listOf(
            PaperRow(RowKind.VALUE, 0, listOf("ROW-FALLBACK", "ROW-AMOUNT"), style = rowStyle),
            PaperRow(
                RowKind.VALUE,
                0,
                listOf("CELL-RESET", "CELL-TONE"),
                style = rowStyle,
                cellStyles = listOf(
                    StyleSpec(StyleWeight.NORMAL, StyleTone.DEFAULT, StyleFill.NONE),
                    StyleSpec(tone = StyleTone.ACCENT),
                ),
            ),
            PaperRow(
                RowKind.VALUE,
                0,
                listOf("DEFAULT-VALUE", "CELL-ACCENT"),
                cellStyles = listOf(StyleSpec(), StyleSpec(StyleWeight.BOLD, StyleTone.ACCENT, StyleFill.ACCENT)),
            ),
            PaperRow(
                RowKind.TOTAL,
                0,
                listOf("TOTAL-RESET", "TOTAL-AMOUNT"),
                style = StyleSpec(StyleWeight.NORMAL, StyleTone.DEFAULT, StyleFill.NONE),
            ),
            PaperRow(RowKind.RESULT, 0, listOf("BUILTIN-RESULT", "BUILTIN-AMOUNT")),
        )
        Loader.loadPDF(PdfRenderer.render(paper(rows))).use { document ->
            assertEquals(1, document.numberOfPages)
            val page = inspect(document.getPage(0))
            fun check(label: String, bold: Boolean, ink: Int, fill: Int?) {
                val cell = page.cell(label)
                assertEquals(bold, cell.font.name.contains("Bold"), "$label font")
                assertEquals(ink, cell.ink, "$label ink")
                assertEquals(fill, page.fill(cell), "$label fill")
            }
            check("ROW-FALLBACK", true, 0x6B7280, 0xE7EAEE)
            check("ROW-AMOUNT", true, 0x6B7280, 0xE7EAEE)
            check("CELL-RESET", false, 0x172630, null)
            check("CELL-TONE", true, 0x1F5FBF, 0xE7EAEE)
            check("DEFAULT-VALUE", false, 0x172630, null)
            check("CELL-ACCENT", true, 0x1F5FBF, 0xEEF3FA)
            check("TOTAL-RESET", false, 0x172630, null)
            check("TOTAL-AMOUNT", false, 0x172630, null)
            check("BUILTIN-RESULT", true, 0x172630, 0xE8EFF2)
        }
    }

    @Test
    fun `styled wrapped cells paginate with their selected font repeated headers and audit controls`() {
        val style = StyleSpec(StyleWeight.BOLD, StyleTone.ACCENT, StyleFill.SUBTLE)
        val rows = listOf(
            PaperRow(
                RowKind.VALUE,
                0,
                listOf("WRAPPED-START " + "M".repeat(10_000) + " WRAPPED-END", "5.00"),
                style = style,
            ),
        ) + List(40) { index ->
            PaperRow(RowKind.VALUE, 0, listOf("Styled item ${index + 1}", "10.00"), style = style)
        }
        val paper = paper(rows, audit = true)
        val bytes = PdfRenderer.render(paper, PdfOptions(includeAudit = false, maxRows = rows.size))
        Loader.loadPDF(bytes).use { document ->
            assertTrue(document.numberOfPages >= 4)
            val allText = PDFTextStripper().getText(document)
            assertTrue(allText.contains("WRAPPED-START"))
            assertTrue(allText.contains("WRAPPED-END"))
            assertTrue(allText.contains("Styled item 40"))
            assertFalse(allText.contains("UNIQUE-AUDIT"))
            var wrappedPages = 0
            document.pages.forEachIndexed { index, page ->
                val draws = inspect(page)
                assertTrue(draws.text.any { it.text == "Member label" }, "page ${index + 1} header")
                assertTrue(draws.text.any { it.text == "Exact amount" }, "page ${index + 1} header")
                val fragments = draws.text.filter { it.text.isNotEmpty() && it.text.all { point -> point == 'M' } }
                if (fragments.isNotEmpty()) wrappedPages++
                fragments.forEach { fragment ->
                    assertTrue(fragment.font.name.contains("Bold"))
                    assertEquals(0x1F5FBF, fragment.ink)
                    assertEquals(0xE7EAEE, draws.fill(fragment))
                    val available = (page.mediaBox.width - 76f) * .38f - 10f
                    assertTrue(fragment.font.getStringWidth(fragment.text) * 8f / 1000f <= available + .1f)
                }
                assertTrue(allText.contains("${index + 1} / ${document.numberOfPages}"))
            }
            assertTrue(wrappedPages >= 3)
        }
        Loader.loadPDF(PdfRenderer.render(paper)).use { document ->
            assertTrue(PDFTextStripper().getText(document).contains("UNIQUE-AUDIT"))
        }
        listOf(PdfOptions(maxPages = 1), PdfOptions(maxRows = rows.size)).forEach { options ->
            assertEquals(
                "MANTRA-PDF-LIMIT",
                assertFailsWith<PdfRenderException> { PdfRenderer.render(paper, options) }.code,
            )
        }
    }
}
