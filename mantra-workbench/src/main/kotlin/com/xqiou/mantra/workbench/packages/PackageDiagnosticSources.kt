package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.packages.PackageResourceRole
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem

/** Exact engine source identities select immutable declared DSL resources, without reopening disk. */
internal fun PackageHostResolver.Binding.diagnosticSource(case: String, location: SourceLocation): SourceText {
    if (editable && location.source == case) return SourceText(case, source.text, case)
    if (source.name == location.source) return source
    val snapshot = resourceCase.snapshot
    val prefix = "${snapshot.manifest.identity.id}@${snapshot.manifest.identity.version}/"
    val entry = snapshot.manifest.resources.firstOrNull {
        prefix + it.path == location.source &&
            it.role in setOf(
                PackageResourceRole.CASE,
                PackageResourceRole.SCHEMA,
                PackageResourceRole.FRAGMENT,
                PackageResourceRole.PARAMETERS,
                PackageResourceRole.LAYOUT,
            )
    } ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic source is unavailable")
    return snapshot.source(entry.path, entry.role)
}
