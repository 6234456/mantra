package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseExplainAddress
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.WorkspaceCatalog.Resolved
import com.xqiou.mantra.workbench.WorkspaceCatalog.Snapshot

/** Root overrides never leak into source cases; the loader owns captured bytes for this request. */
internal fun WorkspaceCatalog.resolve(
    caseId: String,
    snapshot: Snapshot,
    layoutOverride: String? = null,
    parameterOverride: List<String>? = null,
    includeLayout: Boolean = true,
    caseText: String? = null,
    explain: ExplainAddress? = null,
    audit: Boolean = false,
): Resolved {
    val casePath = path(caseId).toRealPath()
    if (snapshot.kind("case").none { it.path.toRealPath() == casePath }) {
        throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Case was not found")
    }
    val key = CanonicalCaseKey(relative(casePath))
    val loader =
        CasePackageLoader(
            root,
            CasePackageOverrides(key.value, caseText, parameterOverride, layoutOverride, includeLayout = includeLayout),
        )
    val graph = try {
        sessions.graph(
            loader,
            CaseRunRequest(
                CaseReference(key.value),
                audit = if (audit) AuditOptions() else null,
                explain = explain?.let { address ->
                    CaseExplainAddress(
                        address.case?.let(::CanonicalCaseKey),
                        InputAddress(address.node, address.coord),
                        address.expectedRevision,
                    )
                },
            ),
        )
    } catch (error: MantraException) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Case cannot be resolved", error.diagnostics)
    }
    val result = graph.result ?: throw WorkspaceException(
        when {
            graph.diagnostics.any { it.finding.code == "MANTRA-LINK-REVISION" } -> WorkspaceProblem.CONFLICT
            explain?.case != null && CanonicalCaseKey(explain.case) !in graph.cases &&
                graph.diagnostics.any { it.finding.code == "MANTRA-LINK-ADDRESS" } -> WorkspaceProblem.NOT_FOUND
            else -> WorkspaceProblem.INVALID
        },
        "Case graph could not be calculated",
        graph.diagnostics.map {
            it.finding.copy(caseKey = it.case?.value, caseRevision = it.revision)
        },
        graph.diagnostics.firstOrNull { it.finding.code == "MANTRA-LINK-REVISION" }?.revision
            ?: graph.cases[graph.root]?.revision,
    )
    val binding = loader.binding(key)
    val view = result.view
    val layout = if (includeLayout) binding.layout ?: Render.defaultLayout(view) else Render.defaultLayout(view)
    return Resolved(
        view, layout, graph.cases.getValue(key).revision, binding.parameterIds, graph.explain,
        binding.packageData.schema, binding.packageData.parameters, binding.sourceOverrides, graph,
        graph.cases.mapValues { (case, run) -> loader.binding(case).layout ?: Render.defaultLayout(run.view) },
    )
}
