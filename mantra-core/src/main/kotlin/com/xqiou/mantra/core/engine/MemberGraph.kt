package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.view.Coord

/** Runtime task identity; domains and completion barriers cannot collide with value vertices. */
internal sealed interface MemberTask {
    data class Domain(val id: String) : MemberTask
    data class Value(val id: String, val coord: Coord) : MemberTask
    data class Guard(val id: String, val coord: Coord) : MemberTask
    data class Spread(val id: String, val coord: Coord) : MemberTask
    data class Validation(val id: String, val coord: Coord) : MemberTask
    data class Slice(val id: String, val fixed: Map<String, String>) : MemberTask
}

/** Demand topological execution with one value producer per task and retained reverse edges. */
internal class MemberGraph(
    private val sink: DiagnosticSink,
    private val describe: (MemberTask) -> String,
    private val location: (MemberTask) -> SourceLocation?,
    private val before: (MemberTask, Boolean) -> Unit = { _, _ -> },
    private val executeTask: (MemberTask, () -> Unit) -> Unit = { _, action -> action() },
) {
    private val done = mutableSetOf<MemberTask>()
    private val stack = ArrayDeque<MemberTask>()
    private val dependencies = linkedMapOf<MemberTask, MutableSet<MemberTask>>()
    private val users = linkedMapOf<MemberTask, MutableSet<MemberTask>>()
    var evaluations: Int = 0
        private set

    fun ensure(task: MemberTask, execute: () -> Unit) {
        stack.lastOrNull()?.let { consumer ->
            dependencies.getOrPut(consumer) { linkedSetOf() } += task
            users.getOrPut(task) { linkedSetOf() } += consumer
        }
        if (task in done) {
            before(task, true)
            return
        }
        if (task in stack) {
            val cycle = stack.dropWhile { it != task } + task
            sink.error(
                "MANTRA-CYCLE",
                "Circular dependency: ${cycle.joinToString(" → ", transform = describe)}",
                location(task),
            )
            sink.throwIfStructuralErrors()
        }
        before(task, false)
        stack.addLast(task)
        try {
            executeTask(task) { sink.scoped(task, execute) }
            done += task
            evaluations++
        } finally {
            stack.removeLast()
        }
    }

    /** Includes downstream balances, validation tasks and whole/slice-map consumers. */
    fun invalidate(changed: Set<MemberTask>): Set<MemberTask> {
        val dirty = linkedSetOf<MemberTask>()
        fun visit(task: MemberTask) {
            if (!dirty.add(task)) return
            users[task].orEmpty().toList().forEach(::visit)
        }
        changed.forEach(::visit)
        done.removeAll(dirty)
        dirty.forEach { consumer ->
            dependencies.remove(consumer).orEmpty().forEach { producer ->
                users[producer]?.let { consumers ->
                    consumers.remove(consumer)
                    if (consumers.isEmpty()) users.remove(producer)
                }
            }
        }
        return dirty
    }

    val dependencyCount: Int get() = dependencies.values.sumOf { it.size }
    val completedTasks: Set<MemberTask> get() = done.toSet()
}
