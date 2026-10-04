package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.Diagnostic
import java.time.Duration

/** Engineering defaults, to be measured and frozen by M4 acceptance; not a performance promise. */
data class BatchLimits(val maxCases: Long = 10_000, val maxDuration: Duration = Duration.ofMinutes(5)) {
    init {
        require(maxCases >= 0) { "Batch case limit cannot be negative" }
        require(!maxDuration.isNegative && !maxDuration.isZero) { "Batch duration must be positive" }
    }
}

/** Batch-wide controls and a separate fresh M3 budget for each individual case. */
data class BatchOptions(
    val limits: BatchLimits = BatchLimits(),
    val control: RunControl = RunControl(),
    val calculation: CalculationOptions = CalculationOptions(),
)

enum class BatchFailureKind { LIMIT, CANCELLED, DEADLINE }

data class BatchFailure(val kind: BatchFailureKind, val completedCases: Long) {
    val code: String get() = "MANTRA-BATCH-${kind.name}"
}

/** A current completed case or explicit rejection; never a stale successful result. */
data class BatchItem(
    val index: Long,
    val caseId: String,
    val result: CalculationResult?,
    val diagnostics: List<Diagnostic>,
    val usage: RunUsage?,
    val runFailure: RunFailure? = null,
) {
    val succeeded: Boolean get() = result?.succeeded == true
    val validationPassed: Boolean get() = result?.validationPassed == true
}

/** Counters cover callbacks actually delivered; a cancelled incomplete case is not included. */
data class BatchSummary(
    val completedCases: Long,
    val succeededCases: Long,
    val validationFailedCases: Long,
    val technicalFailedCases: Long,
    val statistics: CompiledExecutionStatistics,
    val failure: BatchFailure? = null,
)
