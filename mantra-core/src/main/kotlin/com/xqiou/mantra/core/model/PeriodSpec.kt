package com.xqiou.mantra.core.model

import com.xqiou.mantra.core.SourceLocation
import java.time.LocalDate

/** Static and generated periods describe the same half-open timeline. */
sealed interface PeriodSpec {
    data class Generated(val start: LocalDate, val unit: PeriodUnit, val count: Int) : PeriodSpec

    /** Declaration order is chronological order; the resolver never sorts keys or dates. */
    data class Listed(val entries: List<PeriodEntry>) : PeriodSpec
}

enum class PeriodUnit(val months: Long) {
    MONTH(1),
    QUARTER(3),
    YEAR(12),
}

data class PeriodEntry(
    val key: String,
    val start: LocalDate,
    /** The author-facing :end value is exclusive. */
    val endExclusive: LocalDate,
    val label: String? = null,
    val location: SourceLocation? = null,
)

/** The axis whose removal changes a sum into a boundary selection. */
data class BoundaryAggregation(val dimension: String, val boundary: Boundary) {
    enum class Boundary { FIRST, LAST }
}
