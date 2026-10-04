package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunUsage

/** Only independent public requests start/finish an epoch; bound graph calls reuse the caller's. */
internal object RunBoundary {
    fun <T> independent(
        options: CalculationOptions,
        complete: (T, RunUsage) -> T,
        rejected: (T) -> Unit = {},
        action: (RunContext) -> T,
    ): T {
        var context: RunContext? = null
        return try {
            val epoch = RunContext.begin(options).also { context = it }
            epoch.charge(RunCounter.CASES)
            val value = action(epoch)
            try {
                epoch.checkpoint()
                complete(value, epoch.finish())
            } catch (failure: RuntimeException) {
                try {
                    rejected(value)
                } catch (closing: RuntimeException) {
                    failure.addSuppressed(closing)
                }
                throw failure
            }
        } catch (failure: RunAbortedException) {
            throw exception(failure, context?.finish() ?: failure.usage)
        } finally {
            context?.finish()
        }
    }

    fun exception(failure: RunAbortedException, usage: RunUsage = failure.usage): MantraException {
        val problem = failure.failure
        val address = problem.address
        return MantraException(
            listOf(
                Diagnostic(
                    Severity.ERROR,
                    problem.code,
                    "Run ${problem.kind.name.lowercase()} during ${problem.stage.name.lowercase()}",
                    nodeId = address?.nodeId,
                    coord = address?.coord.orEmpty(),
                    category = DiagnosticCategory.EVALUATION,
                    caseKey = address?.caseKey,
                ),
            ),
            usage,
            problem,
        )
    }
}
