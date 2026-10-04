package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.packages.MigrationPreview
import com.xqiou.mantra.packages.MountedPackageCase
import com.xqiou.mantra.packages.PackageParameterChoice
import com.xqiou.mantra.packages.ParameterSelection
import com.xqiou.mantra.packages.ParameterSelectionMode
import com.xqiou.mantra.packages.ParameterSelector
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson

/** Package evidence surrounds the existing strict workbench/4 document instead of changing it. */
internal object PackageHostDocuments {
    fun binding(value: PackageHostResolver.Binding): Map<String, Any?> = linkedMapOf(
        "case" to value.resourceCase.canonicalPath,
        "packageId" to value.resourceCase.snapshot.manifest.identity.id,
        "packageVersion" to value.resourceCase.snapshot.manifest.identity.version.text,
        "packageRevision" to value.resourceCase.snapshot.revision,
        "schema" to value.prepared.schemaIdentity.id,
        "schemaVersion" to value.prepared.schemaIdentity.version,
        "editableCase" to value.editable,
        "resourcesReadOnly" to true,
        "parameterSelectionRevision" to value.selection?.revision,
    )

    fun parameters(execution: PackageWorkspaceCatalog.Execution): List<Map<String, Any?>> =
        execution.bindings.flatMap { (key, binding) ->
            val view = execution.graph.cases[key]?.view ?: return@flatMap emptyList()
            val selection = binding.selection
            if (selection == null) {
                return@flatMap binding.prepared.parameters.flatMap { set ->
                    val entry = binding.resourceCase.snapshot.manifest.parameters.single { it.id == set.id }
                    set.values.keys.map { parameter ->
                        val node = view.nodes[parameter]
                        fun date(name: String): String? = when (val raw = set.meta[name]) {
                            is com.xqiou.mantra.core.model.Value.Text -> raw.value
                            is com.xqiou.mantra.core.model.Value.Date -> raw.value.toString()
                            else -> null
                        }
                        linkedMapOf<String, Any?>(
                            "case" to key.value,
                            "key" to parameter,
                            "set" to set.id,
                            "packageId" to binding.resourceCase.snapshot.manifest.identity.id,
                            "packageVersion" to binding.resourceCase.snapshot.manifest.identity.version.text,
                            "packageRevision" to binding.resourceCase.snapshot.revision,
                            "schema" to binding.prepared.schemaIdentity.id,
                            "schemaVersion" to binding.prepared.schemaIdentity.version,
                            "resource" to entry.path,
                            "sha256" to binding.resourceCase.snapshot.descriptor(entry.path).sha256,
                            "effectiveDate" to null,
                            "validFrom" to date("valid-from"),
                            "validUntil" to date("valid-until"),
                            "endExclusive" to true,
                            "mode" to "declared",
                            "validForDate" to null,
                            "reference" to set.references[parameter],
                            "effectiveLayer" to node?.parameterSource,
                            "effectiveValue" to node?.parameterValue?.let(WorkbenchJson::value),
                            "selectedSetValue" to set.values[parameter]?.let(WorkbenchJson::value),
                            "overriddenByCase" to (node?.parameterSource == "case"),
                        )
                    }
                }
            }
            selection.provenance.map { source ->
                val node = view.nodes[source.key]
                linkedMapOf(
                    "case" to key.value,
                    "key" to source.key,
                    "set" to source.setId,
                    "packageId" to source.packageIdentity.id,
                    "packageVersion" to source.packageIdentity.version.text,
                    "packageRevision" to source.packageRevision,
                    "schema" to source.schema.identity.id,
                    "schemaVersion" to source.schema.identity.version,
                    "resource" to source.resourcePath,
                    "sha256" to source.resourceSha256,
                    "effectiveDate" to source.effectiveDate.toString(),
                    "validFrom" to source.validFrom?.toString(),
                    "validUntil" to source.validUntil?.toString(),
                    "endExclusive" to true,
                    "mode" to source.mode.name.lowercase().replace('_', '-'),
                    "validForDate" to source.validForDate,
                    "reference" to source.reference,
                    "effectiveLayer" to node?.parameterSource,
                    "effectiveValue" to node?.parameterValue?.let(WorkbenchJson::value),
                    "selectedSetValue" to selection.parameters.lastOrNull { source.key in it.values }
                        ?.values?.get(source.key)?.let(WorkbenchJson::value),
                    "overriddenByCase" to (node?.parameterSource == "case"),
                )
            }
        }

    fun preview(
        preview: MigrationPreview,
        beforeGraph: com.xqiou.mantra.core.api.CaseRunResult?,
        afterGraph: com.xqiou.mantra.core.api.CaseRunResult?,
    ): Map<String, Any?> {
        val before = preview.before.result
        val after = preview.after.result
        return linkedMapOf(
            "case" to preview.casePath,
            "baseRevision" to preview.baseRevision,
            "reviewToken" to preview.reviewToken,
            "source" to mapOf(
                "schema" to preview.source.identity.id,
                "version" to preview.source.identity.version,
            ),
            "target" to mapOf(
                "schema" to preview.target.schema.identity.id,
                "version" to preview.target.schema.identity.version,
                "packageId" to preview.target.packageIdentity.id,
                "packageVersion" to preview.target.packageIdentity.version.text,
                "packageRevision" to preview.target.packageRevision,
                "parameterSelectionRevision" to preview.target.parameterSelectionRevision,
            ),
            "original" to preview.original.text,
            "candidate" to preview.candidate.text,
            "originalSha256" to preview.originalSha256,
            "candidateSha256" to preview.candidateSha256,
            "before" to
                preview.before.result?.let {
                    WorkbenchDocuments.run(it.view, com.xqiou.mantra.render.Render.defaultLayout(it.view), beforeGraph)
                },
            "after" to
                preview.after.result?.let {
                    WorkbenchDocuments.run(it.view, com.xqiou.mantra.render.Render.defaultLayout(it.view), afterGraph)
                },
            "difference" to if (before != null && after != null && before.schema.id == after.schema.id) {
                WorkbenchDocuments.compare(
                    before.view,
                    after.view,
                    com.xqiou.mantra.render.Render.defaultLayout(before.view),
                )
            } else {
                null
            },
            "diagnostics" to preview.after.diagnostics.map(WorkbenchDocuments::diagnostic),
        )
    }
}

internal fun selection(case: MountedPackageCase, choice: PackageParameterChoice?): ParameterSelection? = choice?.let {
    when (it.mode) {
        ParameterSelectionMode.EFFECTIVE_DATE -> ParameterSelector.effectiveDate(
            case.snapshot,
            case.entry.schema,
            it.effectiveDate,
            it.candidateIds,
            it.requiredKeys,
        )
        ParameterSelectionMode.WHAT_IF -> ParameterSelector.whatIf(
            case.snapshot,
            case.entry.schema,
            it.effectiveDate,
            it.candidateIds,
            it.requiredKeys,
        )
    }
}
