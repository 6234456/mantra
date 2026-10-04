package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.normein.dsl.trace.DslTraceLimits
import com.xqiou.normein.dsl.trace.DslTraceNode

/** Shared run budget for audit output and the single-value Explain path. */
internal class AuditCapture(
    options: AuditOptions?,
    private val target: Pair<String, Coord>?,
    private val sink: DiagnosticSink,
) {
    private val all = options != null
    private val limits = options ?: AuditOptions()
    private var formulas = 0
    private var events = 0
    private var items = 0
    private var characters = 0
    private var warned = false

    data class Request(
        val enabled: Boolean,
        val kernelLimits: DslTraceLimits,
        val projectionBudget: TraceProjection.Budget,
    )

    fun request(nodeId: String, coord: Coord, eligible: Boolean): Request? {
        val selected = target?.let { it.first == nodeId && it.second.take(coord.size) == coord } == true
        if (!eligible || (!all && !selected)) return null
        val remainingItems = limits.maxSteps - items
        val remainingCharacters = limits.maxCharacters - characters
        val remainingEvents = limits.maxEvents - events
        val enabled = formulas < limits.maxFormulas && remainingItems > 0 &&
            remainingCharacters > 0 && remainingEvents > 0
        if (enabled) formulas++
        return Request(
            enabled,
            DslTraceLimits(
                maxEvents = minOf(10_000, remainingEvents).toLong(),
                maxTextCharacters = remainingCharacters.toLong(),
            ),
            TraceProjection.Budget(
                minOf(limits.maxStepsPerFormula, remainingItems),
                minOf(limits.maxBranchesPerFormula, remainingItems),
                remainingCharacters,
                remainingItems,
            ),
        )
    }

    fun finish(
        request: Request?,
        formula: CompiledFormula,
        root: DslTraceNode?,
        kernelTruncated: Boolean,
        nodeId: String,
        coord: Coord,
        scan: () -> Unit = {},
    ): ExplainTrace? {
        if (request == null) return null
        val projection = if (request.enabled && root != null) {
            TraceProjection.project(formula, root, kernelTruncated, request.projectionBudget, scan)
        } else {
            // Preparation can fail before any trace event; that is not a collection-budget failure.
            TraceProjection.Projection(
                ExplainTrace(emptyList(), emptyList(), !request.enabled || kernelTruncated),
                0,
                0,
            )
        }
        events += projection.events
        items += projection.trace.steps.size + projection.trace.branches.size
        characters += projection.characters
        if (projection.trace.truncated && !warned) {
            sink.warning(
                "MANTRA-AUDIT-TRUNCATED",
                "Audit details reached a kernel or Mantra collection budget; truncated entries are marked explicitly",
                formula.formula.location,
                nodeId,
                coord,
                category = DiagnosticCategory.EVALUATION,
            )
            warned = true
        }
        return projection.trace
    }
}
