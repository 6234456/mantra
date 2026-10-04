package com.xqiou.mantra.render

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.render.layout.Align
import com.xqiou.mantra.render.layout.ColumnContent
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
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfRendererTest {
    private fun paper(rows: Int = 120, title: String = "Printable working paper · Euro € and Σ") = WorkingPaper(
        title, "Independent synthetic pagination fixture", listOf("Period" to "2026"), emptyList(), emptyList(),
        listOf(
            PaperTable(
                "details",
                "1",
                "Long detail table",
                null,
                TableStyle.MATRIX,
                listOf(
                    PaperColumn("label", "Member label", ColumnContent.Label, Align.LEFT, null),
                    PaperColumn("amount", "Exact amount", ColumnContent.Value, Align.RIGHT, null),
                ),
                List(rows) { index ->
                    PaperRow(RowKind.VALUE, 0, listOf("Member ${index + 1} · Überprüfung ✓", "${(index + 1) * 10}.00"))
                },
            ),
        ),
        listOf(
            AuditEntry(
                "audit-final",
                "1/120",
                "Final independent amount",
                "R120",
                "120 * 10",
                "120 × 10",
                "1200.00",
                "Synthetic fixture",
            ),
        ),
        emptyList(), emptyList(), Texts.EN, "working-paper",
    )
    private fun save(name: String, bytes: ByteArray) {
        val path = Path.of("mantra-render/build/out/pdf/$name.pdf")
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }

    @Test fun `bounded actual pages repeat headings preserve unicode values and include audit appendix`() {
        val bytes = PdfRenderer.render(paper())
        save("long-paper", bytes)
        Loader.loadPDF(bytes).use { document ->
            assertTrue(document.numberOfPages >= 4)
            val text = PDFTextStripper().getText(document)
            assertTrue(text.contains("Überprüfung ✓"))
            assertTrue(text.contains("Member 120"))
            assertTrue(text.contains("1200.00"))
            assertTrue(text.contains("120 × 10"))
            assertTrue(text.contains("Synthetic fixture"))
            for (index in 1..document.numberOfPages) assertTrue(text.contains("$index / ${document.numberOfPages}"))
            document.pages.forEach { page ->
                assertTrue(page.resources.fontNames.any { page.resources.getFont(it).isEmbedded })
                assertTrue(page.mediaBox.width > 500)
            }
        }
        assertEquals(
            "MANTRA-PDF-LIMIT",
            assertFailsWith<PdfRenderException> {
                PdfRenderer.render(paper(), PdfOptions(maxPages = 1))
            }.code,
        )
        assertEquals(
            "MANTRA-PDF-LIMIT",
            assertFailsWith<PdfRenderException> {
                PdfRenderer.render(paper(), PdfOptions(maxOutputBytes = 100))
            }.code,
        )
        assertEquals(
            "MANTRA-PDF-GLYPH",
            assertFailsWith<PdfRenderException> {
                PdfRenderer.render(paper(1, "Unsupported glyph 中文"))
            }.code,
        )
    }

    @Test fun `public renderer exports the real period application and audit from its calculation`() {
        val root = Path.of("apps/ifrs-leases")
        val result = Mantra.calculateForAudit(
            Mantra.loadSchema(root.resolve("schema.mantra")),
            Mantra.loadCase(root.resolve("case-demo.mantra")),
        )
        assertTrue(result.succeeded)
        val bytes = Render.pdf(result, Render.loadLayout(root.resolve("layout.mantra")))
        save("lease-paper", bytes)
        Loader.loadPDF(bytes).use { document ->
            val text = PDFTextStripper().getText(document)
            assertTrue(text.contains("Office"))
            assertTrue(text.contains("Storage"))
            assertTrue(text.contains("2026"))
            assertTrue(text.contains("Formula:"))
            assertTrue(document.numberOfPages > 1)
        }
    }
}
