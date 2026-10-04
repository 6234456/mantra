package com.xqiou.mantra.core.model

import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.SourceLocation

/** A named business check is presented in place and never contributes to running totals. */
data class CheckItem(
    override val id: String,
    override val label: String,
    val formula: Formula,
    val severity: Severity,
    val per: List<String>?,
    val condition: Formula?,
    override val presentation: Presentation,
    override val location: SourceLocation,
    override val userDefined: Boolean = false,
) : NodeItem

/** A reconciliation preserves both numeric sides and the exact unrounded difference. */
data class ReconcileItem(
    override val id: String,
    override val label: String,
    val left: Formula,
    val right: Formula,
    val tolerance: java.math.BigDecimal,
    val severity: Severity,
    val per: List<String>?,
    val condition: Formula?,
    override val presentation: Presentation,
    override val location: SourceLocation,
    override val userDefined: Boolean = false,
) : NodeItem
