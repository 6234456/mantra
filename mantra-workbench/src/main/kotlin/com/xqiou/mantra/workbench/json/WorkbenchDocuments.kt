package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.CalculationReader
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.structure.Flow
import com.xqiou.mantra.core.view.CalculationCompare
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.TraceRef
import com.xqiou.mantra.core.view.ValidationResult
import com.xqiou.mantra.core.view.ValueChange
import com.xqiou.mantra.core.view.ViewItem
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.view.ViewSection
import com.xqiou.mantra.core.view.groupKey
import com.xqiou.mantra.core.view.groupTitle
import com.xqiou.mantra.core.view.headlineId
import com.xqiou.mantra.core.view.signLabels
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.ColumnSpec
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.StyleSpec
import com.xqiou.mantra.render.layout.TableSpec
import com.xqiou.mantra.render.layout.TableStyle
import com.xqiou.mantra.render.layout.styleRole
import com.xqiou.mantra.render.paper.NumberFormatter
import com.xqiou.mantra.render.paper.PaperTable
import com.xqiou.mantra.render.paper.WorkingPaper

/** Pure projections of the public read-only calculation and presentation views. */
object WorkbenchDocuments {
    /** One calculated value and its bounded source trace; no evaluator state reaches the UI. */
    fun explain(
        view: CalculationView,
        layout: LayoutSpec,
        nodeId: String,
        coord: List<String>,
        full: ExplainTrace?,
        cell: Pair<String, String>? = null,
        cellValue: Value? = null,
        reader: CalculationReader? = null,
    ): Map<String, Any?> {
        if (reader ==
            null
        ) {
            return view.openReader().use { explain(view, layout, nodeId, coord, full, cell, cellValue, it) }
        }
        reader.chargeScans()
        fun json(value: Value) = WorkbenchJson.value(value, reader)
        val node = view.node(nodeId)
        val formatter = NumberFormatter(layout.number)
        val trace = node.trace(coord)
        fun display(value: Value, source: ViewNode = node) =
            formatter.value(value, source.presentation.format, source.presentation.precision)
        fun aligned(other: ViewNode): List<String> = other.dims.mapNotNull { dim ->
            node.dims.indexOf(dim).takeIf { it >= 0 }?.let(coord::get)
        }
        val references = (trace as? NodeTrace.Computed)?.references.orEmpty().mapNotNull { ref ->
            reader.chargeScans()
            val target = view.nodes[ref.id.removePrefix("all.")] ?: return@mapNotNull null
            val previousMap = ref.kind == TraceRef.Kind.PREVIOUS && ref.coord == null && ref.fixed != null
            val memberMap = ref.kind == TraceRef.Kind.ALL || ref.kind == TraceRef.Kind.MEMBER_MAP || previousMap
            val targetCoord = if (previousMap) {
                target.dims.mapNotNull { dim -> ref.fixed!![dim]?.let { "$dim=$it" } }
            } else {
                when (ref.kind) {
                    TraceRef.Kind.ALL -> emptyList()
                    TraceRef.Kind.MEMBER_MAP -> target.dims.mapNotNull { dim ->
                        node.dims.indexOf(dim).takeIf { it >= 0 }?.let { "$dim=${coord[it]}" }
                    }
                    else -> ref.coord ?: aligned(target)
                }
            }
            val targetId = if (memberMap) "all.${target.id}" else target.id
            linkedMapOf<String, Any?>(
                "address" to address(targetId, targetCoord),
                "label" to target.label,
                "value" to json(ref.value),
                "display" to display(ref.value, target),
                "kind" to ref.kind.name.lowercase().replace('_', '-'),
                "origin" to when (val origin = target.trace(if (memberMap) emptyList() else targetCoord)) {
                    is NodeTrace.Input -> origin.label()
                    is NodeTrace.Param -> origin.source
                    else -> null
                },
            )
        } + listOfNotNull(
            (trace as? NodeTrace.Input)?.link?.let { link ->
                linkedMapOf<String, Any?>(
                    "address" to CaseGraphDocuments.address(link.caseKey, link.from),
                    "label" to link.from.nodeId,
                    "value" to json(node.value(coord)),
                    "display" to display(node.value(coord)),
                    "kind" to "link",
                    "origin" to "link:${link.caseKey}#${link.from.nodeId}",
                    "revision" to link.revision,
                )
            },
        )
        val parts = (trace as? NodeTrace.Sum)?.parts.orEmpty().mapNotNull { part ->
            reader.chargeScans()
            val target = view.nodes[part.id] ?: return@mapNotNull null
            linkedMapOf<String, Any?>(
                "address" to if (part.reduction != null || part.aggregate != null) {
                    address(
                        "aggregate.${part.id}",
                        aggregateCoordinate(view, (part.reduction ?: part.aggregate)!!.fixed),
                    )
                } else {
                    address(part.id, aligned(target))
                },
                "label" to target.label,
                "sign" to part.sign,
                "value" to part.value?.let { json(Value.Num(it)) },
                "display" to (part.value?.let { display(Value.Num(it), target) } ?: "—"),
                "crossFooted" to part.crossFooted,
                "aggregate" to
                    (part.reduction ?: part.aggregate)?.let { reductionDocument(it, layout, target.presentation) },
            )
        }
        val choice = trace as? NodeTrace.Choice
        val selectedValue = choice?.options?.firstOrNull { it.key == choice.selected }?.value
        val options = choice?.options.orEmpty().map { option ->
            reader.chargeScans()
            val optionValue = option.value
            val difference = if (selectedValue is Value.Num && optionValue is Value.Num) {
                Value.Num(selectedValue.value - optionValue.value)
            } else {
                null
            }
            linkedMapOf<String, Any?>(
                "key" to option.key,
                "label" to option.label,
                "value" to json(optionValue),
                "display" to display(optionValue),
                "available" to option.available,
                "selected" to (option.key == choice?.selected),
                "difference" to difference?.let(::json),
                "differenceDisplay" to difference?.let { display(it) },
            )
        }
        val rounding = when (trace) {
            is NodeTrace.Computed -> trace.rounding
            is NodeTrace.Choice -> trace.rounding
            else -> null
        }
        val formula = node.line?.formula ?: node.check?.formula ?: node.reconcile?.left
        val value = cellValue ?: node.value(coord)
        val requestedAddress = address(nodeId, coord).toMutableMap().apply {
            if (cell != null) put("cell", linkedMapOf("row" to cell.first, "column" to cell.second))
        }
        return linkedMapOf(
            "address" to requestedAddress, "label" to node.label,
            "link" to (trace as? NodeTrace.Input)?.link?.let(CaseGraphDocuments::link),
            "kind" to node.kind.name.lowercase().replace('_', '-'),
            "formula" to formula?.let { linkedMapOf("text" to it.source, "location" to location(it.location)) },
            "result" to linkedMapOf(
                "value" to json(value),
                "display" to display(value),
                "rounding" to rounding?.let { linkedMapOf("scale" to it.scale, "mode" to it.mode.name.lowercase()) },
            ),
            "status" to when (trace) {
                is NodeTrace.Inactive -> "inactive"
                is NodeTrace.Failed -> "failed"
                else -> if (node.isActive(coord)) "active" else "inactive"
            },
            "reason" to
                when (trace) {
                    is NodeTrace.Inactive -> trace.reason
                    is NodeTrace.Failed -> trace.message
                    else -> null
                },
            "steps" to full?.steps.orEmpty().map { step ->
                reader.chargeScans()
                linkedMapOf(
                    "text" to step.text,
                    "value" to step.value?.let(::json),
                    "display" to (step.value?.let { display(it) } ?: step.rendered ?: "value unavailable"),
                    "rendered" to step.rendered,
                    "location" to location(step.location),
                    "eventId" to step.eventId,
                    "invocationIndex" to step.invocationIndex?.toString(),
                )
            },
            "branches" to full?.branches.orEmpty().map { branch ->
                reader.chargeScans()
                linkedMapOf(
                    "text" to branch.text,
                    "selected" to branch.selected,
                    "location" to location(branch.location),
                    "eventId" to branch.eventId,
                    "invocationIndex" to branch.invocationIndex?.toString(),
                )
            },
            "references" to references, "parts" to parts, "options" to options,
            "reference" to node.presentation.reference, "truncated" to (full?.truncated ?: false),
            "aggregate" to null,
        )
    }

    /** A navigable source-tree node for an `all.<id>` reference to a dimensioned member map. */
    fun memberMap(
        view: CalculationView,
        layout: LayoutSpec,
        nodeId: String,
        fixed: Map<String, String> = emptyMap(),
        reader: CalculationReader? = null,
    ): Map<String, Any?> {
        if (reader == null) return view.openReader().use { memberMap(view, layout, nodeId, fixed, it) }
        reader.coordinates(view, nodeId, fixed) // Product and fixed-member preflight before nested map growth.
        val node = view.node(nodeId)
        require(node.dims.isNotEmpty()) { "Member map requires a dimensioned node" }
        val formatter = NumberFormatter(layout.number)
        val variableDims = node.dims.filter { it !in fixed }
        require(variableDims.isNotEmpty()) { "Member map requires at least one unfixed dimension" }
        fun build(dims: List<String>, assignment: Map<String, String>): Value {
            reader.chargeScans()
            if (dims.isEmpty()) {
                return node.values[node.dims.map(assignment::getValue)]
                    ?: if (node.type.isNumeric) Value.ZERO else Value.Nil
            }
            return Value.MapV(
                linkedMapOf<Value, Value>().apply {
                    view.members[dims.first()].orEmpty().forEach { member ->
                        put(Value.Kw(member.key), build(dims.drop(1), assignment + (dims.first() to member.key)))
                    }
                },
            )
        }
        val value = build(variableDims, fixed)
        reader.chargeScans(node.values.size.toLong())
        val members = node.values.keys.filter { coord ->
            node.dims.indices.all { i ->
                fixed[node.dims[i]] == null || fixed[node.dims[i]] == coord[i]
            }
        }.sortedWith(compareBy<List<String>> { it.joinToString("\u0000") })
        val references = members.take(63).map { coord ->
            val item = node.value(coord)
            linkedMapOf<String, Any?>(
                "address" to address(nodeId, coord),
                "label" to node.label,
                "value" to WorkbenchJson.value(item, reader),
                "display" to formatter.value(item, node.presentation.format, node.presentation.precision),
                "kind" to "member",
                "origin" to null,
            )
        }
        return linkedMapOf(
            "address" to address("all.$nodeId", node.dims.mapNotNull { dim -> fixed[dim]?.let { "$dim=$it" } }),
            "label" to node.label,
            "kind" to "member-map", "formula" to null,
            "result" to linkedMapOf(
                "value" to WorkbenchJson.value(value, reader),
                "display" to formatter.value(value, node.presentation.format, node.presentation.precision),
                "rounding" to null,
            ),
            "status" to "active", "reason" to null,
            "link" to null, "steps" to emptyList<Any>(), "branches" to emptyList<Any>(),
            "references" to references, "parts" to emptyList<Any>(), "options" to emptyList<Any>(),
            "reference" to node.presentation.reference, "truncated" to (members.size > 63),
            "aggregate" to null,
        )
    }

    /** Explain a weighted aggregate from its captured active-mask evidence. */
    fun aggregate(
        view: CalculationView,
        layout: LayoutSpec,
        nodeId: String,
        fixed: Map<String, String> = emptyMap(),
        reader: CalculationReader? = null,
    ): Map<String, Any?> = aggregateExplanation(view, layout, nodeId, fixed, reader)

    /** Parameter defaults, every supplied set value, case override, and the effective layer. */
    fun parameters(view: CalculationView): Map<String, Any?> = linkedMapOf(
        "parameters" to view.structure.params.map { id ->
            val node = view.node(id)
            val source = node.parameterSource ?: "schema"
            linkedMapOf(
                "id" to id,
                "label" to node.label,
                "reference" to node.presentation.reference,
                "layers" to node.parameterLayers.map { layer ->
                    linkedMapOf<String, Any?>(
                        "layer" to layer.layer,
                        "value" to layer.value?.let(WorkbenchJson::value),
                        "declared" to layer.declared,
                    ).apply {
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
    fun compare(
        base: CalculationView,
        variant: CalculationView,
        layout: LayoutSpec,
        variantParameterSets: List<String> = emptyList(),
        variantCaseId: String? = variant.case.id.takeIf { it != base.case.id },
    ): Map<String, Any?> {
        val diff = CalculationCompare.between(base, variant)
        val formatter = NumberFormatter(layout.number)
        fun change(value: ValueChange): Map<String, Any?> {
            val node = variant.nodes[value.node] ?: base.nodes[value.node]
            fun display(v: Value?): String? =
                v?.let { formatter.value(it, node?.presentation?.format, node?.presentation?.precision) }
            return linkedMapOf(
                "node" to value.node,
                "coord" to value.coord,
                "base" to value.base?.let(WorkbenchJson::value),
                "variant" to value.variant?.let(WorkbenchJson::value),
                "delta" to value.delta?.let { WorkbenchJson.value(Value.Num(it)) },
                "basePresent" to value.basePresent,
                "variantPresent" to value.variantPresent,
                "display" to linkedMapOf(
                    "base" to display(value.base),
                    "variant" to display(value.variant),
                    "delta" to value.delta?.let { display(Value.Num(it)) },
                ),
            )
        }
        return linkedMapOf(
            "variant" to linkedMapOf<String, Any?>("parameters" to variantParameterSets).apply {
                if (variantCaseId != null) put("case", variantCaseId)
            },
            "mainline" to diff.mainline.map { entry ->
                linkedMapOf<String, Any?>("step" to entry.step, "panel" to entry.panel).apply {
                    putAll(change(entry.value))
                }
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
    fun paper(view: CalculationView, layout: LayoutSpec, panelId: String? = null): Map<String, Any?> =
        view.openReader().use { reader -> paper(view, layout, panelId, reader) }

    /** Interactive visibility overrides stay in the renderer and never change the bound layout or exports. */
    fun paper(view: CalculationView, layout: LayoutSpec, panelId: String?, includeZero: Boolean): Map<String, Any?> {
        val effectiveLayout = if (includeZero) layout.copy(hideZero = false) else layout
        return paper(view, effectiveLayout, panelId) +
            ("browsing" to linkedMapOf("includeZero" to includeZero, "hideZero" to layout.hideZero))
    }

    private fun paper(
        view: CalculationView,
        layout: LayoutSpec,
        panelId: String?,
        reader: CalculationReader,
    ): Map<String, Any?> {
        val rendered = Render.paper(view, layout, reader)
        if (panelId == null || rendered.tables.any { it.id == panelId }) return paper(rendered, view, panelId)
        require(view.structure.panels.any { it.id == panelId }) { "Unknown panel $panelId" }
        val dimensions = view.dimensionOrder(
            view.structure.panel(panelId).nodes.flatMap {
                view.node(it).dims
            }.distinct(),
        )
        val table = if (dimensions.size >
            1
        ) {
            TableSpec(
                panelId,
                style = TableStyle.MATRIX,
                rowDimension = dimensions.first(),
                columns = listOf(
                    ColumnSpec("label", null, ColumnContent.Label),
                    ColumnSpec("members", null, ColumnContent.Members("*")),
                    ColumnSpec("cross-total", null, ColumnContent.CrossTotal),
                ),
            )
        } else {
            TableSpec(panelId)
        }
        val fallback = Render.completePaper(view, layout.copy(tables = listOf(table)), reader)
        return paper(fallback, view, panelId)
    }

    fun structure(view: CalculationView): Map<String, Any?> {
        val map = view.structure
        return linkedMapOf(
            "schema" to map.schemaId,
            "schemaVersion" to view.schema.text("version"),
            "title" to map.title,
            "headline" to view.headlineId,
            "groupTitles" to
                view.nodes.values.filter { it.kind == NodeKind.INPUT }.mapNotNull { it.groupKey }.distinct()
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
                        if (crumb.panelId == null) {
                            linkedMapOf("kind" to "mainline")
                        } else {
                            linkedMapOf("panel" to crumb.panelId, "label" to crumb.label, "node" to crumb.nodeId)
                        }
                    },
                    "entries" to panel.entries.map { entry ->
                        linkedMapOf(
                            "step" to entry.step,
                            "panel" to entry.stepPanel,
                            "via" to entry.viaNode,
                            "viaLabel" to entry.viaLabel,
                            "path" to entry.path,
                        )
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
                linkedMapOf(
                    "id" to node.id,
                    "title" to node.label,
                    "panel" to map.panelOf(node.id)?.id,
                    "uses" to node.line?.allowedRefs?.sorted(),
                    "defaultFormula" to view.formulaSlotDefaults[node.id]?.source,
                    "binding" to view.case.formulaBindings[node.id]?.source,
                )
            },
        )
    }

    fun run(
        view: CalculationView,
        layout: LayoutSpec,
        graph: com.xqiou.mantra.core.api.CaseRunResult? = null,
    ): Map<String, Any?> = view.openReader().use { reader -> run(view, layout, graph, reader) }

    private fun run(
        view: CalculationView,
        layout: LayoutSpec,
        graph: com.xqiou.mantra.core.api.CaseRunResult?,
        reader: CalculationReader,
    ): Map<String, Any?> {
        reader.chargeScans(
            view.nodes.size.toLong() + view.nodes.values.sumOf { it.values.size.toLong() } +
                view.members.values.sumOf { it.size.toLong() },
        )
        val formatter = NumberFormatter(layout.number)
        return linkedMapOf(
            "caseGraph" to graph?.let(CaseGraphDocuments::graph),
            "usage" to graph?.let(CaseGraphDocuments::usage),
            "failure" to graph?.failure?.let(CaseGraphDocuments::failure),
            "succeeded" to view.succeeded,
            "validationPassed" to (graph?.validationPassed ?: view.validationPassed),
            "aggregates" to
                view.nodes.mapNotNull { (id, node) ->
                    if (node.dims.isEmpty()) {
                        null
                    } else {
                        reader.reduce(id, emptyMap()).trace?.let {
                            id to
                                reductionDocument(it, layout, node.presentation)
                        }
                    }
                }.toMap(),
            "members" to view.members.mapValues { (_, members) ->
                members.map { member -> linkedMapOf("key" to member.key, "label" to member.label) }
            },
            "values" to view.nodes.mapValues { (_, node) ->
                node.values.mapKeys { (coord, _) -> coord.joinToString("/") }.mapValues { (key, value) ->
                    val coord = if (key.isEmpty()) emptyList() else key.split("/")
                    linkedMapOf<String, Any?>(
                        "value" to WorkbenchJson.value(value, reader),
                        "display" to
                            if (node.isActive(coord)) {
                                formatter.value(value, node.presentation.format, node.presentation.precision)
                            } else {
                                layout.texts.notApplicable
                            },
                        "active" to node.isActive(coord),
                        "validation" to node.validations[coord]?.let { validation(it, node.isActive(coord)) },
                        "link" to null,
                    ).apply {
                        when (val trace = node.trace(coord)) {
                            is NodeTrace.Input -> {
                                put("origin", trace.label())
                                put("link", trace.link?.let(CaseGraphDocuments::link))
                            }
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
        require(panelId == null || tables.isNotEmpty()) { "No table for panel $panelId" }
        return linkedMapOf(
            "title" to paper.title, "subtitle" to paper.subtitle,
            "headline" to
                paper.headline?.let { linkedMapOf("node" to it.nodeId, "label" to it.label, "value" to it.value) },
            "inputGroups" to
                paper.inputGroups.map { linkedMapOf("key" to it.key, "title" to it.title, "inputs" to it.inputs) },
            "header" to paper.header.map { (key, value) -> listOf(key, value) },
            "overview" to paper.overview.map { step ->
                linkedMapOf(
                    "step" to step.step,
                    "panel" to overviewPanel(step.panel),
                    "branches" to step.branches.map(::overviewPanel),
                )
            },
            "auxiliary" to paper.auxiliary.map(::overviewPanel),
            "tables" to tables.map { table(it, view) },
            "audit" to paper.audit.map { entry ->
                linkedMapOf(
                    "anchor" to entry.anchor,
                    "citation" to entry.citation,
                    "label" to entry.label,
                    "member" to entry.member,
                    "formula" to entry.formula,
                    "working" to entry.working,
                    "result" to entry.result,
                    "reference" to entry.reference,
                    "address" to entry.nodeId?.let { address(it, entry.coord) },
                    "explanation" to entry.explanation?.let(::auditExplanation),
                    "aggregate" to (entry.reduction ?: entry.aggregate)?.let { reductionDocument(it) },
                )
            },
            "legend" to paper.legend.map { (mark, meaning) -> listOf(mark, meaning) },
            "diagnostics" to paper.findings.map(::diagnostic),
            "theme" to paper.theme,
        )
    }

    private fun validation(result: ValidationResult, active: Boolean): Map<String, Any?> = linkedMapOf(
        "passed" to result.passed,
        "active" to active,
        "severity" to result.severity.name.lowercase(),
        "reconciliation" to result.reconciliation?.let {
            linkedMapOf(
                "left" to WorkbenchJson.value(Value.Num(it.left)),
                "right" to WorkbenchJson.value(Value.Num(it.right)),
                "difference" to WorkbenchJson.value(Value.Num(it.difference)),
                "tolerance" to WorkbenchJson.value(Value.Num(it.tolerance)),
            )
        },
    )

    private fun auditExplanation(trace: ExplainTrace): Map<String, Any?> = linkedMapOf(
        "steps" to trace.steps.map {
            linkedMapOf(
                "text" to it.text,
                "value" to it.value?.let(WorkbenchJson::value),
                "rendered" to it.rendered,
                "location" to location(it.location),
            )
        },
        "branches" to trace.branches.map {
            linkedMapOf("text" to it.text, "selected" to it.selected, "location" to location(it.location))
        },
        "truncated" to trace.truncated,
    )

    fun diagnostics(diagnostics: List<Diagnostic>): Map<String, Any?> =
        linkedMapOf("diagnostics" to diagnostics.map(::diagnostic))

    fun diagnostic(diagnostic: Diagnostic): Map<String, Any?> = linkedMapOf(
        "severity" to diagnostic.severity.name.lowercase(),
        "category" to diagnostic.category.name.lowercase(),
        "rowIndex" to diagnostic.rowIndex,
        "column" to diagnostic.column,
        "code" to diagnostic.code,
        "message" to diagnostic.message,
        "location" to diagnostic.location?.let(::location),
        "address" to diagnostic.nodeId?.let { node ->
            address(node, diagnostic.coord).toMutableMap().apply {
                put("case", diagnostic.caseKey)
                if (diagnostic.rowIndex != null && diagnostic.column != null) {
                    put("cell", mapOf("row" to diagnostic.rowIndex.toString(), "column" to diagnostic.column))
                }
            }
        },
        "caseRevision" to diagnostic.caseRevision,
        "related" to emptyList<Any>(),
    )

    private fun location(location: SourceLocation): Map<String, Any?> = linkedMapOf<String, Any?>(
        "document" to location.source,
        "line" to location.line,
        "column" to location.column,
    ).apply {
        location.startOffset?.let { put("startOffset", it) }
        location.endOffset?.let { put("endOffset", it) }
    }

    private fun address(id: String, coord: List<String>? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("case" to null, "node" to id).apply {
            if (!coord.isNullOrEmpty()) put("coord", coord)
        }

    private fun flow(flow: Flow): Map<String, Any?> = linkedMapOf(
        "fromPanel" to flow.fromPanel,
        "fromNode" to flow.fromNode,
        "toPanel" to flow.toPanel,
        "toNode" to flow.toNode,
    )

    private fun field(view: CalculationView, id: String): Map<String, Any?> {
        val node = view.node(id)
        val decl = node.input ?: return linkedMapOf("id" to id)
        return linkedMapOf(
            "id" to id, "label" to node.label, "type" to decl.type.keyword, "dims" to node.dims,
            "optional" to decl.optional, "default" to decl.default?.let(WorkbenchJson::value),
            "requiredWhen" to decl.requiredWhen?.source, "minRows" to decl.minRows,
            "options" to decl.options,
            "columns" to decl.columns.map { col ->
                linkedMapOf(
                    "name" to col.name,
                    "type" to col.type.keyword,
                    "optional" to col.optional,
                    "requiredWhen" to col.requiredWhen?.source,
                )
            },
            "keyColumn" to view.dimensions.values.firstOrNull { it.fromTable == id }?.keyColumn,
            "references" to decl.references,
            "constraints" to
                decl.presentation.attributes.filterKeys {
                    it in setOf("min", "max", "required", "pattern", "max-length")
                }
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
        "id" to node.id,
        "label" to node.label,
        "type" to node.type.keyword,
        "reference" to node.presentation.reference,
        "attributes" to node.presentation.attributes.mapValues { (_, value) -> WorkbenchJson.value(value) },
    )

    private fun metadata(node: ViewNode): Map<String, Any?> = linkedMapOf(
        "id" to node.id, "kind" to node.kind.name.lowercase().replace('_', '-'),
        "label" to node.label, "type" to node.type.keyword, "dims" to node.dims,
        "op" to (node.line?.op ?: node.choice?.op)?.keyword,
        "reference" to node.presentation.reference, "source" to node.presentation.source,
        "note" to node.presentation.note, "class" to node.presentation.classes,
        "signLabels" to
            node.signLabels?.let {
                linkedMapOf("positive" to it.positive, "negative" to it.negative, "zero" to it.zero)
            },
        "group" to node.groupKey,
        "attributes" to node.presentation.attributes.mapValues { (_, value) -> WorkbenchJson.value(value) },
        "formula" to
            (node.line?.formula ?: node.check?.formula ?: node.reconcile?.left)?.let {
                linkedMapOf(
                    "text" to it.source,
                    "location" to location(it.location),
                )
            },
        "location" to location(node.location),
        "userDefined" to node.userDefined,
        "slot" to node.slotId,
        "input" to node.input?.let { fieldMetadata(it) },
        "aggregate" to node.line?.ratio?.let { ratio ->
            linkedMapOf(
                "ratio" to listOf(ratio.numerator, ratio.denominator),
                "round" to ratio.rounding?.let { listOf(it.scale, it.mode.name.lowercase()) },
            )
        },
        "reconciliation" to node.reconcile?.let {
            linkedMapOf(
                "left" to it.left.source,
                "right" to it.right.source,
                "tolerance" to it.tolerance.toPlainString(),
                "severity" to it.severity.name.lowercase(),
            )
        },
    )

    private fun fieldMetadata(input: com.xqiou.mantra.core.model.InputDecl): Map<String, Any?> = linkedMapOf(
        "optional" to input.optional,
        "default" to input.default?.let(WorkbenchJson::value),
        "requiredWhen" to input.requiredWhen?.source,
        "minRows" to input.minRows,
        "options" to input.options,
        "columns" to input.columns.map { col ->
            linkedMapOf(
                "name" to col.name,
                "type" to col.type.keyword,
                "optional" to col.optional,
                "requiredWhen" to col.requiredWhen?.source,
            )
        },
        "references" to input.references,
    )

    private fun slots(root: ViewSection, view: CalculationView): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        fun walk(item: ViewItem) {
            if (item is ViewSection) {
                if (item.item.slot) {
                    result += linkedMapOf(
                        "id" to item.id,
                        "title" to item.label,
                        "panel" to view.structure.panelOf(item.id)?.id,
                        "extensions" to view.case.extensions[item.id].orEmpty().mapNotNull {
                            (it as? com.xqiou.mantra.core.model.NodeItem)?.id
                        },
                    )
                }
                item.children.forEach(::walk)
            }
        }
        walk(root)
        return result
    }

    private fun overviewPanel(panel: com.xqiou.mantra.render.paper.OverviewPanel): Map<String, Any?> = linkedMapOf(
        "panelId" to panel.panelId,
        "title" to panel.title,
        "tableRef" to panel.tableRef,
        "value" to panel.value,
        "entry" to panel.entry,
    )

    private fun table(table: PaperTable, view: CalculationView): Map<String, Any?> = linkedMapOf(
        "id" to table.id,
        "ref" to table.ref,
        "title" to table.title,
        "breadcrumb" to table.breadcrumb,
        "style" to table.style.name.lowercase(),
        "columns" to table.columns.map { col ->
            linkedMapOf(
                "id" to col.id,
                "header" to col.header,
                "content" to col.content.styleRole(),
                "align" to col.align.name.lowercase(),
                "width" to col.width,
            )
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
                    val explicit = row.valueAddresses.getOrNull(index)
                    val cellNode = explicit?.nodeId?.let(view.nodes::get) ?: node
                    val cellAddress = when {
                        explicit != null -> address(
                            if (explicit.aggregate) "aggregate.${explicit.nodeId}" else explicit.nodeId,
                            if (explicit.aggregate) aggregateCoordinate(view, explicit.fixed) else explicit.coord,
                        )
                        numeric && node?.line?.ratio != null && coord == null && node.dims.isNotEmpty() ->
                            address("aggregate.${node.id}")
                        numeric && hasExactCoord -> address(requireNotNull(node).id, coord)
                        else -> null
                    }
                    val resolvedStyle = row.cellStyles.getOrNull(index)?.let(row.style::merge) ?: row.style
                    linkedMapOf(
                        "text" to cell,
                        "address" to cellAddress,
                        "editable" to (
                            numeric && (explicit?.aggregate == false || (explicit == null && hasExactCoord)) &&
                                cellNode?.kind == NodeKind.INPUT &&
                                view.structure.panelOf(cellNode.id)?.id == table.id
                            ),
                        "style" to style(resolvedStyle),
                    ).apply {
                        val overrides = styleOverrides(resolvedStyle)
                        if (overrides.isNotEmpty()) put("styleOverrides", overrides)
                    }
                },
            )
        },
    )

    private fun style(style: StyleSpec): Map<String, String> = linkedMapOf(
        "weight" to (style.weight?.name?.lowercase() ?: "normal"),
        "tone" to (style.tone?.name?.lowercase() ?: "default"),
        "fill" to (style.fill?.name?.lowercase() ?: "none"),
    )

    private fun styleOverrides(style: StyleSpec): Map<String, String> = buildMap {
        style.weight?.let { put("weight", it.name.lowercase()) }
        style.tone?.let { put("tone", it.name.lowercase()) }
        style.fill?.let { put("fill", it.name.lowercase()) }
    }
}
