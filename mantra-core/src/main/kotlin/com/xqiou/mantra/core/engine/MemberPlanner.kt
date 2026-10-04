package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.SourceLocation

/** Canonical descriptions and provenance for coordinate-level planning diagnostics. */
internal object MemberPlanner {
    fun location(plan: CalculationPlan, task: MemberTask): SourceLocation? = plan.vertices[id(task)]?.location

    fun describe(plan: CalculationPlan, task: MemberTask): String {
        val id = id(task)
        val vertex = plan.vertices[id]
        val dims = when (vertex) {
            is ValueVertex -> vertex.dims
            is ConditionVertex -> vertex.dims
            else -> emptyList()
        }
        val coord = when (task) {
            is MemberTask.Value -> task.coord
            is MemberTask.Guard -> task.coord
            is MemberTask.Validation -> task.coord
            is MemberTask.Spread -> task.coord
            else -> emptyList()
        }
        val assignment = if (task is MemberTask.Slice) {
            task.fixed.entries.map { "${it.key}=${it.value}" }
        } else {
            dims.zip(coord).map { (dim, key) -> "$dim=$key" }
        }
        val suffix = when (task) {
            is MemberTask.Domain -> "?members"
            is MemberTask.Guard -> "?guard"
            is MemberTask.Spread -> "?spread"
            is MemberTask.Validation -> "?validation"
            is MemberTask.Slice -> "?map"
            is MemberTask.Value -> ""
        }
        return id + suffix + if (assignment.isEmpty()) "" else "[${assignment.joinToString()}]"
    }

    private fun id(task: MemberTask): String = when (task) {
        is MemberTask.Domain -> task.id
        is MemberTask.Value -> task.id
        is MemberTask.Guard -> task.id
        is MemberTask.Spread -> task.id
        is MemberTask.Validation -> task.id
        is MemberTask.Slice -> task.id
    }
}
