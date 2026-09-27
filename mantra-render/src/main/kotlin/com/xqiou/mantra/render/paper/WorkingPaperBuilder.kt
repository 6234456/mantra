package com.xqiou.mantra.render.paper

import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.engine.Coord
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.engine.NodeTrace
import com.xqiou.mantra.core.view.ViewItem
import com.xqiou.mantra.core.view.ViewTreeNode
import com.xqiou.mantra.core.view.ViewNote
import com.xqiou.mantra.core.view.ViewSection
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.engine.TraceRef
import com.xqiou.mantra.core.model.ChoiceItem
import com.xqiou.mantra.core.model.ChoiceRule
import com.xqiou.mantra.core.model.Op
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.SectionDisplay
import com.xqiou.mantra.core.model.TotalItem
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.structure.PanelRole
import com.xqiou.mantra.core.structure.SchemaMap
import com.xqiou.mantra.core.structure.SchemaMaps
import com.xqiou.mantra.render.layout.Align
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.ColumnSpec
import com.xqiou.mantra.render.layout.ExplainMode
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.RowNumberMode
import com.xqiou.mantra.render.layout.TableSpec
import com.xqiou.mantra.render.layout.TableStyle
import com.xqiou.mantra.render.layout.StyleContext
import com.xqiou.mantra.render.layout.styleRole
import java.math.BigDecimal

/**
 * Applies a [LayoutSpec] to a [CalculationView]. With [includeAll], value-dependent hiding
 * (zero and inactive rows) is disabled so that spreadsheet exports keep every line of logic.
 */
class WorkingPaperBuilder(
    private val result: CalculationView,
    private val layout: LayoutSpec,
    private val includeAll: Boolean = false,
) {
    private val numbers = NumberFormatter(layout.number)
    private val explainer = FormulaExplainer(numbers)
    private val texts = layout.texts
    private val audit = mutableListOf<AuditEntry>()
    private var documentRowNumber = 0
    private val sections = linkedMapOf<String, ViewSection>()
    private val tableRefs = linkedMapOf<String, String>()

    init {
        fun index(section: ViewSection) {
            sections[section.id] = section
            section.children.filterIsInstance<ViewSection>().forEach(::index)
        }
        index(result.tree)
    }

    /** Table ref of the table that presents each node (used to annotate carry-over lines). */
    private val nodeTables = hashMapOf<String, String>()

    private val map: SchemaMap by lazy { result.structure }

    /** Table presenting a panel: the panel's own table, or the table of an enclosing section. */
    private fun tableOfPanel(panelId: String): String? = tableRefs[panelId] ?: map.panels.firstOrNull { it.id == panelId }?.resultId?.let(nodeTables::get)

    private fun overviewPanel(panel: com.xqiou.mantra.core.structure.Panel): OverviewPanel {
        val value = panel.resultId?.let { id -> result.nodes[id]?.let { node -> node.crossTotal()?.let { format(node, Value.Num(it)) } } }.orEmpty()
        val entry = panel.entries.takeIf { panel.role == PanelRole.BRANCH }?.joinToString(", ") { "→ ${it.step} · ${it.viaLabel}" }
        return OverviewPanel(panel.id, panel.title, tableOfPanel(panel.id), value, entry)
    }

    private fun overview(): List<OverviewStep> = map.mainline.map { id ->
        val step = map.panel(id)
        val branches = map.panels.filter { it.role == PanelRole.BRANCH && it.position?.stepPanel == id }
        OverviewStep(step.step ?: 0, overviewPanel(step), branches.map(::overviewPanel))
    }

    private fun breadcrumb(sectionId: String): String? {
        val panel = map.panels.firstOrNull { it.id == sectionId } ?: return null
        return panel.breadcrumb.joinToString(" › ") { crumb ->
            when {
                crumb.panelId == null -> texts.mainline
                crumb.nodeId != null -> "„${crumb.label}“"
                else -> crumb.label
            }
        }
    }

    fun build(): WorkingPaper {
        val specs = planTables()
        specs.forEachIndexed { index, spec -> tableRefs[spec.sectionId] = (index + 1).toString() }
        specs.forEach { spec ->
            val ref = tableRefs.getValue(spec.sectionId)
            fun visit(item: ViewItem, root: Boolean) {
                when (item) {
                    is ViewSection -> if (root || item.id !in tableRefs) item.children.forEach { visit(it, false) }
                    is ViewTreeNode -> nodeTables.putIfAbsent(item.id, ref)
                    is ViewNote -> Unit
                }
            }
            sections[spec.sectionId]?.let { visit(it, true) }
        }
        val tables = specs.mapNotNull(::buildTable)
        val schema = result.schema
        return WorkingPaper(
            title = layout.title ?: schema.title,
            subtitle = layout.subtitle ?: schema.text("subtitle"),
            header = header(),
            overview = overview(),
            auxiliary = map.panels.filter { it.role == PanelRole.AUXILIARY }.map(::overviewPanel),
            tables = tables,
            audit = if (layout.explain == ExplainMode.APPENDIX) audit.toList() else emptyList(),
            legend = listOf("Σ" to texts.footed, "✓" to texts.selected, "▲" to texts.userDefined, "–" to texts.notApplicable),
            findings = result.diagnostics,
            texts = texts,
            theme = layout.theme,
        )
    }

    // ── Tables ─────────────────────────────────────────────────────────────────────────────────

    private fun isScheduleSection(section: ViewSection): Boolean =
        section.id !in layout.inline &&
            (section.item.display == SectionDisplay.SCHEDULE || section.id in layout.schedules || layout.tables.any { it.sectionId == section.id })

    private fun isHidden(id: String, presentation: Presentation?): Boolean = id in layout.hidden || presentation?.hidden == true

    private fun planTables(): List<TableSpec> {
        if (layout.tables.isNotEmpty()) {
            return layout.tables.filter { spec ->
                (spec.sectionId in sections).also { known ->
                    if (!known) System.err.println("mantra: layout table `${spec.sectionId}` does not name a section")
                }
            }
        }
        val specs = mutableListOf(TableSpec(result.tree.id))
        fun collect(section: ViewSection) {
            section.children.filterIsInstance<ViewSection>().forEach { child ->
                if (child.item.display == SectionDisplay.HIDDEN || isHidden(child.id, child.item.presentation)) return@forEach
                if (isScheduleSection(child)) specs += TableSpec(child.id)
                collect(child)
            }
        }
        collect(result.tree)
        return specs
    }

    private fun dimsIn(section: ViewSection): List<String> {
        val found = linkedSetOf<String>()
        fun visit(item: ViewItem) {
            when (item) {
                is ViewSection -> if (item === section || !isScheduleSection(item)) {
                    found += item.dims
                    item.children.forEach(::visit)
                }
                is ViewTreeNode -> found += item.dims
                is ViewNote -> Unit
            }
        }
        visit(section)
        return result.dimensionOrder(found)
    }

    private fun buildTable(spec: TableSpec): PaperTable? {
        val section = sections[spec.sectionId] ?: return null
        val dims = dimsIn(section)
        val style = spec.style ?: section.item.layout?.let { if (it == "matrix") TableStyle.MATRIX else TableStyle.TIERED }
            ?: if (dims.isNotEmpty()) TableStyle.MATRIX else TableStyle.TIERED
        val configured = spec.columns ?: if (style == TableStyle.MATRIX) layout.matrixColumns else layout.tieredColumns
        val requested = if (layout.rowNumbers != null && configured.none { it.content == ColumnContent.RowNumber }) {
            listOf(ColumnSpec("row-number", null, ColumnContent.RowNumber, width = 5)) + configured
        } else configured
        val memberDim = requested.firstNotNullOfOrNull { (it.content as? ColumnContent.Members)?.dimension?.takeIf { d -> d != "*" } }
            ?: requested.firstNotNullOfOrNull { (it.content as? ColumnContent.Member)?.dimension }
            ?: dims.firstOrNull()
        val columns = expandColumns(requested, memberDim)
        val context = TableContext(
            section = section,
            ref = tableRefs.getValue(section.id),
            style = style,
            memberDim = memberDim,
            expandMembers = spec.expandMembers ?: layout.expandMembers,
        )
        val rows = RowWalker(context).walk()
        if (rows.none { it.kind != RowKind.HEADING && it.kind != RowKind.NOTE }) return null
        val hierarchy = visibleHierarchy(rows)
        val title = spec.title ?: section.item.title ?: if (section === result.tree) result.schema.title else section.label
        val cells = rows.map { row -> columns.map { cell(it, row, context) } }
        // Columns without any content are dropped (e.g. an unused lead column or reference column).
        val keep = columns.indices.filter { index ->
            val content = columns[index].content
            content == ColumnContent.Label || content is ColumnContent.Member || content == ColumnContent.CrossTotal ||
                cells.any { it[index].isNotEmpty() }
        }
        val visibleColumns = keep.map { columns[it] }
        val hasRowNumber = visibleColumns.any { it.content == ColumnContent.RowNumber }
        return PaperTable(
            id = section.id,
            ref = context.ref,
            title = title,
            breadcrumb = breadcrumb(section.id),
            style = style,
            columns = visibleColumns,
            rows = rows.mapIndexed { i, row ->
                val classes = row.presentation?.classes.orEmpty()
                val contexts = visibleColumns.map { column ->
                    StyleContext(
                        tableId = section.id,
                        sectionPath = row.sectionPath,
                        depth = hierarchy.depths[i],
                        height = hierarchy.heights[i],
                        indent = row.depth,
                        rowIndex = i + 1,
                        rowKind = row.kind.name.lowercase(),
                        hasRowNumber = hasRowNumber,
                        classes = classes,
                        columnId = column.id,
                        columnRole = column.content.styleRole(),
                    )
                }
                PaperRow(row.kind, row.depth, keep.map { cells[i][it] }, row.nodeId, row.flags, row.anchor, row.placement == Placement.PRE, row.optionKey, row.sectionId,
                    classes, layout.styleFor(classes), contexts, contexts.map(layout::styleFor))
            },
        )
    }

    private data class VisibleHierarchy(val depths: IntArray, val heights: IntArray)

    /** D3-style depth and height of the rows actually present in one table. The table root is implicit at depth 0. */
    private fun visibleHierarchy(rows: List<RowData>): VisibleHierarchy {
        val parents = IntArray(rows.size) { -1 }
        val depths = IntArray(rows.size)
        val heights = IntArray(rows.size)
        val openSections = ArrayDeque<Int>()
        fun prefix(prefix: List<String>, path: List<String>) =
            prefix.size <= path.size && prefix.indices.all { prefix[it] == path[it] }
        rows.forEachIndexed { index, row ->
            while (openSections.isNotEmpty() && !prefix(rows[openSections.last()].sectionPath, row.sectionPath)) {
                openSections.removeLast()
            }
            val parent = row.parentRowIndex ?: openSections.lastOrNull() ?: -1
            parents[index] = parent
            depths[index] = if (parent < 0) 1 else depths[parent] + 1
            if (row.kind == RowKind.HEADING) openSections.addLast(index)
        }
        rows.indices.reversed().forEach { index ->
            val parent = parents[index]
            if (parent >= 0) heights[parent] = maxOf(heights[parent], heights[index] + 1)
        }
        return VisibleHierarchy(depths, heights)
    }

    private fun expandColumns(requested: List<ColumnSpec>, memberDim: String?): List<PaperColumn> = requested.flatMap { spec ->
        when (val content = spec.content) {
            is ColumnContent.Members -> {
                val dim = if (content.dimension == "*") memberDim else content.dimension
                dim?.let { d ->
                    result.members[d].orEmpty().map { member ->
                        PaperColumn("${spec.id}-${member.key}", member.label, ColumnContent.Member(d, member.key), Align.RIGHT, spec.width)
                    }
                }.orEmpty()
            }
            is ColumnContent.CrossTotal -> if (memberDim == null) {
                listOf(PaperColumn(spec.id, spec.header ?: texts.main, content, spec.align ?: Align.RIGHT, spec.width))
            } else {
                listOf(PaperColumn(spec.id, spec.header ?: texts.total, content, spec.align ?: Align.RIGHT, spec.width))
            }
            else -> listOf(
                PaperColumn(
                    spec.id,
                    spec.header ?: defaultHeader(content),
                    content,
                    spec.align ?: if (content.numeric) Align.RIGHT else if (content == ColumnContent.Status || content == ColumnContent.Operator) Align.CENTER else Align.LEFT,
                    spec.width,
                ),
            )
        }
    }

    private fun defaultHeader(content: ColumnContent): String = when (content) {
        ColumnContent.Label -> texts.label
        ColumnContent.Pre -> texts.pre
        ColumnContent.Main, ColumnContent.Value -> texts.main
        ColumnContent.Reference -> texts.reference
        ColumnContent.RowNumber -> texts.row
        is ColumnContent.Attribute -> content.name
        ColumnContent.Formula -> texts.formula
        ColumnContent.Explain -> texts.explain
        else -> ""
    }

    private class TableContext(
        val section: ViewSection,
        val ref: String,
        val style: TableStyle,
        val memberDim: String?,
        val expandMembers: Boolean,
    )

    private enum class Placement { PRE, MAIN, NONE }

    private class RowData(
        val kind: RowKind,
        val depth: Int,
        val label: String,
        val op: String = "",
        val nodeId: String? = null,
        val placement: Placement = Placement.NONE,
        val scalar: String = "",
        val members: Map<String, String> = emptyMap(),
        val crossTotal: String = "",
        val presentation: Presentation? = null,
        val flags: Set<RowFlag> = emptySet(),
        val formula: String = "",
        val explain: String = "",
        val rowNumber: String = "",
        val anchor: String? = null,
        val optionKey: String? = null,
        val sectionId: String? = null,
    ) {
        var sectionPath: List<String> = emptyList()
        var parentRowIndex: Int? = null
    }

    private fun cell(column: PaperColumn, row: RowData, context: TableContext): String = when (val content = column.content) {
        ColumnContent.Label -> row.label
        ColumnContent.Operator -> row.op
        ColumnContent.RowNumber -> row.rowNumber
        is ColumnContent.Attribute -> row.presentation?.attributes?.get(content.name)?.let(::attributeText).orEmpty()
        ColumnContent.Reference -> row.presentation?.reference.orEmpty()
        ColumnContent.Note -> row.presentation?.note.orEmpty()
        ColumnContent.Source -> row.presentation?.source.orEmpty()
        ColumnContent.Status -> buildString {
            if (RowFlag.FOOTED in row.flags) append("Σ")
            if (RowFlag.SELECTED in row.flags) append("✓")
            if (RowFlag.USER_DEFINED in row.flags) append("▲")
            if (RowFlag.INACTIVE in row.flags) append("–")
        }
        ColumnContent.Formula -> row.formula
        ColumnContent.Explain -> row.explain
        ColumnContent.Value -> row.scalar
        ColumnContent.Pre -> if (row.placement == Placement.PRE) row.scalar else ""
        ColumnContent.Main -> if (row.placement == Placement.MAIN) row.scalar else ""
        ColumnContent.CrossTotal -> if (context.style == TableStyle.MATRIX) row.crossTotal else row.scalar
        is ColumnContent.Member -> if (content.dimension == context.memberDim) row.members[content.key].orEmpty() else ""
        is ColumnContent.Members -> ""
    }

    private fun attributeText(value: Value): String = when (value) {
        Value.Nil -> ""
        is Value.Text -> value.value
        is Value.Kw -> value.name
        else -> value.toString()
    }

    // ── Row walking ────────────────────────────────────────────────────────────────────────────

    private inner class RowWalker(private val context: TableContext) {
        private val rows = mutableListOf<RowData>()
        private var counter = 0
        private val root = context.section
        private val path = mutableListOf(root.id)
        private val lastRootTotal = root.children.filterIsInstance<ViewTreeNode>().lastOrNull { it.item is TotalItem }?.id

        private fun addRow(row: RowData, childSection: String? = null, parentRowIndex: Int? = null) {
            row.sectionPath = path.toList() + listOfNotNull(childSection)
            row.parentRowIndex = parentRowIndex
            rows += row
        }

        fun walk(): List<RowData> {
            walkChildren(root, depth = 0, childLevel = 0, resultLevel = 0)
            return rows
        }

        private fun nextNumber(): String = when (layout.rowNumbers) {
            RowNumberMode.GLOBAL -> (++documentRowNumber).toString()
            RowNumberMode.TABLE, null -> (++counter).toString()
        }

        private fun walkChildren(section: ViewSection, depth: Int, childLevel: Int, resultLevel: Int) {
            for (child in section.children) {
                when (child) {
                    is ViewSection -> walkSection(child, depth, childLevel)
                    is ViewTreeNode -> {
                        val isResult = child.id == section.resultId && section !== root
                        nodeRows(child, depth, if (isResult) resultLevel else childLevel, if (isResult) section else null)
                    }
                    is ViewNote -> if (!isHidden("", child.item.presentation)) addRow(RowData(RowKind.NOTE, depth, child.item.text, presentation = child.item.presentation))
                }
            }
        }

        private fun walkSection(section: ViewSection, depth: Int, level: Int) {
            if (section.item.display == SectionDisplay.HIDDEN || isHidden(section.id, section.item.presentation)) return
            if (!includeAll && !layout.showInactive && sectionInactive(section)) return
            if (!includeAll && layout.hideZero && sectionZero(section)) return
            if (isScheduleSection(section) && section !== root) {
                addRow(referenceRow(section, depth, level), section.id)
                return
            }
            path += section.id
            val opaque = section.resultId != null
            addRow(RowData(
                RowKind.HEADING,
                depth,
                section.label,
                op = if (opaque && section.item.op == Op.MINUS) layout.operators.minus else "",
                presentation = section.item.presentation,
                flags = if (section.item.userDefined) setOf(RowFlag.USER_DEFINED) else emptySet(),
            ))
            if (opaque) walkChildren(section, depth + 1, level + 1, level) else walkChildren(section, depth + 1, level, level)
            path.removeAt(path.lastIndex)
        }

        private fun referenceRow(section: ViewSection, depth: Int, level: Int): RowData {
            val target = tableRefs[section.id]
            val node = section.resultId?.let { result.nodes[it] }
            val label = section.label + (target?.let { " (→ ${texts.table} $it)" } ?: "")
            val number = nextNumber()
            return RowData(
                kind = RowKind.REFERENCE,
                depth = depth,
                label = label,
                op = when (section.item.op) {
                    Op.MINUS -> layout.operators.minus
                    Op.INFO -> layout.operators.info
                    Op.PLUS -> layout.operators.plus
                },
                nodeId = section.resultId,
                placement = if (level == 0) Placement.MAIN else Placement.PRE,
                scalar = node?.let { n -> n.crossTotal()?.let { format(n, signed(Value.Num(it), layout.signedValues && section.item.op == Op.MINUS)) } }.orEmpty(),
                members = node?.let { memberCells(it, layout.signedValues && section.item.op == Op.MINUS) }.orEmpty(),
                crossTotal = node?.let { n -> n.crossTotal()?.let { format(n, signed(Value.Num(it), layout.signedValues && section.item.op == Op.MINUS)) } }.orEmpty(),
                presentation = section.item.presentation,
                flags = if (layout.signedValues && section.item.op == Op.MINUS) setOf(RowFlag.NEGATED) else emptySet(),
                rowNumber = number,
                anchor = "t${context.ref}-r$number",
                sectionId = section.id,
            )
        }

        private fun nodeRows(node: ViewTreeNode, depth: Int, level: Int, resultOf: ViewSection?) {
            val item = node.item
            val nodeResult = result.nodes[node.id] ?: return
            if (isHidden(node.id, item.presentation)) return
            if (!includeAll && !nodeResult.anyActive && !layout.showInactive) return
            if (!includeAll && layout.hideZero && item !is TotalItem && isZero(nodeResult) && !explainsZero(nodeResult)) return
            val isTotal = item is TotalItem
            val kind = when {
                !isTotal -> RowKind.VALUE
                resultOf != null -> RowKind.RESULT
                node.id == lastRootTotal -> RowKind.TOTAL
                level == 0 -> RowKind.TOTAL
                else -> RowKind.SUBTOTAL
            }
            val op = when {
                isTotal && resultOf != null && resultOf.item.op == Op.MINUS -> layout.operators.minus
                isTotal -> layout.operators.total
                node.op > 0 -> layout.operators.plus
                node.op < 0 -> layout.operators.minus
                else -> layout.operators.info
            }
            val flags = buildSet {
                if (isTotal) add(RowFlag.FOOTED)
                if (item.userDefined) add(RowFlag.USER_DEFINED)
                if (!nodeResult.anyActive) add(RowFlag.INACTIVE)
                if (node.op == 0 && !isTotal) add(RowFlag.INFO)
                if (node.id == lastRootTotal) add(RowFlag.GRAND)
            }
            val number = nextNumber()
            val anchor = "t${context.ref}-r$number"
            val negate = layout.signedValues && (if (isTotal) resultOf?.item?.op == Op.MINUS else node.op < 0)
            val rowFlags = if (negate) flags + RowFlag.NEGATED else flags
            val scalarValue = signed(if (nodeResult.dims.isEmpty()) nodeResult.value() else nodeResult.crossTotal()?.let(Value::Num) ?: Value.Nil, negate)
            val scalarText = when {
                !nodeResult.anyActive -> texts.notApplicable
                nodeResult.dims.isEmpty() -> format(nodeResult, scalarValue)
                nodeResult.type.isNumeric && nodeResult.crossTotal() != null -> format(nodeResult, scalarValue)
                else -> ""
            }
            val carried = nodeResult.line?.formula?.form?.let { form ->
                (form as? com.xqiou.normein.dsl.form.DslForm.Atom)?.sourceText
            }?.let { source -> nodeTables[source]?.takeIf { it != context.ref } }
            addRow(RowData(
                kind = kind,
                depth = depth,
                label = item.label + (carried?.let { " (→ ${texts.table} $it)" } ?: ""),
                op = op,
                nodeId = node.id,
                placement = if (level == 0) Placement.MAIN else Placement.PRE,
                scalar = scalarText,
                members = memberCells(nodeResult, negate),
                crossTotal = if (nodeResult.type.isNumeric || nodeResult.dims.isEmpty()) scalarText else "",
                presentation = item.presentation,
                flags = rowFlags,
                formula = formulaText(nodeResult),
                explain = if (nodeResult.dims.isEmpty()) explainText(nodeResult, emptyList()) else "",
                rowNumber = number,
                anchor = anchor,
            ))
            val parentRowIndex = rows.lastIndex
            recordAudit(nodeResult, "${context.ref}/$number", anchor)
            if (item is ChoiceItem) optionRows(item, nodeResult, depth + 1, level + 1, parentRowIndex)
            if (context.style == TableStyle.TIERED && context.expandMembers && nodeResult.dims.isNotEmpty()) {
                nodeResult.values.keys.forEach { coord ->
                    if (!nodeResult.isActive(coord)) return@forEach
                    addRow(RowData(
                        kind = RowKind.MEMBER,
                        depth = depth + 1,
                        label = memberLabel(nodeResult.dims, coord),
                        placement = Placement.PRE,
                        scalar = format(nodeResult, nodeResult.value(coord)),
                        presentation = item.presentation,
                        flags = setOf(RowFlag.INFO),
                    ), parentRowIndex = parentRowIndex)
                }
            }
        }

        private fun optionRows(item: ChoiceItem, node: ViewNode, depth: Int, level: Int, parentRowIndex: Int) {
            item.options.forEach { option ->
                val perCoord = node.traces.mapValues { (_, trace) -> (trace as? NodeTrace.Choice)?.options?.firstOrNull { it.key == option.key } }
                val selectedAnywhere = node.traces.values.any { (it as? NodeTrace.Choice)?.selected == option.key }
                val scalarOption = perCoord[emptyList()]
                val memberTexts = if (context.memberDim != null && node.dims.contains(context.memberDim)) {
                    perCoord.entries.associate { (coord, outcome) ->
                        coord[node.dims.indexOf(context.memberDim)] to optionText(node, outcome, (node.traces[coord] as? NodeTrace.Choice)?.selected == option.key)
                    }
                } else {
                    emptyMap()
                }
                addRow(RowData(
                    kind = RowKind.OPTION,
                    depth = depth,
                    label = option.label,
                    placement = if (level == 0) Placement.MAIN else Placement.PRE,
                    scalar = scalarOption?.let { optionText(node, it, false) }.orEmpty(),
                    members = memberTexts,
                    crossTotal = scalarOption?.let { optionText(node, it, false) }.orEmpty(),
                    presentation = item.presentation,
                    flags = buildSet {
                        add(RowFlag.INFO)
                        if (selectedAnywhere) add(RowFlag.SELECTED)
                    },
                    formula = option.formula.source,
                    nodeId = node.id,
                    optionKey = option.key,
                ), parentRowIndex = parentRowIndex)
            }
        }

        private fun optionText(node: ViewNode, outcome: com.xqiou.mantra.core.engine.TraceOption?, selected: Boolean): String {
            if (outcome == null) return ""
            if (!outcome.available) return texts.notApplicable
            return format(node, outcome.value) + if (selected) " ✓" else ""
        }

        private fun memberCells(node: ViewNode, negate: Boolean = false): Map<String, String> {
            val dim = context.memberDim ?: return emptyMap()
            val index = node.dims.indexOf(dim)
            if (index < 0) return emptyMap()
            val sums = linkedMapOf<String, BigDecimal>()
            val activeKeys = mutableSetOf<String>()
            val nonNumeric = linkedMapOf<String, Value>()
            node.values.forEach { (coord, value) ->
                val key = coord[index]
                if (node.isActive(coord)) activeKeys += key
                if (value is Value.Num) sums[key] = (sums[key] ?: BigDecimal.ZERO) + value.value else nonNumeric[key] = value
            }
            return result.members[dim].orEmpty().associate { member ->
                val text = when {
                    member.key !in activeKeys -> if (layout.showInactive && node.values.keys.any { it[index] == member.key }) texts.notApplicable else ""
                    sums.containsKey(member.key) -> format(node, signed(Value.Num(sums.getValue(member.key)), negate))
                    else -> format(node, nonNumeric[member.key] ?: Value.Nil)
                }
                member.key to text
            }
        }
    }

    // ── Values, formulas and audit trail ──────────────────────────────────────────────────────

    private fun signed(value: Value, negate: Boolean): Value =
        if (negate && value is Value.Num) Value.Num(value.value.negate()) else value

    private fun format(node: ViewNode, value: Value): String =
        numbers.value(value, presentationOf(node.id)?.format, presentationOf(node.id)?.precision)

    private fun presentationOf(id: String): Presentation? = result.nodes[id]?.presentation

    /**
     * A zero line is kept when it turned non-zero inputs into zero (Freigrenze, exemption threshold,
     * cap): that zero is the explanation. Parameters do not count as such inputs.
     */
    private fun explainsZero(node: ViewNode): Boolean = node.traces.values.any { trace ->
        trace is NodeTrace.Computed && trace.references.any { ref ->
            val source = result.nodes[ref.id.removePrefix("all.")]
            source != null && source.kind != NodeKind.PARAM &&
                (ref.value as? Value.Num)?.value?.signum()?.let { it != 0 } == true
        }
    }

    private fun isZero(node: ViewNode): Boolean =
        node.values.values.all { it == Value.Nil || (it is Value.Num && it.value.signum() == 0) }

    private fun sectionZero(section: ViewSection): Boolean = section.children.all { child ->
        when (child) {
            is ViewSection -> sectionZero(child)
            is ViewTreeNode -> result.nodes[child.id]?.let(::isZero) ?: true
            is ViewNote -> true
        }
    }

    private fun sectionInactive(section: ViewSection): Boolean = section.children.all { child ->
        when (child) {
            is ViewSection -> sectionInactive(child)
            is ViewTreeNode -> result.nodes[child.id]?.anyActive != true
            is ViewNote -> true
        }
    }

    private fun memberLabel(dims: List<String>, coord: Coord): String = dims.mapIndexed { index, dim ->
        result.members[dim]?.firstOrNull { it.key == coord[index] }?.label ?: coord[index]
    }.joinToString(" / ")

    private fun formulaText(node: ViewNode): String {
        node.line?.let { return compact(it.formula.source) }
        node.choice?.let { choice ->
            return (if (choice.rule == ChoiceRule.MIN) "min" else "max") + "(" + choice.options.joinToString("; ") { compact(it.formula.source) } + ")"
        }
        return if (node.total != null) node.components.joinToString(" ") { (if (it.sign < 0) "− " else "+ ") + it.vertexId }.removePrefix("+ ") else ""
    }

    private fun compact(source: String): String = source.replace(Regex("\\s+"), " ").trim()

    private fun explainText(node: ViewNode, coord: Coord): String {
        val trace = node.trace(coord) ?: return ""
        val records = node.dims.mapIndexed { index, dim ->
            dim to (result.members[dim]?.firstOrNull { it.key == coord.getOrNull(index) }?.record.orEmpty())
        }.toMap()
        return when (trace) {
            is NodeTrace.Computed -> {
                val vertex = node.line ?: return ""
                val values = trace.references.associate { ref -> ref.id to ref.value }
                val working = explainer.explain(vertex.formula.form, values, records)
                val final = node.value(coord)
                val rounded = trace.rounding?.takeIf { trace.raw is Value.Num && final is Value.Num && (trace.raw as Value.Num).value.compareTo(final.value) != 0 }
                    ?.let { " → ${numbers.explain(trace.raw)} ${roundingText(it)}" }.orEmpty()
                "$working$rounded"
            }
            is NodeTrace.Sum -> trace.parts.mapIndexed { index, part ->
                val sign = if (part.sign < 0) "− " else if (index == 0) "" else "+ "
                sign + numbers.plain(part.value)
            }.joinToString(" ")
            is NodeTrace.Choice -> {
                val vertex = node.choice ?: return ""
                val rule = if (vertex.rule == ChoiceRule.MIN) "min" else "max"
                rule + "(" + trace.options.joinToString("; ") { option ->
                    option.label + " " + if (option.available) numbers.explain(option.value) else texts.notApplicable
                } + ")" + (trace.selected?.let { selected -> " → " + vertex.options.first { it.key == selected }.label } ?: "")
            }
            else -> ""
        }
    }

    private fun roundingText(rounding: com.xqiou.mantra.core.model.Rounding): String =
        "(${rounding.scale} ${rounding.mode.name.lowercase().replace('_', '-')})"

    private fun recordAudit(node: ViewNode, citation: String, anchor: String) {
        if (node.line == null && node.choice == null && node.total == null) return
        if (node.line?.formula?.form?.let { it is com.xqiou.normein.dsl.form.DslForm.Atom || it is com.xqiou.normein.dsl.form.DslForm.Postfix } == true) return
        if (node.total != null && node.components.size < 2) return
        node.values.keys.forEach { coord ->
            if (!node.isActive(coord)) return@forEach
            val working = explainText(node, coord)
            if (working.isEmpty()) return@forEach
            audit += AuditEntry(
                anchor = if (coord.isEmpty()) anchor else "$anchor-${coord.joinToString("-")}",
                citation = citation,
                label = node.label,
                member = if (coord.isEmpty()) null else memberLabel(node.dims, coord),
                formula = formulaText(node),
                working = working,
                result = format(node, node.value(coord)),
                reference = presentationOf(node.id)?.reference,
            )
        }
    }

    private fun header(): List<Pair<String, String>> {
        val case = result.case
        val schema = result.schema
        fun meta(key: String): String? = case.text(key) ?: (case.meta[key] as? Value.Num)?.value?.toPlainString()
        return layout.header.mapNotNull { key ->
            when (key) {
                "subject" -> (meta("subject") ?: meta("title"))?.let { texts.subject to it }
                "period" -> (meta("period") ?: schema.text("period"))?.let { texts.period to it }
                "schema" -> texts.schema to listOfNotNull(schema.title, schema.text("version")?.let { "v$it" }, "[${schema.id}]").joinToString(" ")
                "prepared-by" -> meta("prepared-by")?.let { texts.preparedBy to it }
                "reviewed-by" -> meta("reviewed-by")?.let { texts.reviewedBy to it }
                "date" -> meta("date")?.let { texts.date to it }
                "reference" -> meta("reference")?.let { texts.index to it }
                else -> meta(key)?.let { key to it }
            }
        }
    }

    @Suppress("unused")
    private fun TraceRef.display(): String = numbers.explain(value)
}
