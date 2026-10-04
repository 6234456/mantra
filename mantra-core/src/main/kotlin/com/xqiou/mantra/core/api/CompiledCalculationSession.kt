package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.engine.CompiledTemplate
import com.xqiou.mantra.core.engine.Evaluator
import com.xqiou.mantra.core.engine.KernelExecution
import com.xqiou.mantra.core.engine.RunBoundary
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.read.ParameterSet

/**
 * Thread-owned VALUE_ONLY worker for a compiled template. Each [calculate] starts a fresh epoch and
 * evaluator; only immutable checked expressions and this worker's kernel sessions are reused.
 * [result] is the last completed detached snapshot, never a replacement for a rejected current case.
 * The worker retains neither a prior RunContext nor a cancellation/deadline signal.
 */
class CompiledCalculationSession internal constructor(
    private val template: CompiledTemplate,
    options: CalculationOptions,
) : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    private var profile = options.formulaLimits
    private var kernel = KernelExecution(options, template.kernels, collectingMetrics = true)
    private var retired = CompiledExecutionStatistics()
    private var closed = false
    private var broken = false

    @Volatile
    var result: CalculationResult? = null
        private set

    /** Real worker counters remain readable by its owner after close. */
    val statistics: CompiledExecutionStatistics get() {
        checkOwnerThread()
        return retired + kernel.statistics()
    }

    fun calculate(
        case: CaseData,
        parameters: List<ParameterSet> = template.parameters,
        options: CalculationOptions = CalculationOptions(formulaLimits = profile),
    ): CalculationResult = calculateEvidence(case, parameters, options, null, null)

    internal fun calculateEvidence(
        case: CaseData,
        parameters: List<ParameterSet>,
        options: CalculationOptions,
        audit: AuditOptions?,
        explain: InputAddress?,
    ): CalculationResult {
        checkOwnerThread()
        check(!closed) { "Compiled calculation session is closed" }
        return RunBoundary.independent(
            options,
            { next, usage ->
                next.withGraphMetadata(emptyList(), usage).also {
                    result = it
                    broken = false
                }
            },
            { broken = true },
        ) { context ->
            val sink = DiagnosticSink()
            // Validate shape before changing the owned sessions. Incompatibility never triggers a
            // hidden Planner fallback and cannot poison an otherwise reusable worker.
            val bound = template.bind(case, parameters, sink, context)
            if (broken || profile != options.formulaLimits) rebuild(options)
            broken = true
            Evaluator(
                bound,
                sink,
                explain?.let { it.nodeId to it.coord },
                audit,
                options,
                execution = kernel,
            ).use { it.run(context) }
        }
    }

    private fun rebuild(options: CalculationOptions) {
        kernel.close()
        retired += kernel.statistics()
        kernel = KernelExecution(options, template.kernels, collectingMetrics = true)
        profile = options.formulaLimits
        broken = false
    }

    override fun close() {
        checkOwnerThread()
        if (!closed) {
            closed = true
            kernel.close()
        }
    }

    private fun checkOwnerThread() {
        check(Thread.currentThread() === ownerThread) {
            "Compiled calculation sessions must be used and closed on their opening thread"
        }
    }
}
