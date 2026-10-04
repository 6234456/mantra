package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellationSource
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.model.Value
import com.xqiou.normein.dsl.compiler.DslCompilationUnitCompiler
import com.xqiou.normein.dsl.compiler.DslCompilationUnitEntry
import com.xqiou.normein.dsl.compiler.DslCompilationUnitEntryResult
import com.xqiou.normein.dsl.compiler.DslCompilationUnitRequest
import com.xqiou.normein.dsl.compiler.DslCompiledExpression
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuildResult
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuilder
import com.xqiou.normein.dsl.environment.DslRootDeclaration
import com.xqiou.normein.dsl.runtime.DslBorrowedValueRowSink
import com.xqiou.normein.dsl.runtime.DslBudgetCounter
import com.xqiou.normein.dsl.runtime.DslBudgetLimits
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslExecutionPlan
import com.xqiou.normein.dsl.runtime.DslExecutionPlanCompiler
import com.xqiou.normein.dsl.runtime.DslExecutionPlanRequest
import com.xqiou.normein.dsl.runtime.DslExecutionPlanResult
import com.xqiou.normein.dsl.runtime.DslExecutionSession
import com.xqiou.normein.dsl.runtime.DslExecutionSessionResult
import com.xqiou.normein.dsl.runtime.DslInputCandidate
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.runtime.DslValueOnlyInput
import com.xqiou.normein.dsl.trace.DslTraceLimits
import com.xqiou.normein.dsl.trace.DslTracePolicy
import com.xqiou.normein.dsl.type.DslFieldPresence
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KernelControlTest {
    private data class Program(val expression: DslCompiledExpression, val plan: DslExecutionPlan)

    private fun program(): Program {
        val scope = assertIs<DslAnalysisScopeBuildResult.Success>(
            DslAnalysisScopeBuilder.create("mantra.control.test", "1")
                .root(DslRootDeclaration("seed", DslType.Decimal, DslFieldPresence.REQUIRED))
                .build(),
        ).scope
        val unit = DslCompilationUnitCompiler().compile(
            DslCompilationUnitRequest(
                listOf(
                    DslCompilationUnitEntry(
                        "value",
                        "controlled",
                        "controlled",
                        "(+ seed 1.0)",
                        DslType.Decimal,
                        scope.contractId,
                        scope.descriptorVersion,
                    ),
                ),
            ),
            MantraKernel.environment,
            listOf(scope),
        )
        assertTrue(unit.diagnostics.isEmpty(), unit.diagnostics.joinToString("\n"))
        val expression = assertIs<DslCompilationUnitEntryResult.Success>(unit.entries.single()).expression
        val plan = assertIs<DslExecutionPlanResult.Success>(
            DslExecutionPlanCompiler().compile(
                DslExecutionPlanRequest(unit, MantraKernel.environment, MantraKernel.kernelArtifact),
            ),
        ).plan
        return Program(expression, plan)
    }

    private fun roots() = listOf(
        DslInputRootCandidate("seed", DslInputCandidate.ControlledValue(DslValues.decimal(BigDecimal("5")))),
    )

    private fun request(program: Program) = DslEvaluationRequest(
        program.expression,
        MantraKernel.environment,
        DslEvaluationInput(
            roots(),
            emptyList(),
            MantraKernel.inputIdentity("control-test", "seed=5"),
            DslTracePolicy.FULL,
        ),
        MantraKernel.kernelArtifact,
        traceLimits = DslTraceLimits(),
    )

    /** Copy host values/diagnostic codes in the sink; never retain the borrowed row. */
    private fun row(session: DslExecutionSession, input: DslValueOnlyInput): Pair<Value?, List<String>> {
        var value: Value? = null
        val diagnostics = mutableListOf<String>()
        session.evaluateValueOnly(
            input,
            DslBorrowedValueRowSink { result ->
                diagnostics += result.diagnostics.map { it.code }
                if (result.outputCount == 1) {
                    if (result.isSuccess(0)) {
                        value = Values.fromDsl(result.value(0))
                    } else {
                        diagnostics += result.outputDiagnostics(0).map { it.code }
                    }
                }
            },
        )
        return value to diagnostics.distinct()
    }

    @Test
    fun `partial formula overrides remain complete detached and bounded by every default ceiling`() {
        val overrides = mutableMapOf(DslBudgetCounter.FUNCTION_CALLS to 1L)
        val options = CalculationOptions(formulaLimits = overrides)
        overrides[DslBudgetCounter.FUNCTION_CALLS] = 100
        assertEquals(DslBudgetCounter.entries.toSet(), options.formulaLimits.keys)
        assertEquals(1L, options.formulaLimits.getValue(DslBudgetCounter.FUNCTION_CALLS))
        assertEquals(
            DslBudgetLimits.defaults().getValue(DslBudgetCounter.MATERIALIZED_BYTES),
            options.formulaLimits[DslBudgetCounter.MATERIALIZED_BYTES],
        )
        assertFailsWith<UnsupportedOperationException> {
            (options.formulaLimits as MutableMap<DslBudgetCounter, Long>)[DslBudgetCounter.FUNCTION_CALLS] =
                Long.MAX_VALUE
        }
        assertFailsWith<IllegalArgumentException> {
            CalculationOptions(formulaLimits = mapOf(DslBudgetCounter.FUNCTION_CALLS to Long.MAX_VALUE))
        }
    }

    @Test
    fun `function call ceiling fails the real FULL and VALUE_ONLY routes without an approximation`() {
        val program = program()
        val options = CalculationOptions(formulaLimits = mapOf(DslBudgetCounter.FUNCTION_CALLS to 0L))
        val context = RunContext.begin(options)
        val control = KernelControl(options)
        val full = assertIs<DslEvaluationOutcome.Failure>(
            DslEvaluationEngine().evaluate(control.fullRequest(request(program), context)),
        )
        assertTrue(full.diagnostics.any { it.code == "DSL-LIMIT-FUNCTION-CALLS" }, full.diagnostics.toString())
        val session = assertIs<DslExecutionSessionResult.Success>(
            program.plan.openValueOnlySession(options = control.sessionOptions),
        ).session
        session.use {
            val (value, diagnostics) = row(it, control.valueOnlyInput(DslValueOnlyInput(roots = roots()), context))
            assertNull(value)
            assertTrue("DSL-LIMIT-FUNCTION-CALLS" in diagnostics, diagnostics.toString())
        }
        context.finish()
    }

    @Test
    fun `cancellation after input preparation reaches both real kernel routes`() {
        val program = program()
        val signal = RunCancellationSource()
        val options = CalculationOptions(control = RunControl(signal))
        val context = RunContext.begin(options)
        val control = KernelControl(options)
        val fullRequest = control.fullRequest(request(program), context)
        val valueOnly = control.valueOnlyInput(DslValueOnlyInput(roots = roots()), context)
        val session = assertIs<DslExecutionSessionResult.Success>(
            program.plan.openValueOnlySession(options = control.sessionOptions),
        ).session
        session.use {
            signal.cancel()
            val full = assertIs<DslEvaluationOutcome.Failure>(DslEvaluationEngine().evaluate(fullRequest))
            assertTrue(full.diagnostics.any { diagnostic -> diagnostic.code == "DSL-RUNTIME-CANCELLED" })
            val (value, diagnostics) = row(it, valueOnly)
            assertNull(value)
            assertTrue("DSL-RUNTIME-CANCELLED" in diagnostics, diagnostics.toString())
        }
        context.finish()
    }

    @Test
    fun `an absolute earlier deadline reaches both kernels without sleeps or timing assertions`() {
        val program = program()
        val options = CalculationOptions(control = RunControl(deadline = Instant.EPOCH))
        // Host time is deliberately before deadline; the real public kernels observe their system clock.
        val context = RunContext.begin(options, Clock.fixed(Instant.EPOCH.minusSeconds(1), ZoneOffset.UTC))
        val control = KernelControl(options)
        val fullRequest = control.fullRequest(request(program), context)
        val valueOnly = control.valueOnlyInput(DslValueOnlyInput(roots = roots()), context)
        assertEquals(Instant.EPOCH, fullRequest.input.deadline)
        assertEquals(Instant.EPOCH, valueOnly.deadline)
        val full = assertIs<DslEvaluationOutcome.Failure>(DslEvaluationEngine().evaluate(fullRequest))
        assertTrue(full.diagnostics.any { it.code == "DSL-RUNTIME-DEADLINE-EXCEEDED" })
        val session = assertIs<DslExecutionSessionResult.Success>(
            program.plan.openValueOnlySession(options = control.sessionOptions),
        ).session
        session.use {
            val (value, diagnostics) = row(it, valueOnly)
            assertNull(value)
            assertTrue("DSL-RUNTIME-DEADLINE-EXCEEDED" in diagnostics, diagnostics.toString())
        }
        context.finish()
    }

    @Test
    fun `budget changes require rebuild while request controls do not become profile identity`() {
        val original = KernelControl(CalculationOptions())
        assertFalse(
            original.requiresRebuild(CalculationOptions(control = RunControl(RunCancellationSource(), Instant.MAX))),
        )
        val lowered = CalculationOptions(formulaLimits = mapOf(DslBudgetCounter.FUNCTION_CALLS to 1))
        assertTrue(original.requiresRebuild(lowered))
        val context = RunContext.begin(lowered)
        assertFailsWith<IllegalStateException> { original.valueOnlyInput(DslValueOnlyInput(), context) }
        context.finish()
        // CalculationSession integration additionally tests that graph/task caches are rebuilt.
    }

    @Test
    fun `adaptor preserves authored trace limits and root identity and does not double charge host work`() {
        val program = program()
        val options = CalculationOptions()
        val context = RunContext.begin(options)
        val base = request(program)
        val adapted = KernelControl(options).fullRequest(base, context)
        assertSame(base.traceLimits, adapted.traceLimits)
        assertSame(base.input.inputIdentity, adapted.input.inputIdentity)
        assertEquals(base.input.roots, adapted.input.roots)
        assertEquals(base.input.tracePolicy, adapted.input.tracePolicy)
        val success =
            assertIs<DslEvaluationOutcome.Success<com.xqiou.normein.dsl.value.DslValue>>(
                DslEvaluationEngine().evaluate(adapted),
            )
        assertEquals(0, BigDecimal("6").compareTo((Values.fromDsl(success.value) as Value.Num).value))
        assertTrue(context.finish().counters.values.all { it == 0L })
    }
}
