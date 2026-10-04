package com.xqiou.mantra.lsp

import com.xqiou.mantra.core.api.language.LanguageSpan
import com.xqiou.mantra.core.api.language.LanguageSymbolId
import com.xqiou.mantra.core.api.language.LanguageSymbolKind
import com.xqiou.mantra.core.api.language.MantraLanguage

class RenameRefused(val reason: String, val affectedUris: List<String> = emptyList()) : RuntimeException(reason)
data class SourceEdit(val span: LanguageSpan, val text: String)
data class VersionedEdits(val uri: String, val version: Int, val edits: List<SourceEdit>)

/** Builds reviewable in-memory edits; it never mutates files or the editor's document store. */
class SafeRename(private val analyzer: LanguageAnalysisEngine, private val documents: Documents) {
    fun prepare(analysis: WorkspaceAnalysis, uri: String, offset: Int): LanguageSpan {
        val symbol = analysis.symbolAt(uri, offset) ?: throw RenameRefused("No definite symbol at this position")
        requireSupported(analysis, symbol)
        return analysis.references(symbol, true).firstOrNull { it.source == uri && it.contains(offset) }
            ?: throw RenameRefused("No editable identifier token")
    }

    fun rename(
        analysis: WorkspaceAnalysis,
        uri: String,
        offset: Int,
        name: String,
        checkpoint: () -> Unit = {},
    ): List<VersionedEdits> {
        val symbol = analysis.symbolAt(uri, offset) ?: throw RenameRefused("No definite symbol at this position")
        requireSupported(analysis, symbol)
        if (!MantraLanguage.validNodeName(name)) throw RenameRefused("Invalid or reserved node identifier")
        val declarationSpans = analysis.definitions.filter { it.id == symbol }.map { it.span }.toSet()
        val targets = analysis.definitions.filter { it.span in declarationSpans }.map { it.id }.toSet()
        if (targets.any { it.kind !in SUPPORTED }) throw RenameRefused("Shared declaration has unsupported ownership")
        if (analysis.definitions.any {
                it.id.schema in targets.map { target -> target.schema } &&
                    it.id.kind != LanguageSymbolKind.LOCAL && it.id.name == name && it.id !in targets
            }
        ) {
            throw RenameRefused("Identifier already exists in an affected schema")
        }
        val ranges = targets.flatMap { analysis.references(it, true) }.distinct()
        val affected = ranges.map { it.source }.distinct()
        val missing = affected.filter { analysis.epoch.overlays[it]?.version == null }
        if (missing.isNotEmpty()) throw RenameRefused("Synchronize all affected documents before rename", missing)
        if (!documents.current(analysis.epoch.revision)) throw RenameRefused("Analysis was superseded by an edit")
        val edits = ranges.map { SourceEdit(it, name) }.groupBy { it.span.source }
        val candidate = LinkedHashMap(analysis.epoch.overlays)
        edits.forEach { (source, values) ->
            checkpoint()
            val document = requireNotNull(candidate[source])
            val sorted = values.sortedBy { it.span.start }
            if (sorted.zipWithNext().any { (a, b) -> a.span.end > b.span.start }) {
                throw RenameRefused("Overlapping semantic edit ranges")
            }
            var text = document.text
            sorted.asReversed().forEach {
                text =
                    text.substring(0, it.span.start) + it.text + text.substring(it.span.end)
            }
            candidate[source] = DocumentSnapshot(source, text, document.version, document.clientUri)
        }
        val speculative = analyzer.analyze(DocumentEpoch(analysis.epoch.revision, candidate), checkpoint)
        if (!speculative.complete) throw RenameRefused("Candidate rename fails complete static reanalysis")
        fun mapped(span: LanguageSpan): LanguageSpan {
            val changes = edits[span.source].orEmpty()
            val delta = changes.filter { it.span.end <= span.start }.sumOf {
                it.text.length -
                    (it.span.end - it.span.start)
            }
            val own = changes.singleOrNull { it.span == span }
            return LanguageSpan(
                span.source,
                span.start + delta,
                span.start + delta + (own?.text?.length ?: (span.end - span.start)),
            )
        }
        fun expected(id: LanguageSymbolId): LanguageSymbolId = if (id in targets) {
            id.copy(name = name)
        } else {
            id.copy(lexicalOwner = id.lexicalOwner?.let(::mapped))
        }
        // Check identity of every old definite use, including unaffected locals. A rooted name
        // becoming a lexical local is a capture even when the candidate otherwise compiles.
        analysis.occurrences.filter { it.definite && it.symbol != null }.forEach { use ->
            checkpoint()
            val span = mapped(use.span)
            val actual = speculative.symbolAt(span.source, span.start)
            if (actual != expected(requireNotNull(use.symbol))) {
                throw RenameRefused("Candidate changes binding identity at ${span.source}:${span.start}")
            }
        }
        if (!documents.current(analysis.epoch.revision)) throw RenameRefused("Documents changed during rename")
        // Re-read closed sources through the same confinement boundary before returning edits.
        analyzer.verifySources(analysis, checkpoint)
        if (!documents.current(
                analysis.epoch.revision,
            )
        ) {
            throw RenameRefused("Documents changed during source verification")
        }
        return edits.map { (source, values) ->
            VersionedEdits(
                source,
                requireNotNull(analysis.epoch.overlays[source]?.version),
                values.sortedBy { it.span.start },
            )
        }
    }

    private fun requireSupported(analysis: WorkspaceAnalysis, symbol: LanguageSymbolId) {
        if (!analysis.complete) {
            throw RenameRefused(
                "Reference graph is incomplete: " +
                    (
                        analysis.issues + analysis.analyses.flatMap {
                            it.issues.map { issue -> issue.reason }
                        }
                        ).take(3).joinToString("; "),
            )
        }
        if (symbol.kind !in SUPPORTED) throw RenameRefused("Initial rename supports nodes and inputs only")
        if (analysis.definitions.none { it.id == symbol }) throw RenameRefused("Declaration is outside this workspace")
    }

    companion object {
        private val SUPPORTED = setOf(LanguageSymbolKind.NODE, LanguageSymbolKind.INPUT)
    }
}
