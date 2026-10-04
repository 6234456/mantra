package com.xqiou.mantra.render.paper

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.api.CalculationReader
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.ViewItem
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.view.ViewSection
import com.xqiou.mantra.core.view.ViewTreeNode
import com.xqiou.mantra.core.view.displayLabel
import com.xqiou.mantra.render.layout.Align
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.ColumnSpec
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.RowNumberMode
import com.xqiou.mantra.render.layout.StyleContext
import com.xqiou.mantra.render.layout.TableSpec
import com.xqiou.mantra.render.layout.TableStyle
import com.xqiou.mantra.render.layout.styleRole

/** Places engine-owned slices on two visible axes. No numeric aggregation belongs in this class. */
internal class MultidimensionalPaperBuilder(
    private val view: CalculationView,
    private val layout: LayoutSpec,
    private val spec: TableSpec,
    private val section: ViewSection,
    private val ref: String,
    private val breadcrumb: String?,
    private val includeAll: Boolean,
    private val auditNode: (ViewNode, String, String) -> Unit,
    private val nextDocumentNumber: () -> String,
    private val reader: CalculationReader,
) {
    private val numbers = NumberFormatter(layout.number)
    private val rowDimension = requireNotNull(spec.rowDimension)
    private val style = spec.style ?: TableStyle.MATRIX
    private var rowNumber = 0
    private val visibleNodeIds = buildSet {
        fun visit(item: ViewItem) {
            reader.chargeScans()
            when (item) {
                is ViewSection -> if (item.id !in layout.hidden && !item.item.presentation.hidden &&
                    item.item.display != com.xqiou.mantra.core.model.SectionDisplay.HIDDEN
                ) {
                    item.children.forEach(::visit)
                }
                is ViewTreeNode -> if (item.id !in layout.hidden && !item.item.presentation.hidden) add(item.id)
                else -> Unit
            }
        }
        visit(view.tree)
    }
    private val nodes = buildList {
        fun visit(item: ViewItem) {
            reader.chargeScans()
            when (item) {
                is ViewSection -> if (item.id !in layout.hidden &&
                    item.item.display != com.xqiou.mantra.core.model.SectionDisplay.HIDDEN &&
                    !item.item.presentation.hidden
                ) {
                    item.children.forEach(::visit)
                }
                is ViewTreeNode -> if (item.id !in layout.hidden &&
                    !item.item.presentation.hidden
                ) {
                    view.nodes[item.id]?.let(::add)
                }
                else -> Unit
            }
        }
        visit(section)
    }
    private val configured = spec.columns ?: if (style == TableStyle.TRANSPOSE) {
        listOf(ColumnSpec("label", null, ColumnContent.Label)) + nodes.map {
            ColumnSpec(it.id, it.displayLabel(), ColumnContent.Node(it.id))
        }
    } else {
        layout.matrixColumns
    }
    private val visibleColumns = configured.filter { column ->
        val node = (column.content as? ColumnContent.Node)?.nodeId
        node == null || node !in view.nodes || node in visibleNodeIds
    }
    private val requested = if (layout.rowNumbers != null &&
        visibleColumns.none { it.content == ColumnContent.RowNumber }
    ) {
        listOf(ColumnSpec("row-number", null, ColumnContent.RowNumber, width = 5)) + visibleColumns
    } else {
        visibleColumns
    }
    private val columns = requested.flatMap { column ->
        when (val content = column.content) {
            is ColumnContent.Members -> {
                val dim = if (content.dimension == "*") {
                    nodes.flatMap { it.dims }.firstOrNull { it != rowDimension }
                } else {
                    content.dimension
                }
                dim?.let { view.members[it] }.orEmpty().map { member ->
                    PaperColumn(
                        "${column.id}-${member.key}",
                        member.label,
                        ColumnContent.Member(requireNotNull(dim), member.key),
                        Align.RIGHT,
                        column.width,
                    )
                }
            }
            else -> listOf(
                PaperColumn(
                    column.id,
                    column.header ?: header(content),
                    content,
                    column.align ?: if (content.numeric) Align.RIGHT else Align.LEFT,
                    column.width,
                ),
            )
        }
    }

    fun build(): PaperTable {
        reader.checkpoint()
        validate()
        val rows = mutableListOf<PaperRow>()
        val members = view.members.getValue(rowDimension)
        if (style == TableStyle.TRANSPOSE) {
            members.forEach { member ->
                reader.chargeScans()
                transposeRow(member.label, spec.fixed + (rowDimension to member.key), rows.size)?.let(rows::add)
            }
            if (members.isNotEmpty()) {
                transposeRow(
                    layout.texts.total,
                    spec.fixed,
                    rows.size,
                    total = true,
                )?.let(rows::add)
            }
        } else {
            members.forEach { member ->
                reader.chargeScans()
                rows += row(RowKind.HEADING, member.label, emptyList(), emptyList(), rows.size)
                matrixRows(spec.fixed + (rowDimension to member.key), rows)
            }
            if (members.isNotEmpty()) {
                rows += row(RowKind.HEADING, layout.texts.total, emptyList(), emptyList(), rows.size)
                matrixRows(spec.fixed, rows)
            }
        }
        (
            nodes + columns.mapNotNull {
                (it.content as? ColumnContent.Node)?.nodeId?.let(view.nodes::get)
            }
            ).distinctBy { it.id }.forEachIndexed { index, node ->
            auditNode(node, "$ref/${index + 1}", "t$ref-n${node.id}")
        }
        return PaperTable(section.id, ref, spec.title ?: section.label, breadcrumb, style, columns, rows)
    }

    private fun validate() {
        val sink = DiagnosticSink()
        fun error(message: String) = sink.error("MANTRA-LAYOUT-AXES", message, section.item.location)
        if (style == TableStyle.TIERED) error("A row dimension requires a matrix or transpose table")
        if (rowDimension !in view.members) error("Unknown row dimension '$rowDimension'")
        if (rowDimension in spec.fixed) error("The row dimension cannot also be fixed")
        spec.fixed.forEach { (dimension, key) ->
            if (view.members[dimension]?.none { it.key == key } !=
                false
            ) {
                error("Unknown fixed member '$dimension=$key'")
            }
        }
        columns.forEach { column ->
            when (val content = column.content) {
                is ColumnContent.Member -> {
                    if (style == TableStyle.TRANSPOSE) error("Transpose columns must select nodes, not members")
                    if (content.dimension == rowDimension || content.dimension in spec.fixed) {
                        error("Row, column and fixed dimensions must be distinct")
                    }
                    if (view.members[content.dimension]?.none { it.key == content.key } != false) {
                        error("Unknown column member '${content.dimension}=${content.key}'")
                    }
                }
                is ColumnContent.Node -> {
                    if (style != TableStyle.TRANSPOSE) error("Node columns require :style :transpose")
                    val target = view.nodes[content.nodeId]
                    if (target == null) {
                        error("Unknown node '${content.nodeId}'")
                    } else if (rowDimension !in
                        target.dims
                    ) {
                        error("Node '${content.nodeId}' must share the row dimension")
                    }
                }
                else -> Unit
            }
        }
        val columnNodes = columns.mapNotNull { (it.content as? ColumnContent.Node)?.nodeId?.let(view.nodes::get) }
        if ((nodes + columnNodes).none {
                rowDimension in it.dims
            }
        ) {
            error("No table node uses row dimension '$rowDimension'")
        }
        sink.throwIfErrors()
    }

    private fun matrixRows(fixed: Map<String, String>, rows: MutableList<PaperRow>) {
        nodes.forEach { node ->
            val slice = slice(node, fixed)
            if (!includeAll && !layout.showInactive && slice.none { node.isActive(it) }) return@forEach
            if (!includeAll && layout.hideZero && node.total == null && node.validations.isEmpty() &&
                zero(node, slice) && !explainsZero(node, slice)
            ) {
                return@forEach
            }
            val values = columns.map { column ->
                when (val content = column.content) {
                    is ColumnContent.Member -> valueCell(node, fixed + (content.dimension to content.key))
                    ColumnContent.Value, ColumnContent.CrossTotal, ColumnContent.Main -> valueCell(node, fixed)
                    else -> "" to null
                }
            }
            val flags = flags(node, slice)
            rows += row(
                if (node.kind == NodeKind.TOTAL) RowKind.TOTAL else RowKind.VALUE,
                node.displayLabel(),
                values.map { it.first },
                values.map { it.second },
                rows.size,
                node.id,
                node.presentation,
                flags,
            )
        }
    }

    private fun transposeRow(label: String, fixed: Map<String, String>, index: Int, total: Boolean = false): PaperRow? {
        val scopes = columns.mapNotNull { (it.content as? ColumnContent.Node)?.nodeId?.let(view.nodes::get) }
            .distinctBy { it.id }.map { it to slice(it, fixed) }
        val active = scopes.any { (node, coords) -> coords.any(node::isActive) }
        if (!includeAll && !layout.showInactive && !active) return null
        if (!total && !includeAll && layout.hideZero && scopes.isNotEmpty() && scopes.all { (node, coords) ->
                zero(node, coords) && !explainsZero(node, coords) && coords.none { it in node.validations }
            }
        ) {
            return null
        }
        val flags = scopes.flatMap { (node, coords) -> flags(node, coords) }.toMutableSet().apply {
            remove(RowFlag.INACTIVE)
            if (!active) add(RowFlag.INACTIVE)
            if (RowFlag.VALIDATION_FAILED in this) remove(RowFlag.VALIDATION_PASSED)
            if (scopes.any { it.first.op >= 0 }) remove(RowFlag.NEGATED)
            if (total) add(RowFlag.FOOTED)
        }
        val values = columns.map { column ->
            (column.content as? ColumnContent.Node)?.let { valueCell(view.node(it.nodeId), fixed) } ?: ("" to null)
        }
        return row(
            if (total) RowKind.TOTAL else RowKind.MEMBER,
            label,
            values.map { it.first },
            values.map { it.second },
            index,
            flags = flags,
        )
    }

    private fun valueCell(node: ViewNode, fixed: Map<String, String>): Pair<String, PaperValueAddress?> {
        val coords = slice(node, fixed)
        if (coords.isEmpty()) return "" to null
        val complete = reader.coordinate(view, node.id, fixed)
        val value = if (complete != null) node.value(complete) else reader.reduce(view, node.id, fixed).value
        if (value == null) return "" to null
        val address = if (complete != null) {
            PaperValueAddress(node.id, complete)
        } else {
            PaperValueAddress(node.id, aggregate = true, fixed = fixed)
        }
        val active = coords.any(node::isActive)
        val text = when {
            !active -> if (layout.showInactive || includeAll) layout.texts.notApplicable else ""
            node.check != null -> {
                val checks = coords.mapNotNull { node.validations[it]?.passed }
                when {
                    checks.any { !it } -> "✗"
                    checks.isNotEmpty() -> "✓"
                    else -> ""
                }
            }
            value == Value.Nil && node.type.isNumeric -> "undefined"
            else ->
                numbers.value(
                    if (layout.signedValues && node.op < 0 &&
                        value is Value.Num
                    ) {
                        Value.Num(value.value.negate())
                    } else {
                        value
                    },
                    node.presentation.format,
                    node.presentation.precision,
                )
        }
        return text to address
    }

    private fun slice(node: ViewNode, fixed: Map<String, String>) = reader.coordinates(view, node.id, fixed)

    private fun zero(node: ViewNode, coords: List<List<String>>) =
        coords.all { (node.value(it) as? Value.Num)?.value?.signum() == 0 }

    private fun explainsZero(node: ViewNode, coords: List<List<String>>) = coords.any { coord ->
        val trace = node.trace(coord)
        trace is NodeTrace.Computed && trace.references.any { ref ->
            val source = view.nodes[ref.id.removePrefix("all.")]
            source != null && source.kind != NodeKind.PARAM &&
                (ref.value as? Value.Num)?.value?.signum()?.let { it != 0 } == true
        }
    }

    private fun flags(node: ViewNode, coords: List<List<String>>) = buildSet {
        if (node.kind == NodeKind.TOTAL) add(RowFlag.FOOTED)
        if (node.userDefined) add(RowFlag.USER_DEFINED)
        if (layout.signedValues && node.op < 0) add(RowFlag.NEGATED)
        if (coords.none(node::isActive)) add(RowFlag.INACTIVE)
        if (zero(node, coords) && explainsZero(node, coords)) add(RowFlag.EXPLAINS_ZERO)
        if (coords.any { node.validations[it]?.passed == false }) {
            add(RowFlag.VALIDATION_FAILED)
        } else if (coords.any { node.validations[it]?.passed == true }) {
            add(RowFlag.VALIDATION_PASSED)
        }
    }

    private fun row(
        kind: RowKind,
        label: String,
        values: List<String>,
        addresses: List<PaperValueAddress?>,
        index: Int,
        nodeId: String? = null,
        presentation: Presentation? = null,
        flags: Set<RowFlag> = emptySet(),
    ): PaperRow {
        val number = if (kind == RowKind.HEADING || columns.none { it.content == ColumnContent.RowNumber }) {
            ""
        } else {
            if (layout.rowNumbers == RowNumberMode.GLOBAL) nextDocumentNumber() else (++rowNumber).toString()
        }
        val contexts = columns.map { column ->
            StyleContext(
                section.id, listOf(section.id), if (kind == RowKind.HEADING) 1 else 2,
                if (kind == RowKind.HEADING) 1 else 0, if (kind == RowKind.HEADING) 0 else 1,
                index + 1, kind.name.lowercase(), columns.any { it.content == ColumnContent.RowNumber },
                presentation?.classes.orEmpty(), column.id, column.content.styleRole(),
            )
        }
        val cells = columns.mapIndexed { columnIndex, column ->
            when (column.content) {
                ColumnContent.Label -> label
                ColumnContent.RowNumber -> number
                ColumnContent.Status -> buildString {
                    if (RowFlag.FOOTED in flags) append("Σ")
                    if (RowFlag.INACTIVE in flags) append("–")
                    if (RowFlag.VALIDATION_FAILED in flags) append("✗")
                    if (RowFlag.VALIDATION_PASSED in flags) append("✓")
                }
                ColumnContent.Reference -> presentation?.reference.orEmpty()
                ColumnContent.Note -> presentation?.note.orEmpty()
                ColumnContent.Source -> presentation?.source.orEmpty()
                else -> values.getOrNull(columnIndex).orEmpty()
            }
        }
        return PaperRow(
            kind, if (kind == RowKind.HEADING) 0 else 1, cells, nodeId, flags,
            anchor = "t$ref-r${index + 1}", classes = presentation?.classes.orEmpty(),
            style = layout.styleFor(presentation?.classes.orEmpty()), cellContexts = contexts,
            cellStyles = contexts.map(layout::styleFor),
            valueAddresses = columns.indices.map {
                addresses.getOrNull(it)
            },
        )
    }

    private fun header(content: ColumnContent): String = when (content) {
        ColumnContent.Label -> layout.texts.label
        ColumnContent.RowNumber -> layout.texts.row
        ColumnContent.CrossTotal -> layout.texts.total
        is ColumnContent.Node -> view.nodes[content.nodeId]?.displayLabel() ?: content.nodeId
        is ColumnContent.Member -> view.members[content.dimension]?.firstOrNull { it.key == content.key }?.label
            ?: content.key
        else -> content.styleRole()
    }
}
