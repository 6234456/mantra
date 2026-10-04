package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.model.Rounding
import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal

/** Member keys in the canonical dimension order of a node. Scalars use an empty list. */
typealias Coord = List<String>

/** An active member of a dimension. [record] is what formulas see through the dimension symbol. */
data class Member(val key: String, val label: String, val index: Int, val record: Map<String, Value>)

/** Source category of an input value before formula evaluation. */
enum class InputOrigin { CASE, SOURCE, DEFAULT, IMPLICIT }

/** One referenced node or member record, with its value in the evaluation context. */
data class TraceRef(
    val id: String,
    val value: Value,
    val kind: Kind,
    val coord: Coord? = null,
    val fixed: Map<String, String>? = null,
) {
    /** How the reference was aligned to the formula's dimensions. */
    enum class Kind { ALIGNED, MEMBER_MAP, ALL, MEMBER, PREVIOUS }
}

/** Signed contribution to a total, including whether more dimensions were cross-footed. */
data class TracePart(
    val id: String,
    val sign: Int,
    val value: BigDecimal?,
    val crossFooted: Boolean,
    /** Evidence when a weighted ratio was cross-footed into this contribution. */
    val aggregate: RatioAggregateTrace? = null,
    val reduction: AggregateTrace? = null,
)

/** Evaluated outcome of one choice option, including its availability. */
data class TraceOption(
    val key: String,
    val label: String,
    val value: Value,
    val available: Boolean,
    val explanation: ExplainTrace? = null,
    val conditionExplanation: ExplainTrace? = null,
)

/** Bounded source-level details captured for one requested node coordinate. */
data class ExplainStep(
    val text: String,
    val value: Value?,
    val location: com.xqiou.mantra.core.SourceLocation,
    /** Kernel summary retained when it cannot be represented as one complete value. */
    val rendered: String? = null,
)

/** One source-level conditional branch and whether evaluation selected it. */
data class ExplainBranch(val text: String, val selected: Boolean, val location: com.xqiou.mantra.core.SourceLocation)

/** Bounded source-level trace; [truncated] signals that the collection budget was reached. */
data class ExplainTrace(val steps: List<ExplainStep>, val branches: List<ExplainBranch>, val truncated: Boolean)

/** How a value came about; renderers turn this into the audit trail ("Rechenweg"). */
sealed interface NodeTrace {
    /** Input origin and an optional external data-source name. */
    data class Input(val origin: InputOrigin, val source: String? = null) : NodeTrace {
        /** Stable lowercase source label used by audit renderers. */
        fun label(): String = if (origin == InputOrigin.SOURCE &&
            source != null
        ) {
            "source:$source"
        } else {
            origin.name.lowercase()
        }
    }

    /** Winning schema, parameter-set or case layer. */
    data class Param(val source: String) : NodeTrace

    /** Formula references, unrounded outcome and applied rounding rule. */
    data class Computed(
        val references: List<TraceRef>,
        val raw: Value,
        val rounding: Rounding?,
        val spread: Boolean,
        val explanation: ExplainTrace? = null,
    ) : NodeTrace

    /** Signed total components in calculation order. */
    data class Sum(val parts: List<TracePart>) : NodeTrace

    /** All evaluated alternatives and the selected option key. */
    data class Choice(val options: List<TraceOption>, val selected: String?, val raw: Value, val rounding: Rounding?) :
        NodeTrace

    /** Decision and exact reconciliation evidence, independent of calculation success. */
    data class Validation(
        val result: ValidationResult,
        val explanation: ExplainTrace? = null,
        val rightExplanation: ExplainTrace? = null,
    ) : NodeTrace {
        val passed: Boolean? get() = result.passed
        val severity: com.xqiou.mantra.core.Severity get() = result.severity
        val reconciliation: Reconciliation? get() = result.reconciliation
    }

    /** A condition excluded this coordinate from the calculation. */
    data class Inactive(val reason: String) : NodeTrace

    /** Evaluation failed and the diagnostic message explains why. */
    data class Failed(val message: String, val explanation: ExplainTrace? = null) : NodeTrace
}
