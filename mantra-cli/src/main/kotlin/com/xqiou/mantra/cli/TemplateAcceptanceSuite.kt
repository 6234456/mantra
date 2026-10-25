package com.xqiou.mantra.cli

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.workbench.TemplateInputText
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate

internal const val TEMPLATE_ACCEPTANCE_FORMAT = "mantra.template-acceptance/1"
internal const val TEMPLATE_ACCEPTANCE_BYTES = 2 * 1024 * 1024

internal data class TemplateAcceptanceSuite(
    val documents: List<AcceptanceDocument>,
    val cases: List<AcceptanceCase>,
    val sha256: String,
)

internal data class AcceptanceDocument(val document: String, val role: String, val sha256: String, val text: String)

internal data class AcceptanceCase(
    val id: String,
    val caseId: String,
    val baseRevision: String,
    val baseRevisions: Map<String, String>,
    val inputs: List<TemplateInputText>,
    val panel: String?,
    val includeZero: Boolean,
    val expect: AcceptanceExpectation?,
)

internal data class AcceptanceExpectation(
    val succeeded: Boolean,
    val validationPassed: Boolean,
    val values: List<AcceptanceValueExpectation>,
    val diagnostics: List<AcceptanceDiagnosticExpectation>?,
)

/** fields distinguishes an omitted optional assertion from one explicitly asserting null. */
internal data class AcceptanceValueExpectation(
    val node: String,
    val present: Boolean,
    val value: Any?,
    val display: String?,
    val active: Boolean?,
    val validationPassed: Boolean?,
    val fields: Set<String>,
)

internal data class AcceptanceDiagnosticExpectation(val code: String, val severity: String, val count: Int)

/** Check both the file-size hint and the bounded read so a concurrently growing file cannot evade the limit. */
internal fun readTemplateAcceptanceSuite(path: Path): TemplateAcceptanceSuite {
    require(Files.size(path) <= TEMPLATE_ACCEPTANCE_BYTES) { "Template acceptance suite exceeds 2 MiB" }
    val bytes = Files.newInputStream(path).use { it.readNBytes(TEMPLATE_ACCEPTANCE_BYTES + 1) }
    return parseTemplateAcceptanceSuite(bytes)
}

/** Strict standalone format. The parsed suite carries the digest of its exact original UTF-8 bytes. */
internal fun parseTemplateAcceptanceSuite(bytes: ByteArray): TemplateAcceptanceSuite {
    require(bytes.size <= TEMPLATE_ACCEPTANCE_BYTES) { "Template acceptance suite exceeds 2 MiB" }
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("Template acceptance suite must be valid UTF-8", error)
    }
    val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    mapper.factory.setStreamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(65_536).build(),
    )
    val root = requireNotNull(mapper.readTree(text)) { "Template acceptance suite must be a JSON object" }
    checkTextBudget(root)
    root.acceptanceFields(setOf("format", "documents", "cases"))
    require(root.acceptanceText("format") == TEMPLATE_ACCEPTANCE_FORMAT) { "Unsupported template acceptance format" }
    val documents = root.acceptanceArray("documents", 1, 2).map { document ->
        document.acceptanceFields(setOf("document", "role", "sha256", "text"))
        AcceptanceDocument(
            document.acceptanceText("document", nonblank = true),
            document.acceptanceText("role").also {
                require(it in setOf("schema", "layout")) { "Candidate document role must be schema or layout" }
            },
            document.acceptanceHash("sha256"),
            document.acceptanceText("text"),
        )
    }
    require(documents.count { it.role == "schema" } == 1 && documents.count { it.role == "layout" } <= 1) {
        "Candidate documents require exactly one schema and at most one layout"
    }
    acceptanceUnique(documents.map { it.document }, "candidate document")
    val cases = root.acceptanceArray("cases", 1, 32).map(::parseAcceptanceCase)
    acceptanceUnique(cases.map { it.id }, "case id")
    require(cases.sumOf { it.expect?.values?.size ?: 0 } <= 2048) { "Suite exceeds 2048 value assertions" }
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return TemplateAcceptanceSuite(documents, cases, sha256)
}

private fun parseAcceptanceCase(node: JsonNode): AcceptanceCase {
    node.acceptanceFields(
        setOf("id", "case", "baseRevision", "baseRevisions", "inputs", "expect"),
        setOf("panel", "includeZero"),
    )
    val id = node.acceptanceText("id", nonblank = true)
    require(id.length <= 120) { "Case id exceeds 120 characters" }
    val revisions = node["baseRevisions"]
    require(revisions.isObject && !revisions.isEmpty) { "baseRevisions must be a nonempty complete source map" }
    val baseRevisions = revisions.fields().asSequence().associate { (name, hash) ->
        require(name.isNotBlank() && hash.isTextual && ACCEPTANCE_HASH.matches(hash.textValue())) {
            "Source revisions must map nonempty document identities to SHA-256 strings"
        }
        name to hash.textValue()
    }
    val inputs = node.acceptanceArray("inputs", 0, 100).map { input ->
        input.acceptanceFields(setOf("node", "text"))
        TemplateInputText(input.acceptanceText("node", nonblank = true), input.acceptanceText("text"))
    }
    acceptanceUnique(inputs.map { it.node }, "input node")
    val expected = node["expect"].let { if (it.isNull) null else parseAcceptanceExpectation(it) }
    return AcceptanceCase(
        id,
        node.acceptanceText("case", nonblank = true),
        node.acceptanceHash("baseRevision"),
        baseRevisions,
        inputs,
        if (node.has("panel")) node.acceptanceText("panel", nonblank = true) else null,
        if (node.has("includeZero")) node.acceptanceBoolean("includeZero") else false,
        expected,
    )
}

private fun parseAcceptanceExpectation(node: JsonNode): AcceptanceExpectation {
    node.acceptanceFields(setOf("succeeded", "validationPassed", "values"), setOf("diagnostics"))
    val values = node.acceptanceArray("values", 0, 256).map { value ->
        value.acceptanceFields(
            setOf("node", "present"),
            setOf("value", "display", "active", "validationPassed"),
        )
        val present = value.acceptanceBoolean("present")
        val fields = value.fieldNames().asSequence().toSet()
        if (present) {
            require(value.has("value")) { "A present value assertion must explicitly declare value, including null" }
        } else {
            require(fields == setOf("node", "present")) {
                "Absent values cannot assert value, display, active or validation"
            }
        }
        AcceptanceValueExpectation(
            value.acceptanceText("node", nonblank = true),
            present,
            if (present) acceptanceWireValue(value["value"]) else null,
            if (value.has("display")) value.acceptanceText("display") else null,
            if (value.has("active")) value.acceptanceBoolean("active") else null,
            if (value.has("validationPassed") && !value["validationPassed"].isNull) {
                value.acceptanceBoolean("validationPassed")
            } else {
                null
            },
            fields,
        )
    }
    acceptanceUnique(values.map { it.node }, "value assertion node")
    val diagnostics = if (node.has("diagnostics")) {
        node.acceptanceArray("diagnostics", 0, 2048).map { diagnostic ->
            diagnostic.acceptanceFields(setOf("code", "severity", "count"))
            val count = diagnostic["count"]
            require(count.isIntegralNumber && count.canConvertToInt() && count.intValue() in 1..10000) {
                "Diagnostic count must be an integer from 1 through 10000"
            }
            AcceptanceDiagnosticExpectation(
                diagnostic.acceptanceText("code", nonblank = true),
                diagnostic.acceptanceText("severity").also {
                    require(it in setOf("error", "warning", "info")) { "Unknown diagnostic severity" }
                },
                count.intValue(),
            )
        }.also { acceptanceUnique(it.map { diagnostic -> diagnostic.code to diagnostic.severity }, "diagnostic group") }
    } else {
        null
    }
    return AcceptanceExpectation(
        node.acceptanceBoolean("succeeded"),
        node.acceptanceBoolean("validationPassed"),
        values,
        diagnostics,
    )
}

/** Accept exact Workbench value shapes; raw JSON numbers would silently change the engine's type contract. */
private fun acceptanceWireValue(node: JsonNode): Any? = when {
    node.isNull -> null
    node.isBoolean -> node.booleanValue()
    node.isTextual -> node.textValue()
    node.isArray -> node.map(::acceptanceWireValue)
    node.isObject -> {
        require(node.size() == 1) { "Encoded values must have exactly one type key" }
        val key = node.fieldNames().next()
        when (key) {
            "n" -> {
                val raw = node.acceptanceText("n")
                require(ACCEPTANCE_DECIMAL.matches(raw)) { "Decimal values must use canonical decimal text" }
                raw.toBigDecimal()
                mapOf("n" to raw)
            }
            "kw" -> mapOf("kw" to node.acceptanceText("kw"))
            "date" -> {
                val raw = node.acceptanceText("date")
                require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(raw)) { "Date must use YYYY-MM-DD" }
                require(runCatching { LocalDate.parse(raw) }.isSuccess) { "Date must be a valid calendar date" }
                mapOf("date" to raw)
            }
            "map" -> {
                require(node["map"].isArray) { "Ordered map must contain an array of key/value pairs" }
                val pairs = node["map"].map { pair ->
                    require(pair.isArray && pair.size() == 2) {
                        "Ordered map entries must contain exactly a key and value"
                    }
                    listOf(acceptanceWireValue(pair[0]), acceptanceWireValue(pair[1]))
                }
                mapOf("map" to pairs)
            }
            else -> throw IllegalArgumentException("Unknown exact value type key")
        }
    }
    else -> throw IllegalArgumentException("Engine values cannot be raw JSON numbers")
}

/** Decimals compare by their exact mathematical value; all other wire types and ordered children remain distinct. */
internal fun exactAcceptanceValueEquals(expected: Any?, actual: Any?): Boolean {
    if (expected is Map<*, *> && actual is Map<*, *>) {
        if (expected.keys != actual.keys) return false
        if (expected.keys == setOf("n")) {
            val left = expected["n"] as? String ?: return false
            val right = actual["n"] as? String ?: return false
            return runCatching { BigDecimal(left).compareTo(BigDecimal(right)) == 0 }.getOrDefault(false)
        }
        return expected.keys.all { key -> exactAcceptanceValueEquals(expected[key], actual[key]) }
    }
    if (expected is List<*> && actual is List<*>) {
        return expected.size == actual.size && expected.indices.all {
            exactAcceptanceValueEquals(expected[it], actual[it])
        }
    }
    return expected == actual
}

private fun JsonNode.acceptanceFields(required: Set<String>, optional: Set<String> = emptySet()) {
    require(isObject) { "Expected a JSON object" }
    val fields = fieldNames().asSequence().toSet()
    require(fields.containsAll(required) && fields.all { it in required || it in optional }) {
        "Missing or unknown template acceptance fields"
    }
}

private fun JsonNode.acceptanceText(key: String, nonblank: Boolean = false): String {
    val value = this[key]
    require(value?.isTextual == true) { "$key must be text" }
    return value.textValue().also { require(!nonblank || it.isNotBlank()) { "$key must not be blank" } }
}

private fun JsonNode.acceptanceBoolean(key: String): Boolean {
    val value = this[key]
    require(value?.isBoolean == true) { "$key must be boolean" }
    return value.booleanValue()
}

private fun JsonNode.acceptanceHash(key: String): String = acceptanceText(key).also {
    require(ACCEPTANCE_HASH.matches(it)) { "$key must be a lowercase SHA-256 string" }
}

private fun JsonNode.acceptanceArray(key: String, minimum: Int, maximum: Int): List<JsonNode> {
    val value = this[key]
    require(value?.isArray == true && value.size() in minimum..maximum) { "$key array exceeds its allowed item count" }
    return value.toList()
}

private fun acceptanceUnique(values: List<Any?>, label: String) {
    require(values.distinct().size == values.size) { "Duplicate $label" }
}

private fun checkTextBudget(node: JsonNode) {
    if (node.isTextual) require(node.textValue().length <= 65_536) { "Text exceeds 65536 UTF-16 code units" }
    if (node.isObject) {
        node.fields().forEach { (name, child) ->
            require(name.length <= 65_536) { "Field name exceeds 65536 UTF-16 code units" }
            checkTextBudget(child)
        }
    } else if (node.isArray) {
        node.forEach(::checkTextBudget)
    }
}

private val ACCEPTANCE_HASH = Regex("[a-f0-9]{64}")
private val ACCEPTANCE_DECIMAL = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
