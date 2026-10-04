package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.Evaluator
import com.xqiou.mantra.core.engine.PlanRebinding
import com.xqiou.mantra.core.engine.Planner
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.view.frozenList
import com.xqiou.mantra.core.view.frozenMap
import com.xqiou.mantra.core.view.snapshot

/**
 * A closeable calculation runtime for one schema. Returned results are detached immutable snapshots.
 * Fact edits invalidate observed member dependencies; formula/extension or parameter-type changes
 * rebuild the plan. This value-only runtime retains no kernel audit or Explain steps.
 * Open, recalculate and close the session on the same thread. Detached [result] snapshots may be
 * passed to other threads; synchronization does not make the underlying kernel session transferable.
 */
class CalculationSession internal constructor(schema: Schema, case: CaseData, parameters: List<ParameterSet>) :
    AutoCloseable {
    private val schema = schema.snapshot()
    private val ownerThread = Thread.currentThread()
    private val initialParameters = parameters.snapshotSets()
    private lateinit var plan: CalculationPlan
    private lateinit var evaluator: Evaluator
    private var closed = false
    private var broken = false
    var result: CalculationResult
        private set
    val lastRun: RecalculationStats get() = evaluator.lastRun

    init {
        val sink = DiagnosticSink()
        plan = build(case.snapshot(), initialParameters, sink)
        evaluator = Evaluator(plan, sink)
        result = try {
            evaluator.run()
        } catch (failure: RuntimeException) {
            evaluator.close()
            throw failure
        }
    }

    /** Omitted parameter sets use the sets supplied when this session was opened. */
    @Synchronized
    fun recalculate(case: CaseData, parameters: List<ParameterSet> = initialParameters): CalculationResult {
        checkOwnerThread()
        check(!closed) { "Calculation session is closed" }
        val snapshot = case.snapshot()
        val sets = parameters.snapshotSets()
        val sink = DiagnosticSink()
        val rebound = if (!broken && PlanRebinding.compatible(plan, snapshot)) {
            PlanRebinding.bind(plan, snapshot, sets, sink)
        } else {
            null
        }
        if (rebound != null) {
            broken = true
            val next = evaluator.recalculate(rebound, sink.all)
            plan = rebound
            result = next
            broken = false
        } else {
            // Build first: a rejected edit leaves the last successful result readable.
            val buildSink = DiagnosticSink()
            val replacement = build(snapshot, sets, buildSink)
            val runtime = Evaluator(replacement, buildSink)
            val next = try {
                runtime.run()
            } catch (failure: RuntimeException) {
                runtime.close()
                throw failure
            }
            evaluator.close()
            evaluator = runtime
            plan = replacement
            result = next
            broken = false
        }
        return result
    }

    private fun build(case: CaseData, sets: List<ParameterSet>, sink: DiagnosticSink): CalculationPlan {
        val result = Planner(sink).plan(schema, case, sets)
        sink.throwIfErrors()
        return checkNotNull(result)
    }

    @Synchronized
    override fun close() {
        checkOwnerThread()
        if (!closed) {
            closed = true
            evaluator.close()
        }
    }

    private fun checkOwnerThread() {
        check(Thread.currentThread() === ownerThread) {
            "Calculation sessions must be used and closed on their opening thread"
        }
    }
}

private fun List<ParameterSet>.snapshotSets(): List<ParameterSet> = frozenList(
    map { set ->
        set.copy(
            meta = frozenMap(set.meta.mapValues { (_, value) -> value.snapshot() }),
            values = frozenMap(set.values.mapValues { (_, value) -> value.snapshot() }),
            references = frozenMap(set.references),
        )
    },
)
