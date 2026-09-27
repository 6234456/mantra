package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.AggregateRule
import com.xqiou.mantra.core.model.Rounding
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.decimalOrZero
import java.math.BigDecimal

/** An active member of a dimension. [record] is what formulas see through the dimension symbol. */
data class Member(val key: String, val label: String, val index: Int, val record: Map<String, Value>)

enum class InputOrigin { CASE, SOURCE, DEFAULT, IMPLICIT }

data class TraceRef(val id: String, val value: Value, val kind: Kind) {
    enum class Kind { ALIGNED, MEMBER_MAP, ALL, MEMBER }
}

data class TracePart(val id: String, val sign: Int, val value: BigDecimal, val crossFooted: Boolean)

data class TraceOption(val key: String, val label: String, val value: Value, val available: Boolean)

/** Bounded source-level details captured for one requested node coordinate. */
data class ExplainStep(val text: String, val value: Value, val location: com.xqiou.mantra.core.SourceLocation)
data class ExplainBranch(val text: String, val selected: Boolean, val location: com.xqiou.mantra.core.SourceLocation)
data class ExplainTrace(val steps: List<ExplainStep>, val branches: List<ExplainBranch>, val truncated: Boolean)

/** How a value came about; renderers turn this into the audit trail ("Rechenweg"). */
sealed interface NodeTrace {
    data class Input(val origin: InputOrigin, val source: String? = null) : NodeTrace {
        fun label(): String = if (origin == InputOrigin.SOURCE && source != null) "source:$source" else origin.name.lowercase()
    }
    data class Param(val source: String) : NodeTrace
    data class Computed(val references: List<TraceRef>, val raw: Value, val rounding: Rounding?, val spread: Boolean) : NodeTrace
    data class Sum(val parts: List<TracePart>) : NodeTrace
    data class Choice(val options: List<TraceOption>, val selected: String?, val raw: Value, val rounding: Rounding?) : NodeTrace
    data class Inactive(val reason: String) : NodeTrace
    data class Failed(val message: String) : NodeTrace
}

class NodeResult(
    val vertex: ValueVertex,
    val values: Map<Coord, Value>,
    val active: Map<Coord, Boolean>,
    val traces: Map<Coord, NodeTrace>,
) {
    val id: String get() = vertex.id
    val dims: List<String> get() = vertex.dims

    fun value(coord: Coord = emptyList()): Value = values[coord] ?: Value.Nil

    fun isActive(coord: Coord = emptyList()): Boolean = active[coord] == true

    fun trace(coord: Coord = emptyList()): NodeTrace? = traces[coord]

    /** Cross total when the schema declares the measure additive; null for rates and other nonadditive measures. */
    fun crossTotal(): BigDecimal? {
        if ((vertex as? LineVertex)?.item?.aggregate == AggregateRule.NONE && dims.isNotEmpty()) return null
        return values.values.fold(BigDecimal.ZERO) { acc, v -> acc + v.decimalOrZero() }
    }

    val anyActive: Boolean get() = active.values.any { it }
}

class CalculationResult(
    val plan: CalculationPlan,
    val members: Map<String, List<Member>>,
    val nodes: Map<String, NodeResult>,
    val diagnostics: List<Diagnostic>,
    val explainTrace: ExplainTrace? = null,
) {
    val schema: Schema get() = plan.schema
    val case: CaseData get() = plan.case
    val tree: ResolvedSection get() = plan.tree
    val succeeded: Boolean get() = diagnostics.none { it.severity == Severity.ERROR }

    fun node(id: String): NodeResult = nodes[id] ?: throw NoSuchElementException("No calculated node `$id`")

    fun value(id: String, vararg coord: String): Value = node(id).value(coord.toList())

    fun decimal(id: String, vararg coord: String): BigDecimal = value(id, *coord).decimalOrZero()
}
