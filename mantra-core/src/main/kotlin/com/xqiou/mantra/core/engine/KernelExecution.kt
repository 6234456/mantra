package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.normein.dsl.diagnostic.DslDiagnostic
import com.xqiou.normein.dsl.identity.DslInputIdentity
import com.xqiou.normein.dsl.runtime.DslBorrowedValueRowSink
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslExecutionSession
import com.xqiou.normein.dsl.runtime.DslExecutionSessionResult
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.runtime.DslValueOnlyInput
import com.xqiou.normein.dsl.runtime.executionCostSnapshotOrNull
import com.xqiou.normein.dsl.trace.DslTraceNode
import com.xqiou.normein.dsl.trace.DslTracePolicy
import com.xqiou.normein.dsl.trace.DslTraceStatus
import com.xqiou.normein.dsl.value.DslValue
import java.util.IdentityHashMap

internal data class KernelOutcome(
    val value: DslValue?,
    val diagnostics: List<DslDiagnostic> = emptyList(),
    val trace: DslTraceNode? = null,
    val truncated: Boolean = false,
)

/** Owns reusable VALUE_ONLY sessions; evidence requests retain the audited reference route. */
internal class KernelExecution(
    options: CalculationOptions,
    private val plans: KernelPlans? = null,
    private val collectingMetrics: Boolean = false,
) : AutoCloseable {
    private val control = KernelControl(options)
    private val environment = MantraKernel.environment
    private val engine = DslEvaluationEngine()
    private val sessions = IdentityHashMap<CompiledFormula, DslExecutionSession>()
    private var sessionOpens = 0L
    private var sessionCloseAttempts = 0L
    private var successfulSessionCloses = 0L
    private var executionPlanCompilations = 0L
    private var retired = com.xqiou.mantra.core.api.CompiledExecutionStatistics()
    var valueOnlyEvaluations: Int = 0
        private set
    var auditedEvaluations: Int = 0
        private set
    val sessionCount: Int get() = sessions.size

    fun evaluate(
        formula: CompiledFormula,
        roots: List<DslInputRootCandidate>,
        identity: DslInputIdentity,
        capture: AuditCapture.Request?,
        context: RunContext,
    ): KernelOutcome {
        if (capture?.enabled == true) {
            context.charge(RunCounter.FORMULA_EXECUTIONS)
            auditedEvaluations++
            val outcome = when (
                val result = engine.evaluate(
                    control.fullRequest(
                        DslEvaluationRequest(
                            expression = formula.expression,
                            environment = environment,
                            input = DslEvaluationInput(
                                roots = roots,
                                bindings = emptyList(),
                                inputIdentity = identity,
                                tracePolicy = DslTracePolicy.FULL,
                            ),
                            kernelArtifact = MantraKernel.kernelArtifact,
                            traceLimits = capture.kernelLimits,
                        ),
                        context,
                    ),
                )
            ) {
                is DslEvaluationOutcome.Success -> KernelOutcome(
                    result.value,
                    trace = result.trace,
                    truncated =
                    result.receipt.traceStatus == DslTraceStatus.TRUNCATED,
                )
                is DslEvaluationOutcome.Failure -> KernelOutcome(
                    null,
                    result.diagnostics,
                    result.partialTrace,
                    result.receipt.traceStatus == DslTraceStatus.TRUNCATED,
                )
            }
            context.checkpoint()
            return outcome
        }
        val session = sessions.getOrPut(formula) { open(formula) }
        context.charge(RunCounter.FORMULA_EXECUTIONS)
        valueOnlyEvaluations++
        var value: DslValue? = null
        val diagnostics = mutableListOf<DslDiagnostic>()
        session.evaluateValueOnly(
            control.valueOnlyInput(DslValueOnlyInput(roots = roots), context),
            DslBorrowedValueRowSink { row ->
                diagnostics += row.diagnostics
                if (row.outputCount == 1) {
                    if (row.isSuccess(0)) {
                        value = row.value(0)
                    } else {
                        diagnostics += row.outputDiagnostics(0)
                    }
                }
            },
        )
        context.checkpoint()
        return KernelOutcome(value, diagnostics.distinct())
    }

    private fun open(formula: CompiledFormula): DslExecutionSession {
        val plan = if (plans != null) {
            plans[formula]
        } else {
            executionPlanCompilations = Math.addExact(executionPlanCompilations, 1)
            KernelPlans.compile(formula)
        }
        return when (
            val result = plan.openValueOnlySession(
                options = control.sessionOptions,
                costMetricsMode = if (collectingMetrics) {
                    com.xqiou.normein.dsl.runtime.DslExecutionCostMetricsMode.ENABLED
                } else {
                    com.xqiou.normein.dsl.runtime.DslExecutionCostMetricsMode.DISABLED
                },
            )
        ) {
            is DslExecutionSessionResult.Success -> result.session.also {
                sessionOpens = Math.addExact(sessionOpens, 1)
            }
            is DslExecutionSessionResult.Failure -> error("VALUE_ONLY session rejected: ${result.diagnostics}")
        }
    }

    fun statistics(): com.xqiou.mantra.core.api.CompiledExecutionStatistics =
        sessions.values.fold(retired) { sum, session -> sum + activity(session) } +
            com.xqiou.mantra.core.api.CompiledExecutionStatistics(
                sessionOpens = sessionOpens,
                sessionCloseAttempts = sessionCloseAttempts,
                successfulSessionCloses = successfulSessionCloses,
                executionPlanCompilations = executionPlanCompilations,
            )

    private fun activity(session: DslExecutionSession): com.xqiou.mantra.core.api.CompiledExecutionStatistics {
        val metrics = session.metrics()
        return com.xqiou.mantra.core.api.CompiledExecutionStatistics(
            rowCycles = metrics.rowCycles,
            physicalRowPreparations = metrics.physicalRowPreparations,
            logicalExpressionEvaluations = metrics.logicalExpressionEvaluations,
            frameAllocations = metrics.frameAllocations,
            valueOnlyEvidenceMaterializations = session.executionCostSnapshotOrNull()?.evidence
                ?.valueOnlyEvidenceMaterializations ?: 0,
        )
    }

    override fun close() {
        var failure: RuntimeException? = null
        fun failed(closing: RuntimeException) {
            if (failure == null) failure = closing else failure!!.addSuppressed(closing)
        }
        sessions.values.forEach { session ->
            try {
                if (collectingMetrics) retired += activity(session)
            } catch (reading: RuntimeException) {
                failed(reading)
            }
            sessionCloseAttempts = Math.addExact(sessionCloseAttempts, 1)
            try {
                session.close()
                successfulSessionCloses = Math.addExact(successfulSessionCloses, 1)
            } catch (closing: RuntimeException) {
                failed(closing)
            }
        }
        sessions.clear()
        failure?.let { throw it }
    }
}
