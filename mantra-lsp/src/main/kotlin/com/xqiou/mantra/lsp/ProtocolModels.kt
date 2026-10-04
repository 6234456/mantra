package com.xqiou.mantra.lsp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.api.language.LanguageSpan

internal class RpcProblem(val code: Int, message: String, val data: Any? = null) : RuntimeException(message)
internal fun JsonNode.text(key: String): String = get(key)?.takeIf { it.isTextual }?.asText()
    ?: throw RpcProblem(-32602, "Expected text field $key")
internal fun JsonNode.integer(key: String): Int =
    get(key)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
        ?: throw RpcProblem(-32602, "Expected integer field $key")
internal fun JsonNode.position(): Position = Position(integer("line"), integer("character"))
internal fun JsonNode.range(): Range = Range(required("start").position(), required("end").position())
internal fun JsonNode.required(key: String): JsonNode = get(key) ?: throw RpcProblem(-32602, "Missing field $key")
internal fun idKey(id: JsonNode): String {
    if (!id.isTextual && !id.isIntegralNumber) throw RpcProblem(-32600, "Request ID must be a string or integer")
    return id.toString()
}
internal fun rangeMap(range: Range): Map<String, Any> = mapOf(
    "start" to mapOf("line" to range.start.line, "character" to range.start.character),
    "end" to mapOf("line" to range.end.line, "character" to range.end.character),
)
internal fun location(span: LanguageSpan, analysis: WorkspaceAnalysis): Map<String, Any> {
    val document = analysis.documents[span.source] ?: throw RpcProblem(-32603, "Source snapshot is absent")
    return mapOf("uri" to document.clientUri, "range" to rangeMap(document.lines.range(span.start, span.end)))
}
internal fun diagnostic(finding: Diagnostic, snapshot: DocumentSnapshot): Map<String, Any> {
    val location = finding.location
    val start = location?.startOffset?.coerceIn(0, snapshot.text.length)
        ?: snapshot.lines.offset(Position((location?.line ?: 1) - 1, (location?.column ?: 1) - 1))
    val end = location?.endOffset?.coerceIn(start, snapshot.text.length) ?: start
    return mapOf(
        "range" to rangeMap(snapshot.lines.range(start, end)),
        "severity" to when (finding.severity) {
            Severity.ERROR -> 1
            Severity.WARNING -> 2
            Severity.INFO -> 3
        },
        "code" to finding.code,
        "source" to "mantra",
        "message" to finding.message,
        "data" to mapOf(
            "category" to finding.category.name,
            "nodeId" to finding.nodeId,
            "spanUnavailable" to (finding.location?.startOffset == null),
        ),
    )
}
