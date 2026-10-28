package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.xqiou.mantra.workbench.ExplainAddress
import com.xqiou.mantra.workbench.TemplateInputText
import com.xqiou.mantra.workbench.TemplatePreviewRequest
import com.xqiou.mantra.workbench.TemplateSourceEdit
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem

/** Strict shape parsing leaves input text untouched until the candidate schema and locale are loaded. */
internal fun WorkbenchRequests.parseTemplatePreview(body: ByteArray): TemplatePreviewRequest {
    fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
    val root = try {
        json.readTree(body) ?: bad("Template preview body is required")
    } catch (error: WorkspaceException) {
        throw error
    } catch (_: Exception) {
        bad("Malformed template preview JSON")
    }
    fun fields(node: JsonNode, required: Set<String>, optional: Set<String> = emptySet()) {
        if (!node.isObject || required.any { !node.has(it) } ||
            node.fieldNames().asSequence().any { it !in required && it !in optional }
        ) {
            bad("Unexpected template preview fields")
        }
    }
    fun text(node: JsonNode, key: String): String = node[key]?.takeIf(JsonNode::isTextual)?.textValue()
        ?: bad("$key must be text")
    fun hash(node: JsonNode, key: String): String = text(node, key).also {
        if (!Regex("[0-9a-f]{64}").matches(it)) bad("$key must be a 64 character hexadecimal revision")
    }
    fields(
        root,
        setOf("baseRevision", "baseRevisions", "draftSequence", "documents", "inputs"),
        setOf("panel", "includeZero", "explain"),
    )
    val revision = hash(root, "baseRevision")
    val revisions = root["baseRevisions"].takeIf(JsonNode::isObject) ?: bad("baseRevisions must be an object")
    val sourceRevisions = revisions.fields().asSequence().associate { (name, value) ->
        if (name.isBlank() || !value.isTextual || !Regex("[0-9a-f]{64}").matches(value.textValue())) {
            bad("Source revisions must be hexadecimal digests")
        }
        name to value.textValue()
    }
    val sequence = root["draftSequence"].takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        ?.takeIf { it in 0..9_007_199_254_740_991L } ?: bad("draftSequence must be a safe nonnegative integer")
    val documents = root["documents"].takeIf { it.isArray && it.size() <= 32 }
        ?: bad("documents must be an array of at most 32 edits")
    val changes = documents.map {
        fields(it, setOf("handle", "text"))
        TemplateSourceEdit(hash(it, "handle"), text(it, "text"))
    }
    val inputs = root["inputs"].takeIf { it.isArray && it.size() <= 100 }
        ?: bad("inputs must be an array of at most 100 scalar values")
    val values = inputs.map {
        fields(it, setOf("node", "text"))
        TemplateInputText(text(it, "node").takeIf(String::isNotBlank) ?: bad("node must be nonempty"), text(it, "text"))
    }
    val panel = root.get("panel")?.let {
        text(root, "panel").takeIf(String::isNotBlank) ?: bad("panel must be nonempty")
    }
    val includeZero = root.get("includeZero")?.let {
        it.takeIf(JsonNode::isBoolean)?.booleanValue() ?: bad("includeZero must be boolean")
    } ?: false
    val explain = root.get("explain")?.let {
        fields(it, setOf("node"), setOf("coord"))
        val coord = it.get("coord")?.let { coordinates ->
            if (!coordinates.isArray || coordinates.size() > 8 || coordinates.any { !it.isTextual }) {
                bad("Explain coordinates must be an array of at most 8 strings")
            }
            coordinates.map(JsonNode::textValue)
        }.orEmpty()
        ExplainAddress(text(it, "node").takeIf(String::isNotBlank) ?: bad("Explain node must be nonempty"), coord)
    }
    return TemplatePreviewRequest(revision, sourceRevisions, sequence, changes, values, panel, includeZero, explain)
}
