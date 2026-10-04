package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.RatioAggregateTrace
import com.xqiou.mantra.core.view.RatioContribution
import java.math.BigDecimal

/** Computes weighted values and their authoritative evidence once per fixed dimension context. */
internal class RatioAggregation(
    private val sink: DiagnosticSink,
    private val values: (String) -> Map<Coord, Value>,
    private val active: (String, Coord) -> Boolean,
) {
    private data class Context(val node: String, val fixed: List<Pair<String, String>>)
    private val cache = mutableMapOf<Context, RatioAggregateTrace>()

    fun aggregate(vertex: LineVertex, dims: List<String>, coord: Coord): RatioAggregateTrace =
        cache.getOrPut(Context(vertex.id, dims.zip(coord))) {
            compute(vertex, dims, coord)
        }

    private fun compute(vertex: LineVertex, dims: List<String>, coord: Coord): RatioAggregateTrace {
        val ratio = checkNotNull(vertex.item.ratio)
        val fixed = dims.zip(coord).toMap()
        val candidates = values(vertex.id).keys.filter { sourceCoord ->
            vertex.dims.withIndex().all { (index, dimension) ->
                fixed[dimension]?.let { it == sourceCoord[index] } ?: true
            }
        }
        val included = candidates.filter { active(vertex.id, it) }
        fun number(id: String, at: Coord) = (values(id)[at] as? Value.Num)?.value ?: BigDecimal.ZERO
        val numerator = included.fold(BigDecimal.ZERO) { sum, at -> sum + number(ratio.numerator, at) }
        val denominator = included.fold(BigDecimal.ZERO) { sum, at -> sum + number(ratio.denominator, at) }
        var reason: String? = null
        val result = when {
            included.isEmpty() -> {
                reason = "no-active-members"
                null
            }
            denominator.signum() == 0 -> {
                reason = "zero-denominator"
                sink.warning(
                    "MANTRA-AGGREGATE-ZERO-DENOMINATOR",
                    "Ratio ${vertex.id} has a zero aggregate denominator",
                    vertex.location,
                    vertex.id,
                    coord,
                    category = DiagnosticCategory.BUSINESS,
                )
                null
            }
            else -> try {
                ratio.rounding?.let { numerator.divide(denominator, it.scale, it.mode) }
                    ?: numerator.divide(denominator)
            } catch (_: ArithmeticException) {
                reason = "rounding-required"
                sink.error(
                    "MANTRA-AGGREGATE-DIVISION",
                    "Ratio ${vertex.id} requires an explicit rounding rule",
                    vertex.location,
                    vertex.id,
                    coord,
                    category = DiagnosticCategory.EVALUATION,
                )
                null
            }
        }
        val truncated = candidates.size > MAX_MEMBERS
        if (truncated) {
            sink.warning(
                "MANTRA-AUDIT-TRUNCATED",
                "Ratio ${vertex.id} retains $MAX_MEMBERS member details; " +
                    "totals include all ${included.size} active members",
                vertex.location,
                vertex.id,
                coord,
                category = DiagnosticCategory.EVALUATION,
            )
        }
        return RatioAggregateTrace(
            ratio.numerator,
            ratio.denominator,
            vertex.dims,
            fixed,
            candidates.take(MAX_MEMBERS).map { at ->
                RatioContribution(at, number(ratio.numerator, at), number(ratio.denominator, at), active(vertex.id, at))
            },
            candidates.size,
            included.size,
            numerator,
            denominator,
            ratio.rounding,
            result,
            reason,
            truncated,
        )
    }

    private companion object {
        const val MAX_MEMBERS = 64
    }
}
