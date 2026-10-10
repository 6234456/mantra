package com.xqiou.mantra.render.pdf

import com.xqiou.mantra.render.layout.Align
import com.xqiou.mantra.render.layout.StyleFill
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.StyleTone
import com.xqiou.mantra.render.layout.StyleWeight
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowKind
import com.xqiou.mantra.render.paper.WorkingPaper
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Color
import java.io.ByteArrayOutputStream

/** Draws the same formatted paper as HTML/Text, with embedded fonts and actual page boundaries. */
object PdfRenderer {
    fun render(paper: WorkingPaper, options: PdfOptions = PdfOptions()): ByteArray {
        val rows =
            paper.tables.sumOf { it.rows.size.toLong() } + if (options.includeAudit) paper.audit.size.toLong() else 0
        if (rows > options.maxRows) throw PdfRenderException("MANTRA-PDF-LIMIT", "Paper exceeds the PDF row limit")
        if (paper.tables.any {
                it.columns.size > 20
            }
        ) {
            throw PdfRenderException("MANTRA-PDF-LIMIT", "PDF tables support at most 20 columns per page")
        }
        val landscape = options.landscape ?: paper.tables.any { it.columns.size > 6 }
        PDDocument().use { document ->
            document.documentInformation.title = paper.title
            document.documentInformation.author = "Mantra"
            document.documentInformation.creator = "Mantra working-paper renderer"
            val fonts = listOf("DejaVuSans.ttf", "DejaVuSans-Bold.ttf").map { name ->
                PdfRenderer::class.java.getResourceAsStream("fonts/$name").use { input ->
                    requireNotNull(input) { "Bundled PDF font is missing: $name" }
                    PDType0Font.load(document, input, true)
                }
            }
            try {
                Pages(document, paper, options, landscape, fonts[0], fonts[1]).use { pages ->
                    pages.heading(paper.title)
                    paper.subtitle?.let { pages.paragraph(it) }
                    paper.header.forEach { (name, value) -> pages.paragraph("$name: $value") }
                    paper.headline?.let { pages.paragraph("${it.label}: ${it.value}", bold = true) }
                    paper.tables.forEach(pages::table)
                    if (paper.findings.isNotEmpty()) {
                        pages.heading(if (paper.texts.total == "Gesamt") "Diagnosen" else "Diagnostics")
                        paper.findings.forEach { finding ->
                            pages.paragraph("${finding.severity} · ${finding.code}: ${finding.message}")
                        }
                    }
                    if (options.includeAudit && paper.audit.isNotEmpty()) {
                        pages.startAppendix()
                        pages.heading(paper.texts.audit)
                        paper.audit.forEach { entry ->
                            pages.paragraph(
                                "${entry.citation} · ${entry.label}${entry.member?.let {
                                    " [$it]"
                                }.orEmpty()}",
                                bold = true,
                            )
                            pages.paragraph("Formula: ${entry.formula}")
                            pages.paragraph("Working: ${entry.working}")
                            pages.paragraph("Result: ${entry.result}")
                            entry.reference?.let { pages.paragraph("Reference: $it") }
                            pages.gap()
                        }
                    }
                    if (paper.legend.isNotEmpty()) {
                        pages.shortSection(paper.texts.legend, paper.legend.map { (name, text) -> "$name: $text" })
                    }
                }
                val output = object : ByteArrayOutputStream() {
                    override fun write(value: Int) {
                        if (count >= options.maxOutputBytes) limit()
                        super.write(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (length > options.maxOutputBytes - count) limit()
                        super.write(bytes, offset, length)
                    }
                    private fun limit(): Nothing =
                        throw PdfRenderException("MANTRA-PDF-LIMIT", "PDF exceeds the output byte limit")
                }
                document.save(output)
                return output.toByteArray()
            } catch (error: IllegalArgumentException) {
                throw PdfRenderException(
                    "MANTRA-PDF-GLYPH",
                    "PDF text contains a glyph unsupported by the bundled font: ${error.message}",
                    error,
                )
            }
        }
    }
}

private class Pages(
    private val document: PDDocument,
    private val paper: WorkingPaper,
    private val options: PdfOptions,
    landscape: Boolean,
    private val regular: PDFont,
    private val bold: PDFont,
) : AutoCloseable {
    private val size = if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
    private val margin = 38f
    private val bodyTop = size.height - 68f
    private val bottom = 45f
    private val width = size.width - margin * 2
    private var y = bodyTop
    private var stream: PDPageContentStream? = null
    private var tableContinuation: (() -> Unit)? = null

    init {
        newPage()
    }

    private fun newPage() {
        if (document.numberOfPages >=
            options.maxPages
        ) {
            throw PdfRenderException("MANTRA-PDF-LIMIT", "PDF exceeds the page limit")
        }
        stream?.close()
        val page = PDPage(size)
        document.addPage(page)
        stream = PDPageContentStream(document, page)
        y = bodyTop
        draw(ellipsize(paper.title, regular, 8f, width), margin, size.height - 31f, regular, 8f, Color(65, 74, 81))
        stream!!.setStrokingColor(Color(185, 193, 198))
        stream!!.moveTo(margin, size.height - 40f)
        stream!!.lineTo(size.width - margin, size.height - 40f)
        stream!!.stroke()
    }

    private fun ensure(height: Float) {
        if (y - height < bottom) {
            newPage()
            tableContinuation?.invoke()
        }
    }

    fun heading(text: String) {
        val lines = wrap(text, bold, 14f, width)
        ensure(minOf(lines.size * 21f + 33f, bodyTop - bottom))
        for (line in lines) {
            ensure(24f)
            draw(line, margin, y - 15f, bold, 14f)
            y -= 21f
        }
        y -= 5f
    }

    fun paragraph(text: String, bold: Boolean = false) {
        val font = if (bold) this.bold else regular
        val lines = wrap(text, font, 9f, width)
        if (bold) ensure(minOf(lines.size * 13f + 30f, bodyTop - bottom))
        for (line in lines) {
            ensure(14f)
            draw(line, margin, y - 10f, font, 9f)
            y -= 13f
        }
        y -= 3f
    }

    fun gap() {
        y -= 6f
    }

    fun shortSection(title: String, paragraphs: List<String>) {
        val height = wrap(title, bold, 14f, width).size * 21f + 5f +
            paragraphs.sumOf { (wrap(it, regular, 9f, width).size * 13 + 3).toDouble() }.toFloat()
        if (height <= bodyTop - bottom) ensure(height)
        heading(title)
        paragraphs.forEach(::paragraph)
    }

    fun startAppendix() {
        tableContinuation = null
        if (y != bodyTop) newPage()
    }

    fun table(table: PaperTable) {
        tableContinuation = null
        heading("${table.ref} · ${table.title}")
        table.breadcrumb?.let(::paragraph)
        val count = table.columns.size.coerceAtLeast(1)
        val widths = if (count ==
            1
        ) {
            listOf(width)
        } else {
            listOf(width * .38f) + List(count - 1) { width * .62f / (count - 1) }
        }
        val aligns = table.columns.map { it.align }
        val headerLines = widths.indices.maxOfOrNull { index ->
            wrap(table.columns[index].header, bold, 8f, widths[index] - 10f).size
        } ?: 1
        if (headerLines * 11f + 45f >
            bodyTop - bottom
        ) {
            throw PdfRenderException("MANTRA-PDF-LIMIT", "Table header cannot fit on one page")
        }
        fun headers() {
            row(table.columns.map { it.header }, widths, aligns, true, true)
        }
        headers()
        tableContinuation = {
            draw(ellipsize("${table.ref} · ${table.title}", bold, 9f, width), margin, y - 10f, bold, 9f)
            y -= 17f
            headers()
        }
        table.rows.forEach { row ->
            val strong = row.kind in setOf(RowKind.HEADING, RowKind.SUBTOTAL, RowKind.RESULT, RowKind.TOTAL)
            val styles = widths.indices.map { row.style.merge(row.cellStyles.getOrNull(it) ?: StyleSpec()) }
            row(row.cells, widths, aligns, strong, row.kind in setOf(RowKind.RESULT, RowKind.TOTAL), styles)
        }
        tableContinuation = null
        y -= 14f
    }

    private fun row(
        cells: List<String>,
        widths: List<Float>,
        aligns: List<Align>,
        strong: Boolean,
        fill: Boolean,
        styles: List<StyleSpec> = emptyList(),
    ) {
        val fonts = widths.indices.map { index ->
            val weight = styles.getOrNull(index)?.weight
            if (weight == StyleWeight.BOLD || (weight == null && strong)) bold else regular
        }
        val colors = widths.indices.map { index ->
            when (styles.getOrNull(index)?.tone) {
                StyleTone.MUTED -> Color(107, 114, 128)
                StyleTone.ACCENT -> Color(31, 95, 191)
                else -> Color(23, 38, 48)
            }
        }
        val fills = widths.indices.map { index ->
            when (styles.getOrNull(index)?.fill) {
                StyleFill.NONE -> null
                StyleFill.SUBTLE -> Color(0xE7EAEE)
                StyleFill.ACCENT -> Color(0xEEF3FA)
                null -> if (fill) Color(232, 239, 242) else null
            }
        }
        val lines = widths.indices.map { index ->
            wrap(cells.getOrElse(index) { "" }, fonts[index], 8f, widths[index] - 10f)
        }
        var first = 0
        val total = lines.maxOfOrNull { it.size } ?: 1
        while (first < total) {
            ensure(21f)
            val available = ((y - bottom - 8f) / 11f).toInt().coerceAtLeast(1)
            val take = minOf(total - first, available)
            val height = take * 11f + 8f
            // Preserve the original full-width fill and drawing order when every cell agrees.
            if (fills.firstOrNull() != null && fills.all { it == fills.first() }) {
                stream!!.setNonStrokingColor(fills.first()!!)
                stream!!.addRect(margin, y - height, width, height)
                stream!!.fill()
            } else {
                var x = margin
                fills.forEachIndexed { column, color ->
                    if (color != null) {
                        stream!!.setNonStrokingColor(color)
                        stream!!.addRect(x, y - height, widths[column], height)
                        stream!!.fill()
                    }
                    x += widths[column]
                }
            }
            var x = margin
            widths.forEachIndexed { column, columnWidth ->
                val font = fonts[column]
                for (line in 0 until take) {
                    val text = lines[column].getOrNull(first + line).orEmpty()
                    val textWidth = font.getStringWidth(text) * 8f / 1000f
                    val position = when (aligns.getOrNull(column)) {
                        Align.RIGHT -> x + columnWidth - 5f - textWidth
                        Align.CENTER -> x + (columnWidth - textWidth) / 2f
                        else -> x + 5f
                    }
                    draw(text, position, y - 11f - line * 11f, font, 8f, colors[column])
                }
                x += columnWidth
            }
            stream!!.setStrokingColor(Color(206, 214, 219))
            stream!!.setLineWidth(.35f)
            stream!!.moveTo(margin, y - height)
            stream!!.lineTo(margin + width, y - height)
            stream!!.stroke()
            y -= height
            first += take
            if (first < total) {
                newPage()
                tableContinuation?.invoke()
            }
        }
    }

    private fun draw(text: String, x: Float, y: Float, font: PDFont, size: Float, color: Color = Color(23, 38, 48)) {
        stream!!.beginText()
        stream!!.setNonStrokingColor(color)
        stream!!.setFont(font, size)
        stream!!.newLineAtOffset(x, y)
        stream!!.showText(text)
        stream!!.endText()
    }

    override fun close() {
        stream?.close()
        stream = null
        val pages = document.numberOfPages
        document.pages.forEachIndexed { index, page ->
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { footer ->
                footer.beginText()
                footer.setFont(regular, 8f)
                footer.setNonStrokingColor(Color(65, 74, 81))
                footer.newLineAtOffset(margin, 24f)
                footer.showText("Mantra · ${index + 1} / $pages")
                footer.endText()
            }
        }
    }
}

private fun wrap(text: String, font: PDFont, size: Float, width: Float): List<String> {
    require(width > 0) { "A PDF table has too many columns for the selected page" }
    val output = mutableListOf<String>()
    for (paragraph in text.replace('\t', ' ').replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        var line = ""
        for (point in paragraph.codePoints().toArray()) {
            val glyph = String(Character.toChars(point))
            val candidate = line + glyph
            if (line.isNotEmpty() && font.getStringWidth(candidate) * size / 1000f > width) {
                output += line.trimEnd()
                line = glyph.trimStart()
            } else {
                line = candidate
            }
        }
        output += line.trimEnd()
    }
    return output.ifEmpty { listOf("") }
}

private fun ellipsize(text: String, font: PDFont, size: Float, width: Float): String {
    val flat = text.replace('\n', ' ').replace('\r', ' ')
    if (font.getStringWidth(flat) * size / 1000f <= width) return flat
    return wrap(flat, font, size, width - font.getStringWidth("…") * size / 1000f).first() + "…"
}
