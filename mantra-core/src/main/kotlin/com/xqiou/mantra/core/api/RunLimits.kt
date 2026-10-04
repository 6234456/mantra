package com.xqiou.mantra.core.api

import java.time.Duration

/** Host work units. Depth and product are high-water marks; other units are cumulative. */
enum class RunCounter(val cumulative: Boolean = true) {
    CASES,
    LINK_DEPTH(false),
    LINK_MAPPINGS,
    COORDINATE_PRODUCT(false),
    COORDINATE_VISITS,
    INPUT_ROWS,
    TASKS,
    FORMULA_EXECUTIONS,
    HOST_SCANS,
    PARTICIPATING_BYTES,
}

/** Defaults are implementation assumptions awaiting measurement, rather than performance promises. */
data class RunLimits(
    val maxCases: Long = 64,
    val maxLinkDepth: Long = 16,
    val maxLinkMappings: Long = 1_024,
    val maxCoordinateProduct: Long = 100_000,
    val maxCoordinateVisits: Long = 5_000_000,
    val maxInputRows: Long = 100_000,
    val maxTasks: Long = 1_000_000,
    val maxFormulaExecutions: Long = 100_000,
    val maxHostScans: Long = 10_000_000,
    val maxParticipatingBytes: Long = 64L * 1024 * 1024,
    val maxDuration: Duration = Duration.ofSeconds(60),
) {
    init {
        require(maxCases >= 1) { "A run must allow its root case" }
        require(RunCounter.entries.all { maximum(it) >= 0 }) { "Run limits cannot be negative" }
        require(!maxDuration.isNegative && !maxDuration.isZero) { "Run duration must be positive" }
    }

    fun maximum(counter: RunCounter): Long = when (counter) {
        RunCounter.CASES -> maxCases
        RunCounter.LINK_DEPTH -> maxLinkDepth
        RunCounter.LINK_MAPPINGS -> maxLinkMappings
        RunCounter.COORDINATE_PRODUCT -> maxCoordinateProduct
        RunCounter.COORDINATE_VISITS -> maxCoordinateVisits
        RunCounter.INPUT_ROWS -> maxInputRows
        RunCounter.TASKS -> maxTasks
        RunCounter.FORMULA_EXECUTIONS -> maxFormulaExecutions
        RunCounter.HOST_SCANS -> maxHostScans
        RunCounter.PARTICIPATING_BYTES -> maxParticipatingBytes
    }
}
