package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.model.Rounding
import java.math.BigDecimal

/** One coordinate considered by a weighted ratio; only [active] members enter its totals. */
data class RatioContribution(
    val coord: Coord,
    val numerator: BigDecimal,
    val denominator: BigDecimal,
    val active: Boolean,
)

/**
 * Exact engine-owned evidence for `sum(numerator) / sum(denominator)`. Member details are bounded;
 * Successful totals cover the complete active mask. When [undefinedReason] is
 * `selected-value-undefined`, numeric contributions and totals contain only available numbers;
 * they are incomplete evidence and must not be interpreted as a successful ratio. Otherwise the
 * unrounded value is the exact rational pair [numeratorTotal]/[denominatorTotal].
 */
data class RatioAggregateTrace(
    val numeratorId: String,
    val denominatorId: String,
    override val dimensions: List<String>,
    override val fixed: Map<String, String>,
    val members: List<RatioContribution>,
    override val memberCount: Int,
    override val activeMemberCount: Int,
    val numeratorTotal: BigDecimal,
    val denominatorTotal: BigDecimal,
    val rounding: Rounding?,
    override val result: BigDecimal?,
    /** Stable reason: no-active-members, zero-denominator, rounding-required or selected-value-undefined. */
    override val undefinedReason: String?,
    override val truncated: Boolean,
) : AggregateTrace
