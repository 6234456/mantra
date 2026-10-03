package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.structure.PanelRole
import com.xqiou.mantra.core.view.displayLabel
import com.xqiou.mantra.core.view.headlineId
import com.xqiou.mantra.core.view.signLabels
import com.xqiou.mantra.excel.ExcelWorkbookBuilder.Slot
import com.xqiou.mantra.render.paper.WorkingPaper
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.xssf.usermodel.XSSFSheet

// ── Overview ───────────────────────────────────────────────────────────────────────────────

internal fun ExcelWorkbookBuilder.writeOverview(sheet: XSSFSheet, paper: WorkingPaper) {
    sheet.setColumnWidth(0, 10 * 256)
    sheet.setColumnWidth(1, 60 * 256)
    sheet.setColumnWidth(2, 22 * 256)
    sheet.setColumnWidth(3, 18 * 256)
    sheet.setColumnWidth(4, 60 * 256)
    text(sheet, 0, 0, paper.title, StyleKey(bold = true, size = 15))
    paper.subtitle?.let { text(sheet, 1, 0, it, StyleKey(muted = true)) }
    var r = 3
    paper.header.forEach { (k, v) ->
        text(sheet, r, 0, k, StyleKey(muted = true))
        text(sheet, r++, 1, v)
    }
    view.headlineId?.let { id ->
        val node = view.nodes[id] ?: return@let
        text(sheet, r, 0, node.displayLabel(), StyleKey(bold = true))
        if (node.signLabels != null) setFormula(Slot(sheet, r, 0), id) { signLabelFormula(node) }
        val slot = Slot(sheet, r, 1)
        valueStyles[slot] =
            StyleKey(format = styles.amountFormat(layout.number.precision), bold = true, fill = Fill.STEP)
        setFormula(slot, id) { aggregateRef(id) }
        r++
    }
    r++
    text(sheet, r++, 0, texts.structure, StyleKey(bold = true, size = 12))
    listOf(
        if (de) "Schritt" else "Step",
        if (de) "Bereich" else "Panel",
        if (de) "Rolle" else "Role",
        texts.result,
        texts.feeds,
    )
        .forEachIndexed { i, h ->
            text(
                sheet,
                r,
                i,
                h,
                StyleKey(
                    bold = true,
                    fill = Fill.HEADER,
                    headerRule = true,
                    align = if (i ==
                        3
                    ) {
                        HorizontalAlignment.RIGHT
                    } else {
                        HorizontalAlignment.LEFT
                    },
                ),
            )
        }
    r++
    fun panelRow(panelId: String, step: String, role: String, entry: String?, bold: Boolean, indent: Short) {
        val panel = map.panel(panelId)
        text(sheet, r, 0, step, StyleKey(bold = bold))
        text(sheet, r, 1, panel.title, StyleKey(bold = bold, link = true, indent = indent))
        findSheetFor(panelId)?.let { link(sheet, r, 1, it.sheetName) }
        text(sheet, r, 2, role, StyleKey(muted = true))
        panel.resultId?.let { resultId ->
            val slot = Slot(sheet, r, 3)
            valueStyles[slot] =
                StyleKey(
                    format = styles.amountFormat(layout.number.precision),
                    bold = bold,
                    fill = if (bold) Fill.STEP else Fill.NONE,
                )
            setFormula(slot, resultId) { aggregateRef(resultId) }
        }
        entry?.let { text(sheet, r, 4, it, StyleKey(muted = true)) }
        r++
    }
    map.mainline.forEach { id ->
        val step = map.panel(id).step ?: 0
        panelRow(id, "$step", texts.mainline, null, bold = true, indent = 0)
        map.panels.filter { it.role == PanelRole.BRANCH && it.position?.stepPanel == id }.forEach { branch ->
            panelRow(
                branch.id,
                "",
                texts.branch,
                branch.entries.joinToString(", ") {
                    "→ ${it.step} · ${it.viaLabel}"
                },
                bold = false,
                indent = 1,
            )
        }
    }
    map.panels.filter {
        it.role == PanelRole.AUXILIARY
    }.forEach { panelRow(it.id, "", texts.auxiliary, null, bold = false, indent = 0) }
    r++
    text(sheet, r++, 0, texts.legend, StyleKey(bold = true))
    listOf(
        Fill.INPUT to
            (if (de) "Eingabe – änderbar, alle Formeln rechnen neu" else "Input – editable, all formulas recalculate"),
        Fill.PARAM to (if (de) "Parameter des Berechnungsschemas – änderbar" else "Schema parameter – editable"),
        Fill.FALLBACK to (if (de) "Wert ohne Formel (siehe unten)" else "Value without formula (see below)"),
    ).forEach { (fill, label) ->
        cell(sheet, r, 0).cellStyle = styles.get(StyleKey(fill = fill))
        text(sheet, r++, 1, label)
    }
    r++
    text(sheet, r++, 0, if (de) "Formelabdeckung" else "Formula coverage", StyleKey(bold = true))
    text(sheet, r, 1, if (de) "Formelzellen" else "Formula cells")
    text(sheet, r++, 2, formulaCells.toString())
    text(sheet, r, 1, if (de) "Werte ohne Formel" else "Values without formula")
    text(sheet, r++, 2, fallbacks.size.toString())
    fallbacks.forEach { f ->
        text(sheet, r, 1, "${f.sheet}!${f.cell} (${f.nodeId})", StyleKey(fill = Fill.FALLBACK))
        text(sheet, r++, 4, f.reason, StyleKey(muted = true))
    }
}

internal fun ExcelWorkbookBuilder.findSheetFor(panelId: String): XSSFSheet? {
    sectionSheets[panelId]?.let { return it }
    val resultId = map.panel(panelId).resultId ?: return null
    val slot = nodeSlots[resultId]?.values?.firstOrNull() ?: return null
    return slot.sheet
}
