package com.xqiou.mantra.render.paper

import com.xqiou.mantra.core.view.RatioAggregateTrace

/** Formats engine-owned aggregation evidence, including the exact mask and rational raw value. */
internal object RatioExplainer {
    fun formula(trace: RatioAggregateTrace): String = "Σ ${trace.numeratorId} ÷ Σ ${trace.denominatorId}"

    fun explain(trace: RatioAggregateTrace): String = buildString {
        append(
            trace.members.joinToString("; ") { member ->
                val coordinate = trace.dimensions.zip(member.coord).joinToString(", ") { (dimension, key) ->
                    "$dimension=$key"
                }
                "[$coordinate] ${member.numerator.toPlainString()} / ${member.denominator.toPlainString()} " +
                    if (member.active) "included" else "excluded"
            },
        )
        append(" → Σ ").append(trace.numeratorId).append(" = ").append(trace.numeratorTotal.toPlainString())
        append("; Σ ").append(trace.denominatorId).append(" = ").append(trace.denominatorTotal.toPlainString())
        append(" → ").append(trace.numeratorTotal.toPlainString()).append(" ÷ ")
        append(trace.denominatorTotal.toPlainString())
        trace.rounding?.let {
            append(" (round ").append(it.scale).append(' ')
            append(it.mode.name.lowercase().replace('_', '-')).append(')')
        }
        append(" = ").append(trace.result?.toPlainString() ?: "undefined [${trace.undefinedReason}]")
        if (trace.truncated) {
            append(
                " [member details truncated; ${trace.activeMemberCount}/${trace.memberCount} included]",
            )
        }
    }
}
