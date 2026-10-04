package com.xqiou.mantra.core.api

/**
 * Work performed by the latest calculation, including domain and completion-barrier tasks.
 * These counters describe evaluation work, not total wall time: snapshots and reductions still run
 * when an unchanged case needs no formula evaluations.
 *
 * @property evaluatedTasks tasks actually executed during this calculation
 * @property reusedTasks completed tasks retained before this calculation, rather than cache-hit events
 * @property invalidatedTasks changed tasks and their observed downstream dependants
 * @property fullRebuild whether this calculation started with a newly compiled runtime
 * @property formulaEvaluations actual kernel evaluation calls during this calculation
 * @property executionSessions reusable formula sessions retained by the runtime, rather than newly opened sessions
 */
data class RecalculationStats(
    val evaluatedTasks: Int,
    val reusedTasks: Int,
    val invalidatedTasks: Int,
    val fullRebuild: Boolean,
    val formulaEvaluations: Int,
    val executionSessions: Int,
)
