package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TemplateSchemaPropertiesTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var root: Path

    private fun workspace(
        directory: Path = root,
        formula: String = "(* base-amount 2)",
        amount: String = "10",
        alias: Boolean = false,
    ): WorkspaceCatalog {
        Files.createDirectories(directory.resolve("parts"))
        Files.writeString(directory.resolve("parts/inner.mantra"), "(fragment (note \"Shared note\"))")
        Files.writeString(
            directory.resolve("parts/outer.mantra"),
            "(fragment (include \"inner.mantra\") (info shared \"Shared\" 5))",
        )
        val include = if (alias) {
            Files.createSymbolicLink(directory.resolve("outer-alias.mantra"), Path.of("parts/outer.mantra"))
            "outer-alias.mantra"
        } else {
            "parts/outer.mantra"
        }
        Files.writeString(
            directory.resolve("schema.mantra"),
            """
            (schema example/properties {:version "1" :mainline [main]}
              (include "$include")
              (input base-amount :decimal)
              (section main "Main" {:panel true}
                (field base-amount "Amount" {:op :info})
                (info answer "Answer" $formula {:class :custom})
                (info hidden-zero "Zero" 0 {:hidden true})
                (info inactive "Inactive" 1 {:when false})
                (note "Root note")
                (check nonnegative "Nonnegative" (>= base-amount 0))))
            """.trimIndent().replace("\n", "\r\n") + "\r\n",
        )
        Files.writeString(
            directory.resolve("layout.mantra"),
            """
            (layout example/property-paper {:locale "de-DE" :precision 2 :hide-zero true}
              (style-class :emphasis {:weight :bold :tone :accent :fill :subtle})
              (style {:class :emphasis} {:use [:emphasis]})
              (table main :label :value))
            """.trimIndent(),
        )
        Files.writeString(
            directory.resolve("case.mantra"),
            "(case original {:schema \"example/properties\" :layout \"example/property-paper\"}" +
                " (inputs {:base-amount $amount}))",
        )
        return workspaceCatalog(directory)
    }

    private fun documents(snapshot: WorkspaceCatalog.DocumentResult): List<Map<*, *>> =
        (snapshot.data["documents"] as List<*>).map { it as Map<*, *> }

    private fun request(
        snapshot: WorkspaceCatalog.DocumentResult,
        text: String? = null,
        operations: List<TemplateSchemaPropertyOperation> = emptyList(),
    ): TemplateSchemaPropertiesRequest {
        val schema = documents(snapshot).single { it["role"] == "schema" }
        return TemplateSchemaPropertiesRequest(
            snapshot.revision,
            (snapshot.data["baseRevisions"] as Map<*, *>).entries.associate { it.key as String to it.value as String },
            23,
            schema["handle"] as String,
            text ?: schema["text"] as String,
            operations,
        )
    }

    private fun owners(document: WorkspaceCatalog.DocumentResult): List<Map<*, *>> =
        (document.data["owners"] as List<*>).map { it as Map<*, *> }

    private fun owner(document: WorkspaceCatalog.DocumentResult, node: String?, property: String): Map<*, *> =
        owners(document).single {
            it["node"] == node && it["property"] == property && it["document"] == "schema.mantra"
        }

    private fun source(document: WorkspaceCatalog.DocumentResult): String =
        (document.data["source"] as Map<*, *>)["text"] as String

    private fun handle(owner: Map<*, *>): String = owner["handle"] as String

    private fun problem(expected: WorkspaceProblem, action: () -> Unit): WorkspaceException =
        assertFailsWith<WorkspaceException>(block = action).also { assertEquals(expected, it.problem) }

    private fun preview(
        catalog: WorkspaceCatalog,
        snapshot: WorkspaceCatalog.DocumentResult,
        text: String,
    ): WorkspaceCatalog.DocumentResult {
        val base = request(snapshot, text)
        return catalog.templatePreview(
            "case.mantra",
            TemplatePreviewRequest(
                base.baseRevision,
                base.baseRevisions,
                24,
                listOf(TemplateSourceEdit(base.handle, text)),
                emptyList(),
                panel = "main",
            ),
        )
    }

    private fun assertPatches(before: String, document: WorkspaceCatalog.DocumentResult) {
        var current = before
        val patches = (document.data["patches"] as List<*>).map { it as Map<*, *> }
        patches.forEach { patch ->
            val start = patch["startOffset"] as Int
            val end = patch["endOffset"] as Int
            assertEquals(patch["removedText"], current.substring(start, end))
            current = current.replaceRange(start, end, patch["text"] as String)
        }
        assertEquals(source(document), current)
        patches.asReversed().forEach { patch ->
            val start = patch["startOffset"] as Int
            val inserted = patch["text"] as String
            current = current.replaceRange(start, start + inserted.length, patch["removedText"] as String)
        }
        assertEquals(before, current)
    }

    @Test fun `catalog includes hidden definitions and captured readonly include owners without file writes`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val before = Files.walk(root).use { files ->
            files.filter(Files::isRegularFile).toList().associateWith(Files::readAllBytes)
        }
        val inspected = catalog.templateSchemaProperties("case.mantra", request(snapshot))
        assertEquals("schema-properties", inspected.data["scope"])
        assertEquals("presentation-items", inspected.data["coverage"])
        assertEquals("example/properties", inspected.data["schemaId"])
        assertEquals(snapshot.revision, inspected.revision)
        assertEquals(snapshot.data["baseRevisions"], inspected.data["baseRevisions"])
        assertEquals(23L, inspected.data["draftSequence"])
        assertEquals("Zero", owner(inspected, "hidden-zero", "label")["value"])
        assertEquals("Inactive", owner(inspected, "inactive", "label")["value"])
        assertEquals("Root note", owner(inspected, null, "text")["value"])
        val shared = owners(inspected).filter { it["document"] != "schema.mantra" }
        assertTrue(shared.any { it["node"] == "shared" })
        assertTrue(shared.any { it["document"] == "parts/inner.mantra" })
        assertTrue(shared.all { it["editable"] == false && it["canUnset"] == false && it["reason"] is String })
        assertEquals(emptyList<Any>(), inspected.data["patches"])
        before.forEach { (file, bytes) -> assertContentEquals(bytes, Files.readAllBytes(file)) }
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { files ->
            files.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/template-schema-properties.schema.json"),
        )
        assertTrue(schema.validate(catalog.envelope(inspected), InputFormat.JSON).isEmpty())
    }

    @Test fun `batched labels and classes produce reversible source and actual final Paper without changing values`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val original = request(snapshot).text
        val inspected = catalog.templateSchemaProperties("case.mantra", request(snapshot))
        val edited = catalog.templateSchemaProperties(
            "case.mantra",
            request(
                snapshot,
                operations = listOf(
                    TemplateSchemaPropertyOperation(
                        "setText",
                        handle(owner(inspected, "answer", "label")),
                        "新😀 Label",
                    ),
                    TemplateSchemaPropertyOperation(
                        "setClasses",
                        handle(owner(inspected, "answer", "classes")),
                        classes = listOf("emphasis", "unregistered"),
                    ),
                    TemplateSchemaPropertyOperation("setText", handle(owner(inspected, null, "text")), "Changed note"),
                ),
            ),
        )
        assertPatches(original, edited)
        assertTrue(source(edited).contains("\r\n"))
        assertEquals(original, Files.readString(root.resolve("schema.mantra")))
        assertNotEquals(handle(owner(inspected, "answer", "label")), handle(owner(edited, "answer", "label")))
        val candidate = preview(catalog, snapshot, source(edited))
        val run = candidate.data["run"] as Map<*, *>
        val values = run["values"] as Map<*, *>
        assertEquals(mapOf("n" to "20"), ((values["answer"] as Map<*, *>)[""] as Map<*, *>)["value"])
        val paper = candidate.data["paper"] as Map<*, *>
        val table = (paper["tables"] as List<*>).single() as Map<*, *>
        val row = (table["rows"] as List<*>).map { it as Map<*, *> }.single { it["node"] == "answer" }
        val cells = (row["cells"] as List<*>).map { it as Map<*, *> }
        assertEquals("新😀 Label", cells[0]["text"])
        assertEquals("20,00", cells[1]["text"])
        assertEquals(mapOf("weight" to "bold", "tone" to "accent", "fill" to "subtle"), cells[1]["style"])
    }

    @Test fun `a syntactically valid bad formula binding can still edit source presentation but cannot preview`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val draft = request(snapshot).text.replace("(* base-amount 2)", "(* undeclared-input 2)")
        val inspected = catalog.templateSchemaProperties("case.mantra", request(snapshot, draft))
        val edited = catalog.templateSchemaProperties(
            "case.mantra",
            request(
                snapshot,
                draft,
                listOf(
                    TemplateSchemaPropertyOperation(
                        "setText",
                        handle(owner(inspected, "answer", "label")),
                        "Still editable",
                    ),
                ),
            ),
        )
        assertTrue(source(edited).contains("(* undeclared-input 2)"))
        assertEquals("Still editable", owner(edited, "answer", "label")["value"])
        problem(WorkspaceProblem.INVALID) { preview(catalog, snapshot, source(edited)) }
    }

    @Test fun `saved business findings and runtime failures do not block source property inspection`() {
        listOf("finding" to "(* base-amount 2)", "runtime" to "(/ base-amount 0)").forEach { (name, formula) ->
            val catalog = workspace(root.resolve(name), formula, amount = if (name == "finding") "-1" else "10")
            val snapshot = catalog.templateSources("case.mantra")
            val inspected = catalog.templateSchemaProperties("case.mantra", request(snapshot))
            assertEquals("Answer", owner(inspected, "answer", "label")["value"])
            val run = catalog.document("case.mantra", "run").data
            assertEquals(name != "runtime", run["succeeded"])
            if (name == "finding") assertEquals(false, run["validationPassed"])
        }
    }

    @Test fun `owner handles cannot target changed drafts readonly includes or another property type`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val inspected = catalog.templateSchemaProperties("case.mantra", request(snapshot))
        val label = handle(owner(inspected, "answer", "label"))
        val classes = handle(owner(inspected, "answer", "classes"))
        val readonly = owners(inspected).first { it["document"] != "schema.mantra" && it["property"] == "label" }
        val bad = listOf(
            TemplateSchemaPropertyOperation("setText", "0".repeat(64), "Unknown"),
            TemplateSchemaPropertyOperation("setText", handle(readonly), "Shared edit"),
            TemplateSchemaPropertyOperation("setText", classes, "Wrong type"),
            TemplateSchemaPropertyOperation("setClasses", label, classes = listOf("custom")),
            TemplateSchemaPropertyOperation("unset", label),
        )
        bad.forEach { operation ->
            problem(WorkspaceProblem.REQUEST) {
                catalog.templateSchemaProperties("case.mantra", request(snapshot, operations = listOf(operation)))
            }
        }
        problem(WorkspaceProblem.REQUEST) {
            catalog.templateSchemaProperties(
                "case.mantra",
                request(
                    snapshot,
                    request(snapshot).text + "; newer draft",
                    listOf(TemplateSchemaPropertyOperation("setText", label, "Old owner")),
                ),
            )
        }
    }

    @Test fun `document identity source closure and full graph revision remain guarded`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val base = request(snapshot)
        listOf("layout", "case", "included").forEach { role ->
            val wrong = documents(snapshot).first { it["role"] == role }["handle"] as String
            problem(WorkspaceProblem.REQUEST) {
                catalog.templateSchemaProperties("case.mantra", base.copy(handle = wrong))
            }
        }
        problem(WorkspaceProblem.REQUEST) {
            catalog.templateSchemaProperties("case.mantra", base.copy(handle = "0".repeat(64)))
        }
        problem(WorkspaceProblem.CONFLICT) {
            catalog.templateSchemaProperties(
                "case.mantra",
                base.copy(baseRevisions = base.baseRevisions - "parts/inner.mantra"),
            )
        }
        listOf(
            base.text.replace("example/properties", "example/other"),
            base.text.replace(":version \"1\"", ":version \"2\""),
            base.text.replace("parts/outer.mantra", "parts/new.mantra"),
        ).forEach { text ->
            problem(WorkspaceProblem.INVALID) {
                catalog.templateSchemaProperties("case.mantra", base.copy(text = text))
            }
        }
    }

    @Test fun `captured nested includes and symbolic aliases resolve without permitting shared edits`() {
        val catalog = workspace(alias = true)
        val snapshot = catalog.templateSources("case.mantra")
        val inspected = catalog.templateSchemaProperties("case.mantra", request(snapshot))
        assertTrue(owners(inspected).any { it["document"] == "parts/outer.mantra" && it["node"] == "shared" })
        assertTrue(owners(inspected).any { it["document"] == "parts/inner.mantra" && it["property"] == "text" })
        assertFalse(owners(inspected).any { it["document"] == "outer-alias.mantra" })
        val rootLabel = owner(inspected, "answer", "label")
        val result = catalog.templateSchemaProperties(
            "case.mantra",
            request(
                snapshot,
                operations = listOf(TemplateSchemaPropertyOperation("setText", handle(rootLabel), "Alias safe")),
            ),
        )
        assertEquals("Alias safe", owner(result, "answer", "label")["value"])
    }

    @Test fun `external dependency changes and broken saved baselines return conflicts instead of draft patches`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        Files.writeString(root.resolve("parts/inner.mantra"), "(fragment invalid")
        problem(WorkspaceProblem.CONFLICT) { catalog.templateSchemaProperties("case.mantra", request(snapshot)) }
        Files.delete(root.resolve("parts/inner.mantra"))
        problem(WorkspaceProblem.CONFLICT) { catalog.templateSchemaProperties("case.mantra", request(snapshot)) }
    }

    @Test fun `final baseline revision check rejects a racing dependency change after valid editing`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val inspected = catalog.templateSchemaProperties("case.mantra", request(snapshot))
        val original = Files.readString(root.resolve("schema.mantra"))
        catalog.beforePreviewCheck = {
            Files.writeString(root.resolve("parts/inner.mantra"), "(fragment (note \"External update\"))")
        }
        try {
            problem(WorkspaceProblem.CONFLICT) {
                catalog.templateSchemaProperties(
                    "case.mantra",
                    request(
                        snapshot,
                        operations = listOf(
                            TemplateSchemaPropertyOperation(
                                "setText",
                                handle(owner(inspected, "answer", "label")),
                                "Draft retained",
                            ),
                        ),
                    ),
                )
            }
        } finally {
            catalog.beforePreviewCheck = null
        }
        assertEquals(original, Files.readString(root.resolve("schema.mantra")))
    }

    @Test fun `a currently invalid saved baseline is rejected even when its supplied source hashes match`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val base = request(snapshot)
        val broken = base.text.replace("(* base-amount 2)", "(* unknown-name 2)")
        Files.writeString(root.resolve("schema.mantra"), broken)
        val current = base.baseRevisions + ("schema.mantra" to sha(broken))
        problem(WorkspaceProblem.INVALID) {
            catalog.templateSchemaProperties("case.mantra", base.copy(baseRevisions = current, text = broken))
        }
    }

    @Test fun `API request validation rejects malformed operations and keeps text budgets separate`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val base = request(snapshot)
        val inspected = catalog.templateSchemaProperties("case.mantra", base)
        val owner = handle(owner(inspected, "answer", "classes"))
        val valid = TemplateSchemaPropertyOperation("setClasses", owner, classes = listOf("custom"))
        val malformed = listOf(
            valid.copy(op = "unknown"), valid.copy(owner = "bad"), valid.copy(text = "extra"),
            valid.copy(classes = null), valid.copy(classes = listOf("Custom")),
            valid.copy(classes = listOf("same", "same")), valid.copy(classes = List(65) { "class-$it" }),
            TemplateSchemaPropertyOperation("setText", owner),
            TemplateSchemaPropertyOperation("unset", owner, classes = emptyList()),
        )
        malformed.forEach { operation ->
            problem(WorkspaceProblem.REQUEST) {
                catalog.templateSchemaProperties("case.mantra", base.copy(operations = listOf(operation)))
            }
        }
        problem(WorkspaceProblem.REQUEST) {
            catalog.templateSchemaProperties("case.mantra", base.copy(operations = listOf(valid, valid)))
        }
        problem(WorkspaceProblem.REQUEST) {
            catalog.templateSchemaProperties("case.mantra", base.copy(draftSequence = -1))
        }
        problem(WorkspaceProblem.TOO_LARGE) {
            catalog.templateSchemaProperties("case.mantra", base.copy(text = "a".repeat(65_537)))
        }
        problem(WorkspaceProblem.TOO_LARGE) {
            catalog.templateSchemaProperties(
                "case.mantra",
                base.copy(operations = listOf(valid.copy(classes = listOf("a".repeat(65_537))))),
            )
        }
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
