package com.xqiou.mantra.core.engine

/** Finds edited producers from original facts, preserving the distinction between absent and zero. */
internal object IncrementalInvalidation {
    fun changed(previous: CalculationPlan, next: CalculationPlan, tasks: Set<MemberTask>): Set<MemberTask> =
        tasks.filterTo(linkedSetOf()) { task ->
            if (task !is MemberTask.Value) return@filterTo false
            when (val vertex = previous.valueVertices.getValue(task.id)) {
                is ParamVertex -> {
                    val replacement = next.valueVertices.getValue(task.id) as ParamVertex
                    vertex.value != replacement.value || vertex.source != replacement.source ||
                        vertex.layers != replacement.layers
                }
                is InputVertex -> {
                    val before = previous.case
                    val after = next.case
                    PlanRebinding.raw(before, task.id, task.coord) != PlanRebinding.raw(after, task.id, task.coord) ||
                        before.inputLocations[task.id] != after.inputLocations[task.id] ||
                        before.inputCells[task.id] != after.inputCells[task.id] ||
                        before.inputOrigins[task.id] != after.inputOrigins[task.id]
                }
                else -> false
            }
        }
}
