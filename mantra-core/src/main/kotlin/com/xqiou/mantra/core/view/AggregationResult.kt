package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.model.BoundaryAggregation
import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal

/** Null means the node has no supported numeric aggregation; Nil means an undefined result. */
data class AggregationResult(val value: Value?, val trace: AggregateTrace?)

/** Engine-owned reduction evidence, shared by calculation, paper, Explain and export. */
sealed interface AggregateTrace {
    val dimensions: List<String>
    val fixed: Map<String, String>
    val truncated: Boolean
    val result: BigDecimal?
    val undefinedReason: String?
    val memberCount: Int
    val activeMemberCount: Int
}

data class AggregateContribution(val coord: Coord, val value: Value, val active: Boolean, val selected: Boolean)

data class SumAggregateTrace(
    override val dimensions: List<String>,
    override val fixed: Map<String, String>,
    val members: List<AggregateContribution>,
    override val memberCount: Int,
    override val activeMemberCount: Int,
    override val result: BigDecimal?,
    override val undefinedReason: String?,
    override val truncated: Boolean,
) : AggregateTrace

data class BoundaryAggregateTrace(
    override val dimensions: List<String>,
    override val fixed: Map<String, String>,
    val dimension: String,
    val boundary: BoundaryAggregation.Boundary,
    val periodKeys: List<String>,
    val selected: List<AggregateContribution>,
    val selectionCount: Int,
    val members: List<AggregateContribution>,
    override val memberCount: Int,
    override val activeMemberCount: Int,
    override val result: BigDecimal?,
    override val undefinedReason: String?,
    override val truncated: Boolean,
) : AggregateTrace
