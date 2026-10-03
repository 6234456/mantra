package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.GuardAlignment

internal object SectionGuards {
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
