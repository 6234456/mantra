package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.engine.NodeTrace
import com.xqiou.mantra.core.engine.ExplainTrace
import com.xqiou.mantra.core.engine.TraceRef
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.structure.Flow
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.CalculationCompare
import com.xqiou.mantra.core.view.ValueChange
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.ViewItem
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.view.ViewSection
import com.xqiou.mantra.core.view.groupKey
import com.xqiou.mantra.core.view.groupTitle
import com.xqiou.mantra.core.view.headlineId
import com.xqiou.mantra.core.view.signLabels
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.TableSpec
import com.xqiou.mantra.render.layout.styleRole
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.paper.NumberFormatter
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.WorkingPaper

/** Pure projections of the public read-only calculation and presentation views. */
object WorkbenchDocuments {
    /** One calculated value and its bounded source trace; no evaluator state reaches the UI. */
    fun explain(view: CalculationView, layout: LayoutSpec, nodeId: String, coord: List<String>,
                full: ExplainTrace?, cell: Pair<String, String>? = null, cellValue: Value? = null): Map<String, Any?> {
        val node = view.node(nodeId)
        val formatter = NumberFormatter(layout.number)
        val trace = node.trace(coord)
        fun display(value: Value, source: ViewNode = node) =
            formatter.value(value, source.presentation.format, source.presentation.precision)
        fun aligned(other: ViewNode): List<String> = other.dims.mapNotNull { dim ->
            node.dims.indexOf(dim).takeIf { it >= 0 }?.let(coord::get)
        }
        val references = (trace as? NodeTrace.Computed)?.references.orEmpty().mapNotNull { ref ->
            val target = view.nodes[ref.id.removePrefix("all.")] ?: return@mapNotNull null
            val memberMap = ref.kind == TraceRef.Kind.ALL || ref.kind == TraceRef.Kind.MEMBER_MAP
            val targetCoord = when (ref.kind) {
                TraceRef.Kind.ALL -> emptyList()
                TraceRef.Kind.MEMBER_MAP -> target.dims.mapNotNull { dim ->
                    node.dims.indexOf(dim).takeIf { it >= 0 }?.let { "$dim=${coord[it]}" }
                }
                else -> aligned(target)
            }
            val targetId = if (memberMap) "all.${target.id}" else target.id
            linkedMapOf<String, Any?>(
                "address" to address(targetId, targetCoord), "label" to target.label,
                "value" to WorkbenchJson.value(ref.value), "display" to display(ref.value, target),
                "kind" to ref.kind.name.lowercase().replace('_', '-'),
                "origin" to when (val origin = target.trace(if (memberMap) emptyList() else targetCoord)) {
                    is NodeTrace.Input -> origin.origin.name.lowercase()
                    is NodeTrace.Param -> origin.source
                    else -> null
                },
            )
        }
        val parts = (trace as? NodeTrace.Sum)?.parts.orEmpty().mapNotNull { part ->
            val target = view.nodes[part.id] ?: return@mapNotNull null
            linkedMapOf<String, Any?>("address" to address(part.id, aligned(target)), "label" to target.label,
                "sign" to part.sign, "value" to WorkbenchJson.value(Value.Num(part.value)),
                "display" to display(Value.Num(part.value), target), "crossFooted" to part.crossFooted)
        }
        val choice = trace as? NodeTrace.Choice
        val selectedValue = choice?.options?.firstOrNull { it.key == choice.selected }?.value
        val options = choice?.options.orEmpty().map { option ->
            val optionValue = option.value
            val difference = if (selectedValue is Value.Num && optionValue is Value.Num)
                Value.Num(selectedValue.value - optionValue.value) else null
            linkedMapOf<String, Any?>("key" to option.key, "label" to option.label,
                "value" to WorkbenchJson.value(optionValue), "display" to display(optionValue),
                "available" to option.available, "selected" to (option.key == choice?.selected),
                "difference" to difference?.let(WorkbenchJson::value),
                "differenceDisplay" to difference?.let { display(it) })
        }
        val rounding = when (trace) {
            is NodeTrace.Computed -> trace.rounding
            is NodeTrace.Choice -> trace.rounding
            else -> null
        }
        val formula = node.line?.formula
        val value = cellValue ?: node.value(coord)
        val requestedAddress = address(nodeId, coord).toMutableMap().apply {
            if (cell != null) put("cell", linkedMapOf("row" to cell.first, "column" to cell.second))
        }
        return linkedMapOf(
            "address" to requestedAddress, "label" to node.label,
            "kind" to node.kind.name.lowercase().replace('_', '-'),
            "formula" to formula?.let { linkedMapOf("text" to it.source, "location" to location(it.location)) },
            "result" to linkedMapOf("value" to WorkbenchJson.value(value), "display" to display(value),
                "rounding" to rounding?.let { linkedMapOf("scale" to it.scale, "mode" to it.mode.name.lowercase()) }),
            "status" to when (trace) {
                is NodeTrace.Inactive -> "inactive"
                is NodeTrace.Failed -> "failed"
                else -> if (node.isActive(coord)) "active" else "inactive"
            },
            "reason" to when (trace) { is NodeTrace.Inactive -> trace.reason; is NodeTrace.Failed -> trace.message; else -> null },
            "steps" to full?.steps.orEmpty().map { step -> linkedMapOf("text" to step.text,
                "value" to WorkbenchJson.value(step.value), "display" to display(step.value),
                "location" to location(step.location)) },
            "branches" to full?.branches.orEmpty().map { branch -> linkedMapOf("text" to branch.text,
                "selected" to branch.selected, "location" to location(branch.location)) },
            "references" to references, "parts" to parts, "options" to options,
            "reference" to node.presentation.reference, "truncated" to (full?.truncated ?: false),
        )
    }

    /** A navigable source-tree node for an `all.<id>` reference to a dimensioned member map. */
    fun memberMap(view: CalculationView, layout: LayoutSpec, nodeId: String,
                  fixed: Map<String, String> = emptyMap()): Map<String, Any?> {
        val node = view.node(nodeId)
        require(node.dims.isNotEmpty()) { "Member map requires a dimensioned node" }
        val formatter = NumberFormatter(layout.number)
        val variableDims = node.dims.filter { it !in fixed }
        require(variableDims.isNotEmpty()) { "Member map requires at least one unfixed dimension" }
        fun build(dims: List<String>, assignment: Map<String, String>): Value {
            if (dims.isEmpty()) return node.values[node.dims.map(assignment::getValue)]
                ?: if (node.type.isNumeric) Value.ZERO else Value.Nil
            return Value.MapV(linkedMapOf<Value, Value>().apply {
                view.members[dims.first()].orEmpty().forEach { member ->
                    put(Value.Kw(member.key), build(dims.drop(1), assignment + (dims.first() to member.key)))
                }
            })
        }
        val value = build(variableDims, fixed)
        val members = node.values.keys.filter { coord -> node.dims.indices.all { i ->
            fixed[node.dims[i]] == null || fixed[node.dims[i]] == coord[i]
        } }.sortedWith(compareBy<List<String>> { it.joinToString("\u0000") })
        val references = members.take(63).map { coord ->
            val item = node.value(coord)
            linkedMapOf<String, Any?>("address" to address(nodeId, coord), "label" to node.label,
                "value" to WorkbenchJson.value(item),
                "display" to formatter.value(item, node.presentation.format, node.presentation.precision),
                "kind" to "member", "origin" to null)
        }
        return linkedMapOf(
            "address" to address("all.$nodeId", node.dims.mapNotNull { dim -> fixed[dim]?.let { "$dim=$it" } }),
            "label" to node.label,
            "kind" to "member-map", "formula" to null,
            "result" to linkedMapOf("value" to WorkbenchJson.value(value),
                "display" to formatter.value(value, node.presentation.format, node.presentation.precision),
                "rounding" to null),
            "status" to "active", "reason" to null,
            "steps" to emptyList<Any>(), "branches" to emptyList<Any>(),
            "references" to references, "parts" to emptyList<Any>(), "options" to emptyList<Any>(),
            "reference" to node.presentation.reference, "truncated" to (members.size > 63),
        )
    }

    /** Parameter defaults, every supplied set value, case override, and the effective layer. */
    fun parameters(view: CalculationView): Map<String, Any?> = linkedMapOf(
        "parameters" to view.structure.params.map { id ->
            val node = view.node(id)
            val source = node.parameterSource ?: "schema"
            linkedMapOf(
                "id" to id, "label" to node.label, "reference" to node.presentation.reference,
                "layers" to node.parameterLayers.map { layer ->
                    linkedMapOf<String, Any?>("layer" to layer.layer, "value" to layer.value?.let(WorkbenchJson::value),
                        "declared" to layer.declared).apply {
                        layer.set?.let { put("set", it) }
                        layer.reference?.let { put("reference", it) }
                    }
                },
                "effective" to linkedMapOf<String, Any?>(
                    "value" to node.parameterValue?.let(WorkbenchJson::value),
                    "layer" to if (source == "case" || source == "schema") source else "parameters",
                ).apply { if (source != "case" && source != "schema") put("set", source) },
            )
        },
    )

    /** Exact engine differences projected with the same number formatter used by Paper and Run. */
    fun compare(base: CalculationView, variant: CalculationView, layout: LayoutSpec,
        variantParameterSets: List<String> = emptyList(),
        variantCaseId: String? = variant.case.id.takeIf { it != base.case.id }): Map<String, Any?> {
        val diff = CalculationCompare.between(base, variant)
        val formatter = NumberFormatter(layout.number)
        fun change(value: ValueChange): Map<String, Any?> {
            val node = variant.nodes[value.node] ?: base.nodes[value.node]
            fun display(v: Value?): String? = v?.let { formatter.value(it, node?.presentation?.format, node?.presentation?.precision) }
            return linkedMapOf(
                "node" to value.node, "coord" to value.coord,
                "base" to value.base?.let(WorkbenchJson::value),
                "variant" to value.variant?.let(WorkbenchJson::value),
                "delta" to value.delta?.let { WorkbenchJson.value(Value.Num(it)) },
                "basePresent" to value.basePresent, "variantPresent" to value.variantPresent,
                "display" to linkedMapOf("base" to display(value.base), "variant" to display(value.variant),
                    "delta" to value.delta?.let { display(Value.Num(it)) }),
            )
        }
        return linkedMapOf(
            "variant" to linkedMapOf<String, Any?>("parameters" to variantParameterSets).apply {
                if (variantCaseId != null) put("case", variantCaseId)
            },
            "mainline" to diff.mainline.map { entry ->
                linkedMapOf<String, Any?>("step" to entry.step, "panel" to entry.panel).apply { putAll(change(entry.value)) }
            },
            "changes" to diff.changes.map { group ->
                linkedMapOf("step" to group.step, "panel" to group.panel, "items" to group.items.map(::change))
            },
            "parameterChanges" to diff.parameterChanges.map { entry ->
                linkedMapOf<String, Any?>().apply {
                    putAll(change(entry.value))
                    put("baseSource", entry.baseSource)
                    put("variantSource", entry.variantSource)
                }
            },
        )
    }

    /** A panel always has a paper table, even when the chosen layout did not declare one. */
    fun paper(view: CalculationView, layout: LayoutSpec, panelId: String? = null): Map<String, Any?> {
        val rendered = Render.paper(view, layout)
        if (panelId == null || rendered.tables.any { it.id == panelId }) return paper(rendered, view, panelId)
        require(view.structure.panels.any { it.id == panelId }) { "Unknown panel $panelId" }
        val fallback = Render.paper(view, layout.copy(tables = listOf(TableSpec(panelId))))
        return paper(fallback, view, panelId)
    }

    fun structure(view: CalculationView): Map<String, Any?> {
        val map = view.structure
        return linkedMapOf(
            "schema" to map.schemaId,
            "schemaVersion" to view.schema.text("version"),
            "title" to map.title,
            "headline" to view.headlineId,
            "groupTitles" to view.nodes.values.filter { it.kind == NodeKind.INPUT }.mapNotNull { it.groupKey }.distinct()
                .associateWith(view::groupTitle),
            "mainline" to map.mainline.map { id ->
                val panel = map.panel(id)
                linkedMapOf("step" to panel.step, "panel" to id, "title" to panel.title, "result" to panel.resultId)
            },
            "panels" to map.panels.map { panel ->
                linkedMapOf(
                    "id" to panel.id, "title" to panel.title, "role" to panel.role.name.lowercase(),
                    "step" to panel.step, "parent" to panel.parentId, "dims" to panel.dims,
                    "result" to panel.resultId,
                    "breadcrumb" to panel.breadcrumb.map { crumb ->
                        if (crumb.panelId == null) linkedMapOf("kind" to "mainline")
                        else linkedMapOf("panel" to crumb.panelId, "label" to crumb.label, "node" to crumb.nodeId)
                    },
                    "entries" to panel.entries.map { entry ->
                        linkedMapOf("step" to entry.step, "panel" to entry.stepPanel, "via" to entry.viaNode,
                            "viaLabel" to entry.viaLabel, "path" to entry.path)
                    },
                    "fields" to panel.fields.map { field(view, it) },
                    "nodes" to panel.nodes,
                    "imports" to panel.imports.map(::flow),
                    "exports" to panel.exports.map(::flow),
                )
            },
            "generalInputs" to map.generalInputs.map { field(view, it) },
            "params" to map.params.map { id -> parameter(view.node(id)) },
            "nodes" to view.nodes.mapValues { (_, node) -> metadata(node) },
            "slots" to slots(view.tree, view),
            "formulaSlots" to view.nodes.values.filter { it.kind == NodeKind.FORMULA_SLOT }.map { node ->
                linkedMapOf("id" to node.id, "title" to node.label,
                    "panel" to map.panelOf(node.id)?.id, "uses" to node.line?.allowedRefs?.sorted(),
                    "defaultFormula" to view.formulaSlotDefaults[node.id]?.source,
                    "binding" to view.case.formulaBindings[node.id]?.source)
            },
        )
    }

    fun run(view: CalculationView, layout: LayoutSpec): Map<String, Any?> {
        val formatter = NumberFormatter(layout.number)
        return linkedMapOf(
            "succeeded" to view.succeeded,
            "members" to view.members.mapValues { (_, members) ->
                members.map { member -> linkedMapOf("key" to member.key, "label" to member.label) }
            },
            "values" to view.nodes.mapValues { (_, node) ->
                node.values.mapKeys { (coord, _) -> coord.joinToString("/") }.mapValues { (key, value) ->
                    val coord = if (key.isEmpty()) emptyList() else key.split("/")
                    linkedMapOf<String, Any?>(
                        "value" to WorkbenchJson.value(value),
                        "display" to if (node.isActive(coord)) formatter.value(value, node.presentation.format, node.presentation.precision)
                            else layout.texts.notApplicable,
                        "active" to node.isActive(coord),
                    ).apply {
                        when (val trace = node.trace(coord)) {
                            is NodeTrace.Input -> put("origin", trace.origin.name.lowercase())
                            is NodeTrace.Param -> put("source", trace.source)
                            else -> Unit
                        }
                    }
                }
            },
            "diagnostics" to view.diagnostics.map(::diagnostic),
        )
    }

    /** If a panel has an explicit table, return only that table. A missing table is reported to the caller. */
    fun paper(paper: WorkingPaper, view: CalculationView, panelId: String? = null): Map<String, Any?> {
        val tables = if (panelId == null) paper.tables else paper.tables.filter { it.id == panelId }
        require(panelId == null || tables.isNotEmpty()) { "No table for panel ${panelId}" }
        return linkedMapOf(
            "title" to paper.title, "subtitle" to paper.subtitle,
            "headline" to paper.headline?.let { linkedMapOf("node" to it.nodeId, "label" to it.label, "value" to it.value) },
            "inputGroups" to paper.inputGroups.map { linkedMapOf("key" to it.key, "title" to it.title, "inputs" to it.inputs) },
            "header" to paper.header.map { (key, value) -> listOf(key, value) },
            "overview" to paper.overview.map { step ->
                linkedMapOf("step" to step.step, "panel" to overviewPanel(step.panel),
                    "branches" to step.branches.map(::overviewPanel))
            },
            "auxiliary" to paper.auxiliary.map(::overviewPanel),
            "tables" to tables.map { table(it, view) },
            "audit" to paper.audit.map { entry ->
                linkedMapOf("anchor" to entry.anchor, "citation" to entry.citation, "label" to entry.label,
                    "member" to entry.member, "formula" to entry.formula, "working" to entry.working,
                    "result" to entry.result, "reference" to entry.reference)
            },
            "legend" to paper.legend.map { (mark, meaning) -> listOf(mark, meaning) },
            "diagnostics" to paper.findings.map(::diagnostic),
            "theme" to paper.theme,
        )
    }

    fun diagnostics(diagnostics: List<Diagnostic>): Map<String, Any?> =
        linkedMapOf("diagnostics" to diagnostics.map(::diagnostic))

    fun diagnostic(diagnostic: Diagnostic): Map<String, Any?> = linkedMapOf(
        "severity" to diagnostic.severity.name.lowercase(),
        "code" to diagnostic.code,
        "message" to diagnostic.message,
        "location" to diagnostic.location?.let(::location),
        "address" to diagnostic.nodeId?.let { address(it, diagnostic.coord) },
        "related" to emptyList<Any>(),
    )

    private fun location(location: SourceLocation): Map<String, Any?> =
        linkedMapOf<String, Any?>("document" to location.source, "line" to location.line, "column" to location.column).apply {
            location.startOffset?.let { put("startOffset", it) }
            location.endOffset?.let { put("endOffset", it) }
        }

    private fun address(id: String, coord: List<String>? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("node" to id).apply { if (!coord.isNullOrEmpty()) put("coord", coord) }

    private fun flow(flow: Flow): Map<String, Any?> = linkedMapOf(
        "fromPanel" to flow.fromPanel, "fromNode" to flow.fromNode,
        "toPanel" to flow.toPanel, "toNode" to flow.toNode,
    )

    private fun field(view: CalculationView, id: String): Map<String, Any?> {
        val node = view.node(id)
        val decl = node.input ?: return linkedMapOf("id" to id)
        return linkedMapOf(
            "id" to id, "label" to node.label, "type" to decl.type.keyword, "dims" to node.dims,
            "optional" to decl.optional, "default" to decl.default?.let(WorkbenchJson::value),
            "options" to decl.options, "columns" to decl.columns.map { col ->
                linkedMapOf("name" to col.name, "type" to col.type.keyword, "optional" to col.optional)
            },
            "references" to decl.references,
            "constraints" to decl.presentation.attributes.filterKeys { it in setOf("min", "max", "required", "pattern", "max-length") }
                .mapValues { (_, value) -> WorkbenchJson.value(value) },
            "help" to (decl.presentation.attributes["help"] as? Value.Text)?.value,
            "unit" to (decl.presentation.attributes["unit"] as? Value.Kw)?.name,
            "reference" to decl.presentation.reference,
            "group" to node.groupKey,
            "groupTitle" to node.groupKey?.let(view::groupTitle),
            "attributes" to decl.presentation.attributes.mapValues { (_, value) -> WorkbenchJson.value(value) },
        )
    }

    private fun parameter(node: ViewNode): Map<String, Any?> = linkedMapOf(
        "id" to node.id, "label" to node.label, "type" to node.type.keyword,
        "reference" to node.presentation.reference,
        "attributes" to node.presentation.attributes.mapValues { (_, value) -> WorkbenchJson.value(value) },
    )

    private fun metadata(node: ViewNode): Map<String, Any?> = linkedMapOf(
        "id" to node.id, "kind" to node.kind.name.lowercase().replace('_', '-'),
        "label" to node.label, "type" to node.type.keyword, "dims" to node.dims,
        "op" to (node.line?.op ?: node.choice?.op)?.keyword,
        "reference" to node.presentation.reference, "source" to node.presentation.source,
        "note" to node.presentation.note, "class" to node.presentation.classes,
        "signLabels" to node.signLabels?.let { linkedMapOf("positive" to it.positive, "negative" to it.negative, "zero" to it.zero) },
        "group" to node.groupKey,
        "attributes" to node.presentation.attributes.mapValues { (_, value) -> WorkbenchJson.value(value) },
        "formula" to node.line?.formula?.let { linkedMapOf("text" to it.source, "location" to location(it.location)) },
        "location" to location(node.location),
        "userDefined" to node.userDefined,
        "slot" to node.slotId,
        "input" to node.input?.let { fieldMetadata(it) },
    )

    private fun fieldMetadata(input: com.xqiou.mantra.core.model.InputDecl): Map<String, Any?> = linkedMapOf(
        "optional" to input.optional, "default" to input.default?.let(WorkbenchJson::value),
        "options" to input.options, "columns" to input.columns.map { col ->
            linkedMapOf("name" to col.name, "type" to col.type.keyword, "optional" to col.optional)
        }, "references" to input.references,
    )

    private fun slots(root: ViewSection, view: CalculationView): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        fun walk(item: ViewItem) {
            if (item is ViewSection) {
                if (item.item.slot) result += linkedMapOf(
                    "id" to item.id, "title" to item.label, "panel" to view.structure.panelOf(item.id)?.id,
                    "extensions" to view.case.extensions[item.id].orEmpty().mapNotNull {
                        (it as? com.xqiou.mantra.core.model.NodeItem)?.id
                    },
                )
                item.children.forEach(::walk)
            }
        }
        walk(root)
        return result
    }

    private fun overviewPanel(panel: com.xqiou.mantra.render.paper.OverviewPanel): Map<String, Any?> =
        linkedMapOf("panelId" to panel.panelId, "title" to panel.title, "tableRef" to panel.tableRef,
            "value" to panel.value, "entry" to panel.entry)

    private fun table(table: PaperTable, view: CalculationView): Map<String, Any?> = linkedMapOf(
        "id" to table.id, "ref" to table.ref, "title" to table.title,
        "breadcrumb" to table.breadcrumb, "style" to table.style.name.lowercase(),
        "columns" to table.columns.map { col ->
            linkedMapOf("id" to col.id, "header" to col.header,
                "content" to col.content.styleRole(), "align" to col.align.name.lowercase(), "width" to col.width)
        },
        "rows" to table.rows.map { row ->
            val node = row.nodeId?.let(view.nodes::get)
            linkedMapOf(
                "kind" to row.kind.name.lowercase(), "depth" to row.depth,
                "node" to row.nodeId, "flags" to row.flags.map { it.name.lowercase().replace('_', '-') }.sorted(),
                "anchor" to row.anchor, "lead" to row.lead, "optionKey" to row.optionKey,
                "section" to row.sectionId, "classes" to row.classes,
                "cells" to row.cells.mapIndexed { index, cell ->
                    val column = table.columns[index]
                    val coord = (column.content as? ColumnContent.Member)?.let { listOf(it.key) }
                    val numeric = column.content.numeric
                    val hasExactCoord = node != null && (node.dims.isEmpty() || (coord != null && node.dims.size == 1))
                    val cellAddress = if (numeric && hasExactCoord && node != null) address(node.id, coord) else null
                    linkedMapOf(
                        "text" to cell,
                        "address" to cellAddress,
                        "editable" to (numeric && hasExactCoord && node?.kind == NodeKind.INPUT &&
                            view.structure.panelOf(node.id)?.id == table.id),
                        "style" to style(row.cellStyles.getOrNull(index) ?: row.style),
                    )
                },
            )
        },
    )

    private fun style(style: StyleSpec): Map<String, String> = linkedMapOf(
        "weight" to (style.weight?.name?.lowercase() ?: "normal"),
        "tone" to (style.tone?.name?.lowercase() ?: "default"),
        "fill" to (style.fill?.name?.lowercase() ?: "none"),
    )
}
