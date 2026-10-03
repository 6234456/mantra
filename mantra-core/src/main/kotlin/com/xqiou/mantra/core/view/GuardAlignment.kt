package com.xqiou.mantra.core.view

/**
 * The dimension assignments on which all inherited section conditions can be evaluated together.
 * Evaluation and formula exporters consume the same assignments, then apply their own boolean or
 * symbolic condition values. A child with fewer dimensions does not choose a reduction rule.
 */
data class GuardAlignment(
    val extraDimensions: List<String>,
    val assignments: List<Map<String, String>>,
)
