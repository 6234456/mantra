package com.xqiou.mantra.render.paper

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.render.layout.Align
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.StyleContext
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.TableStyle
import com.xqiou.mantra.render.layout.Texts

/**
 * Renderer-independent result of applying a layout to a calculation: a working paper made of
 * tables with fully formatted cells. HTML, text and future spreadsheet emitters only draw it.
 */
class WorkingPaper(
    val title: String,
    val subtitle: String?,
    val header: List<Pair<String, String>>,
    /** Mainline steps and the branches feeding them (for navigation and orientation). */
    val overview: List<OverviewStep>,
    val auxiliary: List<OverviewPanel>,
    val tables: List<PaperTable>,
    val audit: List<AuditEntry>,
    val legend: List<Pair<String, String>>,
    val findings: List<Diagnostic>,
    val texts: Texts,
    val theme: String,
    /** Primary result selected by the schema (or its final mainline result). */
    val headline: PaperHeadline? = null,
    /** Input navigation groups, retained independently of where rows are placed in tables. */
    val inputGroups: List<PaperInputGroup> = emptyList(),
)

data class PaperHeadline(val nodeId: String, val label: String, val value: String)
data class PaperInputGroup(val key: String, val title: String, val inputs: List<String>)

/** A panel as shown in the overview; [tableRef] links to the table presenting it, if any. */
class OverviewPanel(
    val panelId: String,
    val title: String,
    val tableRef: String?,
    val value: String,
    /** How the panel's result enters a mainline step, e.g. "→ 1 · Sonderausgaben". */
    val entry: String?,
)

class OverviewStep(val step: Int, val panel: OverviewPanel, val branches: List<OverviewPanel>)

class PaperTable(
    val id: String,
    /** Working-paper index of the table, e.g. "1" or "2"; rows are cited as "<ref>/<row>". */
    val ref: String,
    val title: String,
    /** Position of the table's panel on the mainline, e.g. "Hauptlinie › 1 … › Sonderausgaben". */
    val breadcrumb: String?,
    val style: TableStyle,
    val columns: List<PaperColumn>,
    val rows: List<PaperRow>,
)

class PaperColumn(val id: String, val header: String, val content: ColumnContent, val align: Align, val width: Int?)

enum class RowKind {
    HEADING,
    VALUE,
    OPTION,
    MEMBER,
    SUBTOTAL,
    RESULT,
    TOTAL,
    NOTE,
    REFERENCE,
}

enum class RowFlag {
    INACTIVE,
    USER_DEFINED,
    SELECTED,
    FOOTED,
    INFO,
    GRAND,

    /** Displayed as a deduction (signed layouts show the positive value negatively). */
    NEGATED,

    /** A non-zero input was reduced to zero by a rule, so the row remains visible. */
    EXPLAINS_ZERO,

    /** A business decision, independent of formula evaluation success. */
    VALIDATION_PASSED,
    VALIDATION_FAILED,
}

class PaperRow(
    val kind: RowKind,
    val depth: Int,
    val cells: List<String>,
    val nodeId: String? = null,
    val flags: Set<RowFlag> = emptySet(),
    /** Stable anchor used to link table rows and audit-trail entries. */
    val anchor: String? = null,
    /** Tiered placement: `true` when the row's value belongs in the lead column. */
    val lead: Boolean = false,
    /** For [RowKind.OPTION] rows: the option key of the choice [nodeId]. */
    val optionKey: String? = null,
    /** For [RowKind.REFERENCE] rows: the schedule section the row points to. */
    val sectionId: String? = null,
    val classes: List<String> = emptyList(),
    val style: StyleSpec = StyleSpec(),
    /** One context and resolved style per visible cell; future selectors can use these facts. */
    val cellContexts: List<StyleContext> = emptyList(),
    val cellStyles: List<StyleSpec> = emptyList(),
    /** Exact engine address per visible cell, including multidimensional and transposed values. */
    val valueAddresses: List<PaperValueAddress?> = emptyList(),
)

data class PaperValueAddress(
    val nodeId: String,
    val coord: com.xqiou.mantra.core.view.Coord = emptyList(),
    val aggregate: Boolean = false,
    val fixed: Map<String, String> = emptyMap(),
)

class AuditEntry(
    val anchor: String,
    val citation: String,
    val label: String,
    val member: String?,
    val formula: String,
    val working: String,
    val result: String,
    val reference: String?,
    val nodeId: String? = null,
    val coord: com.xqiou.mantra.core.view.Coord = emptyList(),
    /** The same detached source evidence returned by Explain for this calculated value. */
    val explanation: com.xqiou.mantra.core.view.ExplainTrace? = null,
    /** Authoritative host aggregation evidence; never represented as invented kernel steps. */
    val aggregate: com.xqiou.mantra.core.view.RatioAggregateTrace? = null,
    val reduction: com.xqiou.mantra.core.view.AggregateTrace? = null,
)
