package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.api.CaseRunResult
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunFailure
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.LinkProvenance

/** Typed graph projections; display strings never supply routing or ownership identities. */
internal object CaseGraphDocuments {
    fun address(case: String?, input: InputAddress): Map<String, Any?> = linkedMapOf(
        "case" to case,
        "node" to input.nodeId,
        "coord" to input.coord,
    )

    fun link(source: LinkProvenance): Map<String, Any?> = linkedMapOf(
        "case" to source.caseKey,
        "path" to source.path,
        "caseId" to source.caseId,
        "schema" to linkedMapOf("id" to source.schema.id, "version" to source.schema.version),
        "revision" to source.revision,
        "address" to address(source.caseKey, source.from),
    )

    fun graph(run: CaseRunResult): Map<String, Any?> = linkedMapOf(
        "root" to run.root?.value,
        "cases" to run.cases.values.map { case ->
            linkedMapOf(
                "case" to case.key.value,
                "caseId" to case.caseId,
                "schema" to linkedMapOf("id" to case.schema.id, "version" to case.schema.version),
                "revision" to case.revision,
                "succeeded" to case.result.succeeded,
                "validationPassed" to case.result.validationPassed,
            )
        },
        "edges" to run.edges.map { edge ->
            linkedMapOf(
                "from" to address(edge.source.value, edge.from),
                "to" to address(edge.target.value, edge.to),
                "revision" to edge.sourceRevision,
            )
        },
    )

    fun usage(run: CaseRunResult): Map<String, Any?> = linkedMapOf(
        "counters" to RunCounter.entries.associate { it.wire() to run.usage[it].toString() },
        "limits" to RunCounter.entries.associate { it.wire() to run.limits.maximum(it).toString() },
        "reuse" to linkedMapOf(
            "completedTasks" to run.cases.values.sumOf { it.recalculation?.reusedTasks?.toLong() ?: 0L }.toString(),
            "executionSessions" to
                run.cases.values.sumOf { it.recalculation?.executionSessions?.toLong() ?: 0L }.toString(),
        ),
    )

    fun failure(failure: RunFailure): Map<String, Any?> = linkedMapOf(
        "code" to failure.code,
        "kind" to failure.kind.name.lowercase(),
        "stage" to failure.stage.name.lowercase(),
        "counter" to failure.counter?.wire(),
        "limit" to failure.limit?.toString(),
        "attempted" to failure.attempted?.toString(),
        "overflow" to failure.overflow,
        "address" to failure.address?.let { where ->
            linkedMapOf("case" to where.caseKey, "node" to where.nodeId, "coord" to where.coord)
        },
    )

    private fun RunCounter.wire() = name.lowercase().replace('_', '-')
}
