package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.Evaluator
import com.xqiou.mantra.core.engine.LiteralInputRows
import com.xqiou.mantra.core.engine.PlanRebinding
import com.xqiou.mantra.core.engine.Planner
import com.xqiou.mantra.core.engine.RunBoundary
import com.xqiou.mantra.core.engine.RunContext
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
class CalculationSession internal constructor(
    schema: Schema,
    case: CaseData,
    parameters: List<ParameterSet>,
    context: RunContext,
) : AutoCloseable {
    private val schema = schema.snapshot { context.charge(RunCounter.HOST_SCANS) }
    private val ownerThread = Thread.currentThread()
    private val initialParameters = parameters.snapshotSets()
    private lateinit var plan: CalculationPlan
    private lateinit var evaluator: Evaluator
    private var closed = false
    private var broken = false
    private var formulaProfile = context.options.formulaLimits
    var result: CalculationResult
        private set
    val lastRun: RecalculationStats get() = evaluator.lastRun

    init {
        LiteralInputRows.account(this.schema, case, context)
        val sink = DiagnosticSink()
        plan = build(case.snapshot { context.charge(RunCounter.HOST_SCANS) }, initialParameters, sink, context)
        evaluator = Evaluator(plan, sink, calculationOptions = context.options)
        result = try {
            evaluator.run(context)
        } catch (failure: RuntimeException) {
            evaluator.close()
            throw failure
        }
    }

    /** Omitted parameter sets use the sets supplied when this session was opened. */
    @Synchronized
    fun recalculate(
        case: CaseData,
        parameters: List<ParameterSet> = initialParameters,
        options: CalculationOptions = CalculationOptions(formulaLimits = formulaProfile),
    ): CalculationResult {
        checkOwnerThread()
        check(!closed) { "Calculation session is closed" }
        val published = result
        return try {
            RunBoundary.independent(options, { next, usage ->
                next.withGraphMetadata(emptyList(), usage).also { result = it }
            }) { recalculateBound(case, parameters, it) }
        } catch (failure: RuntimeException) {
            if (result !== published) {
                // The bound runtime finished, but the independent epoch rejected publication.
                // Keep the previous public snapshot and rebuild this rejected runtime next time.
                result = published
                broken = true
            }
            throw failure
        }
    }

    internal fun recalculateBound(
        case: CaseData,
        parameters: List<ParameterSet>,
        context: RunContext,
    ): CalculationResult {
        checkOwnerThread()
        check(!closed) { "Calculation session is closed" }
        context.checkpoint()
        val snapshot = case.snapshot { context.charge(RunCounter.HOST_SCANS) }
        LiteralInputRows.account(schema, snapshot, context)
        val sets = parameters.snapshotSets()
        val sink = DiagnosticSink()
        val rebound = if (!broken && formulaProfile == context.options.formulaLimits &&
            PlanRebinding.compatible(plan, snapshot)
        ) {
            PlanRebinding.bind(plan, snapshot, sets, sink)
        } else {
            null
        }
        if (rebound != null) {
            broken = true
            val next = evaluator.recalculate(rebound, sink.all, context)
            plan = rebound
            result = next
            broken = false
        } else {
            // Build first: a rejected edit leaves the last successful result readable.
            val buildSink = DiagnosticSink()
            val replacement = build(snapshot, sets, buildSink, context)
            val runtime = Evaluator(replacement, buildSink, calculationOptions = context.options)
            val next = try {
                runtime.run(context)
            } catch (failure: RuntimeException) {
                runtime.close()
                throw failure
            }
            evaluator.close()
            evaluator = runtime
            plan = replacement
            formulaProfile = context.options.formulaLimits
            result = next
            broken = false
        }
        return result
    }

    internal fun attachUsage(usage: RunUsage): CalculationSession = apply {
        checkOwnerThread()
        result = result.withGraphMetadata(emptyList(), usage)
    }

    private fun build(
        case: CaseData,
        sets: List<ParameterSet>,
        sink: DiagnosticSink,
        context: RunContext,
    ): CalculationPlan {
        context.checkpoint()
        val result = Planner(sink, context).plan(schema, case, sets)
        sink.throwIfErrors()
        context.checkpoint()
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
