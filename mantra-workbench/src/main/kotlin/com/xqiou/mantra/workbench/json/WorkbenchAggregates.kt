package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.RatioAggregateTrace
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.paper.NumberFormatter

/** Projects engine-owned aggregate evidence. No numeric calculation occurs in this projection. */
internal fun ratioDocument(
    trace: RatioAggregateTrace,
    layout: LayoutSpec? = null,
    presentation: Presentation? = null,
): Map<
    String,
    Any?,
    > {
    val formatter = layout?.let { NumberFormatter(it.number) }
    fun display(value: java.math.BigDecimal) = formatter?.value(
        Value.Num(value),
        null,
        null,
    ) ?: value.toPlainString()
    fun displayResult(value: java.math.BigDecimal) = formatter?.value(
        Value.Num(value),
        presentation?.format,
        presentation?.precision,
    ) ?: value.toPlainString()
    return linkedMapOf(
        "numeratorId" to trace.numeratorId,
        "denominatorId" to trace.denominatorId,

        "dimensions" to trace.dimensions,
        "fixed" to trace.fixed,

        "members" to trace.members.map { member ->
            linkedMapOf(
                "coord" to member.coord,
                "numerator" to WorkbenchJson.value(Value.Num(member.numerator)),

                "denominator" to WorkbenchJson.value(Value.Num(member.denominator)),
                "active" to member.active,
            )
        },

        "memberCount" to trace.memberCount,
        "activeMemberCount" to trace.activeMemberCount,

        "numeratorTotal" to WorkbenchJson.value(Value.Num(trace.numeratorTotal)),

        "denominatorTotal" to WorkbenchJson.value(Value.Num(trace.denominatorTotal)),

        "rounding" to trace.rounding?.let {
            linkedMapOf(
                "scale" to it.scale,
                "mode" to it.mode.name.lowercase(),
            )
        },

        "result" to trace.result?.let { WorkbenchJson.value(Value.Num(it)) },

        "undefinedReason" to trace.undefinedReason,
        "truncated" to trace.truncated,

        "display" to linkedMapOf(
            "numeratorTotal" to display(trace.numeratorTotal),

            "denominatorTotal" to display(trace.denominatorTotal),
            "result" to (trace.result?.let(::displayResult) ?: "—"),
        ),

    )
}

/** Partial cross-foot evidence is read from a checkpoint that actually evaluated that slice. */
internal fun CalculationView.ratioTrace(
    nodeId: String,
    fixed: Map<
        String,
        String,
        >,
): RatioAggregateTrace? {
    if (fixed.isEmpty()) return node(nodeId).aggregateTrace
    return nodes.values.firstNotNullOfOrNull { checkpoint ->
        checkpoint.values.keys.firstNotNullOfOrNull { coord ->
            (checkpoint.trace(coord) as? NodeTrace.Sum)?.parts?.firstNotNullOfOrNull { part ->
                part.aggregate?.takeIf { part.id == nodeId && it.fixed == fixed }
            }
        }
    }
}

internal fun aggregateExplanation(
    view: CalculationView,
    layout: LayoutSpec,
    nodeId: String,
    fixed: Map<
        String,
        String,
        >,
): Map<
    String,
    Any?,
    > {
    val node = view.node(nodeId)
    val trace = requireNotNull(
        view.ratioTrace(
            nodeId,
            fixed,
        ),
    ) { "No engine evidence for the ratio aggregate" }
    val formatter = NumberFormatter(layout.number)
    fun address(id: String, coord: List<String>) = linkedMapOf<
        String,
        Any?,
        >("node" to id).apply {
        if (coord.isNotEmpty()) {
            put(
                "coord",
                coord,
            )
        }
    }
    val references = trace.members.filter { it.active }.flatMap { member ->
        listOf(
            trace.numeratorId,
            trace.denominatorId,
        ).map { id ->
            val source = view.node(id)
            val value = source.value(member.coord)
            linkedMapOf<
                String,
                Any?,
                >(
                "address" to address(
                    id,
                    member.coord,
                ),
                "label" to source.label,

                "value" to WorkbenchJson.value(value),

                "display" to formatter.value(
                    value,
                    source.presentation.format,
                    source.presentation.precision,
                ),

                "kind" to "ratio-component",
                "origin" to null,
            )
        }
    }.take(63)
    return linkedMapOf(
        "address" to address(
            "aggregate.$nodeId",
            node.dims.mapNotNull { dim -> fixed[dim]?.let { "$dim=$it" } },
        ),

        "label" to node.label,
        "kind" to "ratio-aggregate",

        "formula" to linkedMapOf(
            "text" to "sum(${trace.numeratorId}) / sum(${trace.denominatorId})",

            "location" to linkedMapOf(
                "document" to node.location.source,
                "line" to node.location.line,

                "column" to node.location.column,
            ),
        ),

        "result" to linkedMapOf(
            "value" to WorkbenchJson.value(trace.result?.let { Value.Num(it) } ?: Value.Nil),

            "display" to formatter.value(
                trace.result?.let { Value.Num(it) } ?: Value.Nil,

                node.presentation.format,
                node.presentation.precision,
            ),

            "rounding" to trace.rounding?.let {
                linkedMapOf(
                    "scale" to it.scale,
                    "mode" to it.mode.name.lowercase(),
                )
            },
        ),

        "status" to when {
            trace.activeMemberCount == 0 -> "inactive"
            trace.undefinedReason == "rounding-required" -> "failed"
            else -> "active"
        },

        "reason" to trace.undefinedReason,
        "steps" to emptyList<Any>(),
        "branches" to emptyList<Any>(),

        "references" to references,
        "parts" to emptyList<Any>(),
        "options" to emptyList<Any>(),

        "reference" to node.presentation.reference,
        "truncated" to (trace.truncated || trace.activeMemberCount * 2 > 63),

        "aggregate" to ratioDocument(
            trace,
            layout,
            node.presentation,
        ),

    )
}
