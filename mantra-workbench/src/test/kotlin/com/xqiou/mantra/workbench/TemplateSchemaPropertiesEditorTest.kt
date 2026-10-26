package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateSchemaPropertiesEditorTest {
    private val documentHandle = "a".repeat(64)
    private val simple = "(schema test/owners {:version \"1\"} (info answer \"Old\" 1))"

    private fun edit(
        text: String,
        operations: List<TemplateSchemaPropertyOperation> = emptyList(),
        includes: Map<String, SourceText> = emptyMap(),
        handle: String = documentHandle,
    ): TemplateSchemaPropertiesEditor.Result = TemplateSchemaPropertiesEditor.apply(
        SourceText("schema.mantra", text, "/captured"),
        text,
        handle,
        operations,
        SourceResolver { path, _ -> includes[path] },
    )

    private fun owner(
        result: TemplateSchemaPropertiesEditor.Result,
        node: String?,
        property: String,
    ): Map<String, Any?> = result.owners.single { it["node"] == node && it["property"] == property }

    private fun handle(owner: Map<String, Any?>): String = owner.getValue("handle") as String

    private fun textOp(owner: Map<String, Any?>, text: String): TemplateSchemaPropertyOperation =
        TemplateSchemaPropertyOperation("setText", handle(owner), text = text)

    private fun classOp(owner: Map<String, Any?>, vararg classes: String): TemplateSchemaPropertyOperation =
        TemplateSchemaPropertyOperation("setClasses", handle(owner), classes = classes.toList())

    private fun unset(owner: Map<String, Any?>): TemplateSchemaPropertyOperation =
        TemplateSchemaPropertyOperation("unset", handle(owner))

    private fun problem(expected: WorkspaceProblem, action: () -> Unit): WorkspaceException =
        assertFailsWith<WorkspaceException>(block = action).also { assertEquals(expected, it.problem) }

    private fun roundTrip(original: String, result: TemplateSchemaPropertiesEditor.Result) {
        var current = original
        result.patches.forEach { patch ->
            assertEquals(patch.removedText, current.substring(patch.startOffset, patch.endOffset))
            current = current.replaceRange(patch.startOffset, patch.endOffset, patch.text)
        }
        assertEquals(result.text, current)
        result.patches.asReversed().forEach { patch ->
            assertEquals(patch.text, current.substring(patch.startOffset, patch.startOffset + patch.text.length))
            current = current.replaceRange(
                patch.startOffset,
                patch.startOffset + patch.text.length,
                patch.removedText,
            )
        }
        assertEquals(original, current)
    }

    @Test fun `catalog uses declared item models and keeps hidden or zero definitions`() {
        val text = """
            (schema test/owners {:version "1"}
              (input base-amount :decimal)
              (param factor 2)
              (dimension member {:members [:A :B]})
              (defn example-text [] "(line fake-from-string \"Fake\" 9)")
              (section main "Raw section" {:title "Displayed title"}
                (field base-amount "Amount")
                (line ordinary "Line" 1)
                (subtract deduction "Deduction" 2)
                (info informational "Information" 3)
                (formula-slot override "Override" 0)
                (total checkpoint "Total")
                (choice chosen "Choice" {:rule :max}
                  (option :a "Option A" 1) (option :b "Option B" 2))
                (choose-min minimum "Minimum" (option :a "A" 1) (option :b "B" 2))
                (choose-max maximum "Maximum" (option :a "A" 1) (option :b "B" 2))
                (check checked "Check" true)
                (reconcile reconciled "Reconcile" 1 1)
                (note "(line fake-from-note \"Fake\" 9)")
                (section hidden "Hidden" {:display :hidden}
                  (info inactive "Inactive" 0 {:when false :hidden true})
                  (slot extras "Extras"))))
        """.trimIndent()
        val result = edit(text)
        assertEquals("test/owners", result.schemaId)
        assertEquals(text, result.text)
        assertTrue(result.patches.isEmpty())
        val expected = setOf(
            "section", "field", "line", "subtract", "info", "formula-slot", "total",
            "choice", "choose-min", "choose-max", "check", "reconcile", "note", "slot",
        )
        assertEquals(expected, result.owners.map { it["form"] }.toSet())
        assertEquals("Raw section", owner(result, "main", "label")["value"])
        assertEquals(2, result.owners.count { it["node"] == "base-amount" })
        assertEquals(2, result.owners.count { it["node"] == "inactive" })
        assertFalse(result.owners.any { it["node"] in setOf("factor", "member", "fake-from-string") })
        assertFalse(result.owners.any { it["node"] == "fake-from-note" })
        assertTrue(result.owners.all { it["editable"] == true && it["reason"] == null })
        assertEquals(result.owners.size, result.owners.map(::handle).distinct().size)
        assertEquals(result.owners, edit(text).owners)
    }

    @Test fun `batch edits bind initial owners and preserve comments CRLF and UTF16 source`() {
        val text = (
            "; 😀 keep prefix\n" +
                "(schema test/owners {:version \"1\"}\n" +
                "  (section main \"Old\" {:custom false} ; keep section\n" +
                "    (info answer \"Answer\" (* 2 3)) ; keep formula\n" +
                "    (note \"Original note\" {:custom :retained})))\n"
            ).replace("\n", "\r\n")
        val initial = edit(text)
        val label = owner(initial, "main", "label")
        val initialRange = label["range"] as Map<*, *>
        assertEquals(text.indexOf("\"Old\""), initialRange["startOffset"])
        val noteText = "新注释 🙂 \"quoted\"\n(line injected \"Fake\" 9)"
        val result = edit(
            text,
            listOf(
                textOp(label, "新标题 🙂 with more text"),
                classOp(owner(initial, "answer", "classes"), "subtotal", "custom"),
                textOp(owner(initial, null, "text"), noteText),
                classOp(owner(initial, null, "classes"), "muted"),
            ),
        )
        roundTrip(text, result)
        assertEquals("新标题 🙂 with more text", owner(result, "main", "label")["value"])
        assertEquals(noteText, owner(result, null, "text")["value"])
        assertEquals(
            listOf(mapOf("kw" to "subtotal"), mapOf("kw" to "custom")),
            owner(result, "answer", "classes")["value"],
        )
        assertTrue(result.text.contains("; 😀 keep prefix\r\n"))
        assertTrue(result.text.contains("{:custom false} ; keep section\r\n"))
        assertTrue(result.text.contains("(* 2 3)"))
        assertTrue(result.text.contains("; keep formula\r\n"))
        assertTrue(result.text.contains(":custom :retained"))
        assertFalse(result.owners.any { it["node"] == "injected" })
        assertNotEquals(handle(label), handle(owner(result, "main", "label")))
    }

    @Test fun `class omission empty override and removal have different source states`() {
        val initial = edit(simple)
        val missing = owner(initial, "answer", "classes")
        assertEquals(false, missing["declared"])
        assertNull(missing["value"])
        assertNull(missing["range"])
        assertEquals(false, missing["canUnset"])
        val explicit = edit(simple, listOf(classOp(missing)))
        roundTrip(simple, explicit)
        val empty = owner(explicit, "answer", "classes")
        assertEquals(true, empty["declared"])
        assertEquals(emptyList<Any>(), empty["value"])
        assertNotNull(empty["range"])
        assertEquals(true, empty["canUnset"])
        assertTrue(explicit.text.contains(":class []"))
        val removed = edit(explicit.text, listOf(unset(empty)))
        roundTrip(explicit.text, removed)
        assertEquals(false, owner(removed, "answer", "classes")["declared"])
        assertFalse(removed.text.contains(":class"))
        assertTrue(removed.text.contains("(info answer \"Old\" 1 {"))
        val absentAgain = edit(removed.text, listOf(unset(owner(removed, "answer", "classes"))))
        assertTrue(absentAgain.patches.isEmpty())
    }

    @Test fun `equivalent declared class tags keep keyword vector and duplicate spelling`() {
        listOf(":subtotal", "[:subtotal]", "[:subtotal :subtotal]").forEach { form ->
            val text = "(schema test/owners (info answer \"Label\" 1 {:class $form}))"
            val initial = edit(text)
            val classes = owner(initial, "answer", "classes")
            assertEquals(listOf(mapOf("kw" to "subtotal")), classes["value"])
            val result = edit(text, listOf(classOp(classes, "subtotal")))
            assertEquals(text, result.text)
            assertTrue(result.patches.isEmpty())
            assertEquals(initial.owners, result.owners)
        }
        val same = edit(simple, listOf(textOp(owner(edit(simple), "answer", "label"), "Old")))
        assertEquals(simple, same.text)
        assertTrue(same.patches.isEmpty())
    }

    @Test fun `unset removes only class tokens and retains intervening comments and other properties`() {
        val text = """
            (schema test/owners
              (info answer "Old" 1 {:custom false :class ; keep between key and value
                [:subtotal :muted] ; keep after value
                :note "Preserved"}))
        """.trimIndent().replace("\n", "\r\n")
        val result = edit(text, listOf(unset(owner(edit(text), "answer", "classes"))))
        roundTrip(text, result)
        assertEquals(2, result.patches.size)
        assertFalse(result.text.contains(":class"))
        assertFalse(result.text.contains("[:subtotal :muted]"))
        assertTrue(result.text.contains("; keep between key and value\r\n"))
        assertTrue(result.text.contains("; keep after value\r\n"))
        assertTrue(result.text.contains(":custom false"))
        assertTrue(result.text.contains(":note \"Preserved\""))
    }

    @Test fun `captured includes produce source positioned owners that remain read only`() {
        val text = "(schema test/owners (include \"fragment.mantra\") (info own \"Own\" 2))"
        val fragment = SourceText(
            "fragment.mantra",
            "(fragment (section shared \"Shared\" (info included \"Included\" 1 {:class :custom})))",
            "/captured",
        )
        val includes = mapOf("fragment.mantra" to fragment)
        val result = edit(text, includes = includes)
        val included = owner(result, "included", "classes")
        assertEquals("fragment.mantra", included["document"])
        assertEquals(false, included["editable"])
        assertEquals(false, included["canUnset"])
        assertTrue((included["reason"] as String).isNotBlank())
        val range = included["range"] as Map<*, *>
        assertEquals(fragment.text.indexOf(":custom"), range["startOffset"])
        problem(WorkspaceProblem.REQUEST) {
            edit(text, listOf(classOp(included, "subtotal")), includes)
        }
        val own = owner(result, "own", "label")
        assertEquals(true, own["editable"])
        val changed = edit(text, listOf(textOp(own, "Changed")), includes)
        assertEquals("Changed", owner(changed, "own", "label")["value"])
        assertEquals(included["value"], owner(changed, "included", "classes")["value"])
        assertNotEquals(handle(included), handle(owner(changed, "included", "classes")))
    }

    @Test fun `owners expire with any draft bytes or document handle change`() {
        val initial = edit(simple)
        val label = owner(initial, "answer", "label")
        val operation = textOp(label, "New")
        problem(WorkspaceProblem.REQUEST) { edit(simple + "\n; changed", listOf(operation)) }
        problem(WorkspaceProblem.REQUEST) { edit(simple, listOf(operation), handle = "b".repeat(64)) }
        val other = edit(simple + "\n; changed")
        assertTrue(initial.owners.zip(other.owners).all { (left, right) -> handle(left) != handle(right) })
    }

    @Test fun `bad owner and operation types cannot change the source`() {
        val initial = edit(simple)
        val label = owner(initial, "answer", "label")
        val classes = owner(initial, "answer", "classes")
        val operations = listOf(
            TemplateSchemaPropertyOperation("setText", "f".repeat(64), text = "New"),
            textOp(classes, "Wrong type"),
            classOp(label, "custom"),
            unset(label),
            TemplateSchemaPropertyOperation("unknown", handle(label)),
            TemplateSchemaPropertyOperation("setText", handle(label)),
            TemplateSchemaPropertyOperation("setClasses", handle(classes), classes = listOf("custom", "custom")),
            TemplateSchemaPropertyOperation("setClasses", handle(classes), classes = listOf("with/slash")),
            TemplateSchemaPropertyOperation("setClasses", handle(classes), classes = List(65) { "name-$it" }),
        )
        operations.forEach { operation ->
            problem(WorkspaceProblem.REQUEST) { edit(simple, listOf(operation)) }
        }
        val duplicate = textOp(label, "New")
        problem(WorkspaceProblem.REQUEST) { edit(simple, listOf(duplicate, duplicate)) }
        assertEquals(initial.owners, edit(simple).owners)
    }

    @Test fun `public reader errors retain diagnostics and precise source locations`() {
        val duplicate = "(schema test/owners (info answer \"Old\" 1 {:class :one :class :two}))"
        val error = problem(WorkspaceProblem.INVALID) { edit(duplicate) }
        assertTrue(error.diagnostics.any { it.code == "MANTRA-TEMPLATE-SCHEMA-PROPERTY" })
        val original = error.diagnostics.single { it.code == "MANTRA-READ-DUPLICATE-KEY" }
        assertEquals("schema.mantra", original.location?.source)
        assertEquals(duplicate.indexOf(":class :two"), original.location?.startOffset)
        val broken = problem(WorkspaceProblem.INVALID) { edit("(schema broken") }
        assertTrue(broken.diagnostics.any { it.code == "MANTRA-READ-SYNTAX" })
        val malformed = problem(WorkspaceProblem.INVALID) {
            edit("(schema test/owners (info answer \"Old\" 1 {:class [bad-symbol]}))")
        }
        assertTrue(malformed.diagnostics.any { it.code == "MANTRA-SCHEMA-CLASS" })
    }

    @Test fun `formula binding errors do not prevent editing valid presentation source`() {
        val text = "(schema test/owners (info answer \"Old\" (+ unknown-node 1)))"
        val result = edit(text, listOf(textOp(owner(edit(text), "answer", "label"), "New")))
        assertEquals("New", owner(result, "answer", "label")["value"])
        assertTrue(result.text.contains("(+ unknown-node 1)"))
        roundTrip(text, result)
    }

    @Test fun `text is an exact literal rather than executable DSL and controls cannot corrupt it`() {
        val text = "\") (info injected \"Injected\" 100) ; 中文 🙂\nnext line"
        val result = edit(simple, listOf(textOp(owner(edit(simple), "answer", "label"), text)))
        assertEquals(text, owner(result, "answer", "label")["value"])
        assertFalse(result.owners.any { it["node"] == "injected" })
        roundTrip(simple, result)
        val empty = edit(simple, listOf(textOp(owner(edit(simple), "answer", "label"), "")))
        assertEquals("", owner(empty, "answer", "label")["value"])
        problem(WorkspaceProblem.INVALID) {
            edit(simple, listOf(textOp(owner(edit(simple), "answer", "label"), "bad\u0000text")))
        }
    }

    @Test fun `source budget is checked before inspect and after every growing patch`() {
        val prefix = "(schema test/owners (info answer \"A\" 1))\n;"
        val full = prefix + "x".repeat(65_536 - prefix.length)
        val inspected = edit(full)
        assertEquals(65_536, inspected.text.length)
        problem(WorkspaceProblem.TOO_LARGE) { edit(full + "x") }
        problem(WorkspaceProblem.TOO_LARGE) {
            edit(full, listOf(textOp(owner(inspected, "answer", "label"), "Growing")))
        }
    }

    @Test fun `unpaired Unicode cannot collide in owner hashes or corrupt text patches`() {
        listOf('\uD800', '\uD801', '\uDC00').forEach { broken ->
            val source = simple + "\n; " + broken
            val error = problem(WorkspaceProblem.INVALID) { edit(source) }
            val diagnostic = error.diagnostics.single()
            assertEquals("MANTRA-TEMPLATE-SCHEMA-PROPERTY", diagnostic.code)
            assertEquals(source.length - 1, diagnostic.location?.startOffset)
            assertEquals(source.length, diagnostic.location?.endOffset)
        }
        val before = edit(simple)
        val label = owner(before, "answer", "label")
        listOf("\uD800", "\uDC00").forEach { broken ->
            val error = problem(WorkspaceProblem.INVALID) {
                edit(simple, listOf(textOp(label, "Bad $broken text")))
            }
            assertEquals("MANTRA-TEMPLATE-SCHEMA-PROPERTY", error.diagnostics.single().code)
            assertEquals(before.owners, edit(simple).owners)
        }
        val source = simple + "\n; valid 🙂 source"
        val inspected = edit(source)
        val result = edit(source, listOf(textOp(owner(inspected, "answer", "label"), "Good 🙂 text")))
        assertEquals("Good 🙂 text", owner(result, "answer", "label")["value"])
        roundTrip(source, result)
    }
}
