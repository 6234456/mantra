package com.xqiou.mantra.lsp

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphInspector
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.PreparedCasePackage
import java.nio.file.Path

/** Public inspector with metadata-only packages; no CaseGraphRunner or fact-source importer. */
internal fun staticGraphChecks(
    packages: Map<CanonicalCaseKey, PreparedCasePackage>,
    checkpoint: () -> Unit,
): List<Diagnostic> {
    val findings = mutableListOf<Diagnostic>()
    packages.keys.forEach { root ->
        checkpoint()
        val accounted = mutableSetOf<String>()
        val resolver = object : CasePackageResolver {
            override fun identify(reference: CaseReference, control: CaseLoadControl): CanonicalCaseKey {
                checkpoint()
                control.checkpoint()
                val from = reference.fromCase
                val uri = if (from == null) {
                    reference.path
                } else {
                    Path.of(fileUri(from.value)).parent.resolve(reference.path).toRealPath().toUri().toString()
                }
                val key = CanonicalCaseKey(uri)
                require(key in packages) { "Linked case is absent from the confined metadata inventory" }
                return key
            }
            override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage {
                checkpoint()
                control.checkpoint()
                val prepared = requireNotNull(packages[key])
                prepared.sources.forEach { source ->
                    if (accounted.add(source.identity)) control.chargeParticipatingBytes(source.byteLength)
                }
                return prepared
            }
        }
        val inspected = CaseGraphInspector(resolver).inspect(CaseReference(root.value))
        checkpoint()
        findings += inspected.diagnostics.map { owned ->
            // LSP requires a range even for a document-level finding. Preserve the real owning
            // case, mark its unavailable token span in protocol data, and never invent a token.
            owned.finding.copy(
                location = owned.finding.location ?: com.xqiou.mantra.core.SourceLocation(
                    owned.case?.value ?: root.value,
                    1,
                    1,
                ),
            )
        }
    }
    return findings.distinct()
}
