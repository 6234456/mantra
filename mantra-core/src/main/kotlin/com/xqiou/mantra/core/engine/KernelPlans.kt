package com.xqiou.mantra.core.engine

import com.xqiou.normein.dsl.compiler.DslCompilationUnitEntryResult
import com.xqiou.normein.dsl.compiler.DslCompilationUnitResult
import com.xqiou.normein.dsl.identity.DslCompilationUnitEntryIdentity
import com.xqiou.normein.dsl.identity.DslDurableExecutionIdentities
import com.xqiou.normein.dsl.runtime.DslExecutionPlan
import com.xqiou.normein.dsl.runtime.DslExecutionPlanCompiler
import com.xqiou.normein.dsl.runtime.DslExecutionPlanRequest
import com.xqiou.normein.dsl.runtime.DslExecutionPlanResult
import java.util.Collections
import java.util.IdentityHashMap

/** Immutable checked-expression plans. It contains no session, input, or request control. */
internal class KernelPlans private constructor(private val plans: Map<CompiledFormula, DslExecutionPlan>) {
    operator fun get(formula: CompiledFormula): DslExecutionPlan =
        plans[formula] ?: error("Formula does not belong to this compiled template")

    companion object {
        fun create(formulas: List<CompiledFormula>, compiling: CompilationMeter): KernelPlans = KernelPlans(
            Collections.unmodifiableMap(
                IdentityHashMap<CompiledFormula, DslExecutionPlan>().apply {
                    formulas.forEach { put(it, compile(it, compiling)) }
                },
            ),
        )

        fun compile(formula: CompiledFormula, compiling: CompilationMeter? = null): DslExecutionPlan {
            val environment = MantraKernel.environment
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
            compiling?.plan()
            return when (
                val result = DslExecutionPlanCompiler().compile(
                    DslExecutionPlanRequest(unit, environment, MantraKernel.kernelArtifact),
                )
            ) {
                is DslExecutionPlanResult.Success -> result.plan
                is DslExecutionPlanResult.Failure -> {
                    if (compiling == null) error("VALUE_ONLY execution plan rejected: ${result.diagnostics}")
                    throw com.xqiou.mantra.core.MantraException(
                        listOf(
                            com.xqiou.mantra.core.Diagnostic(
                                com.xqiou.mantra.core.Severity.ERROR,
                                "MANTRA-COMPILE-PLAN",
                                "Execution plan rejected: ${result.diagnostics}".take(2048),
                            ),
                        ),
                    )
                }
            }
        }
    }
}
