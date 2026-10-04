package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.Severity
import java.math.BigDecimal

/** Exact numeric evidence retained for one reconciliation coordinate. */
data class Reconciliation(
    val left: BigDecimal,
    val right: BigDecimal,
    val difference: BigDecimal,
    val tolerance: BigDecimal,
)

/** One business decision; null [passed] means its condition excluded it or evaluation failed. */
data class ValidationResult(val passed: Boolean?, val severity: Severity, val reconciliation: Reconciliation? = null)
