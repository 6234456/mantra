package com.xqiou.mantra.core

/** Position of a construct in a Mantra source document (schema, fragment, case or layout). */
data class SourceLocation(
    val source: String,
    val line: Int,
    val column: Int,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
) {
    override fun toString(): String = "$source:$line:$column"
}

enum class Severity { ERROR, WARNING, INFO }

/** Technical failures and business findings have different effects on calculation success. */
enum class DiagnosticCategory { PARSING, STRUCTURAL, EVALUATION, BUSINESS }

/** A stable, user-facing finding. Codes follow the `MANTRA-<AREA>-<DETAIL>` convention. */
data class Diagnostic(
    val severity: Severity,
    val code: String,
    val message: String,
    val location: SourceLocation? = null,
    val nodeId: String? = null,
    val coord: List<String> = emptyList(),
    val category: DiagnosticCategory = DiagnosticCategory.STRUCTURAL,
    /** Zero-based row index for a table input finding. */
    val rowIndex: Int? = null,
    val column: String? = null,
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

class MantraException(val diagnostics: List<Diagnostic>) : RuntimeException(diagnostics.joinToString("\n"))

/** Collects diagnostics while a document or plan is processed. */
class DiagnosticSink {
    private val items = mutableListOf<Diagnostic>()
    private val owners = mutableListOf<Any?>()
    private var owner: Any? = null

    internal fun <T> scoped(owner: Any, action: () -> T): T {
        val previous = this.owner
        this.owner = owner
        return try {
            action()
        } finally {
            this.owner = previous
        }
    }

    internal fun removeOwned(removed: Set<Any>) {
        for (index in items.indices.reversed()) {
            if (owners[index] in removed) {
                items.removeAt(index)
                owners.removeAt(index)
            }
        }
    }

    internal fun replaceUnowned(diagnostics: Collection<Diagnostic>) {
        for (index in items.indices.reversed()) {
            if (owners[index] == null) {
                items.removeAt(index)
                owners.removeAt(index)
            }
        }
        addAll(diagnostics)
    }

    val all: List<Diagnostic> get() = items.toList()
    val hasErrors: Boolean get() = items.any { it.severity == Severity.ERROR }

    fun error(
        code: String,
        message: String,
        location: SourceLocation? = null,
        nodeId: String? = null,
        coord: List<String> = emptyList(),
        category: DiagnosticCategory = DiagnosticCategory.STRUCTURAL,
        rowIndex: Int? = null,
        column: String? = null,
    ) {
        items += Diagnostic(Severity.ERROR, code, message, location, nodeId, coord, category, rowIndex, column)
        owners += owner
    }

    fun warning(
        code: String,
        message: String,
        location: SourceLocation? = null,
        nodeId: String? = null,
        coord: List<String> = emptyList(),
        category: DiagnosticCategory = DiagnosticCategory.STRUCTURAL,
        rowIndex: Int? = null,
        column: String? = null,
    ) {
        items += Diagnostic(Severity.WARNING, code, message, location, nodeId, coord, category, rowIndex, column)
        owners += owner
    }

    fun addAll(diagnostics: Collection<Diagnostic>) {
        items += diagnostics
        owners.addAll(diagnostics.map { owner })
    }

    fun throwIfErrors() {
        if (hasErrors) throw MantraException(items.filter { it.severity == Severity.ERROR })
    }

    /** Runtime scheduling rejects malformed domains/cycles without promoting business or formula findings. */
    internal fun throwIfStructuralErrors() {
        val structural = items.filter {
            it.severity == Severity.ERROR &&
                it.category in setOf(DiagnosticCategory.PARSING, DiagnosticCategory.STRUCTURAL)
        }
        if (structural.isNotEmpty()) throw MantraException(structural)
    }
}
