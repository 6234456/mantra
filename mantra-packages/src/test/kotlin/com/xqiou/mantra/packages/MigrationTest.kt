package com.xqiou.mantra.packages

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationTest {
    @TempDir lateinit var temporary: Path

    private fun preview(fixture: MigrationGraphFixture, coordinator: MigrationCoordinator): MigrationPreview {
        val current = fixture.current(fixture.casePath)
        return coordinator.preview(
            fixture.casePath,
            current.graphRevision,
            fixture.old,
            fixture.target,
            listOf(MigrationOperation.PinSchema(fixture.next)),
        )
    }

    @Test
    fun `reviewed migration preserves original source and facts and commits genuine BUSINESS failure`() {
        val fixture = MigrationGraphFixture(temporary.resolve("workspace"))
        val store = trustedStore(fixture.root, 64_000)
        val coordinator = MigrationCoordinator(store, fixture)
        val original = Files.readString(fixture.root.resolve(fixture.casePath))
        val plan = preview(fixture, coordinator)
        assertEquals(original, Files.readString(fixture.root.resolve(fixture.casePath)))
        assertEquals(Value.num(10), plan.before.result?.value("answer"))
        assertEquals(Value.num(20), plan.after.result?.value("answer"))
        assertEquals(Value.num(0), plan.after.result?.value("base-value"))
        assertEquals(Value.Bool(false), plan.after.result?.value("enabled"))
        assertTrue(checkNotNull(plan.after.result).succeeded)
        assertFalse(checkNotNull(plan.after.result).validationPassed)
        assertTrue(plan.after.diagnostics.any { it.category == DiagnosticCategory.BUSINESS })
        assertTrue(plan.candidate.text.contains(";; Original zero/false facts must survive migration."))
        assertTrue(plan.candidate.text.contains(":title \"Keep my title\""))
        val receipt = coordinator.apply(plan, plan.reviewToken)
        assertEquals(plan.candidate.text, Files.readString(fixture.root.resolve(fixture.casePath)))
        assertEquals(plan.candidateSha256, receipt.newSourceSha256)
        assertEquals(fixture.next, fixture.current(fixture.casePath).schema)
        assertTrue(fixture.schemas.containsKey("migration/root@1.0.0"))
        val originalRun = fixture.evaluate(fixture.casePath, plan.original, fixture.target).result
        assertEquals(fixture.old.identity, originalRun?.schema?.identity)
        assertEquals(Value.num(10), originalRun?.value("answer"))
        assertFailsWith<PackageException> { coordinator.apply(plan, plan.reviewToken) }
        Files.list(fixture.root.resolve("cases")).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    @Test
    fun `root source linked source target resources and review token races cannot commit`() {
        fun scenario(
            name: String,
            mutate: (MigrationGraphFixture) -> Unit,
            token: (MigrationPreview) -> String = { it.reviewToken },
        ) {
            val fixture = MigrationGraphFixture(temporary.resolve(name))
            val coordinator = MigrationCoordinator(trustedStore(fixture.root, 64_000), fixture)
            val plan = preview(fixture, coordinator)
            mutate(fixture)
            val before = Files.readString(fixture.root.resolve(fixture.casePath))
            assertFailsWith<PackageException> { coordinator.apply(plan, token(plan)) }
            assertEquals(before, Files.readString(fixture.root.resolve(fixture.casePath)))
        }
        scenario("root", { fixture ->
            Files.writeString(
                fixture.root.resolve(fixture.casePath),
                Files.readString(fixture.root.resolve(fixture.casePath)) + "\n;; Concurrent user edit",
            )
        })
        scenario("linked", { it.source(11) })
        scenario("target", {
            it.schemas["migration/root@2.0.0"] =
                it.schemas.getValue("migration/root@2.0.0").replace(" 2)", " 3)")
        })
        scenario("token", {}, { "unreviewed" })
    }

    @Test
    fun `technical target failure never creates a committable preview or replaces Nil with zero`() {
        val fixture = MigrationGraphFixture(temporary.resolve("technical"), technical = true)
        val coordinator = MigrationCoordinator(trustedStore(fixture.root, 64_000), fixture)
        val original = Files.readString(fixture.root.resolve(fixture.casePath))
        val failure = assertFailsWith<PackageException> { preview(fixture, coordinator) }
        assertEquals("MANTRA-MIGRATION-TECHNICAL", failure.diagnostic.code)
        assertTrue(failure.diagnostics.any { it.category == DiagnosticCategory.EVALUATION })
        assertEquals(original, Files.readString(fixture.root.resolve(fixture.casePath)))
        val candidate = CaseMigrationEditor.apply(
            storeSource(fixture),
            listOf(MigrationOperation.PinSchema(fixture.next)),
        )
        val result = fixture.evaluate(fixture.casePath, candidate, fixture.target)
        assertFalse(result.result?.succeeded == true)
        if (result.result != null) assertEquals(Value.Nil, result.result.value("answer"))
    }

    @Test
    fun `writable host root rejects traversal symlinks and mismatching explicit patches`() {
        val fixture = MigrationGraphFixture(temporary.resolve("confined"))
        val store = trustedStore(fixture.root, 64_000)
        assertFailsWith<PackageException> { store.read("../outside.mantra") }
        val outside = temporary.resolve("outside.mantra")
        Files.writeString(outside, "(case outside)")
        Files.createSymbolicLink(fixture.root.resolve("cases/link.mantra"), outside)
        assertFailsWith<PackageException> { store.read("cases/link.mantra") }
        assertFailsWith<PackageException> {
            CaseMigrationEditor.apply(
                storeSource(fixture),
                listOf(MigrationOperation.ReplaceText(0, "wrong", "replacement")),
            )
        }
        assertEquals("(case outside)", Files.readString(outside))
    }

    @Test
    fun `migration preview retains inherited BUSINESS case identity revision and actual linked values`() {
        val fixture = MigrationGraphFixture(temporary.resolve("source-business"))
        fixture.source(-1)
        val coordinator = MigrationCoordinator(trustedStore(fixture.root, 64_000), fixture)
        val plan = preview(fixture, coordinator)
        assertTrue(checkNotNull(plan.after.result).succeeded)
        assertFalse(checkNotNull(plan.after.result).validationPassed)
        assertEquals(Value.num(-2), plan.after.result?.value("answer"))
        val sourceFinding = plan.after.diagnostics.single {
            it.caseKey == "cases/source.mantra" &&
                it.category == DiagnosticCategory.BUSINESS
        }
        assertEquals("MANTRA-CHECK-FAILED", sourceFinding.code)
        assertTrue(sourceFinding.caseRevision?.matches(Regex("[0-9a-f]{64}")) == true)
        assertEquals(plan.candidateSha256, coordinator.apply(plan, plan.reviewToken).newSourceSha256)
    }

    private fun storeSource(fixture: MigrationGraphFixture) = trustedStore(fixture.root, 64_000).read(fixture.casePath)
}
