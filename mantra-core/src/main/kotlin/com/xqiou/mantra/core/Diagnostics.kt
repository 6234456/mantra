package com.xqiou.mantra.core

/** Position of a construct in a Mantra source document (schema, fragment, case or layout). */
data class SourceLocation(
    val source: String,
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "$source:$line:$column"
}

enum class Severity { ERROR, WARNING, INFO }

/** A stable, user-facing finding. Codes follow the `MANTRA-<AREA>-<DETAIL>` convention. */
data class Diagnostic(
    val severity: Severity,
    val code: String,
    val message: String,
    val location: SourceLocation? = null,
    val nodeId: String? = null,
) {
    override fun toString(): String = buildString {
        append(severity.name.lowercase())
        append(' ')
        append(code)
        location?.let { append(" at ").append(it) }
        nodeId?.let { append(" [").append(it).append(']') }
        append(": ")
        append(message)
    }
}

class MantraException(val diagnostics: List<Diagnostic>) :
    RuntimeException(diagnostics.joinToString("\n"))

/** Collects diagnostics while a document or plan is processed. */
class DiagnosticSink {
    private val items = mutableListOf<Diagnostic>()

    val all: List<Diagnostic> get() = items.toList()
    val hasErrors: Boolean get() = items.any { it.severity == Severity.ERROR }

    fun error(code: String, message: String, location: SourceLocation? = null, nodeId: String? = null) {
        items += Diagnostic(Severity.ERROR, code, message, location, nodeId)
    }

    fun warning(code: String, message: String, location: SourceLocation? = null, nodeId: String? = null) {
        items += Diagnostic(Severity.WARNING, code, message, location, nodeId)
    }

    fun addAll(diagnostics: Collection<Diagnostic>) {
        items += diagnostics
    }

    fun throwIfErrors() {
        if (hasErrors) throw MantraException(items.filter { it.severity == Severity.ERROR })
    }
}
