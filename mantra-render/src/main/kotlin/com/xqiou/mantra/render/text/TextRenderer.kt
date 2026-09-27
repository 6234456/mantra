package com.xqiou.mantra.render.text

import com.xqiou.mantra.render.layout.Align
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowFlag
import com.xqiou.mantra.render.paper.RowKind
import com.xqiou.mantra.render.paper.WorkingPaper

/** Monospaced rendering of a working paper for terminals, logs and golden tests. */
object TextRenderer {
    private const val INDENT = 2
    private const val MAX_LABEL = 64

    fun render(paper: WorkingPaper, includeAudit: Boolean = true): String = buildString {
        appendLine(paper.title)
        paper.subtitle?.let { appendLine(it) }
        appendLine("=".repeat(paper.title.length.coerceAtLeast(20)))
        paper.header.forEach { (k, v) -> appendLine("$k: $v") }
        paper.headline?.let { appendLine("${it.label}: ${it.value}") }
        if (paper.overview.isNotEmpty()) {
            appendLine()
            appendLine(paper.texts.structure)
            paper.overview.forEach { step ->
                appendLine("  ${paper.texts.mainline} ${step.step}: ${step.panel.title}${step.panel.tableRef?.let { " [$it]" }.orEmpty()}  ${step.panel.value}")
                step.branches.forEach { branch ->
                    appendLine("      ↳ ${branch.title}${branch.tableRef?.let { " [$it]" }.orEmpty()}  ${branch.value}  ${branch.entry.orEmpty()}")
                }
            }
            paper.auxiliary.forEach { aux -> appendLine("  ${paper.texts.auxiliary}: ${aux.title}${aux.tableRef?.let { " [$it]" }.orEmpty()}  ${aux.value}") }
        }
        paper.tables.forEach { table ->
            appendLine()
            append(renderTable(table))
        }
        if (includeAudit && paper.audit.isNotEmpty()) {
            appendLine()
            appendLine(paper.texts.audit)
            appendLine("-".repeat(paper.texts.audit.length))
            paper.audit.forEach { entry ->
                val member = entry.member?.let { " [$it]" }.orEmpty()
                appendLine("${entry.citation} ${entry.label}$member")
                appendLine("    ${paper.texts.formula}: ${entry.formula}")
                appendLine("    ${paper.texts.explain}: ${entry.working} = ${entry.result}")
            }
        }
        if (paper.findings.isNotEmpty()) {
            appendLine()
            appendLine(paper.texts.diagnostics)
            paper.findings.forEach { appendLine("  $it") }
        }
    }

    fun renderTable(table: PaperTable): String = buildString {
        appendLine("[${table.ref}] ${table.title}")
        table.breadcrumb?.let { appendLine("    $it") }
        val labelIndex = table.columns.indexOfFirst { it.content == ColumnContent.Label }
        val cells = table.rows.map { row ->
            row.cells.mapIndexed { index, cell ->
                if (index == labelIndex) (" ".repeat(row.depth * INDENT) + cell).let { if (it.length > MAX_LABEL) it.take(MAX_LABEL - 1) + "…" else it } else cell
            }
        }
        val widths = table.columns.mapIndexed { index, column ->
            (cells.map { it[index].length } + column.header.length).maxOrNull() ?: 0
        }
        val visible = widths.indices.filter { index -> widths[index] > 0 }
        fun line(values: List<String>): String = visible.joinToString(" | ") { index ->
            val column = table.columns[index]
            val value = values[index]
            if (column.align == Align.RIGHT) value.padStart(widths[index]) else value.padEnd(widths[index])
        }.trimEnd()
        appendLine(line(table.columns.map { it.header }))
        appendLine(visible.joinToString("-+-") { "-".repeat(widths[it]) })
        table.rows.forEachIndexed { index, row ->
            if (row.kind == RowKind.TOTAL || row.kind == RowKind.RESULT || row.kind == RowKind.SUBTOTAL) {
                appendLine(visible.joinToString("-+-") { i -> if (table.columns[i].content.numeric) "-".repeat(widths[i]) else " ".repeat(widths[i]) }.trimEnd())
            }
            appendLine(line(cells[index]))
            if (RowFlag.GRAND in row.flags) {
                appendLine(visible.joinToString("=+=") { i -> if (table.columns[i].content.numeric) "=".repeat(widths[i]) else " ".repeat(widths[i]) }.trimEnd())
            }
        }
    }
}
