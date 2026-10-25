package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.xqiou.mantra.workbench.TemplateLayoutOperation
import com.xqiou.mantra.workbench.TemplateLayoutSettingsRequest
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem

/** Keeps authored values as text until the root-layout setting's type is known. */
internal fun WorkbenchRequests.parseTemplateLayoutSettings(body: ByteArray): TemplateLayoutSettingsRequest {
    fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
    val root = try {
        json.readTree(body) ?: bad("Template layout settings body is required")
    } catch (error: WorkspaceException) {
        throw error
    } catch (_: Exception) {
        bad("Malformed template layout settings JSON")
    }
    fun fields(node: JsonNode, names: Set<String>) {
        if (!node.isObject || names.any { !node.has(it) } ||
            node.fieldNames().asSequence().any { it !in names }
        ) {
            bad("Unexpected template layout settings fields")
        }
    }
    fun text(node: JsonNode, key: String): String = node[key]?.takeIf(JsonNode::isTextual)?.textValue()
        ?: bad("$key must be text")
    fun hash(node: JsonNode, key: String): String = text(node, key).also {
        if (!Regex("[0-9a-f]{64}").matches(it)) bad("$key must be a 64 character hexadecimal revision")
    }
    fields(root, setOf("baseRevision", "baseRevisions", "draftSequence", "handle", "text", "operations"))
    val revision = hash(root, "baseRevision")
    val handle = hash(root, "handle")
    val sourceText = text(root, "text")
    val revisions = root["baseRevisions"].takeIf(JsonNode::isObject) ?: bad("baseRevisions must be an object")
    val sourceRevisions = revisions.fields().asSequence().associate { (name, value) ->
        if (name.isBlank() || !value.isTextual || !Regex("[0-9a-f]{64}").matches(value.textValue())) {
            bad("Source revisions must be hexadecimal digests")
        }
        name to value.textValue()
    }
    val sequence = root["draftSequence"].takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        ?.takeIf { it in 0..9_007_199_254_740_991L } ?: bad("draftSequence must be a safe nonnegative integer")
    val operations = root["operations"].takeIf { it.isArray && it.size() <= 16 }
        ?: bad("operations must be an array of at most 16 settings")
    val edits = operations.map {
        if (!it.isObject) bad("A layout operation must be an object")
        val operation = text(it, "op")
        fields(
            it,
            when (operation) {
                "set" -> setOf("op", "property", "text")
                "unset" -> setOf("op", "property")
                else -> bad("Unknown layout settings operation")
            },
        )
        val property = text(it, "property").takeIf(String::isNotBlank) ?: bad("property must be nonempty")
        TemplateLayoutOperation(operation, property, if (operation == "set") text(it, "text") else null)
    }
    if (edits.map { it.property }.distinct().size != edits.size) bad("A setting may be edited only once per request")
    if (sourceText.length > 65_536 || edits.any { it.text?.length?.let { length -> length > 65_536 } == true } ||
        (listOf(sourceText) + edits.mapNotNull { it.text }).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } >
        1_048_576
    ) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Layout settings source exceeds its text budget")
    }
    return TemplateLayoutSettingsRequest(revision, sourceRevisions, sequence, handle, sourceText, edits)
}
