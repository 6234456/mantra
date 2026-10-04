package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.api.CalculationReader
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.AggregateContribution
import com.xqiou.mantra.core.view.AggregateTrace
import com.xqiou.mantra.core.view.AggregationResult
import com.xqiou.mantra.core.view.BoundaryAggregateTrace
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.RatioAggregateTrace
import com.xqiou.mantra.core.view.SumAggregateTrace
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.paper.NumberFormatter

/** Serializes the engine's immutable reduction evidence, without repeating its arithmetic. */
internal fun reductionDocument(
    trace: AggregateTrace,
    layout: LayoutSpec? = null,
    presentation: Presentation? = null,
): Map<String, Any?> {
    if (trace is RatioAggregateTrace) return ratioDocument(trace, layout, presentation)
    val formatter = layout?.let { NumberFormatter(it.number) }
    fun contribution(member: AggregateContribution) = linkedMapOf(
        "coord" to member.coord,
        "value" to WorkbenchJson.value(member.value),
        "active" to member.active,
        "selected" to member.selected,
    )
    val members = when (trace) {
        is BoundaryAggregateTrace -> trace.members
        is SumAggregateTrace -> trace.members
        else -> error("Unknown aggregate evidence")
    }
    return linkedMapOf<String, Any?>(
        "kind" to if (trace is BoundaryAggregateTrace) "boundary" else "sum",
        "dimensions" to trace.dimensions,
        "fixed" to trace.fixed,
        "members" to members.map(::contribution),
        "memberCount" to trace.memberCount,
        "activeMemberCount" to trace.activeMemberCount,
        "result" to trace.result?.let { WorkbenchJson.value(Value.Num(it)) },
        "undefinedReason" to trace.undefinedReason,
        "truncated" to trace.truncated,
        "display" to mapOf(
            "result" to (
                trace.result?.let {
                    formatter?.value(Value.Num(it), presentation?.format, presentation?.precision) ?: it.toPlainString()
                } ?: "—"
                ),
        ),
    ).apply {
        if (trace is BoundaryAggregateTrace) {
            put("dimension", trace.dimension)
            put("boundary", trace.boundary.name.lowercase())
            put("periodKeys", trace.periodKeys)
            put("selected", trace.selected.map(::contribution))
            put("selectionCount", trace.selectionCount)
        }
    }
}

internal fun aggregateCoordinate(view: CalculationView, fixed: Map<String, String>): List<String> =
    view.dimensionOrder(fixed.keys).map { "$it=${fixed.getValue(it)}" }

internal fun reductionExplanation(
    view: CalculationView,
    layout: LayoutSpec,
    nodeId: String,
    fixed: Map<String, String>,
    reader: CalculationReader? = null,
    captured: AggregationResult? = null,
): Map<String, Any?> {
    if (reader == null) return view.openReader().use { reductionExplanation(view, layout, nodeId, fixed, it, captured) }
    val node = view.node(nodeId)
    val reduced = captured ?: reader.reduce(view, nodeId, fixed)
    val trace = requireNotNull(reduced.trace) { "No engine reduction evidence" }
    val formatter = NumberFormatter(layout.number)
    fun address(id: String, coord: List<String>) = linkedMapOf<String, Any?>("case" to null, "node" to id).apply {
        if (coord.isNotEmpty()) put("coord", coord)
    }
    reader.chargeScans(trace.memberCount.toLong())
    val selected = when (trace) {
        is BoundaryAggregateTrace -> trace.selected
        is SumAggregateTrace -> trace.members.filter { it.selected }
        else -> error("Ratio evidence uses its dedicated projection")
    }
    val rule = if (trace is BoundaryAggregateTrace) trace.boundary.name.lowercase() else "sum"
    return linkedMapOf(
        "address" to address("aggregate.$nodeId", aggregateCoordinate(view, fixed)),
        "label" to node.label,
        "kind" to "$rule-aggregate",
        "formula" to mapOf(
            "text" to "$rule($nodeId)",
            "location" to mapOf(
                "document" to node.location.source,
                "line" to node.location.line,
                "column" to node.location.column,
            ),
        ),
        "result" to mapOf(
            "value" to WorkbenchJson.value(reduced.value ?: Value.Nil),
            "display" to
                formatter.value(reduced.value ?: Value.Nil, node.presentation.format, node.presentation.precision),
            "rounding" to null,
        ),
        "status" to if (trace.activeMemberCount > 0) "active" else "inactive", "reason" to trace.undefinedReason,
        "link" to null, "steps" to emptyList<Any>(), "branches" to emptyList<Any>(),
        "references" to selected.take(63).map { member ->
            linkedMapOf(
                "address" to address(nodeId, member.coord),
                "label" to node.label,
                "value" to WorkbenchJson.value(member.value),
                "display" to formatter.value(member.value, node.presentation.format, node.presentation.precision),
                "kind" to "$rule-contribution",
                "origin" to null,
            )
        },
        "parts" to emptyList<Any>(), "options" to emptyList<Any>(),
        "reference" to node.presentation.reference,
        "truncated" to (trace.truncated || selected.size > 63),
        "aggregate" to reductionDocument(trace, layout, node.presentation),
    )
}
