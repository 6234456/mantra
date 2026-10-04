package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.workbench.WorkspaceCatalog.Resolved
import com.xqiou.mantra.workbench.WorkspaceCatalog.Snapshot
import java.nio.file.Path

/** Resolves a case and its declared dependencies into an immutable calculation view. */
internal fun WorkspaceCatalog.resolve(
    caseId: String,
    snapshot: Snapshot,
    layoutOverride: String? = null,
    parameterOverride: List<String>? = null,
    includeLayout: Boolean = true,
    caseText: String? = null,
    explain: ExplainAddress? = null,
): Resolved {
    val casePath = path(caseId)
    val entry = snapshot.kind("case").singleOrNull { it.path == casePath }
        ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Case was not found")
    if (entry.diagnostics.isNotEmpty()) {
        throw WorkspaceException(
            WorkspaceProblem.INVALID,
            "Case document is invalid",
            entry.diagnostics,
        )
    }
    val case = try {
        if (caseText ==
            null
        ) {
            loadCase(casePath)
        } else {
            Mantra.loadCase(SourceText(caseId, caseText, casePath.parent.toString()))
        }
    } catch (error: MantraException) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Case document is invalid", error.diagnostics)
    }
    val schemaId = case.schemaId ?: throw WorkspaceException(
        WorkspaceProblem.INVALID,
        "Case has no schema",
        listOf(diagnostic("MANTRA-WORKBENCH-CASE-SCHEMA", "Case does not declare :schema")),
    )
    val matches = snapshot.kind("schema").filter { it.name == schemaId }
    if (matches.size != 1) {
        throw WorkspaceException(
            WorkspaceProblem.INVALID,
            "Schema cannot be resolved",
            listOf(
                diagnostic("MANTRA-WORKBENCH-CASE-SCHEMA", "Expected one schema for $schemaId, found ${matches.size}"),
            ),
        )
    }
    val schemaPath = matches.single().path
    val schema = try {
        Mantra.loadSchema(
            source(schemaPath),
            SourceResolver { name, relative ->
                val base = relative?.base?.let(Path::of) ?: schemaPath.parent
                runCatching { source(base.resolve(name)) }.getOrNull()
            },
        )
    } catch (error: MantraException) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Schema document is invalid", error.diagnostics)
    }
    val parameterBinding = case.meta["parameters"]
    if (parameterOverride == null && parameterBinding != null && parameterBinding !is Value.Vec) {
        throw WorkspaceException(WorkspaceProblem.INVALID, ":parameters must be a list")
    }
    val parameterIds = parameterOverride ?: (parameterBinding as? Value.Vec)?.items?.map {
        (it as? Value.Text)?.value
            ?: throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter id must be text")
    }.orEmpty()
    val parameterFiles = parameterIds.map { id ->
        val matched = snapshot.kind("parameters").filter { it.name == id }
        if (matched.size !=
            1
        ) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter set $id cannot be resolved")
        }
        matched.single().path
    }
    val parameters = try {
        parameterFiles.map(Mantra::loadParameters)
    } catch (error: MantraException) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Parameter document is invalid", error.diagnostics)
    }
    val bound = BoundSources.load(case, schema, casePath, root)
    val result = try {
        if (explain == null) {
            Mantra.calculateForAudit(schema, bound.case, parameters)
        } else {
            Mantra.calculateForExplain(schema, bound.case, parameters, explain.node, explain.coord)
        }
    } catch (error: MantraException) {
        throw WorkspaceException(WorkspaceProblem.INVALID, "Case cannot be calculated", error.diagnostics)
    }
    val view = CalculationView.of(result)
    if (includeLayout && case.meta["layout"] != null && case.meta["layout"] !is Value.Text) {
        throw WorkspaceException(WorkspaceProblem.INVALID, ":layout must be text")
    }
    val selectedLayout = if (includeLayout) layoutOverride ?: case.text("layout") else null
    val layoutFile = selectedLayout?.let { id ->
        val matched = snapshot.kind("layout").filter { it.name == id }
        if (matched.size != 1) throw WorkspaceException(WorkspaceProblem.INVALID, "Layout $id cannot be resolved")
        matched.single().path
    }
    val layout = layoutFile?.let {
        try {
            LayoutReader.read(source(it))
        } catch (error: MantraException) {
            throw WorkspaceException(WorkspaceProblem.INVALID, "Layout document is invalid", error.diagnostics)
        }
    } ?: Render.defaultLayout(view)
    val revision = revision(
        listOf(casePath) + schema.sources.map { path(it) } + bound.files + parameterFiles + listOfNotNull(layoutFile),
        if (caseText == null) emptyMap() else mapOf(casePath to caseText.toByteArray(Charsets.UTF_8)),
    )
    return Resolved(view, layout, revision, parameterIds, result.explainTrace, schema, parameters, bound.overridden)
}
