package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaseEditsTest {
    @TempDir lateinit var temp: Path
    private fun copyExample(name: String): Pair<Path, String> {
        val source = Path.of("examples/$name")
        val dir = Files.createDirectories(temp.resolve(name))
        Files.list(source).use { stream -> stream.filter { it.toString().endsWith(".mantra") }.forEach {
            Files.copy(it, dir.resolve(it.fileName))
        } }
        val case = when (name) {
            "de-est-2025" -> "case-mustermann.mantra"
            "ifrs-ias36-corporate-assets" -> "case-ie8.mantra"
            else -> "case-demo.mantra"
        }
        return dir to case
    }

    @Test
    fun `preview leaves bytes intact and commit undo redo check revisions`() {
        val (dir, case) = copyExample("de-est-2025")
        val catalog = WorkspaceCatalog(dir)
        val file = dir.resolve(case)
        val original = Files.readAllBytes(file)
        val revision = catalog.document(case, "run").revision
        val operations = listOf(CaseTextEditor.Operation.SetInput("spenden", Value.num("451")))
        val preview = catalog.previewEdits(case, revision, operations)
        assertEquals(revision, preview.revision)
        assertTrue(preview.data["preview"] == true)
        assertTrue(original.contentEquals(Files.readAllBytes(file)))
        val committed = catalog.commitEdits(case, revision, operations)
        assertEquals(preview.data["proposedRevision"], committed.revision)
        assertFalse(original.contentEquals(Files.readAllBytes(file)))
        val stale = assertFailsWith<WorkspaceException> { catalog.commitEdits(case, revision, operations) }
        assertEquals(WorkspaceProblem.CONFLICT, stale.problem)
        assertEquals(committed.revision, stale.currentRevision)
        val undone = catalog.undo(case, committed.revision)
        assertEquals(revision, undone.revision)
        assertTrue(original.contentEquals(Files.readAllBytes(file)))
        val redone = catalog.redo(case, undone.revision)
        assertEquals(committed.revision, redone.revision)
    }

    @Test
    fun `invalid second edit rejects whole batch without changing file`() {
        val (dir, case) = copyExample("de-est-2025")
        val catalog = WorkspaceCatalog(dir)
        val file = dir.resolve(case)
        val original = Files.readAllBytes(file)
        val revision = catalog.document(case, "run").revision
        val error = assertFailsWith<WorkspaceException> {
            catalog.commitEdits(case, revision, listOf(
                CaseTextEditor.Operation.SetInput("spenden", Value.num("451")),
                CaseTextEditor.Operation.SetInput("kinder", Value.Text("invalid")),
            ))
        }
        assertEquals(WorkspaceProblem.INVALID, error.problem)
        assertTrue(original.contentEquals(Files.readAllBytes(file)))
        assertEquals(revision, catalog.document(case, "run").revision)
    }

    @Test
    fun `IAS and SAP cases preview table edits without writing`() {
        for ((domain, table) in listOf("ifrs-ias36-corporate-assets" to "cgus", "sap-co-product-cost" to "products")) {
            val (dir, case) = copyExample(domain)
            val catalog = WorkspaceCatalog(dir)
            val original = Files.readAllBytes(dir.resolve(case))
            val revision = catalog.document(case, "run").revision
            val candidate = if (table == "cgus") Value.MapV(mapOf(
                Value.Kw("id") to Value.Kw("D"), Value.Kw("name") to Value.Text("D"),
                Value.Kw("carrying-amount") to Value.num("10"), Value.Kw("remaining-life") to Value.num("10"),
                Value.Kw("value-in-use") to Value.num("11"),
            )) else Value.MapV(mapOf(Value.Kw("id") to Value.Kw("C"), Value.Kw("name") to Value.Text("Product C")))
            val preview = catalog.previewEdits(case, revision, listOf(CaseTextEditor.Operation.InsertRow(table, candidate)))
            assertTrue(preview.data.containsKey("difference"))
            assertTrue(original.contentEquals(Files.readAllBytes(dir.resolve(case))))
        }
    }

    @Test
    fun `batch validates a retained member against the final view`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        Files.writeString(file, Files.readString(file).replace(":veranlagungsart :zusammen", ":veranlagungsart :einzel"))
        val catalog = WorkspaceCatalog(dir)
        val revision = catalog.document(case, "run").revision
        val change = listOf(
            CaseTextEditor.Operation.SetInput("veranlagungsart", Value.Kw("zusammen")),
            CaseTextEditor.Operation.SetInput("bruttoarbeitslohn", Value.num("32000"), listOf("B")),
        )
        val preview = catalog.previewEdits(case, revision, change)
        assertTrue(preview.data["preview"] == true)
        val committed = catalog.commitEdits(case, revision, change)
        assertEquals(preview.data["proposedRevision"], committed.revision)
        assertContains(Files.readString(file), ":bruttoarbeitslohn {:A 68500 :B 32000}")
    }

    @Test
    fun `temporary unknown formula input and parameter edits do not block a valid batch`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        Files.writeString(file, Files.readString(file).replace(":veranlagungsart :zusammen", ":veranlagungsart :einzel"))
        val catalog = WorkspaceCatalog(dir)
        val revision = catalog.document(case, "run").revision
        val operations = listOf(
            CaseTextEditor.Operation.BindFormula("missing-slot", "(+ 1 2)"),
            CaseTextEditor.Operation.SetInput("missing-input", Value.num("1")),
            CaseTextEditor.Operation.SetParam("missing-param", Value.num("1")),
            CaseTextEditor.Operation.SetInput("veranlagungsart", Value.Kw("zusammen")),
            CaseTextEditor.Operation.SetInput("bruttoarbeitslohn", Value.num("32000"), listOf("B")),
            CaseTextEditor.Operation.UnbindFormula("missing-slot"),
            CaseTextEditor.Operation.ClearInput("missing-input"),
            CaseTextEditor.Operation.ResetParam("missing-param"),
        )
        val preview = catalog.previewEdits(case, revision, operations)
        assertTrue(preview.data["preview"] == true)
        val committed = catalog.commitEdits(case, revision, operations)
        assertEquals(preview.data["proposedRevision"], committed.revision)
        assertContains(Files.readString(file), ":bruttoarbeitslohn {:A 68500 :B 32000}")
    }

    @Test
    fun `retained inactive member is rejected but a later clear can remove it`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        Files.writeString(file, Files.readString(file).replace(":veranlagungsart :zusammen", ":veranlagungsart :einzel"))
        val catalog = WorkspaceCatalog(dir)
        val revision = catalog.document(case, "run").revision
        val setB = CaseTextEditor.Operation.SetInput("bruttoarbeitslohn", Value.num("32000"), listOf("B"))
        val problem = assertFailsWith<WorkspaceException> { catalog.previewEdits(case, revision, listOf(setB)) }
        assertEquals(WorkspaceProblem.INVALID, problem.problem)
        val cleared = catalog.previewEdits(case, revision, listOf(setB,
            CaseTextEditor.Operation.ClearInput("bruttoarbeitslohn", listOf("B"))))
        assertTrue(cleared.data["preview"] == true)
    }

    @Test
    fun `dimensioned input cannot be replaced by scalar without a member coordinate`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        val original = Files.readString(file)
        val catalog = WorkspaceCatalog(dir)
        val revision = catalog.document(case, "run").revision
        val scalar = CaseTextEditor.Operation.SetInput("bruttoarbeitslohn", Value.num("40000"))
        val rejected = assertFailsWith<WorkspaceException> { catalog.previewEdits(case, revision, listOf(scalar)) }
        assertEquals(WorkspaceProblem.INVALID, rejected.problem)
        assertContains(rejected.diagnostics.single().message, "member maps")
        assertFailsWith<WorkspaceException> { catalog.commitEdits(case, revision, listOf(scalar)) }
        assertEquals(original, Files.readString(file))
        val repaired = catalog.previewEdits(case, revision, listOf(scalar,
            CaseTextEditor.Operation.SetInput("bruttoarbeitslohn", Value.MapV(mapOf(
                Value.Kw("A") to Value.num("40000"), Value.Kw("B") to Value.num("32000"))))))
        assertTrue(repaired.data["preview"] == true)
    }

    @Test
    fun `batch may repair an intermediate invalid layout before final validation`() {
        val (dir, case) = copyExample("de-est-2025")
        val catalog = WorkspaceCatalog(dir)
        val file = dir.resolve(case)
        val original = Files.readString(file)
        val revision = catalog.document(case, "run").revision
        val operations = listOf(
            CaseTextEditor.Operation.SetBindings(null, "missing/layout"),
            CaseTextEditor.Operation.SetInput("spenden", Value.num("451")),
            CaseTextEditor.Operation.SetBindings(null, "de.est/steuerberechnung"),
        )
        val preview = catalog.previewEdits(case, revision, operations)
        assertEquals(original, Files.readString(file))
        val committed = catalog.commitEdits(case, revision, operations)
        assertEquals(preview.data["proposedRevision"], committed.revision)
        assertContains(Files.readString(file), ":spenden 451")
        assertContains(Files.readString(file), ":layout \"de.est/steuerberechnung\"")
    }

    @Test
    fun `member coordinate validation can ignore transient invalid layout`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        Files.writeString(file, Files.readString(file).replace(":veranlagungsart :zusammen", ":veranlagungsart :einzel"))
        val catalog = WorkspaceCatalog(dir)
        val revision = catalog.document(case, "run").revision
        val result = catalog.previewEdits(case, revision, listOf(
            CaseTextEditor.Operation.SetBindings(null, "missing/layout"),
            CaseTextEditor.Operation.SetInput("veranlagungsart", Value.Kw("zusammen")),
            CaseTextEditor.Operation.SetInput("bruttoarbeitslohn", Value.num("32000"), listOf("B")),
            CaseTextEditor.Operation.SetBindings(null, "de.est/steuerberechnung"),
        ))
        assertTrue(result.data["preview"] == true)
    }

    @Test
    fun `member coordinate validation can ignore transient missing parameter set`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        Files.writeString(file, Files.readString(file).replace(":veranlagungsart :zusammen", ":veranlagungsart :einzel"))
        val catalog = WorkspaceCatalog(dir)
        val revision = catalog.document(case, "run").revision
        val operations = listOf(
            CaseTextEditor.Operation.SetBindings(listOf("missing/parameters"), null),
            CaseTextEditor.Operation.SetInput("veranlagungsart", Value.Kw("zusammen")),
            CaseTextEditor.Operation.SetInput("bruttoarbeitslohn", Value.num("32000"), listOf("B")),
            CaseTextEditor.Operation.SetBindings(listOf("de.est/params-2026"), null),
        )
        val preview = catalog.previewEdits(case, revision, operations)
        assertTrue(preview.data["preview"] == true)
        val committed = catalog.commitEdits(case, revision, operations)
        assertEquals(preview.data["proposedRevision"], committed.revision)
        assertContains(Files.readString(file), ":parameters [\"de.est/params-2026\"]")
        assertContains(Files.readString(file), ":bruttoarbeitslohn {:A 68500 :B 32000}")
    }

    @Test
    fun `member coordinate validation can ignore transient invalid parameter document`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        Files.writeString(file, Files.readString(file).replace(":veranlagungsart :zusammen", ":veranlagungsart :einzel"))
        Files.writeString(dir.resolve("temp-invalid.mantra"), "(parameters temp/invalid (bad))")
        val catalog = WorkspaceCatalog(dir)
        val revision = catalog.document(case, "run").revision
        val preview = catalog.previewEdits(case, revision, listOf(
            CaseTextEditor.Operation.SetBindings(listOf("temp/invalid"), null),
            CaseTextEditor.Operation.SetInput("veranlagungsart", Value.Kw("zusammen")),
            CaseTextEditor.Operation.SetInput("bruttoarbeitslohn", Value.num("32000"), listOf("B")),
            CaseTextEditor.Operation.SetBindings(listOf("de.est/params-2026"), null),
        ))
        assertTrue(preview.data["preview"] == true)
    }

    @Test
    fun `external case edit before final replace returns conflict without overwriting it`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        val catalog = WorkspaceCatalog(dir)
        val revision = catalog.document(case, "run").revision
        catalog.beforeWriteCheck = {
            Files.writeString(file, Files.readString(file).replace(":spenden 450", ":spenden 999"))
        }
        val conflict = assertFailsWith<WorkspaceException> {
            catalog.commitEdits(case, revision, listOf(CaseTextEditor.Operation.SetInput("spenden", Value.num("451"))))
        }
        assertEquals(WorkspaceProblem.CONFLICT, conflict.problem)
        assertEquals(catalog.document(case, "run").revision, conflict.currentRevision)
        assertContains(Files.readString(file), ":spenden 999")
        assertFalse(Files.readString(file).contains(":spenden 451"))
    }

    @Test
    fun `external parameter edit before final replace changes full revision and blocks case write`() {
        val (dir, case) = copyExample("de-est-2025")
        val file = dir.resolve(case)
        Files.writeString(file, Files.readString(file).replace(":layout \"de.est/steuerberechnung\"",
            ":layout \"de.est/steuerberechnung\"\n   :parameters [\"de.est/params-2026\"]"))
        val catalog = WorkspaceCatalog(dir)
        val original = Files.readString(file)
        val revision = catalog.document(case, "run").revision
        val parameterFile = dir.resolve("params-2026.mantra")
        catalog.beforeWriteCheck = {
            Files.writeString(parameterFile, Files.readString(parameterFile).replace("tarif-gfb 12348", "tarif-gfb 12349"))
        }
        val conflict = assertFailsWith<WorkspaceException> {
            catalog.commitEdits(case, revision, listOf(CaseTextEditor.Operation.SetInput("spenden", Value.num("451"))))
        }
        assertEquals(WorkspaceProblem.CONFLICT, conflict.problem)
        assertEquals(catalog.document(case, "run").revision, conflict.currentRevision)
        assertEquals(original, Files.readString(file))
    }
}
