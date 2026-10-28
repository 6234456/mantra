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

class TemplateStyleClassesTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var root: Path

    private val defaultLayout = "(layout example/layout {:title \"Original\"})"

    private fun workspace(layout: String = defaultLayout, formula: String = "(* base-amount 2)"): WorkspaceCatalog {
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema example/styles {:version "1" :mainline [main]}
              (include "fragment.mantra")
              (input base-amount :decimal)
              (section main "Main" {:panel true}
                (field base-amount "Amount" {:op :info})
                (info answer "Answer" $formula {:class :custom})
                (check nonnegative "Nonnegative" (>= base-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(root.resolve("fragment.mantra"), "(fragment (defn twice [^Decimal value] (* value 2)))")
        Files.writeString(root.resolve("layout.mantra"), layout)
        Files.writeString(
            root.resolve("case.mantra"),
            "(case original {:schema \"example/styles\" :layout \"example/layout\"}\r\n" +
                "  (inputs {:base-amount 10}))\r\n",
        )
        return workspaceCatalog(root)
    }

    private fun documents(snapshot: WorkspaceCatalog.DocumentResult): List<Map<*, *>> =
        (snapshot.data["documents"] as List<*>).map { it as Map<*, *> }

    private fun request(
        snapshot: WorkspaceCatalog.DocumentResult,
        text: String? = null,
        operations: List<TemplateStyleClassOperation> = emptyList(),
    ): TemplateStyleClassesRequest {
        val layout = documents(snapshot).single { it["role"] == "layout" }
        return TemplateStyleClassesRequest(
            snapshot.revision,
            (snapshot.data["baseRevisions"] as Map<*, *>).entries.associate { it.key as String to it.value as String },
            19,
            layout["handle"] as String,
            text ?: layout["text"] as String,
            operations,
        )
    }

    private fun classes(result: WorkspaceCatalog.DocumentResult): List<Map<*, *>> =
        (result.data["classes"] as List<*>).map { it as Map<*, *> }

    private fun klass(result: WorkspaceCatalog.DocumentResult, name: String): Map<*, *> =
        classes(result).single { it["name"] == name }

    private fun source(result: WorkspaceCatalog.DocumentResult): String =
        (result.data["source"] as Map<*, *>)["text"] as String

    private fun style(weight: String? = null, tone: String? = null, fill: String? = null): Map<String, String?> =
        mapOf("weight" to weight, "tone" to tone, "fill" to fill)

    private fun upsert(name: String, weight: String? = null, tone: String? = null, fill: String? = null) =
        TemplateStyleClassOperation("upsert", name, TemplateStyleSpec(weight, tone, fill))

    private fun problem(expected: WorkspaceProblem, action: () -> Unit): WorkspaceException =
        assertFailsWith<WorkspaceException>(block = action).also { assertEquals(expected, it.problem) }

    private fun assertRoundTrip(original: String, result: WorkspaceCatalog.DocumentResult) {
        val patches = (result.data["patches"] as List<*>).map { it as Map<*, *> }
        var current = original
        patches.forEach { patch ->
            val start = (patch["startOffset"] as Number).toInt()
            val end = (patch["endOffset"] as Number).toInt()
            assertEquals(patch["removedText"], current.substring(start, end))
            current = current.replaceRange(start, end, patch["text"] as String)
        }
        assertEquals(source(result), current)
        patches.asReversed().forEach { patch ->
            val start = (patch["startOffset"] as Number).toInt()
            val inserted = patch["text"] as String
            assertEquals(inserted, current.substring(start, start + inserted.length))
            current = current.replaceRange(start, start + inserted.length, patch["removedText"] as String)
        }
        assertEquals(original, current)
    }

    @Test fun `catalog combines actual selected preset rules with local declarations and selector names`() {
        val layout = """
            (layout example/layout {:style-preset [:working-paper :utilities]}
              (style-class :custom {:weight :bold :fill :none})
              (style {:class :unregistered} {:tone :accent}))
        """.trimIndent()
        val catalog = workspace(layout)
        val snapshot = catalog.templateSources("case.mantra")
        val result = catalog.templateStyleClasses("case.mantra", request(snapshot))
        assertEquals(snapshot.revision, result.revision)
        assertEquals(snapshot.data["baseRevisions"], result.data["baseRevisions"])
        assertEquals(19L, result.data["draftSequence"])
        assertEquals("layout-style-classes", result.data["scope"])
        assertEquals("submitted-layout", result.data["usageScope"])
        assertEquals("example/layout", result.data["layoutId"])
        assertEquals(listOf("working-paper", "utilities"), result.data["stylePresets"])
        assertEquals(
            listOf(
                "source", "assumption", "detail", "subtotal", "result", "note", "variance", "control",
                "normal", "strong", "muted", "accent", "subtle", "highlight", "custom", "unregistered",
            ),
            classes(result).map { it["name"] },
        )
        assertEquals(style("bold", "accent", "accent"), klass(result, "result")["inherited"])
        assertEquals(style("bold", fill = "none"), klass(result, "custom")["local"])
        assertEquals(style("bold", fill = "none"), klass(result, "custom")["effective"])
        assertEquals(style(), klass(result, "unregistered")["effective"])
        assertEquals(null, klass(result, "unregistered")["local"])
        assertEquals(emptyList<Any>(), result.data["patches"])
        assertEquals(layout, source(result))
        assertTrue(klass(result, "strong")["removeReason"] is String)
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/template-style-classes.schema.json"),
        )
        assertTrue(schema.validate(catalog.envelope(result), InputFormat.JSON).isEmpty())
    }

    @Test fun `explicit neutral declarations differ from null and an empty local definition`() {
        val catalog = workspace("(layout example/layout {:style-preset :working-paper})")
        val snapshot = catalog.templateSources("case.mantra")
        val created = catalog.templateStyleClasses(
            "case.mantra",
            request(snapshot, operations = listOf(upsert("result", "normal", "default", "none"), upsert("empty"))),
        )
        assertEquals(style("normal", "default", "none"), klass(created, "result")["local"])
        assertEquals(style("normal", "default", "none"), klass(created, "result")["effective"])
        assertEquals(style("bold", "accent", "accent"), klass(created, "result")["inherited"])
        assertEquals(style(), klass(created, "empty")["local"])
        assertTrue(source(created).contains("(style-class :empty {})"))
        assertRoundTrip(request(snapshot).text, created)
        val resetProperties = catalog.templateStyleClasses(
            "case.mantra",
            request(snapshot, source(created), listOf(upsert("result"))),
        )
        assertEquals(style(), klass(resetProperties, "result")["local"])
        assertEquals(style("bold", "accent", "accent"), klass(resetProperties, "result")["effective"])
        assertRoundTrip(source(created), resetProperties)
    }

    @Test fun `upsert changes only property tokens preserving comments CRLF and unrelated layout source`() {
        val layout = "(layout example/layout {:title \"😀\" :unknown 12.00}\r\n" +
            "  (style-class :custom {:weight ; weight note\r\n    :bold :tone :muted})\r\n" +
            "  (style {:class :custom} {:fill :subtle}))\r\n"
        val catalog = workspace(layout)
        val snapshot = catalog.templateSources("case.mantra")
        val changed = catalog.templateStyleClasses(
            "case.mantra",
            request(snapshot, operations = listOf(upsert("custom", tone = "accent", fill = "none"))),
        )
        val expected = layout.replace(":weight", "").replace(":bold", "")
            .replace(":tone :muted", ":tone :accent :fill :none")
        assertEquals(expected, source(changed))
        assertRoundTrip(layout, changed)
        assertEquals(layout, Files.readString(root.resolve("layout.mantra")))
    }

    @Test fun `remove preserves all comment and whitespace bytes within a definition`() {
        val layout = "(layout example/layout {}\r\n" +
            "  (style-class ; head\r\n    :custom {; map\r\n      :weight ; value\r\n      :bold})\r\n" +
            "  (style {:class :custom} {:fill :subtle}))\r\n"
        val catalog = workspace(layout)
        val snapshot = catalog.templateSources("case.mantra")
        val removed = catalog.templateStyleClasses(
            "case.mantra",
            request(snapshot, operations = listOf(TemplateStyleClassOperation("remove", "custom"))),
        )
        val expected = layout.replace("(style-class", "").replace(":custom {; map", " ; map")
            .replace(":weight", "").replace(":bold})", "")
        assertEquals(expected, source(removed))
        assertEquals(null, klass(removed, "custom")["local"])
        assertRoundTrip(layout, removed)
        assertEquals(layout, Files.readString(root.resolve("layout.mantra")))
    }

    @Test fun `current use dependencies block deletion unless a selected preset provides the same class`() {
        val layout = """
            (layout example/layout {:style-preset :utilities}
              (style-class :strong {:weight :normal})
              (style-class :custom {:fill :accent})
              (style {:all true} {:use [:custom :strong]}))
        """.trimIndent()
        val catalog = workspace(layout)
        val snapshot = catalog.templateSources("case.mantra")
        val inspected = catalog.templateStyleClasses("case.mantra", request(snapshot))
        assertEquals(false, klass(inspected, "custom")["canRemove"])
        assertTrue((klass(inspected, "custom")["removeReason"] as String).contains(":use"))
        assertEquals(true, klass(inspected, "strong")["canRemove"])
        val blocked = problem(WorkspaceProblem.INVALID) {
            catalog.templateStyleClasses(
                "case.mantra",
                request(snapshot, operations = listOf(TemplateStyleClassOperation("remove", "custom"))),
            )
        }
        assertEquals("MANTRA-TEMPLATE-STYLE", blocked.diagnostics.single().code)
        val removed = catalog.templateStyleClasses(
            "case.mantra",
            request(snapshot, operations = listOf(TemplateStyleClassOperation("remove", "strong"))),
        )
        assertEquals(null, klass(removed, "strong")["local"])
        assertEquals(style("bold"), klass(removed, "strong")["effective"])
        assertEquals(1, (klass(removed, "strong")["references"] as List<*>).size)
        assertRoundTrip(layout, removed)
    }

    @Test fun `references describe only current submitted layout token spans with repeated use and soft selectors`() {
        val layout = """
            (layout example/layout {:title "😀" :style-preset :utilities}
              (style-class :custom {})
              (style {:class custom} {:use [:custom :strong :custom]}))
        """.trimIndent()
        val catalog = workspace(layout)
        val snapshot = catalog.templateSources("case.mantra")
        val result = catalog.templateStyleClasses("case.mantra", request(snapshot))
        val refs = (klass(result, "custom")["references"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("selector", "use", "use"), refs.map { it["kind"] })
        assertEquals(
            listOf("custom", ":custom", ":custom"),
            refs.map {
                source(result).substring((it["startOffset"] as Number).toInt(), (it["endOffset"] as Number).toInt())
            },
        )
        val withoutCurrentUse = layout.replace("{:use [:custom :strong :custom]}", "{:fill :none}")
        val inspected = catalog.templateStyleClasses("case.mantra", request(snapshot, withoutCurrentUse))
        assertEquals(true, klass(inspected, "custom")["canRemove"])
        // The saved schema tag :custom does not masquerade as a current layout reference or deletion blocker.
        assertEquals(1, (klass(inspected, "custom")["references"] as List<*>).size)
    }

    @Test fun `valid non simple selector names stay visible and read only without becoming editable class names`() {
        val catalog = workspace(
            "(layout example/layout {} (style {:class :future/tag} {:weight :bold}) " +
                "(style {:class FutureTag} {:fill :none}))",
        )
        val snapshot = catalog.templateSources("case.mantra")
        val result = catalog.templateStyleClasses("case.mantra", request(snapshot))
        assertEquals(listOf("future/tag", "FutureTag"), classes(result).map { it["name"] })
        classes(result).forEach {
            assertEquals(false, it["editable"])
            assertTrue(it["reason"] is String)
            assertEquals(false, it["canRemove"])
        }
        for (name in listOf("future/tag", "FutureTag")) {
            problem(WorkspaceProblem.REQUEST) {
                catalog.templateStyleClasses("case.mantra", request(snapshot, operations = listOf(upsert(name))))
            }
        }
    }

    @Test fun `no op upsert preserves original spelling and produces no source patches`() {
        val layout = "(layout example/layout {} (style-class :custom { :fill :none ; comment\r\n :weight :normal }))"
        val catalog = workspace(layout)
        val result = catalog.templateStyleClasses(
            "case.mantra",
            request(
                catalog.templateSources("case.mantra"),
                operations = listOf(upsert("custom", "normal", fill = "none")),
            ),
        )
        assertEquals(layout, source(result))
        assertEquals(emptyList<Any>(), result.data["patches"])
        problem(WorkspaceProblem.INVALID) {
            catalog.templateStyleClasses(
                "case.mantra",
                request(
                    catalog.templateSources("case.mantra"),
                    operations = listOf(TemplateStyleClassOperation("remove", "absent")),
                ),
            )
        }
    }

    @Test fun `ambiguous metadata definitions and unsupported declarations fail without guessing a source owner`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val invalidSources = listOf(
            "(layout example/layout {:style-preset :utilities :style-preset :working-paper})",
            "(layout example/layout {} (style-class :custom {}) (style-class :custom {:fill :none}))",
            "(layout example/layout {} (style-class :custom {:weight :bold :weight :normal}))",
            "(layout example/layout {} (style-class :custom {:weight}))",
            "(layout example/layout {} (style-class :custom {non-keyword :bold}))",
            "(layout example/layout {} (style-class :custom {:color :red}))",
            "(layout example/layout {} (style-class :custom {:weight nil}))",
            "(layout example/layout {} (style-class :custom {:use :strong}))",
            "(layout example/layout {} (style {:all true} {:use :missing}))",
            "(layout example/layout {:style-preset [:utilities :utilities]})",
            "(layout another/layout {})",
            "(layout example/layout",
        )
        invalidSources.forEach { text ->
            val failure = problem(WorkspaceProblem.INVALID) {
                catalog.templateStyleClasses("case.mantra", request(snapshot, text))
            }
            assertTrue(failure.diagnostics.isNotEmpty(), text)
        }
        assertEquals(defaultLayout, Files.readString(root.resolve("layout.mantra")))
    }

    @Test fun `malformed operations invalid enums and budgets reject without modifying workspace source`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val valid = request(snapshot)
        val malformed = listOf(
            valid.copy(operations = listOf(upsert(":custom"))),
            valid.copy(operations = listOf(upsert("custom", weight = "heavy"))),
            valid.copy(operations = listOf(upsert("custom", tone = "red"))),
            valid.copy(operations = listOf(upsert("custom", fill = "blue"))),
            valid.copy(operations = listOf(TemplateStyleClassOperation("upsert", "custom"))),
            valid.copy(
                operations = listOf(
                    TemplateStyleClassOperation("remove", "custom", TemplateStyleSpec(null, null, null)),
                ),
            ),
            valid.copy(operations = listOf(TemplateStyleClassOperation("rename", "custom"))),
            valid.copy(operations = listOf(upsert("custom"), upsert("custom"))),
            valid.copy(operations = (0..16).map { upsert("class-$it") }),
            valid.copy(draftSequence = -1),
            valid.copy(draftSequence = 9_007_199_254_740_992),
            valid.copy(handle = "layout.mantra"),
        )
        malformed.forEach { bad ->
            problem(WorkspaceProblem.REQUEST) { catalog.templateStyleClasses("case.mantra", bad) }
        }
        problem(WorkspaceProblem.TOO_LARGE) {
            catalog.templateStyleClasses("case.mantra", valid.copy(text = "x".repeat(65_537)))
        }
        val exactBudget = defaultLayout + " ".repeat(65_536 - defaultLayout.length)
        problem(WorkspaceProblem.TOO_LARGE) {
            catalog.templateStyleClasses(
                "case.mantra",
                valid.copy(text = exactBudget, operations = listOf(upsert("custom"))),
            )
        }
        assertEquals(defaultLayout, Files.readString(root.resolve("layout.mantra")))
    }

    @Test fun `only captured root layout handle is editable and participating source map must match completely`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val valid = request(snapshot)
        documents(snapshot).filter { it["role"] != "layout" }.forEach {
            problem(WorkspaceProblem.REQUEST) {
                catalog.templateStyleClasses("case.mantra", valid.copy(handle = it["handle"] as String))
            }
        }
        problem(WorkspaceProblem.REQUEST) {
            catalog.templateStyleClasses("case.mantra", valid.copy(handle = "f".repeat(64)))
        }
        problem(WorkspaceProblem.CONFLICT) {
            catalog.templateStyleClasses("case.mantra", valid.copy(baseRevision = "f".repeat(64)))
        }
        problem(WorkspaceProblem.CONFLICT) {
            catalog.templateStyleClasses(
                "case.mantra",
                valid.copy(
                    baseRevisions =
                    valid.baseRevisions - "fragment.mantra",
                ),
            )
        }
        problem(WorkspaceProblem.CONFLICT) {
            catalog.templateStyleClasses(
                "case.mantra",
                valid.copy(baseRevisions = valid.baseRevisions + ("extra" to "f".repeat(64))),
            )
        }
    }

    @Test fun `changed deleted damaged and late changing dependencies remain revision conflicts`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val fragment = root.resolve("fragment.mantra")
        val original = Files.readString(fragment)
        for (changed in listOf(original + "\n; external", "(fragment", null)) {
            if (changed == null) Files.delete(fragment) else Files.writeString(fragment, changed)
            problem(WorkspaceProblem.CONFLICT) { catalog.templateStyleClasses("case.mantra", request(snapshot)) }
            Files.writeString(fragment, original)
        }
        catalog.beforePreviewCheck = { Files.writeString(fragment, original + "\n; late") }
        try {
            problem(WorkspaceProblem.CONFLICT) { catalog.templateStyleClasses("case.mantra", request(snapshot)) }
        } finally {
            catalog.beforePreviewCheck = null
        }
    }

    @Test fun `style patches change real Paper styles but preserve exact values workspace bytes and history`() {
        val catalog = workspace()
        val run = catalog.document("case.mantra", "run")
        val snapshot = catalog.templateSources("case.mantra")
        val before = Files.list(root).use { paths -> paths.toList().associateWith(Files::readAllBytes) }
        val edited = catalog.templateStyleClasses(
            "case.mantra",
            request(snapshot, operations = listOf(upsert("custom", "bold", "accent", "subtle"))),
        )
        assertNotEquals(defaultLayout, source(edited))
        val preview = catalog.templatePreview(
            "case.mantra",
            TemplatePreviewRequest(
                snapshot.revision,
                request(snapshot).baseRevisions,
                20,
                listOf(TemplateSourceEdit(request(snapshot).handle, source(edited))),
                emptyList(),
            ),
        )
        val paper = preview.data["paper"] as Map<*, *>
        val answer = (paper["tables"] as List<*>).flatMap {
            ((it as Map<*, *>)["rows"] as List<*>).map { row -> row as Map<*, *> }
        }
            .single { it["node"] == "answer" }
        val numeric = (answer["cells"] as List<*>).map { it as Map<*, *> }.single {
            it["address"] != null && (it["text"] as String).isNotBlank()
        }
        assertEquals(style("bold", "accent", "subtle"), numeric["style"])
        fun values(data: Map<*, *>): Map<Any?, Map<Any?, Any?>> =
            (data["values"] as Map<*, *>).entries.associate { (id, coordinates) ->
                id to (coordinates as Map<*, *>).entries.associate { (coordinate, value) ->
                    coordinate to (value as Map<*, *>)["value"]
                }
            }
        assertEquals(values(run.data), values(preview.data["run"] as Map<*, *>))
        before.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        problem(WorkspaceProblem.REQUEST) { catalog.undo("case.mantra", snapshot.revision) }
        assertFalse(edited.data.containsKey("run"))
        assertFalse(edited.data.containsKey("paper"))
    }

    @Test fun `runtime failure and business findings do not block style inspection`() {
        var catalog = workspace(formula = "(/ base-amount 0)")
        assertEquals(false, catalog.document("case.mantra", "run").data["succeeded"])
        assertEquals(
            emptyList<Any>(),
            catalog.templateStyleClasses(
                "case.mantra",
                request(catalog.templateSources("case.mantra")),
            ).data["classes"],
        )
        catalog.close()
        catalog = workspace()
        Files.writeString(
            root.resolve("case.mantra"),
            Files.readString(root.resolve("case.mantra")).replace(":base-amount 10", ":base-amount -10"),
        )
        assertEquals(false, catalog.document("case.mantra", "run").data["validationPassed"])
        val result = catalog.templateStyleClasses(
            "case.mantra",
            request(catalog.templateSources("case.mantra"), operations = listOf(upsert("custom"))),
        )
        assertEquals(style(), klass(result, "custom")["local"])
    }
}
