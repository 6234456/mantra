package com.xqiou.mantra.cli

import com.xqiou.mantra.core.api.RuntimeVersions
import com.xqiou.mantra.workbench.TemplatePreviewRequest
import com.xqiou.mantra.workbench.TemplateSourceEdit
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import java.io.IOException

internal data class AcceptanceCapture(val suite: Map<String, Any?>, val sourceDocuments: Set<String>)
internal data class AcceptanceRun(
    val report: Map<String, Any?>,
    val status: Int,
    val sourceDocuments: Set<String>,
    val sourceCaptureComplete: Boolean,
)

private data class AcceptanceSnapshot(
    val revision: String,
    val revisions: Map<String, String>,
    val documents: List<Map<String, Any?>>,
) {
    val roots: List<Map<String, Any?>> get() = documents.filter { it["editable"] == true }
    val rootIdentities: Set<List<Any?>> get() = roots.map { listOf(it["document"], it["role"], it["sha256"]) }.toSet()
}

/** Capture source identities only. In particular, do not turn a computed output into its own oracle. */
internal fun captureTemplateAcceptanceSuite(
    catalog: WorkspaceCatalog,
    caseIds: List<String>,
    format: String = TEMPLATE_ACCEPTANCE_FORMAT,
): AcceptanceCapture {
    require(format in setOf(TEMPLATE_ACCEPTANCE_FORMAT, TEMPLATE_ACCEPTANCE_FORMAT_V2)) {
        "Unsupported template acceptance format"
    }
    require(caseIds.size in 1..32) { "Capture requires between 1 and 32 cases" }
    val snapshots = caseIds.distinct().associateWith { acceptanceSnapshot(catalog, it) }
    val roots = snapshots.values.first().roots
    val identities = snapshots.values.first().rootIdentities
    require(snapshots.values.all { it.rootIdentities == identities }) {
        "All acceptance cases must use the same root schema/layout documents and baseline hashes"
    }
    // The capture is also a multi-case read. Do not emit an already stale suite after a later read.
    snapshots.forEach { (caseId, captured) ->
        requireFreshSnapshot(caseId, captured, acceptanceSnapshot(catalog, caseId))
    }
    val documents = roots.map { source ->
        linkedMapOf(
            "document" to source.getValue("document"),
            "role" to source.getValue("role"),
            "sha256" to source.getValue("sha256"),
            "text" to source.getValue("text"),
        )
    }
    val cases = caseIds.mapIndexed { index, caseId ->
        val captured = snapshots.getValue(caseId)
        linkedMapOf(
            "id" to "case-${index + 1}",
            "case" to caseId,
            "baseRevision" to captured.revision,
            "baseRevisions" to captured.revisions,
            "inputs" to emptyList<Any>(),
            "expect" to null,
        )
    }
    return AcceptanceCapture(
        linkedMapOf("format" to format, "documents" to documents, "cases" to cases),
        snapshots.values.flatMap {
            it.revisions.keys + it.documents.map { source -> source["document"] as String }
        }.toSet(),
    )
}

/**
 * Run each case through the existing disposable candidate service and then re-read every captured
 * baseline. This is a revision check, not a promise to lock an externally writable workspace.
 */
internal fun runTemplateAcceptanceSuite(
    catalog: WorkspaceCatalog,
    suite: TemplateAcceptanceSuite,
    beforeFinalCheck: (() -> Unit)? = null,
): AcceptanceRun {
    val snapshots = linkedMapOf<String, AcceptanceSnapshot>()
    val failures = linkedMapOf<String, Exception>()
    val sources = suite.cases.flatMap { it.baseRevisions.keys }.toMutableSet()
    // Capture every distinct case before evaluating any candidate. Handles remain local to its case.
    suite.cases.map { it.caseId }.distinct().forEach { caseId ->
        try {
            val captured = acceptanceSnapshot(catalog, caseId)
            snapshots[caseId] = captured
            sources += captured.revisions.keys
            sources += captured.documents.map { it["document"] as String }
        } catch (error: WorkspaceException) {
            failures[caseId] = error
        } catch (error: IOException) {
            failures[caseId] = error
        } catch (error: IllegalArgumentException) {
            failures[caseId] = error
        }
    }
    var sourceCaptureComplete = failures.isEmpty()
    val results = suite.cases.mapIndexed { index, example ->
        val result = acceptanceResult(example, suite.format)
        try {
            failures[example.caseId]?.let { throw it }
            val captured = snapshots.getValue(example.caseId)
            requirePinnedSnapshot(example, suite.documents, captured)
            requireNotNull(example.expect) { "Expected results are not filled in for ${example.id}" }
            val handles = captured.roots.associate { it["document"] as String to it["handle"] as String }
            val response = catalog.templatePreview(
                example.caseId,
                TemplatePreviewRequest(
                    captured.revision,
                    captured.revisions,
                    index.toLong() + 1,
                    suite.documents.map { TemplateSourceEdit(handles.getValue(it.document), it.text) },
                    example.inputs,
                    example.panel,
                    example.includeZero,
                ),
            ).data
            listOf("proposedRevision", "succeeded", "validationPassed", "diagnostics", "difference").forEach { field ->
                result[field] = response[field]
            }
            if (suite.format == TEMPLATE_ACCEPTANCE_FORMAT_V2) result["paper"] = response["paper"]
            val assertions = acceptanceAssertions(example.expect, response)
            result["assertions"] = assertions
            result["status"] = if (assertions.all { it["passed"] == true }) "passed" else "failed"
        } catch (error: WorkspaceException) {
            acceptanceFailure(result, error)
        } catch (error: IOException) {
            acceptanceFailure(result, error)
        } catch (error: IllegalArgumentException) {
            acceptanceFailure(result, error)
        }
        result
    }
    beforeFinalCheck?.invoke()
    snapshots.forEach { (caseId, captured) ->
        try {
            val current = acceptanceSnapshot(catalog, caseId)
            sources += current.revisions.keys
            sources += current.documents.map { it["document"] as String }
            requireFreshSnapshot(caseId, captured, current)
        } catch (error: WorkspaceException) {
            sourceCaptureComplete = false
            results.filter { it["case"] == caseId }.forEach { acceptanceFinalConflict(it, error) }
        } catch (error: IOException) {
            sourceCaptureComplete = false
            results.filter { it["case"] == caseId }.forEach { acceptanceFinalConflict(it, error) }
        } catch (error: IllegalArgumentException) {
            sourceCaptureComplete = false
            results.filter { it["case"] == caseId }.forEach { acceptanceFinalConflict(it, error) }
        }
    }
    val status = when {
        results.any { it["status"] in setOf("error", "conflict") } -> 2
        results.any { it["status"] == "failed" } -> 3
        else -> 0
    }
    return AcceptanceRun(
        linkedMapOf(
            "format" to if (suite.format == TEMPLATE_ACCEPTANCE_FORMAT_V2) {
                "mantra.template-acceptance-report/2"
            } else {
                "mantra.template-acceptance-report/1"
            },
            "engine" to linkedMapOf("mantra" to RuntimeVersions.mantra, "normein" to RuntimeVersions.normein),
            "suiteSha256" to suite.sha256,
            "passed" to (status == 0),
            "cases" to results,
        ),
        status,
        sources,
        sourceCaptureComplete,
    )
}

private fun acceptanceSnapshot(catalog: WorkspaceCatalog, caseId: String): AcceptanceSnapshot {
    val snapshot = catalog.templateSources(caseId)
    @Suppress("UNCHECKED_CAST")
    return AcceptanceSnapshot(
        snapshot.revision,
        snapshot.data.getValue("baseRevisions") as Map<String, String>,
        snapshot.data.getValue("documents") as List<Map<String, Any?>>,
    )
}

private fun requirePinnedSnapshot(
    example: AcceptanceCase,
    documents: List<AcceptanceDocument>,
    captured: AcceptanceSnapshot,
) {
    if (example.baseRevision != captured.revision || example.baseRevisions != captured.revisions) {
        throw WorkspaceException(
            WorkspaceProblem.CONFLICT,
            "Pinned acceptance baseline differs for ${example.caseId}",
            currentRevision = captured.revision,
        )
    }
    if (documents.map { listOf(it.document, it.role, it.sha256) }.toSet() != captured.rootIdentities) {
        throw WorkspaceException(
            WorkspaceProblem.CONFLICT,
            "Shared candidate roots differ from the captured schema/layout for ${example.caseId}",
            currentRevision = captured.revision,
        )
    }
}

private fun requireFreshSnapshot(caseId: String, captured: AcceptanceSnapshot, current: AcceptanceSnapshot) {
    if (captured.revision != current.revision || captured.revisions != current.revisions ||
        captured.rootIdentities != current.rootIdentities
    ) {
        throw WorkspaceException(
            WorkspaceProblem.CONFLICT,
            "Acceptance baseline changed while running the suite: $caseId",
            currentRevision = current.revision,
        )
    }
}

private fun acceptanceResult(example: AcceptanceCase, format: String): MutableMap<String, Any?> = linkedMapOf(
    "id" to example.id,
    "case" to example.caseId,
    "status" to "error",
    "baseRevision" to example.baseRevision,
    "baseRevisions" to example.baseRevisions,
    "proposedRevision" to null,
    "succeeded" to null,
    "validationPassed" to null,
    "diagnostics" to emptyList<Any>(),
    "difference" to null,
    "assertions" to emptyList<Any>(),
    "error" to null,
).apply { if (format == TEMPLATE_ACCEPTANCE_FORMAT_V2) put("paper", null) }

private fun acceptanceFailure(result: MutableMap<String, Any?>, error: Exception) {
    result["status"] =
        if (error is WorkspaceException && error.problem == WorkspaceProblem.CONFLICT) "conflict" else "error"
    result["error"] = acceptanceError(error)
    if (error is WorkspaceException && result["proposedRevision"] == null) {
        result["diagnostics"] = error.diagnostics.map(WorkbenchDocuments::diagnostic)
    }
}

private fun acceptanceFinalConflict(result: MutableMap<String, Any?>, error: Exception) {
    result["status"] = "conflict"
    result["error"] = linkedMapOf(
        "problem" to "CONFLICT",
        "message" to "Final acceptance baseline check failed: ${error.message}",
        "currentRevision" to (error as? WorkspaceException)?.currentRevision,
    )
}

private fun acceptanceError(error: Exception): Map<String, Any?> = linkedMapOf(
    "problem" to ((error as? WorkspaceException)?.problem?.name ?: "INVALID"),
    "message" to error.message,
    "currentRevision" to (error as? WorkspaceException)?.currentRevision,
)

private fun acceptanceAssertions(
    expected: AcceptanceExpectation,
    preview: Map<String, Any?>,
): List<Map<String, Any?>> = buildList {
    fun assertion(path: String, expectation: Any?, actual: Any?, passed: Boolean = expectation == actual) {
        add(linkedMapOf("path" to path, "expected" to expectation, "actual" to actual, "passed" to passed))
    }
    assertion("/succeeded", expected.succeeded, preview["succeeded"])
    assertion("/validationPassed", expected.validationPassed, preview["validationPassed"])
    val run = preview.getValue("run") as Map<*, *>
    val values = run["values"] as Map<*, *>
    val structure = preview.getValue("structure") as Map<*, *>
    val nodes = structure["nodes"] as Map<*, *>
    expected.values.forEach { value ->
        val declaration = nodes[value.node] as? Map<*, *>
        require((declaration?.get("dims") as? List<*>)?.isNotEmpty() != true) {
            "Acceptance assertions support only scalar results; ${value.node} has dimensions"
        }
        val coordinates = values[value.node] as? Map<*, *>
        val present = coordinates?.containsKey("") == true
        val actual = coordinates?.get("") as? Map<*, *>
        val path = "/run/values/${value.node.replace("~", "~0").replace("/", "~1")}/"
        assertion("$path/present", value.present, present)
        if (value.present) {
            assertion(
                "$path/value",
                value.value,
                actual?.get("value"),
                present && exactAcceptanceValueEquals(value.value, actual?.get("value")),
            )
            if ("display" in
                value.fields
            ) {
                assertion(
                    "$path/display",
                    value.display,
                    actual?.get("display"),
                    present && value.display == actual?.get("display"),
                )
            }
            if ("active" in
                value.fields
            ) {
                assertion(
                    "$path/active",
                    value.active,
                    actual?.get("active"),
                    present && value.active == actual?.get("active"),
                )
            }
            if ("validationPassed" in value.fields) {
                val passed = (actual?.get("validation") as? Map<*, *>)?.get("passed")
                assertion(
                    "$path/validationPassed",
                    value.validationPassed,
                    passed,
                    present && value.validationPassed == passed,
                )
            }
        }
    }
    expected.diagnostics?.let { diagnostics ->
        val expectedCounts = diagnostics.associate { (it.code to it.severity) to it.count }
        val actualCounts = (preview.getValue("diagnostics") as List<*>).map { it as Map<*, *> }
            .groupingBy { (it["code"] as String) to (it["severity"] as String) }.eachCount()
        fun countsTree(counts: Map<Pair<String, String>, Int>): List<Map<String, Any>> =
            counts.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (key, count) ->
                linkedMapOf("code" to key.first, "severity" to key.second, "count" to count)
            }
        assertion("/diagnostics", countsTree(expectedCounts), countsTree(actualCounts), expectedCounts == actualCounts)
    }
    expected.paper?.let { paper ->
        @Suppress("UNCHECKED_CAST")
        addAll(paperAcceptanceAssertions(paper, preview.getValue("paper") as Map<String, Any?>))
    }
}
