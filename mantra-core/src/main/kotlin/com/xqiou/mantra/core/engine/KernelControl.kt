package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.normein.dsl.runtime.DslBudgetLimits
import com.xqiou.normein.dsl.runtime.DslCancellation
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslExecutionSessionOptions
import com.xqiou.normein.dsl.runtime.DslValueOnlyInput
import java.time.Instant

/** Holds only an immutable per-formula profile; never retains a request context or signal. */
internal class KernelControl(options: CalculationOptions) {
    val profile = options.formulaLimits
    private val limits = DslBudgetLimits(profile)
    val sessionOptions: DslExecutionSessionOptions get() = DslExecutionSessionOptions(budgetLimits = limits)

    fun requiresRebuild(next: CalculationOptions): Boolean = profile != next.formulaLimits

    fun fullRequest(request: DslEvaluationRequest, context: RunContext): DslEvaluationRequest {
        checkProfile(context)
        return request.copy(budgetLimits = limits, input = fullInput(request.input, context))
    }

    fun valueOnlyInput(input: DslValueOnlyInput, context: RunContext): DslValueOnlyInput {
        checkProfile(context)
        return DslValueOnlyInput(
            roots = input.roots,
            bindings = input.bindings,
            cancellation = cancellation(input.cancellation, context),
            deadline = earlier(input.deadline, context.deadline),
        )
    }

    private fun fullInput(input: DslEvaluationInput, context: RunContext): DslEvaluationInput = input.copy(
        cancellation = cancellation(input.cancellation, context),
        deadline = earlier(input.deadline, context.deadline),
    )

    private fun cancellation(previous: DslCancellation, context: RunContext): DslCancellation {
        // The lambda captures the signal only, rather than this mutable request context.
        val signal = context.options.control.cancellation
        return DslCancellation { previous.isCancelled() || signal.isCancelled() }
    }

    private fun checkProfile(context: RunContext) {
        context.checkpoint()
        check(profile == context.options.formulaLimits) { "Formula budget profile changed; rebuild the runtime" }
    }

    private fun earlier(first: Instant?, second: Instant): Instant = if (first == null) second else minOf(first, second)
}
