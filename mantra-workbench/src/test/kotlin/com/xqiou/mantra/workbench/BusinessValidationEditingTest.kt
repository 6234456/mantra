package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
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

/** Business findings persist with the case, while malformed facts retain write rejection. */
class BusinessValidationEditingTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var directory: Path
    private val caseId = "case.mantra"

    private fun workspace(): WorkspaceCatalog {
        Files.writeString(
            directory.resolve("schema.mantra"),
            """
            (schema test/business-edits {:title "Business edits" :mainline [summary]}
              (input base-amount :decimal)
              (input reviewed :boolean {:default true})
              (input reason :text {:optional true :required-when reviewed})
              (input entries :table
                {:min-rows 1
                 :columns {:mode :keyword
                           :note {:type :text? :required-when (= row.mode :direct)}
                           :amount :decimal}})
              (section summary "Summary" {:display :schedule}
                (line observed "Observed amount" base-amount {:op :info})
                (line entries-total "Entry total" (sum (map (fn [row] row.amount) entries)) {:op :info})
                (check nonnegative "Nonnegative amount" (>= observed 0))))
            """.trimIndent(),
        )
        Files.writeString(
            directory.resolve(caseId),
            """
            (case sample {:schema "test/business-edits"}
              (inputs {:base-amount 10 :reason "Reviewed"
                       :entries [{:mode :pooled :note nil :amount 2}
                                 {:mode :direct :note "Allocated" :amount 3}]}))
            """.trimIndent(),
        )
        return workspaceCatalog(directory)
    }

    private fun run(result: WorkspaceCatalog.DocumentResult): Map<*, *> = result.data["run"] as Map<*, *>

    private fun findings(result: WorkspaceCatalog.DocumentResult): List<Map<*, *>> =
        (result.data["diagnostics"] as List<*>).map { it as Map<*, *> }

    @Test
    fun `a business error commits successfully and restoring the fact clears validation failure`() {
        val catalog = workspace()
        val initial = catalog.document(caseId, "run")
        assertEquals(true, initial.data["validationPassed"])
        val committed = catalog.commitEdits(
            caseId,
            initial.revision,
            listOf(CaseTextEditor.Operation.SetInput("base-amount", Value.num(-10))),
        )
        assertEquals(true, run(committed)["succeeded"])
        assertEquals(false, run(committed)["validationPassed"])
        assertContains(Files.readString(directory.resolve(caseId)), ":base-amount -10")
        val finding = findings(committed).single()
        assertEquals("business", finding["category"])
        assertEquals("error", finding["severity"])
        assertEquals("MANTRA-CHECK-FAILED", finding["code"])
        val restored = catalog.commitEdits(
            caseId,
            committed.revision,
            listOf(CaseTextEditor.Operation.SetInput("base-amount", Value.num(10))),
        )
        assertEquals(true, run(restored)["succeeded"])
        assertEquals(true, run(restored)["validationPassed"])
        assertEquals(emptyList(), findings(restored))
        assertEquals(true, catalog.document(caseId, "run").data["validationPassed"])
    }

    @Test
    fun `blank conditional table cells become nil and retain the original row and cell address`() {
        val catalog = workspace()
        val initial = catalog.document(caseId, "run")
        val row = catalog.parseEditorRowText(
            caseId,
            "entries",
            mapOf("mode" to "direct", "note" to "  ", "amount" to "3"),
        )
        assertEquals(Value.Nil, row.entries[Value.Kw("note")])
        val committed = catalog.commitEdits(
            caseId,
            initial.revision,
            listOf(CaseTextEditor.Operation.UpdateRow("entries", 1, row)),
        )
        assertEquals(true, run(committed)["succeeded"])
        assertEquals(false, run(committed)["validationPassed"])
        val finding = findings(committed).single()
        assertEquals("business", finding["category"])
        assertEquals("MANTRA-INPUT-REQUIRED", finding["code"])
        assertEquals(1, finding["rowIndex"])
        assertEquals("note", finding["column"])
        val address = finding["address"] as Map<*, *>
        assertEquals("entries", address["node"])
        assertEquals(mapOf("row" to "1", "column" to "note"), address["cell"])
        val saved = Mantra.loadCase(directory.resolve(caseId))
        assertEquals(2, (saved.inputs.getValue("entries") as Value.Vec).items.size)
        val amounts = run(committed)["values"] as Map<*, *>
        val amount = ((amounts["entries-total"] as Map<*, *>)[""] as Map<*, *>)["value"] as Map<*, *>
        assertEquals("5", amount["n"])
        val restored = catalog.commitEdits(
            caseId,
            committed.revision,
            listOf(
                CaseTextEditor.Operation.UpdateRow(
                    "entries",
                    1,
                    catalog.parseEditorRowText(
                        caseId,
                        "entries",
                        mapOf("mode" to "direct", "note" to "Allocated", "amount" to "3"),
                    ),
                ),
            ),
        )
        assertEquals(true, run(restored)["validationPassed"])
    }

    @Test
    fun `clearing a conditionally required scalar saves the missing fact and its finding`() {
        val catalog = workspace()
        val initial = catalog.document(caseId, "run")
        val committed = catalog.commitEdits(
            caseId,
            initial.revision,
            listOf(CaseTextEditor.Operation.ClearInput("reason")),
        )
        assertEquals(true, run(committed)["succeeded"])
        assertEquals(false, run(committed)["validationPassed"])
        val finding = findings(committed).single()
        assertEquals("MANTRA-INPUT-REQUIRED", finding["code"])
        assertEquals("business", finding["category"])
        assertFalse(Mantra.loadCase(directory.resolve(caseId)).inputs.containsKey("reason"))
    }

    @Test
    fun `malformed numeric facts and row text remain rejected without changing the file`() {
        val catalog = workspace()
        val original = Files.readString(directory.resolve(caseId))
        val initial = catalog.document(caseId, "run")
        val rejected = assertFailsWith<WorkspaceException> {
            catalog.commitEdits(
                caseId,
                initial.revision,
                listOf(CaseTextEditor.Operation.SetInput("base-amount", Value.Text("not a number"))),
            )
        }
        assertEquals(WorkspaceProblem.INVALID, rejected.problem)
        assertTrue(rejected.diagnostics.any { it.code == "MANTRA-INPUT-TYPE" })
        val invalidRow = assertFailsWith<WorkspaceException> {
            catalog.parseEditorRowText(
                caseId,
                "entries",
                mapOf("mode" to "direct", "note" to "A reason", "amount" to "not a number"),
            )
        }
        assertEquals(WorkspaceProblem.INVALID, invalidRow.problem)
        val unknownBlankColumn = assertFailsWith<WorkspaceException> {
            catalog.parseEditorRowText(caseId, "entries", mapOf("unknown-column" to ""))
        }
        assertEquals(WorkspaceProblem.INVALID, unknownBlankColumn.problem)
        assertEquals(original, Files.readString(directory.resolve(caseId)))
        assertEquals(initial.revision, catalog.document(caseId, "run").revision)
    }
}
