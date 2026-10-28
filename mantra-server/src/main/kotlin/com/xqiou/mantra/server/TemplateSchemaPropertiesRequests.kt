package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.xqiou.mantra.workbench.TemplateSchemaPropertiesRequest
import com.xqiou.mantra.workbench.TemplateSchemaPropertyOperation
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The new source-only endpoint has a strict standalone request shape and preserves raw user text. */
internal fun WorkbenchRequests.parseTemplateSchemaProperties(body: ByteArray): TemplateSchemaPropertiesRequest {
    fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
    fun tooLarge(): Nothing =
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Schema properties exceed the source budget")
    if (body.size > 1_048_576) tooLarge()
    val root = try {
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
        json.readTree(decoded) ?: bad("Schema properties body is required")
    } catch (error: WorkspaceException) {
        throw error
    } catch (_: Exception) {
        bad("Malformed schema properties UTF-8 or JSON")
    }
    fun fields(node: JsonNode, names: Set<String>) {
        if (!node.isObject || names.any { !node.has(it) } ||
            node.fieldNames().asSequence().any { it !in names }
        ) {
            bad("Unexpected schema properties fields")
        }
    }
    fun text(node: JsonNode, key: String): String = node[key]?.takeIf(JsonNode::isTextual)?.textValue()
        ?: bad("$key must be text")
    fun hash(node: JsonNode, key: String): String = text(node, key).also {
        if (!Regex("[0-9a-f]{64}").matches(it)) bad("$key must be a lowercase SHA-256 string")
    }
    fields(root, setOf("baseRevision", "baseRevisions", "draftSequence", "handle", "text", "operations"))
    val revision = hash(root, "baseRevision")
    val handle = hash(root, "handle")
    val sourceText = text(root, "text")
    if (sourceText.length > 65_536) tooLarge()
    val revisions = root["baseRevisions"].takeIf { it.isObject && !it.isEmpty }
        ?: bad("baseRevisions must be a nonempty object")
    val sourceRevisions = revisions.fields().asSequence().associate { (name, value) ->
        if (name.isBlank() || !value.isTextual || !Regex("[0-9a-f]{64}").matches(value.textValue())) {
            bad("Source revisions must be lowercase SHA-256 strings")
        }
        name to value.textValue()
    }
    val sequence = root["draftSequence"].takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        ?.takeIf { it in 0..9_007_199_254_740_991L } ?: bad("draftSequence must be a safe nonnegative integer")
    val operations = root["operations"].takeIf { it.isArray && it.size() <= 32 }
        ?: bad("operations must be an array of at most 32 edits")
    val edits = operations.map { operation ->
        if (!operation.isObject) bad("A schema property operation must be an object")
        val op = text(operation, "op")
        fields(
            operation,
            when (op) {
                "setText" -> setOf("op", "owner", "text")
                "setClasses" -> setOf("op", "owner", "classes")
                "unset" -> setOf("op", "owner")
                else -> bad("Unknown schema property operation")
            },
        )
        val owner = hash(operation, "owner")
        val rawText = if (op == "setText") {
            text(operation, "text").also { if (it.length > 65_536) tooLarge() }
        } else {
            null
        }
        val classes = if (op == "setClasses") {
            val names = operation["classes"].takeIf { it.isArray && it.size() <= 64 }
                ?: bad("classes must be an array of at most 64 simple class names")
            names.map { name ->
                if (!name.isTextual || !Regex("[a-z][a-z0-9-]*").matches(name.textValue())) {
                    bad("Class names must match [a-z][a-z0-9-]* without a colon")
                }
                name.textValue().also { if (it.length > 65_536) tooLarge() }
            }.also { if (it.distinct().size != it.size) bad("Class names must be unique") }
        } else {
            null
        }
        TemplateSchemaPropertyOperation(op, owner, rawText, classes)
    }
    if (edits.map { it.owner }.distinct().size != edits.size) bad("An owner may be edited only once per request")
    return TemplateSchemaPropertiesRequest(revision, sourceRevisions, sequence, handle, sourceText, edits)
}
