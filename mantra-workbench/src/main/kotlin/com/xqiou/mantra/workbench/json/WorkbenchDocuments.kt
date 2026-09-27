package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.engine.NodeTrace
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.structure.Flow
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.ViewItem
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.view.ViewSection
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
            "headline" to view.schema.attributes["headline"]?.let(WorkbenchJson::value),
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
