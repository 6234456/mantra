package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.NodeResult
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.decimalOrZero
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.mantra.core.view.Member
import com.xqiou.mantra.core.view.ViewNode
import com.xqiou.mantra.core.view.ViewSection
import com.xqiou.mantra.core.view.frozenList
import com.xqiou.mantra.core.view.snapshot
import java.math.BigDecimal

/**
 * Outcome of a calculation, including partial values and diagnostics when evaluation fails.
 * Read [view] for immutable presentation metadata and values; executable plans stay inside core.
 */
class CalculationResult internal constructor(
    internal val plan: CalculationPlan,
    internal val rawMembers: Map<String, List<Member>>,
    internal val rawNodes: Map<String, NodeResult>,
    diagnostics: List<Diagnostic>,
    explainTrace: ExplainTrace? = null,
) {
    /** Findings retained with detached coordinate lists. */
    val diagnostics: List<Diagnostic> = frozenList(diagnostics.map { it.copy(coord = frozenList(it.coord)) })

    /** Source-level trace captured for an Explain request, when requested. */
    val explainTrace: ExplainTrace? = explainTrace?.snapshot()

    /** Detached schema document retained for reproducing this calculation. */
    val schema: Schema = plan.schema.snapshot()

    /** Detached, read-only snapshot captured before the calculation is returned to its caller. */
    val view: CalculationView = CalculationView.fromResult(this)
    val case: CaseData get() = view.case
    val tree: ViewSection get() = view.tree
    val members: Map<String, List<Member>> get() = view.members
    val nodes: Map<String, ViewNode> get() = view.nodes
    val succeeded: Boolean get() = diagnostics.none { it.severity == Severity.ERROR }

    /** Returns one node; an unknown id throws [NoSuchElementException]. */
    fun node(id: String): ViewNode = view.node(id)

    /** Returns a coordinate value, or [Value.Nil] when that coordinate has no value. */
    fun value(id: String, vararg coord: String): Value = node(id).value(coord.toList())

    /** Reads a numeric value, treating absent or nonnumeric values as zero. */
    fun decimal(id: String, vararg coord: String): BigDecimal = value(id, *coord).decimalOrZero()
}
