package com.xqiou.mantra.cli

import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateAcceptanceSuiteTest {
    @TempDir lateinit var directory: Path

    private val hash = "a".repeat(64)

    private fun document(
        role: String = "schema",
        name: String = "schema.mantra",
        text: String = "(schema example {})",
    ) = linkedMapOf<String, Any?>("document" to name, "role" to role, "sha256" to hash, "text" to text)

    private fun value(node: String = "answer", expected: Any? = mapOf("n" to "3.75")) =
        linkedMapOf<String, Any?>("node" to node, "present" to true, "value" to expected)

    private fun expected(values: List<Map<String, Any?>> = listOf(value())) = linkedMapOf<String, Any?>(
        "succeeded" to true,
        "validationPassed" to true,
        "values" to values,
    )

    private fun case(id: String = "positive", expectation: Any? = expected()) = linkedMapOf<String, Any?>(
        "id" to id,
        "case" to "case.mantra",
        "baseRevision" to hash,
        "baseRevisions" to mapOf("schema.mantra" to hash, "case.mantra" to hash),
        "inputs" to listOf(mapOf("node" to "amount", "text" to "1,25")),
        "expect" to expectation,
    )

    private fun suite(
        cases: List<Map<String, Any?>> = listOf(case()),
        documents: List<Map<String, Any?>> = listOf(document()),
    ) = linkedMapOf<String, Any?>("format" to TEMPLATE_ACCEPTANCE_FORMAT, "documents" to documents, "cases" to cases)

    private fun parse(tree: Any?): TemplateAcceptanceSuite =
        parseTemplateAcceptanceSuite(WorkbenchJson.write(tree).toByteArray(Charsets.UTF_8))

    private fun parseCase(candidate: Map<String, Any?>): TemplateAcceptanceSuite =
        parse(suite(cases = listOf(candidate)))

    private fun parseValues(vararg values: Map<String, Any?>): TemplateAcceptanceSuite =
        parseCase(case(expectation = expected(values.toList())))

    @Test fun `retains raw inputs exact expected types and the digest of original bytes`() {
        val nested = mapOf(
            "map" to listOf(listOf(mapOf("kw" to "rate"), listOf(mapOf("n" to "9007199254740993.0000000001")))),
        )
        val value = value(expected = nested).apply {
            put("display", "9.007.199.254.740.993,00")
            put("active", false)
            put("validationPassed", null)
        }
        val json = WorkbenchJson.write(suite(cases = listOf(case(expectation = expected(listOf(value))))))
        val bytes = (" \n" + json + "\n").toByteArray(Charsets.UTF_8)
        val result = parseTemplateAcceptanceSuite(bytes)
        assertEquals(nested, result.cases.single().expect!!.values.single().value)
        assertEquals("1,25", result.cases.single().inputs.single().text)
        assertEquals(false, result.cases.single().expect!!.values.single().active)
        assertTrue("validationPassed" in result.cases.single().expect!!.values.single().fields)
        assertNull(result.cases.single().expect!!.values.single().validationPassed)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(digest, result.sha256)
        assertFalse(result.cases.single().includeZero)
        assertNull(result.cases.single().panel)
    }

    @Test fun `capture null expectations stay unvalidated rather than acquiring current results`() {
        val result = parse(suite(cases = listOf(case(expectation = null))))
        assertNull(result.cases.single().expect)
        assertNull(parse(suite()).cases.single().expect!!.diagnostics)
        val explicit = expected().apply { put("diagnostics", emptyList<Any>()) }
        assertEquals(emptyList(), parseCase(case(expectation = explicit)).cases.single().expect!!.diagnostics)
    }

    @Test fun `complete source maps and repeated case paths do not acquire document-count restrictions`() {
        val sources = (1..180).associate { "fragment-$it.mantra" to hash }
        val inputs = listOf(
            mapOf("node" to "zero", "text" to "0"),
            mapOf("node" to "disabled", "text" to "false"),
            mapOf("node" to "blank", "text" to ""),
        )
        val first = case(id = "first").apply {
            put("baseRevisions", sources)
            put("inputs", inputs)
            put("panel", "main")
            put("includeZero", true)
        }
        val result = parse(suite(cases = listOf(first, case(id = "second"))))
        assertEquals(180, result.cases.first().baseRevisions.size)
        assertEquals(listOf("0", "false", ""), result.cases.first().inputs.map { it.text })
        assertTrue(result.cases.first().includeZero)
        assertEquals("main", result.cases.first().panel)
        assertEquals(result.cases.first().caseId, result.cases.last().caseId)
    }

    @Test fun `null value presence and absent scalar entries remain distinct`() {
        val nil = value(expected = null)
        val absent = linkedMapOf<String, Any?>("node" to "hidden", "present" to false)
        val result = parse(suite(cases = listOf(case(expectation = expected(listOf(nil, absent))))))
            .cases.single().expect!!.values
        assertTrue(result[0].present)
        assertNull(result[0].value)
        assertFalse(result[1].present)
        assertFails { parseValues(value().apply { remove("value") }) }
        assertFails { parseValues(absent.apply { put("value", null) }) }
    }

    @Test fun `rejects duplicate decoded keys trailing JSON and invalid UTF-8`() {
        val text = WorkbenchJson.write(suite())
        val duplicate = text.replaceFirst("\"format\":", "\"format\":\"ignored\",\"form\\u0061t\":")
        assertFails { parseTemplateAcceptanceSuite(duplicate.toByteArray()) }
        assertFails { parseTemplateAcceptanceSuite((text + " {}").toByteArray()) }
        assertFails { parseTemplateAcceptanceSuite(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { parseTemplateAcceptanceSuite(byteArrayOf()) }
    }

    @Test fun `rejects unknown or missing fields at each structured level`() {
        assertFails { parse(suite().apply { put("preview", true) }) }
        assertFails { parse(suite(documents = listOf(document().apply { put("handle", hash) }))) }
        assertFails { parse(suite(cases = listOf(case().apply { remove("baseRevisions") }))) }
        assertFails { parse(suite(cases = listOf(case().apply { put("coord", listOf("A")) }))) }
        assertFails { parseCase(case(expectation = expected().apply { put("paper", emptyList<Any>()) })) }
        assertFails { parseValues(value().apply { put("coord", emptyList<Any>()) }) }
    }

    @Test fun `rejects invalid identities roles and duplicate declaration identities`() {
        assertFails { parse(suite(documents = emptyList())) }
        assertFails { parse(suite(documents = listOf(document("layout", "layout.mantra")))) }
        assertFails { parse(suite(documents = listOf(document(), document()))) }
        assertFails { parse(suite(documents = listOf(document(), document("schema", "other.mantra")))) }
        assertFails { parse(suite(documents = listOf(document().apply { put("role", "fragment") }))) }
        assertFails { parse(suite(cases = listOf(case(), case()))) }
        assertFails { parse(suite(cases = listOf(case().apply { put("baseRevision", hash.uppercase()) }))) }
        assertFails { parse(suite(cases = listOf(case().apply { put("baseRevisions", emptyMap<String, String>()) }))) }
        val inputs = listOf(mapOf("node" to "x", "text" to "0"), mapOf("node" to "x", "text" to "false"))
        assertFails { parseCase(case().apply { put("inputs", inputs) }) }
        assertFails { parse(suite(cases = listOf(case(expectation = expected(listOf(value(), value())))))) }
        assertFails { parse(suite(cases = listOf(case().apply { put("panel", " ") }))) }
        assertFails { parse(suite(cases = listOf(case().apply { put("id", "i".repeat(121)) }))) }
    }

    @Test fun `strict engine encodings reject numbers invalid decimals invalid dates and malformed maps`() {
        listOf<Any?>(
            1,
            mapOf("n" to "1e3"),
            mapOf("n" to "01"),
            mapOf("n" to "+1"),
            mapOf("n" to "NaN"),
            mapOf("n" to true),
            mapOf("date" to "2026-02-30"),
            mapOf("date" to "2026-2-01"),
            mapOf("n" to "1", "kw" to "1"),
            mapOf("unknown" to "value"),
            mapOf("map" to listOf(listOf("x"))),
        ).forEach { invalid ->
            assertFails(invalid.toString()) { parseValues(value(expected = invalid)) }
        }
        val dates = parseValues(value(expected = mapOf("date" to "2024-02-29")))
        assertEquals(mapOf("date" to "2024-02-29"), dates.cases.single().expect!!.values.single().value)
        assertFailsWith<IllegalArgumentException> {
            parseValues(value(expected = mapOf("date" to "2026-02-30")))
        }
    }

    @Test fun `diagnostic expectations preserve exact grouped counts and reject ambiguous groups`() {
        fun expectation(groups: List<Map<String, Any?>>) = expected().apply { put("diagnostics", groups) }
        val group = mapOf<String, Any?>("code" to "MANTRA-CHECK-FAILED", "severity" to "warning", "count" to 2)
        val result = parse(suite(cases = listOf(case(expectation = expectation(listOf(group))))))
        assertEquals(
            AcceptanceDiagnosticExpectation("MANTRA-CHECK-FAILED", "warning", 2),
            result.cases.single().expect!!.diagnostics!!.single(),
        )
        assertFails { parse(suite(cases = listOf(case(expectation = expectation(listOf(group, group)))))) }
        listOf(0, -1, 10001, "1", true).forEach { count ->
            assertFails { parseCase(case(expectation = expectation(listOf(group + ("count" to count))))) }
        }
        assertFails { parseCase(case(expectation = expectation(listOf(group + ("severity" to "fatal"))))) }
    }

    @Test fun `decimal comparison is exact scale independent typed and recursive without floating conversion`() {
        assertTrue(
            exactAcceptanceValueEquals(
                mapOf("n" to "9007199254740993.0000000001"),
                mapOf("n" to "9007199254740993.000000000100"),
            ),
        )
        assertFalse(
            exactAcceptanceValueEquals(
                mapOf("n" to "9007199254740993.0000000001"),
                mapOf("n" to "9007199254740993.0000000002"),
            ),
        )
        assertTrue(exactAcceptanceValueEquals(mapOf("n" to "-0.000"), mapOf("n" to "0")))
        assertFalse(exactAcceptanceValueEquals(mapOf("n" to "1"), "1"))
        assertFalse(exactAcceptanceValueEquals(mapOf("n" to "1"), mapOf("kw" to "1")))
        val first = mapOf("map" to listOf(listOf("a", mapOf("n" to "1.00")), listOf("b", false)))
        val second = mapOf("map" to listOf(listOf("a", mapOf("n" to "1")), listOf("b", false)))
        val reordered = mapOf("map" to listOf(listOf("b", false), listOf("a", mapOf("n" to "1"))))
        assertTrue(exactAcceptanceValueEquals(first, second))
        assertFalse(exactAcceptanceValueEquals(first, reordered))
        assertFalse(exactAcceptanceValueEquals(null, false))
    }

    @Test fun `bounded reads depth text and suite assertion counts fail closed`() {
        val path = directory.resolve("too-big.json")
        Files.write(path, ByteArray(TEMPLATE_ACCEPTANCE_BYTES + 1) { 32 })
        assertFails { readTemplateAcceptanceSuite(path) }
        assertFails { parseTemplateAcceptanceSuite(ByteArray(TEMPLATE_ACCEPTANCE_BYTES + 1)) }
        assertFails { parse(suite(documents = listOf(document(text = "x".repeat(65_537))))) }
        var nested: Any? = null
        repeat(20) { nested = listOf(nested) }
        assertFails { parse(suite(cases = listOf(case(expectation = expected(listOf(value(expected = nested))))))) }
        assertFails { parse(suite(cases = (1..33).map { case(id = "case-$it") })) }
        assertFails { parseCase(case(expectation = expected((1..257).map { value(node = "n$it") }))) }
        val cases = (1..9).map { id ->
            case(id = "case-$id", expectation = expected((1..256).map { node -> value(node = "n$node") }))
        }
        assertFails { parse(suite(cases = cases)) }
        val normal = directory.resolve("suite.json")
        Files.writeString(normal, WorkbenchJson.write(suite()))
        assertEquals("positive", readTemplateAcceptanceSuite(normal).cases.single().id)
    }
}
