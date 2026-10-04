package com.xqiou.mantra.render.paper

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.AggregateTrace
import com.xqiou.mantra.core.view.BoundaryAggregateTrace
import com.xqiou.mantra.core.view.RatioAggregateTrace
import com.xqiou.mantra.core.view.SumAggregateTrace

/** Describes a host reduction from its actual engine evidence, never from substituted source text. */
internal object ReductionExplainer {
    fun formula(trace: AggregateTrace): String = when (trace) {
        is RatioAggregateTrace -> RatioExplainer.formula(trace)
        is BoundaryAggregateTrace -> "${trace.boundary.name.lowercase()}(${trace.dimension})"
        is SumAggregateTrace -> "Σ contributions"
    }

    fun explain(trace: AggregateTrace): String {
        if (trace is RatioAggregateTrace) return RatioExplainer.explain(trace)
        val contributions = when (trace) {
            is BoundaryAggregateTrace -> trace.selected
            is SumAggregateTrace -> trace.members
            else -> error("Unknown aggregate evidence")
        }
        return buildString {
            append(formula(trace)).append(": ")
            append(
                contributions.joinToString("; ") { part ->
                    val coordinate = trace.dimensions.zip(part.coord).joinToString(", ") { (dimension, key) ->
                        "$dimension=$key"
                    }
                    val value = (part.value as? Value.Num)?.value?.toPlainString() ?: "nil"
                    "[$coordinate] $value ${if (part.active && part.selected) "included" else "excluded"}"
                },
            )
            append(" → ").append(trace.result?.toPlainString() ?: "undefined [${trace.undefinedReason}]")
            if (trace.truncated) {
                append(
                    " [member details truncated; ${trace.activeMemberCount}/${trace.memberCount} included]",
                )
            }
        }
    }
}
