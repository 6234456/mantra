package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaperPreviewTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var root: Path
    private val caseId = "case.mantra"

    private fun workspace(): WorkspaceCatalog {
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema test/preview {:title "Preview" :mainline [main]}
              (input base-amount :decimal)
              (section main "Main" {:panel true :display :schedule}
                (line observed "Observed" base-amount {:op :info})
                (line doubled "Doubled" (* base-amount 2) {:op :info})
                (line blank "Blank" 0 {:op :info})
                (line hidden "Hidden" 5 {:hidden true :op :info})
                (check nonnegative "Nonnegative" (>= base-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(root.resolve(caseId), "(case c {:schema \"test/preview\"} (inputs {:base-amount 10}))")
        return workspaceCatalog(root)
    }

    private fun change(amount: String) = listOf(CaseTextEditor.Operation.SetInput("base-amount", Value.num(amount)))

    private fun nested(data: Map<*, *>, key: String) = data[key] as Map<*, *>

    private fun value(run: Map<*, *>, node: String): Any? =
        ((nested(run, "values")[node] as? Map<*, *>)?.get("") as? Map<*, *>)?.get("value")

    private fun rows(paper: Map<*, *>): List<Map<*, *>> = (paper["tables"] as List<*>).flatMap {
        ((it as Map<*, *>)["rows"] as List<*>).map { row -> row as Map<*, *> }
    }

    @Test fun `candidate run difference and real paper match the eventual commit and strict schema`() {
        val catalog = workspace()
        val original = Files.readAllBytes(root.resolve(caseId))
        val base = catalog.document(caseId, "run")
        val preview = catalog.previewPaper(caseId, base.revision, change("12.34567890123456789"), 17, "main", true)
        assertEquals(base.revision, preview.revision)
        assertEquals(17L, preview.data["draftSequence"])
        assertEquals(true, preview.data["preview"])
        assertNotEquals(base.revision, preview.data["proposedRevision"])
        val run = nested(preview.data, "run")
        assertEquals(mapOf("n" to "24.69135780246913578"), value(run, "doubled"))
        val paper = nested(preview.data, "paper")
        assertEquals(mapOf("includeZero" to true, "hideZero" to true), paper["browsing"])
        assertTrue(rows(paper).any { it["node"] == "blank" })
        assertFalse(rows(paper).any { it["node"] == "hidden" })
        assertTrue((nested(preview.data, "difference")["changes"] as List<*>).isNotEmpty())
        assertTrue(original.contentEquals(Files.readAllBytes(root.resolve(caseId))))

        val schemaRoot = Path.of("docs/workbench/schema")
        val schemas = Files.list(schemaRoot).use { files ->
            files.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/preview-paper.schema.json"),
        )
        val errors = schema.validate(
            WorkbenchJson.write(WorkbenchJson.envelope(preview.revision, "test", "test", preview.data)),
            InputFormat.JSON,
        )
        assertTrue(errors.isEmpty(), errors.toString())

        val committed = catalog.commitEdits(caseId, base.revision, change("12.34567890123456789"))
        assertEquals(preview.data["proposedRevision"], committed.revision)
        assertEquals(run["values"], catalog.document(caseId, "run").data["values"])
        val savedPaper = catalog.document(caseId, "paper", "main", includeZero = true)
        assertEquals(rows(paper).map { it["cells"] }, rows(savedPaper.data).map { it["cells"] })
    }

    @Test fun `new and existing previews and text parsing leave the formal cache and history untouched`() {
        val catalog = workspace()
        val base = catalog.document(caseId, "run")
        val stats = catalog.sessions.stats(caseId)
        assertTrue(stats != null)
        catalog.previewPaper(caseId, base.revision, change("21"), 0)
        catalog.previewEdits(caseId, base.revision, change("22"))
        catalog.parseEditText(caseId, "base-amount", false, "23")
        assertEquals(stats, catalog.sessions.stats(caseId))
        val emptyUndo = assertFailsWith<WorkspaceException> { catalog.undo(caseId, base.revision) }
        assertEquals(WorkspaceProblem.REQUEST, emptyUndo.problem)
        val current = catalog.document(caseId, "run")
        assertEquals(base.revision, current.revision)
        assertEquals(base.data["values"], current.data["values"])
        assertEquals(0, requireNotNull(catalog.sessions.stats(caseId)).formulaEvaluations)
        assertEquals(
            "(case c {:schema \"test/preview\"} (inputs {:base-amount 10}))",
            Files.readString(root.resolve(caseId)),
        )
    }

    @Test fun `business failure returns current candidate values and diagnostics without blocking preview`() {
        val catalog = workspace()
        val base = catalog.document(caseId, "run")
        val preview = catalog.previewPaper(caseId, base.revision, change("-7"), 1)
        assertEquals(true, preview.data["succeeded"])
        assertEquals(false, preview.data["validationPassed"])
        assertEquals(mapOf("n" to "-14"), value(nested(preview.data, "run"), "doubled"))
        assertTrue(
            (preview.data["diagnostics"] as List<*>).any {
                (it as Map<*, *>)["code"] == "MANTRA-CHECK-FAILED"
            },
        )
        assertEquals(base.revision, catalog.document(caseId, "run").revision)
    }

    @Test fun `runtime failure keeps current partial results rather than the old successful values`() {
        val catalog = workspace()
        val schemaFile = root.resolve("schema.mantra")
        Files.writeString(schemaFile, Files.readString(schemaFile).replace("(* base-amount 2)", "(/ 100 base-amount)"))
        val base = catalog.document(caseId, "run")
        val preview = catalog.previewPaper(caseId, base.revision, change("0"), 2)
        assertEquals(false, preview.data["succeeded"])
        assertEquals(mapOf("n" to "0"), value(nested(preview.data, "run"), "observed"))
        assertNotEquals(mapOf("n" to "10"), value(nested(preview.data, "run"), "doubled"))
        assertTrue(
            (preview.data["diagnostics"] as List<*>).any {
                (it as Map<*, *>)["code"] == "MANTRA-EVALUATION"
            },
        )
        assertEquals(base.revision, catalog.document(caseId, "run").revision)
    }

    @Test fun `malformed facts missing panels and unsafe sequences reject without writes`() {
        val catalog = workspace()
        val base = catalog.document(caseId, "run")
        val original = Files.readAllBytes(root.resolve(caseId))
        val malformed = assertFailsWith<WorkspaceException> {
            catalog.previewPaper(
                caseId,
                base.revision,
                listOf(CaseTextEditor.Operation.SetInput("base-amount", Value.Text("bad"))),
                3,
            )
        }
        assertEquals(WorkspaceProblem.INVALID, malformed.problem)
        assertTrue(malformed.diagnostics.any { it.code == "MANTRA-INPUT-TYPE" })
        assertEquals(
            WorkspaceProblem.NOT_FOUND,
            assertFailsWith<WorkspaceException> {
                catalog.previewPaper(caseId, base.revision, change("11"), 4, "missing")
            }.problem,
        )
        for (sequence in listOf(-1L, 9_007_199_254_740_992L)) {
            assertEquals(
                WorkspaceProblem.REQUEST,
                assertFailsWith<WorkspaceException> {
                    catalog.previewPaper(caseId, base.revision, change("11"), sequence)
                }.problem,
            )
        }
        assertTrue(original.contentEquals(Files.readAllBytes(root.resolve(caseId))))
    }

    @Test fun `stale baseline and participating source changes during preview return conflict`() {
        val catalog = workspace()
        val base = catalog.document(caseId, "run")
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> {
                catalog.previewPaper(caseId, "0".repeat(64), change("11"), 5)
            }.problem,
        )
        catalog.beforePreviewCheck = {
            val schemaFile = root.resolve("schema.mantra")
            Files.writeString(
                schemaFile,
                Files.readString(schemaFile).replace("(* base-amount 2)", "(* base-amount 3)"),
            )
        }
        val conflict =
            assertFailsWith<WorkspaceException> { catalog.previewPaper(caseId, base.revision, change("11"), 6) }
        assertEquals(WorkspaceProblem.CONFLICT, conflict.problem)
        assertEquals(catalog.document(caseId, "run").revision, conflict.currentRevision)
        assertEquals(
            "(case c {:schema \"test/preview\"} (inputs {:base-amount 10}))",
            Files.readString(root.resolve(caseId)),
        )
    }
}
