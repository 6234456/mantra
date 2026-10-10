package com.xqiou.mantra.core

import com.xqiou.mantra.core.engine.MantraKernel
import com.xqiou.normein.dsl.compiler.DslCompilationUnitEntryResult
import com.xqiou.normein.dsl.compiler.DslCompilationUnitResult
import com.xqiou.normein.dsl.compiler.DslCompileRequest
import com.xqiou.normein.dsl.compiler.DslCompileResult
import com.xqiou.normein.dsl.compiler.DslCompiledExpression
import com.xqiou.normein.dsl.compiler.DslSemanticCompiler
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuildResult
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuilder
import com.xqiou.normein.dsl.identity.DslCompilationUnitEntryIdentity
import com.xqiou.normein.dsl.identity.DslDurableExecutionIdentities
import com.xqiou.normein.dsl.runtime.DslBorrowedValueRowSink
import com.xqiou.normein.dsl.runtime.DslBudgetCounter
import com.xqiou.normein.dsl.runtime.DslBudgetLimits
import com.xqiou.normein.dsl.runtime.DslCancellation
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslExecutionPlanCompiler
import com.xqiou.normein.dsl.runtime.DslExecutionPlanRequest
import com.xqiou.normein.dsl.runtime.DslExecutionPlanResult
import com.xqiou.normein.dsl.runtime.DslExecutionSessionOptions
import com.xqiou.normein.dsl.runtime.DslExecutionSessionResult
import com.xqiou.normein.dsl.runtime.DslValueOnlyInput
import com.xqiou.normein.dsl.trace.DslTracePolicy
import com.xqiou.normein.dsl.value.DslValue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Observes real public FULL receipts; never invents VALUE_ONLY usage. */
class ConvergeKernelBudgetTest {
    private val environment = MantraKernel.environment
    private val scope = assertIs<DslAnalysisScopeBuildResult.Success>(
        DslAnalysisScopeBuilder.create("mantra.converge-contract", "1").build(),
    ).scope

    private fun compiled(source: String): DslCompiledExpression = assertIs<DslCompileResult.Success>(
        DslSemanticCompiler().compile(DslCompileRequest(source), environment, scope),
    ).expression

    private fun limits(counter: DslBudgetCounter, maximum: Long) =
        DslBudgetLimits(DslBudgetLimits.defaults() + (counter to maximum))

    private fun full(
        source: String,
        budget: DslBudgetLimits = DslBudgetLimits(),
        cancellation: DslCancellation = DslCancellation.NONE,
        deadline: Instant? = null,
    ) = DslEvaluationEngine().evaluate(
        DslEvaluationRequest(
            expression = compiled(source),
            environment = environment,
            input = DslEvaluationInput(
                roots = emptyList(),
                bindings = emptyList(),
                inputIdentity = MantraKernel.inputIdentity("converge-contract", "converge-contract"),
                tracePolicy = DslTracePolicy.FULL,
                cancellation = cancellation,
                deadline = deadline,
            ),
            kernelArtifact = MantraKernel.kernelArtifact,
            budgetLimits = budget,
        ),
    )

    private fun valueOnly(source: String, budget: DslBudgetLimits): List<String> {
        val expression = compiled(source)
        val entry = DslCompilationUnitEntryResult.Success("value", "converge-contract", expression)
        val identity = DslCompilationUnitEntryIdentity(
            entry.entryId,
            entry.logicalLocation,
            expression.expressionSlotId,
            expression.expectedType,
            checkNotNull(expression.analysisScopeContractId),
            checkNotNull(expression.analysisScopeDescriptorVersion),
            expression.analysisScopeFingerprint,
            expression.executionFingerprint,
        )
        val fingerprint = DslDurableExecutionIdentities.compilationUnitFingerprint(listOf(identity)).value
        val unit = DslCompilationUnitResult(
            environment.contractId,
            environment.descriptorVersion,
            environment.fingerprint,
            listOf(entry),
            emptyList(),
            expression.references,
            fingerprint,
        )
        val plan = assertIs<DslExecutionPlanResult.Success>(
            DslExecutionPlanCompiler().compile(DslExecutionPlanRequest(unit, environment, MantraKernel.kernelArtifact)),
        ).plan
        val session = assertIs<DslExecutionSessionResult.Success>(
            plan.openValueOnlySession(options = DslExecutionSessionOptions(budgetLimits = budget)),
        ).session
        return session.use {
            val codes = mutableListOf<String>()
            it.evaluateValueOnly(
                DslValueOnlyInput(),
                DslBorrowedValueRowSink { row ->
                    codes += row.diagnostics.map { diagnostic -> diagnostic.code }
                    for (index in 0 until row.outputCount) {
                        codes += row.outputDiagnostics(index).map { diagnostic -> diagnostic.code }
                        // Copy scalar facts inside the borrowed row lifetime; no row or callable escapes.
                        if (row.isSuccess(index)) assertTrue(row.value(index) != DslValue.Nil)
                    }
                },
            )
            codes
        }
    }

    @Test
    fun `one constant decimal step charges exact iteration and adjacent comparison operations`() {
        val source = "(calc/converge (fn [^Decimal x] 7) 7 1 0)"
        val success = assertIs<DslEvaluationOutcome.Success<DslValue>>(full(source))
        assertEquals(1L, success.receipt.budgetUsage[DslBudgetCounter.ITERATIONS])
        assertEquals(3L, success.receipt.budgetUsage[DslBudgetCounter.NUMERIC_OPERATIONS])
        val exhausted = assertIs<DslEvaluationOutcome.Failure>(full(source, limits(DslBudgetCounter.ITERATIONS, 0)))
        assertTrue(exhausted.diagnostics.any { it.code == "DSL-LIMIT-ITERATIONS" })
        val numeric =
            assertIs<DslEvaluationOutcome.Failure>(full(source, limits(DslBudgetCounter.NUMERIC_OPERATIONS, 2)))
        assertTrue(numeric.diagnostics.any { it.code == "DSL-LIMIT-NUMERIC-OPERATIONS" })
        assertTrue(valueOnly(source, limits(DslBudgetCounter.ITERATIONS, 1)).isEmpty())
        assertTrue("DSL-LIMIT-ITERATIONS" in valueOnly(source, limits(DslBudgetCounter.ITERATIONS, 0)))
        assertTrue("DSL-LIMIT-NUMERIC-OPERATIONS" in valueOnly(source, limits(DslBudgetCounter.NUMERIC_OPERATIONS, 2)))
    }

    @Test
    fun `band early exit charges the actual visited prefix rather than argument count`() {
        val rows = "[[0 0.10] [10 0.20] [20 0.30]]"
        val prefix = assertIs<DslEvaluationOutcome.Success<DslValue>>(full("(table/band 5 $rows)"))
        val complete = assertIs<DslEvaluationOutcome.Success<DslValue>>(full("(table/band 25 $rows)"))
        val prefixScans = prefix.receipt.budgetUsage[DslBudgetCounter.ITEMS_SCANNED]
        val completeScans = complete.receipt.budgetUsage[DslBudgetCounter.ITEMS_SCANNED]
        assertTrue(prefixScans >= 2L)
        assertTrue(completeScans > prefixScans)
        val failure = assertIs<DslEvaluationOutcome.Failure>(
            full("(table/band 25 $rows)", limits(DslBudgetCounter.ITEMS_SCANNED, completeScans - 1)),
        )
        assertTrue(failure.diagnostics.any { it.code == "DSL-LIMIT-ITEMS-SCANNED" })
        assertTrue(
            "DSL-LIMIT-ITEMS-SCANNED" in valueOnly(
                "(table/band 25 $rows)",
                limits(DslBudgetCounter.ITEMS_SCANNED, 0),
            ),
        )
    }

    @Test
    fun `full collection algorithms consume additional scans for additional real entries`() {
        val small = "(alloc/waterfall 8 {:A 3 :B nil})"
        val large = "(alloc/waterfall 8 {:A 3 :B 2 :C nil})"
        val first = assertIs<DslEvaluationOutcome.Success<DslValue>>(full(small))
        val second = assertIs<DslEvaluationOutcome.Success<DslValue>>(full(large))
        assertTrue(
            second.receipt.budgetUsage[DslBudgetCounter.ITEMS_SCANNED] >
                first.receipt.budgetUsage[DslBudgetCounter.ITEMS_SCANNED],
        )
        assertTrue("DSL-LIMIT-ITEMS-SCANNED" in valueOnly(large, limits(DslBudgetCounter.ITEMS_SCANNED, 0)))
    }

    @Test
    fun `table selection charges scanning numeric work and preserves runtime failures`() {
        val small = "(table/sum-where [{:group :A :amount 2}] {:group :A} :amount)"
        val large = "(table/sum-where [{:group :A :amount 2} {:group :A :amount 3}] {:group :A} :amount)"
        val first = assertIs<DslEvaluationOutcome.Success<DslValue>>(full(small))
        val second = assertIs<DslEvaluationOutcome.Success<DslValue>>(full(large))
        val scans = second.receipt.budgetUsage[DslBudgetCounter.ITEMS_SCANNED]
        assertTrue(scans > first.receipt.budgetUsage[DslBudgetCounter.ITEMS_SCANNED])
        assertTrue(
            second.receipt.budgetUsage[DslBudgetCounter.NUMERIC_OPERATIONS] >
                first.receipt.budgetUsage[DslBudgetCounter.NUMERIC_OPERATIONS],
        )
        val exhausted = assertIs<DslEvaluationOutcome.Failure>(
            full(
                large,
                limits(
                    DslBudgetCounter.ITEMS_SCANNED,
                    scans - 1,
                ),
            ),
        )
        assertTrue(exhausted.diagnostics.any { it.code == "DSL-LIMIT-ITEMS-SCANNED" })
        assertTrue("DSL-LIMIT-ITEMS-SCANNED" in valueOnly(large, limits(DslBudgetCounter.ITEMS_SCANNED, 0)))
        assertTrue("DSL-LIMIT-NUMERIC-OPERATIONS" in valueOnly(large, limits(DslBudgetCounter.NUMERIC_OPERATIONS, 0)))
        val count = "(table/count-where [{:group :A}] {})"
        assertTrue("DSL-LIMIT-NUMERIC-OPERATIONS" in valueOnly(count, limits(DslBudgetCounter.NUMERIC_OPERATIONS, 0)))
        val cancelled = assertIs<DslEvaluationOutcome.Failure>(full(large, cancellation = DslCancellation { true }))
        assertTrue(cancelled.diagnostics.any { it.code == "DSL-RUNTIME-CANCELLED" })
        val deadline = assertIs<DslEvaluationOutcome.Failure>(full(count, deadline = Instant.EPOCH))
        assertTrue(deadline.diagnostics.any { it.code == "DSL-RUNTIME-DEADLINE-EXCEEDED" })
    }

    @Test
    fun `original cancellation and deadline failures survive without a convergence error alias`() {
        val source = "(calc/converge (fn [x] x) 7 1 0)"
        val cancelled = assertIs<DslEvaluationOutcome.Failure>(full(source, cancellation = DslCancellation { true }))
        assertTrue(cancelled.diagnostics.any { it.code == "DSL-RUNTIME-CANCELLED" })
        val deadline = assertIs<DslEvaluationOutcome.Failure>(full(source, deadline = Instant.EPOCH))
        assertTrue(deadline.diagnostics.any { it.code == "DSL-RUNTIME-DEADLINE-EXCEEDED" })
        assertTrue((cancelled.diagnostics + deadline.diagnostics).none { it.code.contains("NOT-CONVERGED") })
    }
}
