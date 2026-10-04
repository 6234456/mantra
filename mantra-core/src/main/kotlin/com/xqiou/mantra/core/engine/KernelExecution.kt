package com.xqiou.mantra.core.engine

import com.xqiou.normein.dsl.compiler.DslCompilationUnitEntryResult
import com.xqiou.normein.dsl.compiler.DslCompilationUnitResult
import com.xqiou.normein.dsl.diagnostic.DslDiagnostic
import com.xqiou.normein.dsl.identity.DslCompilationUnitEntryIdentity
import com.xqiou.normein.dsl.identity.DslDurableExecutionIdentities
import com.xqiou.normein.dsl.identity.DslInputIdentity
import com.xqiou.normein.dsl.runtime.DslBorrowedValueRowSink
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslExecutionPlanCompiler
import com.xqiou.normein.dsl.runtime.DslExecutionPlanRequest
import com.xqiou.normein.dsl.runtime.DslExecutionPlanResult
import com.xqiou.normein.dsl.runtime.DslExecutionSession
import com.xqiou.normein.dsl.runtime.DslExecutionSessionResult
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.runtime.DslValueOnlyInput
import com.xqiou.normein.dsl.trace.DslTraceLimits
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
internal class KernelExecution : AutoCloseable {
    private val environment = MantraKernel.environment
    private val engine = DslEvaluationEngine()
    private val sessions = IdentityHashMap<CompiledFormula, DslExecutionSession>()
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
    ): KernelOutcome {
        if (capture?.enabled == true) {
            auditedEvaluations++
            return when (
                val result = engine.evaluate(
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
        }
        valueOnlyEvaluations++
        val session = sessions.getOrPut(formula) { open(formula) }
        var value: DslValue? = null
        val diagnostics = mutableListOf<DslDiagnostic>()
        session.evaluateValueOnly(
            DslValueOnlyInput(roots = roots),
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
        return KernelOutcome(value, diagnostics.distinct())
    }

    private fun open(formula: CompiledFormula): DslExecutionSession {
        val expression = formula.expression
        val entry = DslCompilationUnitEntryResult.Success(
            "value",
            expression.logicalLocation ?: expression.expressionSlotId,
            expression,
        )
        val fingerprint = DslDurableExecutionIdentities.compilationUnitFingerprint(
            listOf(
                DslCompilationUnitEntryIdentity(
                    entry.entryId,
                    entry.logicalLocation,
                    expression.expressionSlotId,
                    expression.expectedType,
                    checkNotNull(expression.analysisScopeContractId),
                    checkNotNull(expression.analysisScopeDescriptorVersion),
                    expression.analysisScopeFingerprint,
                    expression.executionFingerprint,
                ),
            ),
        ).value
        val unit = DslCompilationUnitResult(
            environment.contractId,
            environment.descriptorVersion,
            environment.fingerprint,
            listOf(entry),
            emptyList(),
            expression.references,
            fingerprint,
        )
        val plan = when (
            val result = DslExecutionPlanCompiler().compile(
                DslExecutionPlanRequest(unit, environment, MantraKernel.kernelArtifact),
            )
        ) {
            is DslExecutionPlanResult.Success -> result.plan
            is DslExecutionPlanResult.Failure -> error("VALUE_ONLY execution plan rejected: ${result.diagnostics}")
        }
        return when (val result = plan.openValueOnlySession()) {
            is DslExecutionSessionResult.Success -> result.session
            is DslExecutionSessionResult.Failure -> error("VALUE_ONLY session rejected: ${result.diagnostics}")
        }
    }

    override fun close() {
        sessions.values.forEach(DslExecutionSession::close)
        sessions.clear()
    }
}
