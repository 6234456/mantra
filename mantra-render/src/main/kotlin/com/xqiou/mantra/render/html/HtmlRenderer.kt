package com.xqiou.mantra.render.html

import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.render.layout.Align
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.StyleFill
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.StyleTone
import com.xqiou.mantra.render.layout.StyleWeight
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.RowFlag
import com.xqiou.mantra.render.paper.RowKind
import com.xqiou.mantra.render.paper.WorkingPaper

/** Standalone HTML working paper (A4-print friendly, light and dark themes). */
object HtmlRenderer {
    fun render(paper: WorkingPaper): String = buildString {
        val auditRows = auditRows(paper)
        val auditAnchors = linkedMapOf<String, String>().apply {
            paper.audit.forEach { entry -> auditRows[entry.anchor]?.let { putIfAbsent(it, entry.anchor) } }
        }
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"${if (paper.texts.total == "Gesamt") "de" else "en"}\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("<title>${esc(paper.title)}</title>")
        appendLine("<style>")
        appendLine(CSS)
        appendLine("</style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<main class=\"paper theme-${esc(paper.theme)}\">")
        appendLine("<header class=\"wp-header\">")
        appendLine("<div class=\"wp-title\"><h1>${esc(paper.title)}</h1>")
        paper.subtitle?.let { appendLine("<p class=\"subtitle\">${esc(it)}</p>") }
        appendLine("</div>")
        if (paper.header.isNotEmpty()) {
            appendLine("<dl class=\"wp-meta\">")
            paper.header.forEach { (k, v) -> appendLine("<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>") }
            appendLine("</dl>")
        }
        appendLine("</header>")
        paper.headline?.let {
            appendLine("<p class=\"headline\"><strong>${esc(it.label)}</strong> ${esc(it.value)}</p>")
        }
        if (paper.overview.isNotEmpty()) append(structure(paper))
        if (paper.tables.size > 1) {
            appendLine("<nav class=\"toc\"><ol>")
            paper.tables.forEach {
                appendLine(
                    "<li><a href=\"#table-${esc(
                        it.ref,
                    )}\"><span class=\"ref\">${esc(it.ref)}</span> ${esc(it.title)}</a></li>",
                )
            }
            if (paper.audit.isNotEmpty()) {
                appendLine(
                    "<li><a href=\"#audit\"><span class=\"ref\">§</span> ${esc(paper.texts.audit)}</a></li>",
                )
            }
            appendLine("</ol></nav>")
        }
        paper.tables.forEach { append(table(it, paper, auditAnchors)) }
        if (paper.audit.isNotEmpty()) append(audit(paper, auditRows))
        append(footer(paper))
        appendLine("</main>")
        appendLine("</body>")
        appendLine("</html>")
    }

    /** Resolve actual paper addresses; node/member identifiers may contain arbitrary hyphens. */
    private fun auditRows(paper: WorkingPaper): Map<String, String> {
        data class Address(
            val table: String,
            val node: String,
            val coord: List<String>,
            val fixed: Map<String, String>?,
        )
        val anchors = mutableSetOf<String>()
        val values = linkedMapOf<Address, String>()
        val nodes = linkedMapOf<Pair<String, String>, String>()
        paper.tables.forEach { table ->
            table.rows.forEach { row ->
                val anchor = row.anchor ?: return@forEach
                anchors.add(anchor)
                row.nodeId?.let { nodes.putIfAbsent(table.ref to it, anchor) }
                row.valueAddresses.filterNotNull().forEach { address ->
                    values.putIfAbsent(
                        Address(
                            table.ref,
                            address.nodeId,
                            address.coord,
                            address.fixed.takeIf {
                                address.aggregate
                            },
                        ),
                        anchor,
                    )
                    nodes.putIfAbsent(table.ref to address.nodeId, anchor)
                }
            }
        }
        val refs = paper.tables.mapTo(mutableSetOf()) { it.ref }
        return paper.audit.mapNotNull { entry ->
            var prefix = entry.anchor
            while (prefix !in anchors && '-' in prefix) prefix = prefix.substringBeforeLast('-')
            val ref = entry.anchor.substringBefore('-').removePrefix("t")
            val fixed = (entry.reduction ?: entry.aggregate)?.fixed
            val row = prefix.takeIf { it in anchors }
                ?: entry.nodeId?.let { values[Address(ref, it, entry.coord, fixed)] ?: nodes[ref to it] }
                ?: ref.takeIf { it in refs }?.let { "table-$it" }
            row?.let { entry.anchor to it }
        }.toMap()
    }

    private fun structure(paper: WorkingPaper): String = buildString {
        fun link(ref: String?, body: String) = if (ref == null || paper.tables.none { it.ref == ref }) {
            "<span class=\"card\">$body</span>"
        } else {
            "<a class=\"card\" href=\"#table-${esc(ref)}\">$body</a>"
        }
        appendLine("<section class=\"structure\" id=\"structure\">")
        appendLine("<h2><span class=\"ref\">⌂</span>${esc(paper.texts.structure)}</h2>")
        appendLine("<ol class=\"mainline\" aria-label=\"${esc(paper.texts.mainline)}\">")
        paper.overview.forEach { step ->
            appendLine("<li class=\"step\">")
            appendLine(
                link(
                    step.panel.tableRef,
                    "<span class=\"step-no\">${esc(
                        paper.texts.mainline,
                    )} ${step.step}</span><span class=\"title\">${esc(
                        step.panel.title,
                    )}</span><span class=\"value\">${esc(step.panel.value)}</span>",
                ),
            )
            if (step.branches.isNotEmpty()) {
                appendLine("<ul class=\"branches\">")
                step.branches.forEach { branch ->
                    appendLine(
                        "<li>" +
                            link(
                                branch.tableRef,
                                "<span class=\"title\">↳ ${esc(
                                    branch.title,
                                )}</span><span class=\"value\">${esc(branch.value)}</span>${branch.entry?.let {
                                    "<span class=\"entry\">${esc(it)}</span>"
                                }.orEmpty()}",
                            ) +
                            "</li>",
                    )
                }
                appendLine("</ul>")
            }
            appendLine("</li>")
        }
        appendLine("</ol>")
        if (paper.auxiliary.isNotEmpty()) {
            appendLine(
                "<p class=\"aux\"><strong>${esc(paper.texts.auxiliary)}:</strong> " +
                    paper.auxiliary.joinToString(" · ") { aux ->
                        (
                            aux.tableRef?.let {
                                "<a href=\"#table-${esc(it)}\">${esc(aux.title)}</a>"
                            } ?: esc(aux.title)
                            ) +
                            " ${esc(aux.value)}"
                    } + "</p>",
            )
        }
        appendLine("</section>")
    }

    private fun table(table: PaperTable, paper: WorkingPaper, auditAnchors: Map<String, String>): String = buildString {
        appendLine("<section class=\"block\" id=\"table-${esc(table.ref)}\">")
        table.breadcrumb?.let { appendLine("<p class=\"crumb\"><a href=\"#structure\">⌂</a> ${esc(it)}</p>") }
        appendLine("<h2><span class=\"ref\">${esc(table.ref)}</span>${esc(table.title)}</h2>")
        appendLine("<div class=\"table-wrap\"><table class=\"calc ${table.style.name.lowercase()}\">")
        appendLine("<colgroup>")
        table.columns.forEach { column ->
            val width = column.width?.let { " style=\"width:${it * 0.62}em\"" }.orEmpty()
            appendLine("<col class=\"c-${cssName(column.content)}\"$width>")
        }
        appendLine("</colgroup>")
        appendLine("<thead><tr>")
        table.columns.forEach { column ->
            appendLine("<th class=\"${align(column.align)} c-${cssName(column.content)}\">${esc(column.header)}</th>")
        }
        appendLine("</tr></thead>")
        appendLine("<tbody>")
        table.rows.forEach { row ->
            val classes = buildList {
                add(row.kind.name.lowercase())
                if (RowFlag.INACTIVE in row.flags) add("inactive")
                if (RowFlag.USER_DEFINED in row.flags) add("user")
                if (RowFlag.SELECTED in row.flags) add("selected")
                if (RowFlag.GRAND in row.flags) add("grand")
                if (RowFlag.INFO in row.flags) add("info")
                row.classes.forEach { add("u-$it") }
            }
            val id = row.anchor?.let { " id=\"${esc(it)}\"" }.orEmpty()
            val context = row.cellContexts.firstOrNull()
            val location = context?.let {
                " data-section=\"${esc(
                    it.sectionPath.lastOrNull().orEmpty(),
                )}\" data-depth=\"${it.depth}\" data-height=\"${it.height}\" data-row-index=\"${it.rowIndex}\""
            }.orEmpty()
            appendLine("<tr class=\"${esc(classes.joinToString(" "))}\"$id$location>")
            table.columns.forEachIndexed { index, column ->
                val cellStyle = styleAttribute(row.cellStyles.getOrNull(index) ?: row.style)
                val raw = row.cells[index]
                val content = when {
                    column.content == ColumnContent.Label -> {
                        val text = esc(raw)
                        // "(→ Tabelle 5)" at the end of a label links to that table.
                        val linked = Regex("\\(→ [^)]*? (\\S+)\\)$").find(raw)?.groupValues?.get(1)
                            ?.takeIf { target -> paper.tables.any { it.ref == target } }?.let { target ->
                                "$text <a class=\"xref\" href=\"#table-${esc(target)}\">↗</a>"
                            } ?: text
                        "<span class=\"label\" style=\"--depth:${row.depth}\">$linked</span>"
                    }
                    column.content == ColumnContent.RowNumber && raw.isNotEmpty() && row.anchor != null &&
                        row.anchor in auditAnchors ->
                        "<a href=\"#audit-${esc(
                            auditAnchors.getValue(row.anchor),
                        )}\" title=\"${esc(paper.texts.audit)}\">${esc(raw)}</a>"
                    else -> esc(raw)
                }
                appendLine(
                    "<td class=\"${align(
                        column.align,
                    )} c-${cssName(column.content)}\" data-column=\"${esc(column.id)}\"$cellStyle>$content</td>",
                )
            }
            appendLine("</tr>")
        }
        appendLine("</tbody></table></div>")
        appendLine("</section>")
    }

    private fun styleAttribute(style: StyleSpec): String {
        val declarations = buildList {
            when (style.weight) {
                StyleWeight.BOLD -> add("font-weight:700")
                StyleWeight.NORMAL -> add("font-weight:400")
                null -> Unit
            }
            when (style.tone) {
                StyleTone.ACCENT -> add("color:var(--accent)")
                StyleTone.MUTED -> add("color:var(--muted)")
                StyleTone.DEFAULT -> add("color:var(--ink)")
                null -> Unit
            }
            when (style.fill) {
                StyleFill.ACCENT -> add("background:color-mix(in srgb,var(--accent) 10%,var(--paper))")
                StyleFill.SUBTLE -> add("background:var(--heading)")
                StyleFill.NONE -> add("background:transparent")
                null -> Unit
            }
        }
        return if (declarations.isEmpty()) "" else " style=\"${declarations.joinToString(";")}\""
    }

    private fun audit(paper: WorkingPaper, auditRows: Map<String, String>): String = buildString {
        val texts = paper.texts
        appendLine("<section class=\"block audit\" id=\"audit\">")
        appendLine("<h2><span class=\"ref\">§</span>${esc(texts.audit)}</h2>")
        appendLine("<p class=\"intro\">${esc(texts.auditIntro)}</p>")
        appendLine("<div class=\"table-wrap\"><table class=\"audit-table\">")
        appendLine(
            "<thead><tr><th class=\"left\">${esc(
                texts.row,
            )}</th><th class=\"left\">${esc(
                texts.label,
            )}</th><th class=\"left\">${esc(
                texts.formula,
            )} / ${esc(texts.explain)}</th><th class=\"right\">${esc(texts.result)}</th></tr></thead>",
        )
        appendLine("<tbody>")
        paper.audit.forEach { entry ->
            appendLine("<tr id=\"audit-${esc(entry.anchor)}\">")
            appendLine(
                "<td class=\"left cite\">" +
                    (
                        auditRows[entry.anchor]?.let { "<a href=\"#${esc(it)}\">${esc(entry.citation)}</a>" }
                            ?: esc(entry.citation)
                        ) + "</td>",
            )
            val member = entry.member?.let { " <span class=\"member\">${esc(it)}</span>" }.orEmpty()
            val ref = entry.reference?.let { "<div class=\"norm\">${esc(it)}</div>" }.orEmpty()
            appendLine("<td class=\"left\">${esc(entry.label)}$member$ref</td>")
            appendLine(
                "<td class=\"left\"><code class=\"formula\">${esc(
                    entry.formula,
                )}</code><div class=\"working\">${esc(entry.working)}</div></td>",
            )
            appendLine("<td class=\"right num\">${esc(entry.result)}</td>")
            appendLine("</tr>")
        }
        appendLine("</tbody></table></div>")
        appendLine("</section>")
    }

    private fun footer(paper: WorkingPaper): String = buildString {
        appendLine("<footer class=\"wp-footer\">")
        appendLine(
            "<div class=\"legend\"><strong>${esc(paper.texts.legend)}:</strong> " +
                paper.legend.joinToString(" · ") { (mark, text) -> "<span><b>${esc(mark)}</b> ${esc(text)}</span>" } +
                "</div>",
        )
        if (paper.findings.isNotEmpty()) {
            appendLine("<div class=\"findings\"><strong>${esc(paper.texts.diagnostics)}</strong><ul>")
            paper.findings.forEach { finding ->
                val css = if (finding.severity == Severity.ERROR) "error" else "warning"
                appendLine(
                    "<li class=\"$css\"><code>${esc(
                        finding.code,
                    )}</code> ${esc(finding.message)}${finding.location?.let {
                        " <span class=\"loc\">${esc(it.toString())}</span>"
                    }.orEmpty()}</li>",
                )
            }
            appendLine("</ul></div>")
        }
        appendLine("<div class=\"engine\">Mantra calculation engine · Normein DSL</div>")
        appendLine("</footer>")
    }

    private fun cssName(content: ColumnContent): String = when (content) {
        is ColumnContent.Member -> "member"
        is ColumnContent.Members -> "member"
        else -> content.javaClass.simpleName.lowercase()
    }

    private fun align(align: Align): String = when (align) {
        Align.LEFT -> "left"
        Align.RIGHT -> "right num"
        Align.CENTER -> "center"
    }

    private fun esc(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    private val CSS = """
:root {
  --page: #eceef1; --paper: #ffffff; --ink: #1f2328; --muted: #6b7280; --faint: #9aa1ab;
  --rule: #d8dce2; --rule-strong: #2b3036; --heading: #f4f6f8; --accent: #1f5fbf;
  --selected: #1a7f37; --user: #8a5300; --error: #b42318; --warning: #9a6700; --shadow: 0 1px 3px rgba(16,24,40,.12), 0 8px 24px rgba(16,24,40,.06);
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --page: #0f1115; --paper: #171a20; --ink: #e6e8eb; --muted: #9aa3ad; --faint: #6b7380;
    --rule: #2c323b; --rule-strong: #c9ced6; --heading: #1f242c; --accent: #7cb0ff;
    --selected: #4ac26b; --user: #e3a64a; --error: #ff6b5e; --warning: #e5b53f; --shadow: none;
  }
}
:root[data-theme="dark"] {
  --page: #0f1115; --paper: #171a20; --ink: #e6e8eb; --muted: #9aa3ad; --faint: #6b7380;
  --rule: #2c323b; --rule-strong: #c9ced6; --heading: #1f242c; --accent: #7cb0ff;
  --selected: #4ac26b; --user: #e3a64a; --error: #ff6b5e; --warning: #e5b53f; --shadow: none;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--page); color: var(--ink); font: 13px/1.45 Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
.paper { max-width: 1120px; margin: 24px auto; background: var(--paper); padding: 32px 40px 24px; box-shadow: var(--shadow); border-radius: 4px; }
.wp-header { display: flex; flex-wrap: wrap; gap: 16px 32px; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid var(--rule-strong); padding-bottom: 14px; margin-bottom: 18px; }
h1 { font-size: 20px; margin: 0 0 4px; letter-spacing: -0.01em; }
.subtitle { margin: 0; color: var(--muted); }
.wp-meta { display: grid; grid-template-columns: repeat(2, minmax(160px, auto)); gap: 2px 18px; margin: 0; font-size: 12px; }
.wp-meta div { display: flex; gap: 8px; }
.wp-meta dt { color: var(--muted); min-width: 140px; }
.wp-meta dd { margin: 0; font-weight: 500; }
.toc ol { list-style: none; padding: 0; margin: 0 0 18px; display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 12px; }
.toc a { color: var(--accent); text-decoration: none; }
.ref { display: inline-block; min-width: 1.6em; padding: 0 5px; margin-right: 8px; border: 1px solid var(--rule-strong); border-radius: 3px; font-size: 11px; font-weight: 600; text-align: center; }
.block { margin: 22px 0 8px; }
.structure { margin: 4px 0 18px; }
.mainline { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; counter-reset: step; }
.mainline .step { display: flex; flex-direction: column; gap: 6px; }
.mainline .card { display: grid; gap: 1px; padding: 8px 10px; border: 1px solid var(--rule); border-radius: 6px; color: var(--ink); text-decoration: none; background: var(--heading); }
.mainline .step > .card { border-color: var(--rule-strong); border-top-width: 3px; }
.mainline a.card:hover { border-color: var(--accent); }
.mainline .step-no { font-size: 10.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); }
.mainline .title { font-weight: 600; font-size: 12.5px; }
.mainline .value { font-variant-numeric: tabular-nums; font-size: 12.5px; }
.mainline .branches { list-style: none; margin: 0; padding: 0 0 0 10px; display: grid; gap: 4px; border-left: 2px solid var(--rule); }
.mainline .branches .card { background: transparent; padding: 5px 8px; }
.mainline .branches .title { font-weight: 500; }
.mainline .entry { font-size: 11px; color: var(--muted); }
.structure .aux { font-size: 12px; color: var(--muted); margin: 8px 0 0; }
.crumb { font-size: 11.5px; color: var(--muted); margin: 0 0 3px; }
.crumb a { color: var(--accent); text-decoration: none; }
h2 { font-size: 15px; margin: 0 0 8px; display: flex; align-items: center; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-variant-numeric: tabular-nums; }
th { font-size: 11px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: .03em; border-bottom: 1px solid var(--rule-strong); padding: 4px 6px; }
td { padding: 3px 6px; vertical-align: top; border: 0; }
.left { text-align: left; } .right { text-align: right; } .center { text-align: center; }
td.num { white-space: nowrap; }
.label { display: inline-block; padding-left: calc(var(--depth, 0) * 1.25em); }
td.c-rownumber, td.c-operator, td.c-status { color: var(--muted); white-space: nowrap; }
td.c-rownumber a { color: var(--muted); text-decoration: none; }
td.c-rownumber a:hover { color: var(--accent); text-decoration: underline; }
td.c-reference, td.c-note, td.c-source, td.c-attribute { color: var(--muted); font-size: 11.5px; }
td.c-formula, td.c-explain { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; color: var(--muted); }
tr.heading td { font-weight: 600; padding-top: 9px; background: linear-gradient(var(--heading), var(--heading)) bottom / 100% 0 no-repeat; }
tr.heading td.c-label { border-bottom: 1px solid var(--rule); }
tr.subtotal td.num, tr.result td.num, tr.total td.num { border-top: 1px solid var(--rule-strong); }
tr.subtotal td.num:empty, tr.result td.num:empty, tr.total td.num:empty { border-top-color: transparent; }
tr.result td, tr.total td { font-weight: 600; }
tr.total td { padding-top: 5px; }
tr.grand td.num:not(:empty) { border-bottom: 3px double var(--rule-strong); }
tr.grand td { font-weight: 700; }
tr.info td.c-label { color: var(--muted); }
tr.inactive td { color: var(--faint); }
tr.user td.c-label { font-style: italic; color: var(--user); }
tr.option td { font-size: 12px; color: var(--muted); }
tr.option.selected td { color: var(--selected); }
tr.member td { font-size: 12px; color: var(--muted); }
tr.note td { font-size: 11.5px; color: var(--muted); font-style: italic; }
td.c-label a.xref { color: var(--accent); text-decoration: none; margin-left: 4px; }
th.num { white-space: nowrap; }
td.c-label { min-width: 15em; }
tr:target td { background: color-mix(in srgb, var(--accent) 12%, transparent); }
.audit .intro { color: var(--muted); margin: 0 0 8px; }
.audit-table td { border-bottom: 1px solid var(--rule); padding: 5px 6px; }
.audit-table .cite a { color: var(--accent); text-decoration: none; white-space: nowrap; }
.audit-table .member { color: var(--muted); font-size: 11.5px; }
.audit-table .norm { color: var(--muted); font-size: 11px; }
code.formula { font-size: 11px; color: var(--muted); }
.working { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11.5px; margin-top: 2px; }
.wp-footer { margin-top: 26px; border-top: 1px solid var(--rule); padding-top: 10px; font-size: 11.5px; color: var(--muted); display: grid; gap: 8px; }
.legend span { margin-right: 4px; }
.findings ul { margin: 4px 0 0; padding-left: 18px; }
.findings .error { color: var(--error); } .findings .warning { color: var(--warning); }
.findings .loc { color: var(--muted); }
.engine { color: var(--faint); }
@media (max-width: 720px) { .paper { margin: 0; padding: 16px; border-radius: 0; } .wp-meta { grid-template-columns: 1fr; } }
@media print { body { background: #fff; } .paper { box-shadow: none; margin: 0; max-width: none; padding: 0; } .toc { display: none; } .block { break-inside: auto; } tr { break-inside: avoid; } }
    """.trimIndent()
}
