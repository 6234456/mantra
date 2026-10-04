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
}
