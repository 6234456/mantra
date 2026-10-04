package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.api.CalculationReader
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.paper.RowFlag
import com.xqiou.mantra.render.paper.RowKind
import com.xqiou.mantra.render.paper.WorkingPaper

/** Shared limits for captured-package and directory-workspace presentation exports. */
internal object PaperExportLimits {
    fun boundedUtf8(text: String, limit: Int): ByteArray {
        if (text.length > limit) tooLarge("Text export exceeds its byte limit")
        val output = java.io.ByteArrayOutputStream()
        val bounded = object : java.io.OutputStream() {
            override fun write(value: Int) {
                if (output.size() >= limit) tooLarge("Text export exceeds its byte limit")
                output.write(value)
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (length > limit - output.size()) tooLarge("Text export exceeds its byte limit")
                output.write(buffer, offset, length)
            }
        }
        java.io.OutputStreamWriter(bounded, Charsets.UTF_8).use { it.write(text) }
        return output.toByteArray()
    }

    /** Bound detached presentation payload before allocating renderer output or padding. */
    fun checkPaper(paper: WorkingPaper, budget: ExportBudget, reader: CalculationReader, html: Boolean) {
        if (paper.tables.size > budget.maxSheets) tooLarge("Paper exceeds its table limit")
        var cells = 0L
        var bytes = 0L
        fun text(value: String?) {
            if (value == null) return
            bytes += utf8Size(value, reader, html)
            if (bytes > budget.maxBytes) tooLarge("Paper text exceeds its byte limit")
        }
        text(paper.title)
        text(paper.subtitle)
        paper.header.forEach {
            text(it.first)
            text(it.second)
        }
        paper.headline?.let {
            text(it.label)
            text(it.value)
        }
        fun panel(panel: com.xqiou.mantra.render.paper.OverviewPanel) {
            text(panel.title)
            text(panel.tableRef)
            text(panel.value)
            text(panel.entry)
        }
        paper.overview.forEach {
            panel(it.panel)
            it.branches.forEach(::panel)
        }
        paper.auxiliary.forEach(::panel)
        paper.tables.forEach { table ->
            text(table.ref)
            text(table.title)
            text(table.breadcrumb)
            cells += table.columns.size
            if (cells > budget.maxCells) tooLarge("Paper exceeds its cell limit")
            table.columns.forEach { text(it.header) }
            table.rows.forEach { row ->
                reader.chargeScans()
                cells += row.cells.size
                if (cells > budget.maxCells) tooLarge("Paper exceeds its cell limit")
                row.cells.forEach(::text)
            }
        }
        paper.audit.forEach { entry ->
            cells += 6
            if (cells > budget.maxCells) tooLarge("Paper audit exceeds its cell limit")
            text(entry.citation)
            text(entry.label)
            text(entry.member)
            text(entry.formula)
            text(entry.working)
            text(entry.result)
            text(entry.reference)
        }
        paper.legend.forEach {
            text(it.first)
            text(it.second)
        }
        paper.findings.forEach {
            text(it.message)
            text(it.code)
        }
    }

    /** Text columns pad every row to their widest value; reject amplification before renderTable. */
    fun checkTextExpansion(paper: WorkingPaper, budget: ExportBudget, reader: CalculationReader) {
        var bytes = 0L
        fun add(amount: Long) {
            if (amount > budget.maxBytes - bytes) tooLarge("Padded text export exceeds its byte limit")
            bytes += amount
        }
        paper.tables.forEach { table ->
            val label = table.columns.indexOfFirst { it.content == ColumnContent.Label }
            fun cell(row: com.xqiou.mantra.render.paper.PaperRow, index: Int): String {
                val value = row.cells[index]
                if (index != label) return value
                val formatted = " ".repeat(row.depth * 2) + value
                return if (formatted.length > 64) formatted.take(63) + "…" else formatted
            }
            val widths = table.columns.mapIndexed { index, column ->
                maxOf(
                    column.header.length,
                    table.rows.maxOfOrNull { row ->
                        reader.chargeScans()
                        cell(row, index).length
                    } ?: 0,
                )
            }
            val visible = widths.indices.filter { widths[it] > 0 }
            val separators = 3L * (visible.size - 1).coerceAtLeast(0) + 1
            val plainLine = visible.sumOf { widths[it].toLong() } + separators
            add(plainLine * 2) // Header and separator.
            table.rows.forEach { row ->
                var line = separators
                visible.forEach { index ->
                    val value = cell(row, index)
                    line += widths[index] - value.length + utf8Size(value, reader)
                }
                add(line)
                if (row.kind in setOf(RowKind.TOTAL, RowKind.RESULT, RowKind.SUBTOTAL)) add(plainLine)
                if (RowFlag.GRAND in row.flags) add(plainLine)
            }
        }
    }

    private fun utf8Size(text: String, reader: CalculationReader, html: Boolean = false): Long {
        reader.chargeScans(text.length.toLong())
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            bytes += when {
                html && char == '&' -> 5
                html && (char == '<' || char == '>') -> 4
                html && char == '"' -> 6
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                    index++
                    4
                }
                char.isSurrogate() -> 1 // OutputStreamWriter's replacement byte for an unpaired surrogate.
                else -> 3
            }
        }
        return bytes
    }

    private fun tooLarge(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.TOO_LARGE, message)
}
