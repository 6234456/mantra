package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceExportTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var temp: Path

    @Test
    fun `unsupported guard export reports invalid with the translation reason`() {
        val schemaPath = temp.resolve("schema.mantra")
        Files.writeString(
            schemaPath,
            """
            (schema test/unsupported-guard {}
              (section guarded "Guarded" {:when (> (count (range 3)) 0)}
                (line answer "Answer" 7)))
            """.trimIndent(),
        )
        val casePath = temp.resolve("case.mantra")
        Files.writeString(casePath, "(case sample {:schema \"test/unsupported-guard\"})")
        val result = Mantra.calculate(Mantra.loadSchema(schemaPath), Mantra.loadCase(casePath))
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(7.0, result.decimal("answer").toDouble())

        val catalog = workspaceCatalog(temp)
        val preview = assertFailsWith<WorkspaceException> { catalog.exportPreview("case.mantra") }
        val download = assertFailsWith<WorkspaceException> { catalog.export("case.mantra", "xlsx") }
        listOf(preview, download).forEach { error ->
            assertEquals(WorkspaceProblem.INVALID, error.problem)
            assertContains(error.message.orEmpty(), "guarded")
            assertContains(error.message.orEmpty(), "function range")
            assertContains(error.message.orEmpty(), "no computed fallback value")
        }
        assertEquals(preview.message, download.message)
    }
    private fun presentationCase() {
        Files.writeString(
            temp.resolve("schema.mantra"),
            """
            (schema test/presentation {:title "Presentation" :mainline [main]}
              (section main "Main" {:panel true} (line answer "Answer" 7)))
            """.trimIndent(),
        )
        Files.writeString(temp.resolve("case.mantra"), "(case sample {:schema \"test/presentation\"})")
    }

    @Test
    fun `all presentation formats honor byte ceilings`() {
        presentationCase()
        WorkspaceCatalog(temp, exportBudget = ExportBudget(maxBytes = 8)).use { catalog ->
            for (format in listOf("html", "txt", "pdf")) {
                val error = assertFailsWith<WorkspaceException>(format) { catalog.export("case.mantra", format) }
                assertEquals(WorkspaceProblem.TOO_LARGE, error.problem, format)
            }
        }
    }

    @Test
    fun `pdf is binary and presentation exports share cell ceilings`() {
        presentationCase()
        WorkspaceCatalog(temp).use { catalog ->
            assertTrue(catalog.export("case.mantra", "pdf").take(5).toByteArray().contentEquals("%PDF-".toByteArray()))
        }
        WorkspaceCatalog(temp, exportBudget = ExportBudget(maxCells = 1)).use { catalog ->
            for (format in listOf("html", "txt", "pdf")) {
                val error = assertFailsWith<WorkspaceException>(format) { catalog.export("case.mantra", format) }
                assertEquals(WorkspaceProblem.TOO_LARGE, error.problem, format)
            }
        }
    }

    @Test
    fun `legacy preview and download share byte ceiling and recover after source becomes small again`() {
        Files.writeString(
            temp.resolve("schema.mantra"),
            """
            (schema test/workbook-bytes {:title "Workbook bytes" :mainline [main]}
              (input records :table {:columns {:id :keyword :base-value :decimal}})
              (dimension entry {:from records :key :id})
              (section main "Main" {:panel true :per entry}
                (line answer "Answer" entry.base-value)))
            """.trimIndent(),
        )
        val file = temp.resolve("case.mantra")
        val small = "(case sample {:schema \"test/workbook-bytes\"} (inputs {:records [{:id :A :base-value 0}]}))"
        Files.writeString(file, small)
        val minimum = WorkspaceCatalog(temp).use { it.export("case.mantra", "xlsx").size }
        WorkspaceCatalog(temp, exportBudget = ExportBudget(maxBytes = minimum + 1024)).use { catalog ->
            assertTrue(catalog.exportPreview("case.mantra").data.isNotEmpty())
            val records = (1..250).joinToString(" ") { "{:id :Entry$it :base-value $it}" }
            Files.writeString(file, "(case sample {:schema \"test/workbook-bytes\"} (inputs {:records [$records]}))")
            val preview = assertFailsWith<WorkspaceException> { catalog.exportPreview("case.mantra") }
            val download = assertFailsWith<WorkspaceException> { catalog.export("case.mantra", "xlsx") }
            assertEquals(WorkspaceProblem.TOO_LARGE, preview.problem)
            assertEquals(WorkspaceProblem.TOO_LARGE, download.problem)
            Files.writeString(file, small)
            assertTrue(catalog.exportPreview("case.mantra").data.isNotEmpty())
            assertTrue(catalog.export("case.mantra", "xlsx").size <= minimum + 1024)
        }
    }
}
