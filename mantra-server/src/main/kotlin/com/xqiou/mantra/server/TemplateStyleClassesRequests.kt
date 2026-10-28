package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.xqiou.mantra.workbench.TemplateStyleClassOperation
import com.xqiou.mantra.workbench.TemplateStyleClassesRequest
import com.xqiou.mantra.workbench.TemplateStyleSpec
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem

/** Validates finite declarations while preserving explicit defaults independently from omission. */
internal fun WorkbenchRequests.parseTemplateStyleClasses(body: ByteArray): TemplateStyleClassesRequest {
    fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
    val root = try {
        json.readTree(body) ?: bad("Template style classes body is required")
    } catch (error: WorkspaceException) {
        throw error
    } catch (_: Exception) {
        bad("Malformed template style classes JSON")
    }
    fun fields(node: JsonNode, names: Set<String>) {
        if (!node.isObject || names.any { !node.has(it) } ||
            node.fieldNames().asSequence().any { it !in names }
        ) {
            bad("Unexpected template style classes fields")
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
    val revisions = root["baseRevisions"].takeIf { it.isObject && !it.isEmpty }
        ?: bad("baseRevisions must be a nonempty object")
    val sourceRevisions = revisions.fields().asSequence().associate { (name, value) ->
        if (name.isBlank() || !value.isTextual || !Regex("[0-9a-f]{64}").matches(value.textValue())) {
            bad("Source revisions must be hexadecimal digests")
        }
        name to value.textValue()
    }
    val sequence = root["draftSequence"].takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        ?.takeIf { it in 0..9_007_199_254_740_991L } ?: bad("draftSequence must be a safe nonnegative integer")
    val operations = root["operations"].takeIf { it.isArray && it.size() <= 16 }
        ?: bad("operations must be an array of at most 16 classes")
    val edits = operations.map {
        if (!it.isObject) bad("A style class operation must be an object")
        val operation = text(it, "op")
        fields(
            it,
            when (operation) {
                "upsert" -> setOf("op", "name", "style")
                "remove" -> setOf("op", "name")
                else -> bad("Unknown style class operation")
            },
        )
        val name = text(it, "name").takeIf { value -> Regex("[a-z][a-z0-9-]*").matches(value) }
            ?: bad("Class names must match [a-z][a-z0-9-]* without a colon")
        val style = if (operation == "upsert") {
            val declarations = it["style"]
            fields(declarations, setOf("weight", "tone", "fill"))
            fun choice(key: String, choices: Set<String>): String? {
                val value = declarations[key]
                if (value.isNull) return null
                return value.takeIf { form -> form.isTextual && form.textValue() in choices }?.textValue()
                    ?: bad("$key is not a supported style keyword or null")
            }
            TemplateStyleSpec(
                choice("weight", setOf("normal", "bold")),
                choice("tone", setOf("default", "muted", "accent")),
                choice("fill", setOf("none", "subtle", "accent")),
            )
        } else {
            null
        }
        TemplateStyleClassOperation(operation, name, style)
    }
    if (edits.map { it.name }.distinct().size != edits.size) bad("A class may be edited only once per request")
    if (sourceText.length > 65_536) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Style class source exceeds its text budget")
    }
    return TemplateStyleClassesRequest(revision, sourceRevisions, sequence, handle, sourceText, edits)
}
