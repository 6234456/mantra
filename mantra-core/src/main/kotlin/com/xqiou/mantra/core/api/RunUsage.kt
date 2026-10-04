package com.xqiou.mantra.core.api

import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.EnumMap

enum class RunStage { LOADING, IMPORTING, BINDING, PLANNING, DOMAIN, TASK, FORMULA, REDUCING, READING, PROJECTING }

data class RunAddress(val caseKey: String? = null, val nodeId: String? = null, val coord: List<String> = emptyList())

enum class RunFailureKind { LIMIT, CANCELLED, DEADLINE }

/** Host-control failure, projected by the outer calculation boundary as EVALUATION. */
data class RunFailure(
    val kind: RunFailureKind,
    val stage: RunStage,
    val address: RunAddress?,
    val counter: RunCounter? = null,
    val limit: Long? = null,
    val attempted: Long? = null,
    val overflow: Boolean = false,
    val deadline: Instant? = null,
) {
    val code: String get() = when (kind) {
        RunFailureKind.LIMIT -> "MANTRA-RUN-LIMIT"
        RunFailureKind.CANCELLED -> "MANTRA-RUN-CANCELLED"
        RunFailureKind.DEADLINE -> "MANTRA-RUN-DEADLINE"
    }
}

/** Immutable host usage. This intentionally does not claim aggregate VALUE_ONLY kernel counters. */
class RunUsage internal constructor(
    counters: Map<RunCounter, Long>,
    val startedAt: Instant,
    val deadline: Instant,
    /** SDK observation only; omit from deterministic wire goldens. */
    val elapsed: Duration,
) {
    val counters: Map<RunCounter, Long> = Collections.unmodifiableMap(
        EnumMap<RunCounter, Long>(RunCounter::class.java).apply { putAll(counters) },
    )
    operator fun get(counter: RunCounter): Long = counters[counter] ?: 0
}
