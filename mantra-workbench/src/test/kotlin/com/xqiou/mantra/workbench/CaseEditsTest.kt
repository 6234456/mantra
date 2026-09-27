package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
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

    @Test fun `date input text uses the documented German format`() {
        val dir = Files.createDirectories(temp.resolve("dates"))
        Files.writeString(dir.resolve("schema.mantra"), """
            (schema test/date {:title "Date" :mainline [main]}
              (input report-date :date {:optional true})
              (section main "Main" {:panel true} (field report-date "Report date") (total sum "Sum")))
        """.trimIndent())
        Files.writeString(dir.resolve("case.mantra"), "(case sample {:schema \"test/date\"})")
        val catalog = WorkspaceCatalog(dir)
        assertEquals(Value.Date(LocalDate.of(2025, 12, 31)), catalog.parseEditText("case.mantra", "report-date", false, "31.12.2025"))
        assertFailsWith<WorkspaceException> { catalog.parseEditText("case.mantra", "report-date", false, "31.02.2025") }
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
}
