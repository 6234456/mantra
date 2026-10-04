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
 * totals always cover the complete active mask. The unrounded value is the exact rational pair
 * [numeratorTotal]/[denominatorTotal], so no implicit decimal approximation is introduced.
 */
data class RatioAggregateTrace(
    val numeratorId: String,
    val denominatorId: String,
    val dimensions: List<String>,
    val fixed: Map<String, String>,
    val members: List<RatioContribution>,
    val memberCount: Int,
    val activeMemberCount: Int,
    val numeratorTotal: BigDecimal,
    val denominatorTotal: BigDecimal,
    val rounding: Rounding?,
    val result: BigDecimal?,
    /** Stable reason: no-active-members, zero-denominator or rounding-required. */
    val undefinedReason: String?,
    val truncated: Boolean,
)
