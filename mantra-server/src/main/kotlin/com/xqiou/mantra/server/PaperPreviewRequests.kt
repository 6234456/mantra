package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.xqiou.mantra.workbench.CaseTextEditor
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem

internal data class PaperPreviewRequest(
    val revision: String,
    val operations: List<CaseTextEditor.Operation>,
    val draftSequence: Long,
    val panel: String?,
    val includeZero: Boolean,
)

internal fun WorkbenchRequests.parsePaperPreview(caseId: String, body: ByteArray): PaperPreviewRequest {
    fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
    val root = try {
        json.readTree(body) ?: bad("Preview body is required")
    } catch (error: WorkspaceException) {
        throw error
    } catch (_: Exception) {
        bad("Malformed preview JSON")
    }
    val allowed = setOf("baseRevision", "operations", "draftSequence", "panel", "includeZero")
    if (!root.isObject || root.fieldNames().asSequence().any { it !in allowed } ||
        listOf("baseRevision", "operations", "draftSequence").any { !root.has(it) }
    ) {
        bad("Unexpected preview fields")
    }
    val sequence = root["draftSequence"]?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        ?.takeIf { it in 0..9_007_199_254_740_991L } ?: bad("draftSequence must be a safe nonnegative integer")
    val panel = root.get("panel")?.let {
        it.takeIf(JsonNode::isTextual)?.textValue()?.takeIf(String::isNotBlank)
            ?: bad("panel must be nonempty text")
    }
    val includeZero = root.get("includeZero")?.let {
        it.takeIf(JsonNode::isBoolean)?.booleanValue() ?: bad("includeZero must be boolean")
    } ?: false
    val edit = root.deepCopy<ObjectNode>().apply { remove(listOf("draftSequence", "panel", "includeZero")) }
    val (revision, operations) = parseEdits(caseId, json.writeValueAsBytes(edit), false)
    return PaperPreviewRequest(revision, operations, sequence, panel, includeZero)
}
