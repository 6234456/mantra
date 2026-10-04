package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.view.frozenList
import java.time.DateTimeException
import java.time.Instant

/** A single owner thread, a single input iterator, and one callback at a time. */
internal object BatchRunner {
    fun run(
        compiled: CompiledCalculation,
        cases: Iterable<CaseData>,
        parameters: List<ParameterSet>,
        options: BatchOptions,
        consume: (BatchItem) -> Unit,
    ): BatchSummary {
        val durationDeadline = try {
            Instant.now().plus(options.limits.maxDuration)
        } catch (_: DateTimeException) {
            Instant.MAX
        } catch (_: ArithmeticException) {
            Instant.MAX
        }
        val deadline = earlier(options.control.deadline, durationDeadline)!!
        val cancellation = RunCancellation {
            options.control.cancellation.isCancelled() || options.calculation.control.cancellation.isCancelled()
        }
        val perCase = CalculationOptions(
            options.calculation.limits,
            RunControl(cancellation, earlier(deadline, options.calculation.control.deadline)),
            options.calculation.formulaLimits,
        )
        var completed = 0L
        var succeeded = 0L
        var validationFailed = 0L
        var technicalFailed = 0L
        fun stopped(): BatchFailure? = when {
            cancellation.isCancelled() -> BatchFailure(BatchFailureKind.CANCELLED, completed)
            !Instant.now().isBefore(deadline) -> BatchFailure(BatchFailureKind.DEADLINE, completed)
            else -> null
        }
        val worker = compiled.openSession(perCase)
        var failure: BatchFailure? = null
        var thrown: Throwable? = null
        try {
            // The iterator and callback are caller code. Exceptions escape after finally closes
            // the owned sessions; no failed consumer action is reported as a completed case.
            failure = stopped()
            if (failure == null) {
                val iterator = cases.iterator()
                while (true) {
                    failure = stopped()
                    if (failure != null) break
                    val hasNext = iterator.hasNext()
                    failure = stopped()
                    if (failure != null || !hasNext) break
                    if (completed >= options.limits.maxCases) {
                        failure = BatchFailure(BatchFailureKind.LIMIT, completed)
                        break
                    }
                    val case = iterator.next()
                    failure = stopped()
                    if (failure != null) break
                    val item = try {
                        val result = worker.calculate(case, parameters, perCase)
                        BatchItem(completed, case.id, result, result.diagnostics, result.usage, result.runFailure)
                    } catch (rejected: MantraException) {
                        failure = stopped()
                        if (failure != null) break
                        // A case-local budget/structural rejection is a completed rejection and
                        // later cases may proceed. Global cancel/deadline never deliver this item.
                        BatchItem(
                            completed,
                            case.id,
                            null,
                            frozenList(
                                rejected.diagnostics.map {
                                    it.copy(coord = frozenList(it.coord))
                                },
                            ),
                            rejected.usage,
                            rejected.runFailure,
                        )
                    }
                    failure = stopped()
                    if (failure != null) break
                    consume(item)
                    completed = Math.addExact(completed, 1)
                    if (item.succeeded) {
                        succeeded = Math.addExact(succeeded, 1)
                    } else {
                        technicalFailed = Math.addExact(technicalFailed, 1)
                    }
                    if (item.result != null &&
                        !item.validationPassed
                    ) {
                        validationFailed = Math.addExact(validationFailed, 1)
                    }
                }
            }
        } catch (unexpected: Throwable) {
            thrown = unexpected
            throw unexpected
        } finally {
            try {
                worker.close()
            } catch (closing: RuntimeException) {
                if (thrown == null) throw closing else thrown!!.addSuppressed(closing)
            }
        }
        return BatchSummary(completed, succeeded, validationFailed, technicalFailed, worker.statistics, failure)
    }

    private fun earlier(first: Instant?, second: Instant?): Instant? = when {
        first == null -> second
        second == null -> first
        first.isBefore(second) -> first
        else -> second
    }
}
