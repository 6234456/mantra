package com.xqiou.mantra.core.api.language

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.model.SchemaIdentity

/** Offsets are end-exclusive UTF-16 indices in the original source, including CRLF. */
data class LanguageSpan(val source: String, val start: Int, val end: Int) {
    init {
        require(start >= 0 && end >= start)
    }
    fun contains(offset: Int): Boolean = offset >= start && offset < end
}

enum class LanguageSymbolKind { NODE, INPUT, PARAMETER, DIMENSION, SECTION, FUNCTION, LOCAL }

data class LanguageSymbolId(
    val schema: SchemaIdentity,
    val kind: LanguageSymbolKind,
    val name: String,
    /** Lexical binders are identified by their exact definition, not a reusable depth/slot pair. */
    val lexicalOwner: LanguageSpan? = null,
    /** Case-owned extension/function identities cannot alias another case of the same schema. */
    val ownerSource: String? = null,
)

data class LanguageDefinition(
    val id: LanguageSymbolId,
    val name: String,
    val span: LanguageSpan,
    val detail: String,
    val dimensions: List<String> = emptyList(),
)

enum class LanguageUseKind { FORMULA, HOST, PREVIOUS, NAMED_CALL, LEXICAL, DYNAMIC }

data class LanguageOccurrence(
    val symbol: LanguageSymbolId?,
    val span: LanguageSpan,
    val kind: LanguageUseKind,
    val inferredType: String? = null,
    val definite: Boolean = true,
)

data class LanguageFormulaContext(
    val ownerId: String,
    val role: String,
    val span: LanguageSpan,
    val dimensions: List<String>,
    val expectedType: String,
    val rowTable: String? = null,
)

data class LanguageCompletion(
    val label: String,
    val replacement: LanguageSpan,
    val insertText: String,
    val kind: String,
    val detail: String,
    val documentation: String? = null,
)

data class LanguageHover(val span: LanguageSpan, val text: String)
data class LanguageTypeEvidence(val span: LanguageSpan, val type: String)

data class LanguageIssue(val reason: String, val source: String? = null)

/** Collections returned by the factory are detached, immutable snapshots. No executable plan escapes. */
class LanguageAnalysis internal constructor(
    val schema: SchemaIdentity,
    val definitions: List<LanguageDefinition>,
    val occurrences: List<LanguageOccurrence>,
    val formulas: List<LanguageFormulaContext>,
    val diagnostics: List<Diagnostic>,
    val types: List<LanguageTypeEvidence>,
    val issues: List<LanguageIssue>,
    private val assistance: Map<LanguageSpan, FormulaAssistance>,
) {
    val complete: Boolean get() = issues.isEmpty() && diagnostics.none {
        it.severity == com.xqiou.mantra.core.Severity.ERROR
    }

    fun symbolAt(source: String, offset: Int): LanguageSymbolId? {
        val uses = occurrences.filter { it.span.source == source && it.span.contains(offset) }
        if (uses.any { !it.definite }) return null
        val symbols = uses.mapNotNull { it.symbol }.distinct()
        if (symbols.size == 1) return symbols.single()
        return definitions.singleOrNull { it.span.source == source && it.span.contains(offset) }?.id
    }

    fun definitionsOf(symbol: LanguageSymbolId): List<LanguageDefinition> = definitions.filter { it.id == symbol }

    fun referencesOf(symbol: LanguageSymbolId, includeDeclaration: Boolean = false): List<LanguageSpan> = (
        occurrences.filter { it.symbol == symbol && it.definite }.map { it.span } +
            if (includeDeclaration) definitionsOf(symbol).map { it.span } else emptyList()
        ).distinct()

    fun complete(source: String, offset: Int): List<LanguageCompletion> = assistance.entries
        .filter { it.key.source == source && offset in it.key.start..it.key.end }
        .minByOrNull { it.key.end - it.key.start }?.value?.complete(offset).orEmpty()

    fun hover(source: String, offset: Int): LanguageHover? {
        val symbol = symbolAt(source, offset)
        val use = occurrences.firstOrNull {
            it.span.source == source && it.span.contains(offset) && it.symbol == symbol
        }
        val definition = symbol?.let { definitionsOf(it).firstOrNull() }
        if (definition != null) {
            return LanguageHover(
                use?.span ?: definition.span,
                listOf(definition.name, definition.detail, use?.inferredType).filterNotNull().joinToString("\n\n"),
            )
        }
        types.filter { it.span.source == source && it.span.contains(offset) }
            .minByOrNull { it.span.end - it.span.start }?.let { return LanguageHover(it.span, it.type) }
        return assistance.entries.filter { it.key.source == source && offset in it.key.start..it.key.end }
            .minByOrNull { it.key.end - it.key.start }?.value?.hover(offset)
    }
}

internal interface FormulaAssistance {
    fun complete(offset: Int): List<LanguageCompletion>
    fun hover(offset: Int): LanguageHover?
}
