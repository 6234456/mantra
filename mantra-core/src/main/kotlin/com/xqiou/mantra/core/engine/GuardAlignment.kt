package com.xqiou.mantra.core.engine

/**
 * The dimension assignments on which all inherited section conditions can be evaluated together.
 * Evaluation and formula exporters consume the same assignments, then apply their own boolean or
 * symbolic condition values. A child with fewer dimensions does not choose a reduction rule.
 */
data class GuardAlignment(
    val extraDimensions: List<String>,
    val assignments: List<Map<String, String>>,
)

object SectionGuards {
    fun align(
        plan: CalculationPlan,
        vertex: ValueVertex,
        coord: Coord,
        memberKeys: (String) -> List<String>,
    ): GuardAlignment {
        val guards = vertex.guards.map { plan.vertices.getValue(it) as ConditionVertex }
        val fixed = vertex.dims.zip(coord).toMap()
        val extra = plan.dimensionOrder(guards.flatMap { it.dims }.filter { it !in fixed }.toSet())
        val assignments = extra.fold(listOf(fixed)) { partial, dim ->
            partial.flatMap { assignment -> memberKeys(dim).map { assignment + (dim to it) } }
        }
        return GuardAlignment(extra, assignments)
    }
}
