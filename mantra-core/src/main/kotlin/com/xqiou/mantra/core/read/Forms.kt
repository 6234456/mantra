package com.xqiou.mantra.core.read

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.Formula
import com.xqiou.mantra.core.model.Value
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormAtomKind
import com.xqiou.normein.dsl.form.DslFormLiteral
import com.xqiou.normein.dsl.form.DslFormLiterals
import com.xqiou.normein.dsl.form.DslFormReadResult
import com.xqiou.normein.dsl.form.DslFormReader
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.math.BigDecimal

/** A Mantra source document: one root form read with the public Normein form reader. */
class SourceText(
    val name: String,
    val text: String,
    /** Resolver-specific base used to resolve relative `include` paths. */
    val base: String? = null,
)

/** Resolves `include` paths and schema references relative to the including document. */
fun interface SourceResolver {
    fun resolve(path: String, relativeTo: SourceText?): SourceText?
}

class Document(val source: SourceText, val root: DslForm) {
    fun location(form: DslForm): SourceLocation =
        SourceLocation(source.name, form.span.line, form.span.column, form.span.startOffset, form.span.endOffset)

    fun slice(form: DslForm): String = source.text.substring(form.span.startOffset, form.span.endOffset)

    fun formula(form: DslForm): Formula = Formula(slice(form), location(form), form)

    companion object {
        private val reader = DslFormReader()

        fun read(source: SourceText, sink: DiagnosticSink): Document? =
            when (val result = reader.readDocument(source.text, source.name)) {
                is DslFormReadResult.Success -> Document(source, result.document.root)
                is DslFormReadResult.Failure -> {
                    result.diagnostics.forEach { diagnostic ->
                        sink.error(
                            "MANTRA-READ-SYNTAX",
                            "${diagnostic.code}: ${diagnostic.message}",
                            diagnostic.span?.let {
                                SourceLocation(source.name, it.line, it.column, it.startOffset, it.endOffset)
                            },
                            category = DiagnosticCategory.PARSING,
                        )
                    }
                    null
                }
            }
    }
}

private val DslForm.literalKind: DslFormLiteral?
    get() = (this as? DslForm.Atom)
        ?.takeIf { it.kind == DslFormAtomKind.SYMBOL }
        ?.let(DslFormLiterals::classify)

val DslForm.symbol: String?
    get() = when (val literal = literalKind) {
        is DslFormLiteral.Symbol -> literal.value
        is DslFormLiteral.Boolean, DslFormLiteral.Nil -> (this as DslForm.Atom).sourceText
        else -> null
    }

val DslForm.keyword: String?
    get() = (literalKind as? DslFormLiteral.Keyword)?.let { keyword ->
        (keyword.namespace?.let { "$it/" } ?: "") + keyword.name
    }

val DslForm.string: String?
    get() = (this as? DslForm.Atom)?.takeIf { it.kind == DslFormAtomKind.STRING }?.value

val DslForm.number: BigDecimal?
    get() = (literalKind as? DslFormLiteral.Number)?.value

fun DslForm.isSequence(kind: DslFormSequenceKind): Boolean = this is DslForm.Sequence && this.kind == kind

val DslForm.listHead: String?
    get() = (this as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.LIST }?.values?.firstOrNull()?.symbol

/**
 * Converts a data form (numbers, strings, keywords, booleans, nil, vectors, maps) into a [Value].
 * Symbols and code forms are rejected: data positions never evaluate.
 */
fun Document.literal(form: DslForm, sink: DiagnosticSink, what: String, symbolsAsText: Boolean = false): Value? {
    (form.literalKind as? DslFormLiteral.Failure)?.let { failure ->
        sink.error(
            "MANTRA-READ-LITERAL",
            "${failure.diagnostic.code}: ${failure.diagnostic.message}",
            location(form),
            category = DiagnosticCategory.PARSING,
        )
        return null
    }
    form.number?.let { return Value.Num(it) }
    form.string?.let { return Value.Text(it) }
    form.keyword?.let { return Value.Kw(it) }
    when (form.symbol) {
        "true" -> return Value.Bool(true)
        "false" -> return Value.Bool(false)
        "nil" -> return Value.Nil
    }
    if (symbolsAsText) form.symbol?.let { return Value.Text(it) }
    if (form is DslForm.Sequence) {
        when (form.kind) {
            DslFormSequenceKind.VECTOR -> {
                val items = form.values.map { literal(it, sink, what, symbolsAsText) ?: return null }
                return Value.Vec(items)
            }
            DslFormSequenceKind.MAP -> {
                if (form.values.size % 2 != 0) {
                    sink.error("MANTRA-READ-MAP", "Map literal in $what has an odd number of forms", location(form))
                    return null
                }
                val entries = linkedMapOf<Value, Value>()
                form.values.chunked(2).forEach { (k, v) ->
                    val key = literal(k, sink, what, symbolsAsText) ?: return null
                    val value = literal(v, sink, what, symbolsAsText) ?: return null
                    if (entries.put(key, value) != null) {
                        sink.error("MANTRA-READ-DUPLICATE-KEY", "Duplicate key $key in $what", location(k))
                    }
                }
                return Value.MapV(entries)
            }
            else -> Unit
        }
    }
    sink.error("MANTRA-READ-LITERAL", "Expected a literal value in $what, found `${slice(form)}`", location(form))
    return null
}

/** Reads an options map with keyword keys. Values stay forms so callers decide how to read them. */
fun Document.options(form: DslForm?, sink: DiagnosticSink, what: String): Map<String, DslForm> {
    if (form == null) return emptyMap()
    if (!form.isSequence(DslFormSequenceKind.MAP)) {
        sink.error("MANTRA-READ-OPTIONS", "Expected an options map for $what", location(form))
        return emptyMap()
    }
    val values = (form as DslForm.Sequence).values
    if (values.size % 2 != 0) {
        sink.error("MANTRA-READ-OPTIONS", "Options map for $what has an odd number of forms", location(form))
        return emptyMap()
    }
    val result = linkedMapOf<String, DslForm>()
    values.chunked(2).forEach { (k, v) ->
        val key = k.keyword
        if (key == null) {
            sink.error("MANTRA-READ-OPTIONS", "Option keys must be keywords in $what", location(k))
        } else if (result.put(key, v) != null) {
            sink.error("MANTRA-READ-DUPLICATE-KEY", "Duplicate option :$key in $what", location(k))
        }
    }
    return result
}

private val IDENTIFIER = Regex("""[a-zA-Z][a-zA-Z0-9_\-]*[?!*]?""")

fun isIdentifier(text: String): Boolean = IDENTIFIER.matches(text)
