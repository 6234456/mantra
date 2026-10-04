package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.packages.PackageException
import com.xqiou.mantra.packages.PackageParameterChoice
import com.xqiou.mantra.packages.ParameterSelectionMode
import com.xqiou.mantra.workbench.CaseTextEditor
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageWorkspaceCatalogTest {
    @TempDir lateinit var temporary: Path
    private val key = "old/cases/demo.mantra"
    private val next = "new/cases/demo.mantra"

    private fun choice(
        mode: ParameterSelectionMode = ParameterSelectionMode.EFFECTIVE_DATE,
        date: String = "2026-06-30",
    ) = PackageParameterChoice(LocalDate.parse(date), mode, listOf("current"), setOf("rate"))

    private fun host(store: MemoryHostStore? = null, technical: Boolean = false): PackageWorkspaceCatalog {
        val fixture = PackageHostFixture(temporary)
        val mounts = listOf(
            PackageMount("old", fixture.snapshot("demo.old", "1.0.0")),
            PackageMount("new", fixture.snapshot("demo.new", "2.0.0", factor = 3, technical = technical)),
        )
        return PackageWorkspaceCatalog(
            mounts,
            mapOf(key to choice(), next to choice()),
            store?.let { listOf(EditablePackageCase(key, it, "host-case.mantra")) } ?: emptyList(),
        )
    }
    private fun revision(host: PackageWorkspaceCatalog) = requireNotNull(host.evaluate(key).revision)
    private fun data(document: Map<String, Any?>) = document["data"] as Map<*, *>
    private fun token(preview: Map<String, Any?>) = data(preview)["reviewToken"] as String

    @Test fun `real captured package projects current public documents and dated provenance`() {
        val host = host()
        val execution = host.evaluate(key)
        assertTrue(execution.graph.succeeded)
        assertEquals(Value.ZERO, execution.graph.result!!.view.value("answer"))
        assertEquals(Value.Bool(false), execution.graph.result!!.view.value("enabled"))
        val document = host.document(key, "parameters")
        assertEquals("mantra.packages/1", document["contract"])
        val embedded = data(document)["document"] as Map<*, *>
        assertEquals("mantra.workbench/4", embedded["contract"])
        val source = (data(document)["parameterSources"] as List<*>).single() as Map<*, *>
        assertEquals("2026-06-30", source["effectiveDate"])
        assertEquals("2027-01-01", source["validUntil"])
        assertEquals(true, source["validForDate"])
        assertEquals("parameters/current.mantra", source["resource"])
        assertEquals(64, (source["sha256"] as String).length)
        assertTrue(host.export(key, "xlsx").isNotEmpty())
    }

    @Test fun `what-if expiry is visible and case overrides do not inherit selected-source attribution`() {
        val fixture = PackageHostFixture(temporary)
        val snapshot = fixture.snapshot("demo.old", "1.0.0")
        val store = MemoryHostStore(
            fixture.original.replace(":base-value 0", ":base-value 5")
                .replace("(inputs", "(params {:rate 9})\n  (inputs"),
        )
        val host = PackageWorkspaceCatalog(
            listOf(PackageMount("old", snapshot)),
            mapOf(key to choice(ParameterSelectionMode.WHAT_IF, "2027-01-01")),
            listOf(EditablePackageCase(key, store, "case.mantra")),
        )
        assertEquals(Value.num("45"), host.evaluate(key).graph.result!!.view.value("answer"))
        val source = (data(host.document(key, "parameters"))["parameterSources"] as List<*>).single() as Map<*, *>
        assertEquals(false, source["validForDate"])
        assertEquals(true, source["overriddenByCase"])
        assertEquals("case", source["effectiveLayer"])
        assertEquals(mapOf("n" to "9"), source["effectiveValue"])
        assertEquals(mapOf("n" to "2"), source["selectedSetValue"])
    }

    @Test fun `readonly package cannot acquire a writer from manifest metadata`() {
        val host = host()
        assertFailsWith<WorkspaceException> {
            host.edit(key, revision(host), listOf(CaseTextEditor.Operation.SetInput("base-value", Value.num("5"))))
        }
        assertEquals(Value.ZERO, host.evaluate(key).graph.result!!.view.value("answer"))
    }

    @Test fun `migration is reviewed exact and source binding survives reopen undo redo with prior edits`() {
        val fixture = PackageHostFixture(temporary)
        val store = MemoryHostStore(fixture.original)
        val host = host(store)
        host.edit(key, revision(host), listOf(CaseTextEditor.Operation.SetInput("base-value", Value.num("5"))))
        assertEquals(Value.num("10"), host.evaluate(key).graph.result!!.view.value("answer"))
        val oldText = store.text
        val preview = host.previewMigration(key, revision(host), next)
        assertEquals(oldText, store.text)
        assertEquals(1, store.writes)
        assertFailsWith<WorkspaceException> { host.applyMigration(key, "0".repeat(64)) }
        host.applyMigration(key, token(preview))
        assertEquals(Value.num("30"), host.evaluate(key).graph.result!!.view.value("answer"))
        assertTrue("Keep original zero/false facts" in store.text)
        assertTrue(":enabled false" in store.text)
        assertTrue(":host-package" in store.text)
        val reopened = host(store)
        assertEquals("2.0.0", reopened.evaluate(key).graph.result!!.schema.version)
        host.restore(key, revision(host), true)
        assertEquals("1.0.0", host.evaluate(key).graph.result!!.schema.version)
        assertEquals(oldText, store.text)
        host.restore(key, revision(host), true)
        assertEquals(fixture.original, store.text)
        host.restore(key, revision(host), false)
        host.restore(key, revision(host), false)
        assertEquals(Value.num("30"), host.evaluate(key).graph.result!!.view.value("answer"))
        assertEquals("2.0.0", host.evaluate(key).graph.result!!.schema.version)
    }

    @Test fun `external source CAS change rejects apply without damaging history`() {
        val fixture = PackageHostFixture(temporary)
        val store = MemoryHostStore(fixture.original)
        val host = host(store)
        val preview = host.previewMigration(key, revision(host), next)
        store.text = store.text.replace(":base-value 0", ":base-value 7")
        val changed = store.text
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> { host.applyMigration(key, token(preview)) }.problem,
        )
        assertEquals(changed, store.text)
        assertEquals(0, store.writes)
        assertFailsWith<IllegalArgumentException> { host.restore(key, revision(host), true) }
    }

    @Test fun `technical target failure cannot become committed migration or stale successful result`() {
        val fixture = PackageHostFixture(temporary)
        val store = MemoryHostStore(fixture.original)
        val host = host(store, technical = true)
        assertTrue(host.evaluate(key).graph.succeeded)
        val failure = assertFailsWith<PackageException> { host.previewMigration(key, revision(host), next) }
        assertTrue(failure.diagnostics.any { it.code == "MANTRA-MIGRATION-TECHNICAL" })
        assertTrue(failure.diagnostics.any { it.category != com.xqiou.mantra.core.DiagnosticCategory.BUSINESS })
        assertEquals(0, store.writes)
        val result = host.document(next, "run")
        assertFalse(data(result)["succeeded"] as Boolean)
        assertTrue((data(result)["diagnostics"] as List<*>).isNotEmpty())
        val failed = host.evaluate(next).graph
        assertTrue(failed.result == null || !failed.result!!.succeeded)
        assertEquals(fixture.original, store.text)
    }
}
