package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TemplateLayoutSettingsTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var root: Path

    private val defaultLayout = "(layout example/layout {:title \"Original\" :locale \"de-DE\"})"

    private fun workspace(layout: String = defaultLayout, formula: String = "(* base-amount 2)"): WorkspaceCatalog {
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema example/settings {:version "1" :mainline [main]}
              (include "fragment.mantra")
              (input base-amount :decimal)
              (section main "Main" {:panel true}
                (field base-amount "Amount" {:op :info})
                (info answer "Answer" $formula)
                (check nonnegative "Nonnegative" (>= base-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(root.resolve("fragment.mantra"), "(fragment (defn twice [^Decimal value] (* value 2)))")
        Files.writeString(root.resolve("layout.mantra"), layout)
        Files.writeString(
            root.resolve("case.mantra"),
            "(case original {:schema \"example/settings\" :layout \"example/layout\"}\r\n  (inputs {:base-amount 10}))\r\n",
        )
        return workspaceCatalog(root).also { catalog ->
            try {
                catalog.document("case.mantra", "run")
            } catch (failure: WorkspaceException) {
                throw AssertionError("Invalid settings fixture: ${failure.diagnostics}", failure)
            }
        }
    }

    private fun documents(snapshot: WorkspaceCatalog.DocumentResult): List<Map<*, *>> =
        (snapshot.data["documents"] as List<*>).map { it as Map<*, *> }

    private fun request(
        snapshot: WorkspaceCatalog.DocumentResult,
        text: String? = null,
        operations: List<TemplateLayoutOperation> = emptyList(),
    ): TemplateLayoutSettingsRequest {
        val layout = documents(snapshot).single { it["role"] == "layout" }
        return TemplateLayoutSettingsRequest(
            snapshot.revision,
            (snapshot.data["baseRevisions"] as Map<*, *>).entries.associate { it.key as String to it.value as String },
            17,
            layout["handle"] as String,
            text ?: layout["text"] as String,
            operations,
        )
    }

    private fun property(result: WorkspaceCatalog.DocumentResult, id: String): Map<*, *> =
        (result.data["properties"] as List<*>).map { it as Map<*, *> }.single { it["id"] == id }

    private fun source(result: WorkspaceCatalog.DocumentResult): String =
        (result.data["source"] as Map<*, *>)["text"] as String

    private fun patches(result: WorkspaceCatalog.DocumentResult): List<Map<*, *>> =
        (result.data["patches"] as List<*>).map { it as Map<*, *> }

    private fun exactValues(run: Map<*, *>): Map<Any?, Map<Any?, Any?>> =
        (run["values"] as Map<*, *>).entries.associate { (id, coordinates) ->
            id to (coordinates as Map<*, *>).entries.associate { (coordinate, value) ->
                coordinate to (value as Map<*, *>)["value"]
            }
        }

    private fun assertRoundTrip(original: String, result: WorkspaceCatalog.DocumentResult) {
        var current = original
        patches(result).forEach { patch ->
            val start = (patch["startOffset"] as Number).toInt()
            val end = (patch["endOffset"] as Number).toInt()
            assertEquals(patch["removedText"], current.substring(start, end))
            current = current.replaceRange(start, end, patch["text"] as String)
        }
        assertEquals(source(result), current)
        patches(result).asReversed().forEach { patch ->
            val start = (patch["startOffset"] as Number).toInt()
            val inserted = patch["text"] as String
            assertEquals(inserted, current.substring(start, start + inserted.length))
            current = current.replaceRange(start, start + inserted.length, patch["removedText"] as String)
        }
        assertEquals(original, current)
    }

    private fun problem(expected: WorkspaceProblem, action: () -> Unit): WorkspaceException =
        assertFailsWith<WorkspaceException>(block = action).also { assertEquals(expected, it.problem) }

    @Test fun `catalogue describes fourteen root settings from the actual preset and matches its JSON schema`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val result = catalog.templateLayoutSettings("case.mantra", request(snapshot))
        assertEquals(snapshot.revision, result.revision)
        assertEquals(17L, result.data["draftSequence"])
        assertEquals(snapshot.data["baseRevisions"], result.data["baseRevisions"])
        assertEquals("layout", result.data["scope"])
        assertEquals("example/layout", result.data["layoutId"])
        assertEquals("de-staffel-4", result.data["preset"])
        assertEquals(14, (result.data["properties"] as List<*>).size)
        assertEquals(
            setOf("report", "numbers", "visibility"),
            (result.data["properties"] as List<*>).map {
                (it as Map<*, *>)["group"]
            }.toSet(),
        )
        assertEquals("Original", property(result, "title")["explicitValue"])
        assertEquals(null, property(result, "title")["inheritedValue"])
        assertEquals(false, property(result, "precision")["declared"])
        assertEquals(mapOf("n" to "2"), property(result, "precision")["effectiveValue"])
        assertEquals(true, property(result, "hide-zero")["inheritedValue"])
        assertEquals(listOf("de", "en"), property(result, "language")["options"])
        assertEquals(mapOf("kw" to "de"), property(result, "language")["effectiveValue"])
        assertEquals(emptyList<Any>(), result.data["patches"])
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { files ->
            files.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/template-layout-settings.schema.json"),
        )
        val errors = schema.validate(catalog.envelope(result), InputFormat.JSON)
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun `explicit false zero and empty text stay distinct from inherited values and unset`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val original = request(snapshot).text
        val edited = catalog.templateLayoutSettings(
            "case.mantra",
            request(
                snapshot,
                operations = listOf(
                    TemplateLayoutOperation("set", "grouping", "false"),
                    TemplateLayoutOperation("set", "precision", "0"),
                    TemplateLayoutOperation("set", "zero", ""),
                ),
            ),
        )
        assertEquals(false, property(edited, "grouping")["explicitValue"])
        assertEquals(mapOf("n" to "0"), property(edited, "precision")["explicitValue"])
        assertEquals("", property(edited, "zero")["explicitValue"])
        listOf("grouping", "precision", "zero").forEach { assertEquals(true, property(edited, it)["declared"]) }
        assertRoundTrip(original, edited)
        val reset = catalog.templateLayoutSettings(
            "case.mantra",
            request(
                snapshot,
                source(edited),
                listOf("grouping", "precision", "zero").map {
                    TemplateLayoutOperation("unset", it)
                },
            ),
        )
        listOf("grouping", "precision", "zero").forEach { assertEquals(false, property(reset, it)["declared"]) }
        assertEquals(true, property(reset, "grouping")["effectiveValue"])
        assertEquals(mapOf("n" to "2"), property(reset, "precision")["effectiveValue"])
        assertEquals(null, property(reset, "zero")["effectiveValue"])
        assertRoundTrip(source(edited), reset)
    }

    @Test fun `unset preserves comments between tokens and exact CRLF text outside them`() {
        val original = "; 😀 source\r\n" +
            "(layout example/layout {:hide-zero ; keep this reason\r\n true :grouping false})\r\n"
        val catalog = workspace(original)
        val snapshot = catalog.templateSources("case.mantra")
        val result = catalog.templateLayoutSettings(
            "case.mantra",
            request(snapshot, operations = listOf(TemplateLayoutOperation("unset", "hide-zero"))),
        )
        val expected = "; 😀 source\r\n" +
            "(layout example/layout { ; keep this reason\r\n  :grouping false})\r\n"
        assertEquals(expected, source(result))
        assertEquals(false, property(result, "hide-zero")["declared"])
        assertEquals(true, property(result, "hide-zero")["effectiveValue"])
        assertRoundTrip(original, result)
    }

    @Test fun `value patches use UTF16 offsets and preserve unrelated source spelling and declarations`() {
        val original = "; 😀\r\n(layout example/layout {:title \"Old\" :precision 2.0 :extra \"kept\"}\r\n" +
            "  (style {:all true} {:tone :muted}))\r\n"
        val catalog = workspace(original)
        val snapshot = catalog.templateSources("case.mantra")
        val result = catalog.templateLayoutSettings(
            "case.mantra",
            request(snapshot, operations = listOf(TemplateLayoutOperation("set", "title", "New \"quote\"\nline"))),
        )
        val patch = patches(result).single()
        assertEquals(original.indexOf("\"Old\""), patch["startOffset"])
        assertEquals(original.indexOf("\"Old\"") + "\"Old\"".length, patch["endOffset"])
        assertTrue(source(result).contains(":precision 2.0 :extra \"kept\""))
        assertTrue(source(result).endsWith("\r\n  (style {:all true} {:tone :muted}))\r\n"))
        assertEquals("New \"quote\"\nline", property(result, "title")["effectiveValue"])
        assertRoundTrip(original, result)
    }

    @Test fun `a missing metadata map is inserted without rewriting the layout body`() {
        val original = "(layout example/layout\r\n  (style {:all true} {:weight :bold}))\r\n"
        val catalog = workspace(original)
        val result = catalog.templateLayoutSettings(
            "case.mantra",
            request(
                catalog.templateSources("case.mantra"),
                operations = listOf(TemplateLayoutOperation("set", "subtitle", "Added")),
            ),
        )
        assertEquals(
            "(layout example/layout {:subtitle \"Added\"}\r\n  (style {:all true} {:weight :bold}))\r\n",
            source(result),
        )
        assertRoundTrip(original, result)
    }

    @Test fun `equivalent set and absent unset leave original escaped spelling unchanged`() {
        val original = "(layout example/layout {:title \"\\u0061\" :precision 2.0})"
        val catalog = workspace(original)
        val snapshot = catalog.templateSources("case.mantra")
        // The pinned public form reader retains this literal escape rather than decoding it to "a".
        val inspected = catalog.templateLayoutSettings("case.mantra", request(snapshot))
        assertEquals("\\u0061", property(inspected, "title")["effectiveValue"])
        val result = catalog.templateLayoutSettings(
            "case.mantra",
            request(
                snapshot,
                operations = listOf(
                    TemplateLayoutOperation("set", "title", "\\u0061"),
                    TemplateLayoutOperation("set", "precision", "2"),
                    TemplateLayoutOperation("unset", "subtitle"),
                ),
            ),
        )
        assertEquals(original, source(result))
        assertEquals(emptyList<Any>(), result.data["patches"])
    }

    @Test fun `manual unsupported values are read only and can be reset without evaluating expressions`() {
        val original = "(layout example/layout {:precision -2 :zero nil :title (+ 1 2) :extra \"preserved\"})"
        val catalog = workspace(original)
        val snapshot = catalog.templateSources("case.mantra")
        val inspected = catalog.templateLayoutSettings("case.mantra", request(snapshot))
        listOf("precision", "zero", "title").forEach {
            assertEquals(true, property(inspected, it)["declared"])
            assertEquals(false, property(inspected, it)["editable"])
            assertEquals(true, property(inspected, it)["canUnset"])
            assertTrue((property(inspected, it)["reason"] as String).isNotBlank())
        }
        assertEquals(mapOf("n" to "-2"), property(inspected, "precision")["explicitValue"])
        assertEquals(null, property(inspected, "title")["explicitValue"])
        assertEquals(null, property(inspected, "zero")["explicitValue"])
        assertTrue((property(inspected, "title")["reason"] as String).contains("not evaluated"))
        assertEquals(original, source(inspected))
        problem(WorkspaceProblem.INVALID) {
            catalog.templateLayoutSettings(
                "case.mantra",
                request(snapshot, operations = listOf(TemplateLayoutOperation("set", "precision", "2"))),
            )
        }
        val reset = catalog.templateLayoutSettings(
            "case.mantra",
            request(
                snapshot,
                operations = listOf("precision", "zero", "title").map {
                    TemplateLayoutOperation("unset", it)
                },
            ),
        )
        assertTrue(source(reset).contains(":extra \"preserved\""))
        listOf("precision", "zero", "title").forEach { assertEquals(false, property(reset, it)["declared"]) }
        assertRoundTrip(original, reset)
    }

    @Test fun `new text survives the real literal reader or fails without changing its meaning`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        for (text in listOf("Quoted \"title\"", "line one\nline two", "slash\\title", "中文😀", "\\u0061")) {
            val edited = catalog.templateLayoutSettings(
                "case.mantra",
                request(snapshot, operations = listOf(TemplateLayoutOperation("set", "title", text))),
            )
            assertEquals(text, property(edited, "title")["effectiveValue"])
            assertRoundTrip(defaultLayout, edited)
        }
        // Public reader probes show these JSON escapes retain spelling rather than the requested control character.
        for (text in listOf("a\bb", "a\u000cb", "a\u0001b")) {
            val error = problem(WorkspaceProblem.INVALID) {
                catalog.templateLayoutSettings(
                    "case.mantra",
                    request(snapshot, operations = listOf(TemplateLayoutOperation("set", "title", text))),
                )
            }
            assertTrue(error.diagnostics.any { it.code == "MANTRA-TEMPLATE-SETTING" })
        }
        assertEquals(defaultLayout, Files.readString(root.resolve("layout.mantra")))
    }

    @Test fun `set rejects unsupported types ranges locale fallbacks and expression injection`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val invalid = listOf(
            "precision" to "-1", "precision" to "19", "precision" to "2.0", "precision" to "(+ 1 2)",
            "percent-precision" to "", "grouping" to "FALSE", "hide-zero" to "0", "language" to "zh",
            "negative" to "red", "row-numbers" to "none", "locale" to "", "locale" to "de_DE", "locale" to "und",
        )
        invalid.forEach { (id, value) ->
            val error = problem(WorkspaceProblem.INVALID) {
                catalog.templateLayoutSettings(
                    "case.mantra",
                    request(snapshot, operations = listOf(TemplateLayoutOperation("set", id, value))),
                )
            }
            assertTrue(error.diagnostics.isNotEmpty(), "$id=$value must include a diagnostic")
        }
        assertEquals(defaultLayout, Files.readString(root.resolve("layout.mantra")))
    }

    @Test fun `duplicate metadata or changed layout identity is rejected rather than ambiguously patched`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        for (text in listOf(
            "(layout example/layout {:precision 2 :precision 3})",
            "(layout another/layout {})",
            "(layout example/layout {}",
        )) {
            val error =
                problem(WorkspaceProblem.INVALID) {
                    catalog.templateLayoutSettings("case.mantra", request(snapshot, text))
                }
            assertTrue(error.diagnostics.isNotEmpty())
        }
    }

    @Test fun `unknown duplicate malformed or oversized operations fail without altering files`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val valid = request(snapshot)
        val malformed = listOf(
            valid.copy(operations = listOf(TemplateLayoutOperation("set", "unknown", "1"))),
            valid.copy(operations = listOf(TemplateLayoutOperation("set", "title"))),
            valid.copy(operations = listOf(TemplateLayoutOperation("unset", "title", "extra"))),
            valid.copy(operations = listOf(TemplateLayoutOperation("delete", "title"))),
            valid.copy(
                operations = listOf(
                    TemplateLayoutOperation("unset", "title"),
                    TemplateLayoutOperation("unset", "title"),
                ),
            ),
            valid.copy(draftSequence = -1),
            valid.copy(draftSequence = 9_007_199_254_740_992),
            valid.copy(handle = "layout.mantra"),
        )
        malformed.forEach { bad ->
            problem(WorkspaceProblem.REQUEST) { catalog.templateLayoutSettings("case.mantra", bad) }
        }
        problem(WorkspaceProblem.TOO_LARGE) {
            catalog.templateLayoutSettings("case.mantra", valid.copy(text = "x".repeat(65_537)))
        }
        assertEquals(defaultLayout, Files.readString(root.resolve("layout.mantra")))
    }

    @Test fun `only the root layout handle can be edited and the complete baseline source map is required`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val valid = request(snapshot)
        documents(snapshot).filter { it["role"] != "layout" }.forEach { document ->
            problem(WorkspaceProblem.REQUEST) {
                catalog.templateLayoutSettings("case.mantra", valid.copy(handle = document["handle"] as String))
            }
        }
        problem(WorkspaceProblem.REQUEST) {
            catalog.templateLayoutSettings("case.mantra", valid.copy(handle = "f".repeat(64)))
        }
        problem(WorkspaceProblem.CONFLICT) {
            catalog.templateLayoutSettings("case.mantra", valid.copy(baseRevision = "f".repeat(64)))
        }
        problem(WorkspaceProblem.CONFLICT) {
            catalog.templateLayoutSettings(
                "case.mantra",
                valid.copy(
                    baseRevisions =
                    valid.baseRevisions - "fragment.mantra",
                ),
            )
        }
        problem(WorkspaceProblem.CONFLICT) {
            catalog.templateLayoutSettings(
                "case.mantra",
                valid.copy(
                    baseRevisions =
                    valid.baseRevisions + ("extra.mantra" to "f".repeat(64)),
                ),
            )
        }
    }

    @Test fun `changed deleted or damaged dependency remains a conflict with the captured source baseline`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val original = Files.readString(root.resolve("fragment.mantra"))
        for (changed in listOf(original + "\n; external", "(fragment", null)) {
            if (changed ==
                null
            ) {
                Files.delete(root.resolve("fragment.mantra"))
            } else {
                Files.writeString(root.resolve("fragment.mantra"), changed)
            }
            problem(WorkspaceProblem.CONFLICT) { catalog.templateLayoutSettings("case.mantra", request(snapshot)) }
            Files.writeString(root.resolve("fragment.mantra"), original)
        }
    }

    @Test fun `source changes during settings inspection are rechecked before returning the result`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        catalog.beforePreviewCheck =
            { Files.writeString(root.resolve("fragment.mantra"), "(fragment)\n; changed externally") }
        try {
            problem(WorkspaceProblem.CONFLICT) { catalog.templateLayoutSettings("case.mantra", request(snapshot)) }
        } finally {
            catalog.beforePreviewCheck = null
        }
    }

    @Test fun `settings neither modify workspace files nor history or exact calculation values`() {
        val catalog = workspace()
        val before = Files.list(root).use { paths -> paths.toList().associateWith(Files::readAllBytes) }
        val run = catalog.document("case.mantra", "run")
        val snapshot = catalog.templateSources("case.mantra")
        val result = catalog.templateLayoutSettings(
            "case.mantra",
            request(
                snapshot,
                operations = listOf(
                    TemplateLayoutOperation("set", "precision", "0"),
                    TemplateLayoutOperation("set", "negative", "parentheses"),
                ),
            ),
        )
        assertNotEquals(defaultLayout, source(result))
        before.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        assertEquals(run.data["values"], catalog.document("case.mantra", "run").data["values"])
        val preview = catalog.templatePreview(
            "case.mantra",
            TemplatePreviewRequest(
                snapshot.revision,
                request(snapshot).baseRevisions,
                18,
                listOf(TemplateSourceEdit(request(snapshot).handle, source(result))),
                emptyList(),
            ),
        )
        assertEquals(exactValues(run.data), exactValues(preview.data["run"] as Map<*, *>))
        problem(WorkspaceProblem.REQUEST) { catalog.undo("case.mantra", snapshot.revision) }
    }

    @Test fun `inheritance is resolved from the chosen preset rather than hard coded form defaults`() {
        val catalog = workspace("(layout example/layout {:preset :ifrs-schedule :precision 3 :signed false})")
        val snapshot = catalog.templateSources("case.mantra")
        val inspected = catalog.templateLayoutSettings("case.mantra", request(snapshot))
        assertEquals(mapOf("n" to "0"), property(inspected, "precision")["inheritedValue"])
        assertEquals(mapOf("n" to "3"), property(inspected, "precision")["effectiveValue"])
        assertEquals(true, property(inspected, "signed")["inheritedValue"])
        assertEquals(false, property(inspected, "signed")["effectiveValue"])
        assertEquals(mapOf("kw" to "en"), property(inspected, "language")["effectiveValue"])
        val reset = catalog.templateLayoutSettings(
            "case.mantra",
            request(
                snapshot,
                operations = listOf(
                    TemplateLayoutOperation("unset", "precision"),
                    TemplateLayoutOperation("unset", "signed"),
                ),
            ),
        )
        assertEquals(mapOf("n" to "0"), property(reset, "precision")["effectiveValue"])
        assertEquals(true, property(reset, "signed")["effectiveValue"])
    }

    @Test fun `runtime failures in the saved case do not block source settings`() {
        val catalog = workspace(formula = "(/ base-amount 0)")
        val run = catalog.document("case.mantra", "run")
        assertEquals(false, run.data["succeeded"])
        val result = catalog.templateLayoutSettings("case.mantra", request(catalog.templateSources("case.mantra")))
        assertEquals(14, (result.data["properties"] as List<*>).size)
        assertFalse(result.data.containsKey("run"))
        assertFalse(result.data.containsKey("paper"))
    }

    @Test fun `business findings retain their nonblocking semantics during settings inspection`() {
        val catalog = workspace()
        val caseFile = root.resolve("case.mantra")
        Files.writeString(caseFile, Files.readString(caseFile).replace(":base-amount 10", ":base-amount -10"))
        val run = catalog.document("case.mantra", "run")
        assertEquals(true, run.data["succeeded"])
        assertEquals(false, run.data["validationPassed"])
        val result = catalog.templateLayoutSettings(
            "case.mantra",
            request(
                catalog.templateSources("case.mantra"),
                operations = listOf(TemplateLayoutOperation("set", "precision", "0")),
            ),
        )
        assertEquals(mapOf("n" to "0"), property(result, "precision")["effectiveValue"])
    }
}
