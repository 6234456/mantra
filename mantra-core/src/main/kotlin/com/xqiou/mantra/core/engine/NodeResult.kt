package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.model.AggregateRule
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.decimalOrZero
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.mantra.core.view.RatioAggregateTrace
import java.math.BigDecimal

/** Evaluation-owned storage; consumers read the immutable ViewNode snapshot. */
internal class NodeResult(
    val vertex: ValueVertex,
    val values: Map<Coord, Value>,
    val active: Map<Coord, Boolean>,
    val traces: Map<Coord, NodeTrace>,
    val aggregateValue: BigDecimal? = null,
    val aggregateTrace: RatioAggregateTrace? = null,
) {
    val id: String get() = vertex.id
    val dims: List<String> get() = vertex.dims

    fun value(coord: Coord = emptyList()): Value = values[coord] ?: Value.Nil

    fun isActive(coord: Coord = emptyList()): Boolean = active[coord] == true

    fun trace(coord: Coord = emptyList()): NodeTrace? = traces[coord]

    /** Cross total when the schema declares the measure additive; null for rates and other nonadditive measures. */
    fun crossTotal(): BigDecimal? {
        if (vertex.isValidation) return null
        if ((vertex as? LineVertex)?.item?.aggregate == AggregateRule.RATIO) return aggregateValue
        if (vertex is TotalVertex && values.any { (coord, value) -> value == Value.Nil && isActive(coord) }) return null
        if ((vertex as? LineVertex)?.item?.aggregate == AggregateRule.NONE && dims.isNotEmpty()) return null
        return values.values.fold(BigDecimal.ZERO) { acc, v -> acc + v.decimalOrZero() }
    }

    val anyActive: Boolean get() = active.values.any { it }
}
