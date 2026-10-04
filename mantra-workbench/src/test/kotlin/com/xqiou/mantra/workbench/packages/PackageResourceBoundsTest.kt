package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellation
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.packages.MigrationOperation
import com.xqiou.mantra.packages.MigrationPlan
import com.xqiou.mantra.packages.MigrationTarget
import com.xqiou.mantra.workbench.ExportBudget
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackageResourceBoundsTest {
    @TempDir lateinit var temporary: Path
    private val key = "old/cases/demo.mantra"
    private val target = "new/cases/demo.mantra"

    private fun host(
        store: MemoryHostStore? = null,
        options: CalculationOptions = CalculationOptions(),
    ): PackageWorkspaceCatalog {
        val fixture = PackageHostFixture(temporary)
        return PackageWorkspaceCatalog(
            listOf(
                PackageMount("old", fixture.snapshot("bounds.old", "1.0.0")),
                PackageMount("new", fixture.snapshot("bounds.new", "2.0.0", factor = 3)),
            ),
            editable = store?.let {
                listOf(EditablePackageCase(key, it, "host.mantra"))
            } ?: emptyList(),
            options = options,
        )
    }

    @Test fun `package workbook preview and download share sheet cell and serialized byte ceilings`() {
        val host = host()
        for (budget in listOf(ExportBudget(maxCells = 1), ExportBudget(maxSheets = 1), ExportBudget(maxBytes = 64))) {
            assertEquals(
                WorkspaceProblem.TOO_LARGE,
                assertFailsWith<WorkspaceException> { host.export(key, "xlsx", budget) }.problem,
            )
            assertEquals(
                WorkspaceProblem.TOO_LARGE,
                assertFailsWith<WorkspaceException> { host.exportPreview(key, null, budget) }.problem,
            )
        }
        assertTrue(host.export(key, "xlsx").isNotEmpty())
        assertTrue(host.exportPreview(key)["data"] != null)
    }

    @Test fun `all package artifact formats enforce host byte ceilings and recover on a legal request`() {
        val host = host()
        for (format in listOf("html", "text", "pdf")) {
            assertEquals(
                WorkspaceProblem.TOO_LARGE,
                assertFailsWith<WorkspaceException> { host.export(key, format, ExportBudget(maxBytes = 64)) }.problem,
            )
            assertTrue(host.export(key, format).size > 64)
        }
    }

    @Test fun `graph control limit maps to too large and cancellation or deadline keeps its technical finding`() {
        val limited = host(options = CalculationOptions(limits = RunLimits(maxParticipatingBytes = 0)))
        assertEquals(
            WorkspaceProblem.TOO_LARGE,
            assertFailsWith<WorkspaceException> { limited.export(key, "xlsx") }.problem,
        )
        assertEquals(
            WorkspaceProblem.TOO_LARGE,
            assertFailsWith<WorkspaceException> { limited.exportPreview(key) }.problem,
        )
        for ((control, code) in listOf(
            RunControl(cancellation = RunCancellation { true }) to "MANTRA-RUN-CANCELLED",
            RunControl(deadline = java.time.Instant.EPOCH) to "MANTRA-RUN-DEADLINE",
        )) {
            val stopped = host(options = CalculationOptions(control = control))
            val failure = assertFailsWith<WorkspaceException> { stopped.export(key, "xlsx") }
            assertEquals(WorkspaceProblem.INVALID, failure.problem)
            assertTrue(failure.diagnostics.any { it.code == code })
        }
    }

    @Test fun `migration intent cache enforces total source payload and count without retaining calculation results`() {
        val fixture = PackageHostFixture(temporary)
        val snapshot = fixture.snapshot("bounds.intent", "1.0.0")
        val schema = snapshot.manifest.schemas.single().binding
        val destination = MigrationTarget(schema, snapshot.manifest.identity, snapshot.revision, null)
        fun plan(index: Int) = MigrationPlan(
            key,
            "base-$index",
            schema,
            destination,
            listOf(MigrationOperation.ReplaceText(0, "x".repeat(200), "y".repeat(200))),
        )
        val cache = PackageMigrationWorkflow(maxEntries = 2, maxPayloadBytes = 1_200)
        val tokens = (1..3).map { it.toString().padStart(64, '0') }
        cache.retain(plan(1), tokens[0])
        cache.retain(plan(2), tokens[1])
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> { cache.plan(key, tokens[0]) }.problem,
        )
        assertEquals("base-2", cache.plan(key, tokens[1]).baseRevision)
        cache.retain(plan(3), tokens[2])
        assertEquals("base-3", cache.plan(key, tokens[2]).baseRevision)
        val tooBig = MigrationPlan(
            key,
            "large",
            schema,
            destination,
            listOf(MigrationOperation.ReplaceText(0, "x".repeat(2_000), "y".repeat(2_000))),
        )
        assertEquals(
            WorkspaceProblem.TOO_LARGE,
            assertFailsWith<WorkspaceException> { cache.retain(tooBig, "4".padStart(64, '0')) }.problem,
        )
        assertEquals("base-3", cache.plan(key, tokens[2]).baseRevision)
        assertEquals(
            WorkspaceProblem.REQUEST,
            assertFailsWith<WorkspaceException> { cache.plan("other/case.mantra", tokens[2]) }.problem,
        )
        val countBound = PackageMigrationWorkflow(maxEntries = 2, maxPayloadBytes = 10_000)
        tokens.forEachIndexed { index, token -> countBound.retain(plan(index + 1), token) }
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> { countBound.plan(key, tokens[0]) }.problem,
        )
        assertEquals("base-2", countBound.plan(key, tokens[1]).baseRevision)
        assertEquals("base-3", countBound.plan(key, tokens[2]).baseRevision)
        cache.remove(tokens[2])
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> { cache.plan(key, tokens[2]) }.problem,
        )
    }

    @Test fun `apply never turns a freshly re-evaluated changed source into the previously reviewed migration`() {
        val fixture = PackageHostFixture(temporary)
        val store = MemoryHostStore(fixture.original)
        val host = host(store)
        val revision = requireNotNull(host.evaluate(key).revision)
        val preview = host.previewMigration(key, revision, target)
        val token = (preview["data"] as Map<*, *>)["reviewToken"] as String
        store.text = store.text.replace(":base-value 0", ":base-value 7")
        val changed = store.text
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> { host.applyMigration(key, token) }.problem,
        )
        assertEquals(changed, store.text)
        assertEquals(0, store.writes)
        // Restoring exact source bytes also restores the reviewed graph; no stale result was adopted.
        store.text = fixture.original
        host.applyMigration(key, token)
        assertEquals(1, store.writes)
        assertEquals("2.0.0", host.evaluate(key).graph.result!!.schema.version)
    }
}
