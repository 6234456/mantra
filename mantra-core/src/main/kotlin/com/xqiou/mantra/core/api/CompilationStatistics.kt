package com.xqiou.mantra.core.api

/**
 * Actual public compiler calls made while constructing one template. Syntax/semantic counts
 * describe invocations, not unavailable private kernel cache-miss counters.
 */
data class CompilationStatistics(
    val syntaxCompilerCalls: Long,
    val semanticCompilerCalls: Long,
    val executionPlanCompilations: Long,
    val formulaCount: Long,
)

/** Actual worker activity; no counter is inferred from the requested batch length. */
data class CompiledExecutionStatistics(
    val sessionOpens: Long = 0,
    val sessionCloseAttempts: Long = 0,
    /** Actual close calls that returned successfully, separate from attempts. */
    val successfulSessionCloses: Long = 0,
    val executionPlanCompilations: Long = 0,
    val rowCycles: Long = 0,
    val physicalRowPreparations: Long = 0,
    val logicalExpressionEvaluations: Long = 0,
    val frameAllocations: Long = 0,
    val valueOnlyEvidenceMaterializations: Long = 0,
) {
    internal operator fun plus(other: CompiledExecutionStatistics) = CompiledExecutionStatistics(
        Math.addExact(sessionOpens, other.sessionOpens),
        Math.addExact(sessionCloseAttempts, other.sessionCloseAttempts),
        Math.addExact(successfulSessionCloses, other.successfulSessionCloses),
        Math.addExact(executionPlanCompilations, other.executionPlanCompilations),
        Math.addExact(rowCycles, other.rowCycles),
        Math.addExact(physicalRowPreparations, other.physicalRowPreparations),
        Math.addExact(logicalExpressionEvaluations, other.logicalExpressionEvaluations),
        Math.addExact(frameAllocations, other.frameAllocations),
        Math.addExact(valueOnlyEvidenceMaterializations, other.valueOnlyEvidenceMaterializations),
    )
}
