package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.ParticipatingSource
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaVersionTest {
    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text), SourceResolver { _, _ -> null })
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text))

    @Test
    fun `legacy actual schema identity keeps null version and requires real schema participation`() {
        val template = schema("(schema test/legacy (line source-amount \"Amount\" 1))")
        assertNull(template.version)
        assertEquals(SchemaIdentity("test/legacy", null), template.identity)
        val participation = ParticipatingSource("memory:schema", SourceRole.SCHEMA, "0".repeat(64), 0)
        val prepared = PreparedCasePackage(
            CanonicalCaseKey("memory:case"),
            "c",
            template.identity,
            template,
            CaseData.empty("c"),
            emptyList(),
            listOf(participation),
            "r1",
        )
        assertNull(prepared.schemaIdentity.version)
        assertFailsWith<IllegalArgumentException> {
            PreparedCasePackage(
                CanonicalCaseKey("memory:case"),
                "c",
                template.identity,
                template,
                CaseData.empty("c"),
                emptyList(),
                emptyList(),
                "r1",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PreparedCasePackage(
                CanonicalCaseKey("memory:case"),
                "c",
                SchemaIdentity(template.id, "unversioned"),
                template,
                CaseData.empty("c"),
                emptyList(),
                listOf(participation),
                "r1",
            )
        }
    }

    @Test
    fun `schema and case version forms require nonblank literal strings`() {
        listOf("nil", "false", "1", ":v1", "v1", "\" \"").forEach { invalid ->
            assertFailsWith<MantraException> { schema("(schema test/version {:version $invalid})") }
            assertFailsWith<MantraException> { case("(case c {:schema-version $invalid})") }
        }
        assertEquals("2025.01", schema("(schema test/version {:version \"2025.01\"})").version)
    }

    @Test
    fun `exact version assertion applies on initial calculation and incremental rebind`() {
        val template = schema("(schema test/version {:version \"01\"} (line source-amount \"Amount\" 1))")
        val facts = case("(case c {:schema test/version :schema-version \"01\"})")
        assertEquals("01", facts.schemaVersion)
        Mantra.openSession(template, facts).use { session ->
            val failure = assertFailsWith<MantraException> {
                session.recalculate(facts.copy(meta = facts.meta + ("schema-version" to Value.Text("1"))))
            }
            assertTrue(failure.diagnostics.any { it.code == "MANTRA-CASE-SCHEMA-VERSION" })
            assertTrue(session.recalculate(facts).succeeded)
        }
        assertFailsWith<MantraException> {
            Mantra.calculate(
                template,
                facts.copy(
                    meta =
                    facts.meta + ("schema-version" to Value.Text("1")),
                ),
            )
        }
        assertFailsWith<MantraException> { Mantra.calculate(schema("(schema test/version)"), facts) }
    }

    @Test
    fun `graph metadata appends inherited business findings without rereading mutable runtime values`() {
        val template = schema("(schema test/meta (input source-amount :decimal))")
        val facts = case("(case c (inputs {:source-amount 5}))")
        Mantra.openSession(template, facts).use { session ->
            val initial = session.result
            session.recalculate(facts.copy(inputs = mapOf("source-amount" to Value.num(10))))
            val sourceFinding = Diagnostic(
                Severity.ERROR,
                "SOURCE-BUSINESS",
                "source failed reconciliation",
                nodeId = "check",
                category = DiagnosticCategory.BUSINESS,
                caseKey = "source",
                caseRevision = "source-r1",
            )
            val attributed = initial.withGraphMetadata(listOf(sourceFinding), session.result.usage!!)
            val finalized = attributed.withGraphMetadata(emptyList(), session.result.usage!!)
            assertEquals(Value.num(5), finalized.value("source-amount"))
            assertTrue(finalized.succeeded)
            assertFalse(finalized.validationPassed)
            assertFalse(finalized.view.validationPassed)
            assertEquals(listOf(sourceFinding), finalized.diagnostics)
            assertEquals("source-r1", finalized.view.diagnostics.single().caseRevision)
        }
    }
}
