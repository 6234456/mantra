package com.xqiou.mantra.core.api

/**
 * Bounds for an optional calculation-wide audit trace. Reaching a bound adds a warning and marks
 * omitted details as truncated; it never changes or stops the calculation itself.
 *
 * Kernel events are counted over the whole run. Steps and characters bound the detached public
 * projection, including source snippets, rendered values and branch snippets. Values are summaries
 * produced by Normein, with its existing collection and redaction limits.
 */
data class AuditOptions(
    val maxFormulas: Int = 2_000,
    val maxEvents: Int = 100_000,
    val maxSteps: Int = 16_384,
    val maxCharacters: Int = 1_000_000,
    val maxStepsPerFormula: Int = 64,
    val maxBranchesPerFormula: Int = 32,
) {
    init {
        require(maxFormulas in 0..2_000) { "Audit formula limit must be between 0 and 2,000" }
        require(maxEvents in 0..100_000) { "Audit event limit must be between 0 and 100,000" }
        require(maxSteps in 0..16_384) { "Audit step limit must be between 0 and 16,384" }
        require(maxCharacters in 0..1_000_000) { "Audit character limit must be between 0 and 1,000,000" }
        require(maxStepsPerFormula in 0..64) { "Per-formula step limit must be between 0 and 64" }
        require(maxBranchesPerFormula in 0..32) { "Per-formula branch limit must be between 0 and 32" }
    }
}
