package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceText
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaseRowsEditingTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var temp: Path

    private val original = """(case demo {:schema "test/rows" :title "🔎 Example"}
  ;; Unrelated text must survive.
  (inputs {:items (rows [:id :amount :note]
    [:A 12.5000 "first"] ; first row
    [:B 0 nil]) :outside 7}))"""

    private fun rows(text: String) = (
        Mantra.loadCase(SourceText("case.mantra", text))
            .inputs.getValue("items") as Value.Vec
        ).items.map { it as Value.MapV }

    @Test
    fun `keyed compact cell edits preserve every other byte and sequential spans`() {
        val edited = CaseTextEditor.apply(
            original,
            listOf(
                CaseTextEditor.Operation.SetCell("items", "A", "amount", Value.num("19.2500"), "id"),
                CaseTextEditor.Operation.SetCell("items", "B", "note", Value.Text("updated"), "id"),
            ),
        )
        assertEquals(original.replace("12.5000", "19.2500").replace("[:B 0 nil]", "[:B 0 \"updated\"]"), edited)
        assertEquals(Value.num("19.2500"), rows(edited)[0].entries[Value.Kw("amount")])
        assertEquals(Value.Text("updated"), rows(edited)[1].entries[Value.Kw("note")])
    }

    @Test
    fun `compact indexed cell edits retain rows spelling and explicit nil`() {
        val edited = CaseTextEditor.apply(
            original,
            listOf(CaseTextEditor.Operation.SetCell("items", "0", "note", Value.Nil, null)),
        )
        assertEquals(original.replace("\"first\"", "nil"), edited)
        assertEquals(Value.Nil, rows(edited)[0].entries[Value.Kw("note")])
    }

    @Test
    fun `clear and absent column additions expand only the table and preserve absence separately from nil`() {
        val clear = CaseTextEditor.apply(
            original,
            listOf(CaseTextEditor.Operation.ClearCell("items", "A", "note", "id")),
        )
        assertFalse(rows(clear)[0].entries.containsKey(Value.Kw("note")))
        assertEquals(Value.Nil, rows(clear)[1].entries[Value.Kw("note")])
        assertEquals(original.substringBefore("(rows"), clear.substringBefore("[{:id"))
        assertEquals(original.substringAfter("])"), clear.substringAfter("}]"))
        val add = CaseTextEditor.apply(
            original,
            listOf(CaseTextEditor.Operation.SetCell("items", "B", "extra", Value.Bool(false), "id")),
        )
        assertFalse(rows(add)[0].entries.containsKey(Value.Kw("extra")))
        assertEquals(Value.Bool(false), rows(add)[1].entries[Value.Kw("extra")])
    }

    @Test
    fun `row operations retain equivalent records after compact table expansion`() {
        val row = Value.MapV(linkedMapOf(Value.Kw("id") to Value.Kw("C"), Value.Kw("amount") to Value.num("2")))
        val originalRows = rows(original)
        val cases = listOf(
            CaseTextEditor.Operation.InsertRow("items", row, 1) to listOf(originalRows[0], row, originalRows[1]),
            CaseTextEditor.Operation.UpdateRow("items", 0, row) to listOf(row, originalRows[1]),
            CaseTextEditor.Operation.DeleteRow("items", 0) to listOf(originalRows[1]),
            CaseTextEditor.Operation.MoveRow("items", 0, 1) to originalRows.reversed(),
        )
        cases.forEach { (operation, expected) ->
            val changed = CaseTextEditor.apply(original, listOf(operation))
            assertEquals(expected, rows(changed))
            assertTrue(changed.contains(";; Unrelated text must survive."))
            assertTrue(changed.endsWith(" :outside 7}))"))
        }
        val empty = "(case demo (inputs {:items (rows [:id :amount])}))"
        assertEquals(
            listOf(row),
            rows(CaseTextEditor.apply(empty, listOf(CaseTextEditor.Operation.InsertRow("items", row)))),
        )
    }

    @Test
    fun `workbench preview commit undo and redo keep compact source and revisions`() {
        Files.writeString(
            temp.resolve("schema.mantra"),
            """(schema test/rows {:mainline [main]}
              (input outside :decimal)
              (input items :table {:columns {:id :keyword :amount :decimal :note :any?}})
              (dimension item {:from items :key :id})
              (section main "Main" {:panel true}
                (line answer "Answer" (sum (map (fn [entry] entry.amount) items)))))""",
        )
        val file = temp.resolve("case.mantra")
        Files.writeString(file, original)
        val catalog = workspaceCatalog(temp)
        val revision = catalog.document("case.mantra", "run").revision
        val operation = listOf(CaseTextEditor.Operation.SetCell("items", "A", "amount", Value.num("13.5000"), "id"))
        val preview = catalog.previewEdits("case.mantra", revision, operation)
        assertEquals(original, Files.readString(file))
        val changed = catalog.commitEdits("case.mantra", revision, operation)
        assertEquals(preview.data["proposedRevision"], changed.revision)
        val expected = original.replace("12.5000", "13.5000")
        assertEquals(expected, Files.readString(file))
        val undo = catalog.undo("case.mantra", changed.revision)
        assertEquals(revision, undo.revision)
        assertEquals(original, Files.readString(file))
        val redo = catalog.redo("case.mantra", undo.revision)
        assertEquals(changed.revision, redo.revision)
        assertEquals(expected, Files.readString(file))
        val cleared = catalog.commitEdits(
            "case.mantra",
            redo.revision,
            listOf(CaseTextEditor.Operation.ClearCell("items", "A", "note", "id")),
        )
        assertFalse(rows(Files.readString(file))[0].entries.containsKey(Value.Kw("note")))
        catalog.undo("case.mantra", cleared.revision)
        assertEquals(expected, Files.readString(file))
    }
}
