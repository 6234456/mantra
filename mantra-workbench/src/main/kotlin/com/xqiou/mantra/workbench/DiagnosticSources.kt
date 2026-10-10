package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.read.SourceText

/** The array index selects an engine finding, never a client-authored filesystem address. */
internal fun WorkspaceCatalog.diagnosticSourceContext(
    caseId: String,
    diagnostic: Int,
    expectedRevision: String,
): WorkspaceCatalog.DocumentResult {
    DiagnosticSources.checkRequest(diagnostic, expectedRevision)
    val resolved = resolve(caseId, scan(), freshDiagnostics = true)
    DiagnosticSources.checkRevision(expectedRevision, resolved.revision)
    val finding = resolved.view.diagnostics.getOrNull(diagnostic)
        ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic was not found")
    val graph = checkNotNull(resolved.graph)
    val key = finding.caseKey?.let(::CanonicalCaseKey) ?: checkNotNull(graph.root)
    val sourceCase = graph.cases[key]
        ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic case does not participate in this graph")
    finding.caseRevision?.let { DiagnosticSources.checkRevision(it, sourceCase.revision) }
    val location = finding.location
        ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic has no source location")
    val source = resolved.caseSources[key]?.get(location.source)
        ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic source is unavailable")
    return WorkspaceCatalog.DocumentResult(
        resolved.revision,
        DiagnosticSources.excerpt(source, location, key.value, sourceCase.revision),
    )
}

/** Bounded plain-text projection shared by live and immutable package documents. */
internal object DiagnosticSources {
    fun checkRequest(diagnostic: Int, expectedRevision: String) {
        if (diagnostic < 0 || !Regex("[0-9a-f]{16}([0-9a-f]{48})?").matches(expectedRevision)) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Diagnostic index and exact revision are required")
        }
    }

    fun checkRevision(expected: String, actual: String) {
        if (expected != actual) {
            throw WorkspaceException(
                WorkspaceProblem.CONFLICT,
                "Diagnostic source revision has changed",
                currentRevision = actual,
            )
        }
    }

    fun excerpt(source: SourceText, location: SourceLocation, case: String, revision: String): Map<String, Any?> {
        fun missing(): Nothing =
            throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic source span is unavailable")
        if (source.name != location.source || source.text.length > 65_536) missing()
        val text = source.text
        val starts = mutableListOf(0)
        text.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
        val lineIndex = location.line - 1
        if (lineIndex !in starts.indices || location.column < 1) missing()
        fun lineEnd(index: Int): Int = (starts.getOrNull(index + 1)?.minus(1) ?: text.length).let {
            if (it > starts[index] && text[it - 1] == '\r') it - 1 else it
        }
        val start = location.startOffset ?: (starts[lineIndex] + location.column - 1)
        if (start !in starts[lineIndex]..lineEnd(lineIndex) ||
            start - starts[lineIndex] + 1 != location.column
        ) {
            missing()
        }
        val pointLength = if (start < lineEnd(lineIndex)) Character.charCount(text.codePointAt(start)) else 0
        val end = location.endOffset ?: (start + pointLength).coerceAtMost(lineEnd(lineIndex))
        if (end !in start..text.length || splitsSurrogate(text, start) || splitsSurrogate(text, end)) missing()
        val first = (lineIndex - 3).coerceAtLeast(0)
        val last = (lineIndex + 3).coerceAtMost(starts.lastIndex)
        var truncated = end > (starts.getOrNull(last + 1) ?: text.length)
        val lines = (first..last).map { index ->
            val lineStart = starts[index]
            val lineEnd = lineEnd(index)
            val focus = (start - lineStart).coerceIn(0, lineEnd - lineStart)
            var from = if (index == lineIndex) (focus - 256).coerceAtLeast(0) else 0
            if (splitsSurrogate(text, lineStart + from)) from--
            var until = (from + 512).coerceAtMost(lineEnd - lineStart)
            if (splitsSurrogate(text, lineStart + until)) until--
            val visibleStart = lineStart + from
            val visibleEnd = lineStart + until
            val highlightStart = start.coerceAtLeast(visibleStart)
            val highlightEnd = end.coerceAtMost(visibleEnd)
            val highlighted = highlightStart < highlightEnd
            truncated = truncated || from > 0 || visibleEnd < lineEnd
            linkedMapOf(
                "number" to index + 1,
                "text" to text.substring(visibleStart, visibleEnd),
                "startColumn" to from + 1,
                "highlightStart" to (highlightStart - visibleStart).takeIf { highlighted },
                "highlightEnd" to (highlightEnd - visibleStart).takeIf { highlighted },
            )
        }
        val position = linkedMapOf<String, Any?>(
            "document" to location.source,
            "line" to location.line,
            "column" to location.column,
        ).apply {
            location.startOffset?.let { put("startOffset", it) }
            location.endOffset?.let { put("endOffset", it) }
        }
        return linkedMapOf(
            "case" to case,
            "revision" to revision,
            "location" to position,
            "lines" to lines,
            "truncated" to truncated,
        )
    }

    private fun splitsSurrogate(text: String, offset: Int): Boolean = offset in 1 until text.length &&
        text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()
}
