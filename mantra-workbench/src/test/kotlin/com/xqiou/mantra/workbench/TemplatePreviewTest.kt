package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TemplatePreviewTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var root: Path

    private fun workspace(): WorkspaceCatalog {
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema example/template {:version "1" :mainline [main]}
              (include "fragment.mantra")
              (input base-amount :decimal)
              (section main "Main" {:panel true}
                (field base-amount "Amount" {:op :info})
                (info answer "Answer" (* base-amount 2))
                (check nonnegative "Nonnegative" (>= base-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(root.resolve("fragment.mantra"), "(fragment (defn twice [^Decimal value] (* value 2)))")
        Files.writeString(
            root.resolve("layout.mantra"),
            "(layout example/layout {:title \"Original\" :locale \"de-DE\"})",
        )
        Files.writeString(
            root.resolve("case.mantra"),
            "(case original {:schema \"example/template\" :layout \"example/layout\"}" +
                "\r\n  (inputs {:base-amount 10}))\r\n",
        )
        return workspaceCatalog(root).also { catalog ->
            try {
                catalog.document("case.mantra", "run")
            } catch (error: WorkspaceException) {
                throw AssertionError("Invalid test fixture: ${error.diagnostics}", error)
            }
        }
    }

    private fun documents(snapshot: WorkspaceCatalog.DocumentResult): List<Map<*, *>> =
        (snapshot.data["documents"] as List<*>).map { it as Map<*, *> }

    private fun request(
        snapshot: WorkspaceCatalog.DocumentResult,
        changes: Map<String, String> = emptyMap(),
        input: String? = "12",
        explain: ExplainAddress? = null,
    ): TemplatePreviewRequest {
        val sources = documents(snapshot).associateBy { it["document"] as String }
        return TemplatePreviewRequest(
            snapshot.revision,
            (snapshot.data["baseRevisions"] as Map<*, *>).entries.associate { it.key as String to it.value as String },
            19,
            changes.map { (document, text) ->
                TemplateSourceEdit(sources.getValue(document)["handle"] as String, text)
            },
            input?.let { listOf(TemplateInputText("base-amount", it)) }.orEmpty(),
            panel = "main",
            explain = explain,
        )
    }

    private fun source(snapshot: WorkspaceCatalog.DocumentResult, document: String): String =
        documents(snapshot).single { it["document"] == document }["text"] as String

    private fun WorkspaceCatalog.successPreview(request: TemplatePreviewRequest): WorkspaceCatalog.DocumentResult =
        try {
            templatePreview("case.mantra", request)
        } catch (error: WorkspaceException) {
            throw AssertionError("Candidate unexpectedly rejected: ${error.diagnostics}", error)
        }

    private fun runValue(candidate: WorkspaceCatalog.DocumentResult, node: String): Any? {
        val run = candidate.data["run"] as Map<*, *>
        val values = run["values"] as Map<*, *>
        return (((values[node] as Map<*, *>)[""] as Map<*, *>)["value"])
    }

    private fun validate(document: WorkspaceCatalog.DocumentResult, schemaName: String) {
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/$schemaName.schema.json"),
        )
        val errors = schema.validate(
            WorkbenchJson.write(WorkbenchJson.envelope(document.revision, "test", "test", document.data)),
            InputFormat.JSON,
        )
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun `multi-document candidate uses real run paper difference and Explain without writes`() {
        val catalog = workspace()
        val original = Files.list(root).use { files -> files.toList().associateWith(Files::readAllBytes) }
        val baseline = catalog.document("case.mantra", "run")
        val stats = catalog.sessions.stats("case.mantra")
        val snapshot = catalog.templateSources("case.mantra")
        validate(snapshot, "template-sources")
        assertEquals(4, (snapshot.data["baseRevisions"] as Map<*, *>).size)
        assertEquals(
            setOf("schema", "layout"),
            documents(snapshot).filter {
                it["editable"] == true
            }.map { it["role"] }.toSet(),
        )
        val candidate = catalog.successPreview(
            request(
                snapshot,
                mapOf(
                    "schema.mantra" to
                        source(snapshot, "schema.mantra").replace("(* base-amount 2)", "(* base-amount 3)"),
                    "layout.mantra" to source(snapshot, "layout.mantra").replace("Original", "Candidate title"),
                ),
                explain = ExplainAddress("answer", emptyList()),
            ),
        )
        validate(candidate, "template-preview")
        assertEquals(snapshot.revision, candidate.revision)
        assertNotEquals(snapshot.revision, candidate.data["proposedRevision"])
        assertEquals(mapOf("n" to "36"), runValue(candidate, "answer")) // Independent expectation: 12 * 3.
        assertEquals("Candidate title", (candidate.data["paper"] as Map<*, *>)["title"])
        assertTrue(((candidate.data["difference"] as Map<*, *>)["changes"] as List<*>).isNotEmpty())
        val explain = candidate.data["explain"] as Map<*, *>
        assertEquals(candidate.data["proposedRevision"], explain["revision"])
        assertEquals("36", (explain["result"] as Map<*, *>)["display"].toString().replace(",00", "").replace(".00", ""))
        assertTrue((explain["steps"] as List<*>).isNotEmpty())
        assertEquals(stats, catalog.sessions.stats("case.mantra"))
        original.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        assertEquals(baseline.data["values"], catalog.document("case.mantra", "run").data["values"])
        assertEquals(
            WorkspaceProblem.REQUEST,
            assertFailsWith<WorkspaceException> {
                catalog.undo("case.mantra", baseline.revision)
            }.problem,
        )
    }

    @Test fun `input text uses candidate locale and candidate declared type before validating old literals`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val english = catalog.templatePreview(
            "case.mantra",
            request(
                snapshot,
                mapOf("layout.mantra" to source(snapshot, "layout.mantra").replace("de-DE", "en-US")),
                "1.25",
            ),
        )
        assertEquals(mapOf("n" to "2.50"), runValue(english, "answer"))
        val textSchema = source(
            snapshot,
            "schema.mantra",
        ).replace("(input base-amount :decimal)", "(input base-amount :text)")
            .replace("(* base-amount 2)", "7").replace("(>= base-amount 0)", "true")
        val text = catalog.templatePreview(
            "case.mantra",
            request(snapshot, mapOf("schema.mantra" to textSchema), "hello"),
        )
        assertEquals("hello", runValue(text, "base-amount"))
        assertEquals(mapOf("n" to "7"), runValue(text, "answer"))
    }

    @Test fun `technical formula failures reject but findings and runtime partial failures remain candidates`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val invalid = assertFailsWith<WorkspaceException> {
            catalog.templatePreview(
                "case.mantra",
                request(
                    snapshot,
                    mapOf(
                        "schema.mantra" to source(
                            snapshot,
                            "schema.mantra",
                        ).replace("(* base-amount 2)", "unknown-symbol"),
                    ),
                ),
            )
        }
        assertEquals(WorkspaceProblem.INVALID, invalid.problem)
        assertTrue(invalid.diagnostics.any { it.location?.source == "schema.mantra" })
        val finding = catalog.templatePreview("case.mantra", request(snapshot, input = "-1"))
        assertEquals(true, finding.data["succeeded"])
        assertEquals(false, finding.data["validationPassed"])
        assertEquals(mapOf("n" to "-2"), runValue(finding, "answer"))
        val runtime = catalog.templatePreview(
            "case.mantra",
            request(
                snapshot,
                mapOf(
                    "schema.mantra" to source(
                        snapshot,
                        "schema.mantra",
                    ).replace("(* base-amount 2)", "(/ base-amount 0)"),
                ),
            ),
        )
        validate(runtime, "template-preview")
        assertEquals(false, runtime.data["succeeded"])
        assertTrue((runtime.data["diagnostics"] as List<*>).isNotEmpty())
        assertEquals(mapOf("n" to "12"), runValue(runtime, "base-amount"))
    }

    @Test fun `full source revisions and changes during calculation reject with conflict`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val missing = request(snapshot).copy(baseRevisions = request(snapshot).baseRevisions - "fragment.mantra")
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview("case.mantra", missing)
            }.problem,
        )
        catalog.beforePreviewCheck =
            {
                Files.writeString(
                    root.resolve("fragment.mantra"),
                    Files.readString(root.resolve("fragment.mantra")) + "\n; external",
                )
            }
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview("case.mantra", request(snapshot))
            }.problem,
        )
    }

    @Test fun `identity include dependencies read-only handles and budgets are enforced`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        for (replacement in listOf(
            source(snapshot, "schema.mantra").replace("example/template", "another/template"),
            source(snapshot, "schema.mantra").replace("\"1\"", "\"2\""),
            source(snapshot, "schema.mantra").replace("fragment.mantra", "extra.mantra"),
        )) {
            assertEquals(
                WorkspaceProblem.INVALID,
                assertFailsWith<WorkspaceException> {
                    catalog.templatePreview("case.mantra", request(snapshot, mapOf("schema.mantra" to replacement)))
                }.problem,
            )
        }
        assertEquals(
            WorkspaceProblem.REQUEST,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview(
                    "case.mantra",
                    request(snapshot, mapOf("case.mantra" to source(snapshot, "case.mantra"))),
                )
            }.problem,
        )
        assertEquals(
            WorkspaceProblem.REQUEST,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview(
                    "case.mantra",
                    request(snapshot).copy(documents = listOf(TemplateSourceEdit("0".repeat(64), "outside"))),
                )
            }.problem,
        )
        assertEquals(
            WorkspaceProblem.TOO_LARGE,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview("case.mantra", request(snapshot, mapOf("schema.mantra" to " ".repeat(65_537))))
            }.problem,
        )
        assertEquals(
            WorkspaceProblem.REQUEST,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview(
                    "case.mantra",
                    request(
                        snapshot,
                    ).copy(
                        inputs = listOf(TemplateInputText("base-amount", "1"), TemplateInputText("base-amount", "2")),
                    ),
                )
            }.problem,
        )
    }

    @Test fun `malformed source has structured diagnostics and missing explain target or panel has not-found`() {
        val catalog = workspace()
        val snapshot = catalog.templateSources("case.mantra")
        val syntax =
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview("case.mantra", request(snapshot, mapOf("schema.mantra" to "(schema")))
            }
        assertEquals(WorkspaceProblem.INVALID, syntax.problem)
        assertTrue(syntax.diagnostics.any { it.code == "MANTRA-READ-SYNTAX" })
        assertEquals(
            WorkspaceProblem.NOT_FOUND,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview(
                    "case.mantra",
                    request(snapshot, explain = ExplainAddress("missing", emptyList())),
                )
            }.problem,
        )
        assertEquals(
            WorkspaceProblem.NOT_FOUND,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview("case.mantra", request(snapshot).copy(panel = "missing"))
            }.problem,
        )
        Files.writeString(root.resolve("schema.mantra"), "(schema")
        assertEquals(
            WorkspaceProblem.INVALID,
            assertFailsWith<WorkspaceException> {
                catalog.templateSources("case.mantra")
            }.problem,
        )
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview("case.mantra", request(snapshot))
            }.problem,
        )
    }

    @Test fun `root schema overlay leaves a linked case sharing that schema on its captured original`() {
        val catalog = workspace()
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema example/template {:version "1" :mainline [main]}
              (input seed :decimal)
              (input incoming :decimal)
              (section main "Main" {:panel true} (info answer "Answer" (+ seed incoming))))
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("source.mantra"),
            "(case source {:schema \"example/template\"} (inputs {:seed 3 :incoming 0}))",
        )
        Files.writeString(
            root.resolve("case.mantra"),
            """
            (case target {:schema "example/template" :layout "example/layout"}
              (inputs {:seed 2})
              (links {:path "source.mantra" :schema "example/template" :schema-version "1"
                :mappings [{:from {:node answer :coord []} :to {:input incoming :coord []}}]}))
            """.trimIndent(),
        )
        val snapshot = catalog.templateSources("case.mantra")
        assertTrue("source.mantra" in (snapshot.data["baseRevisions"] as Map<*, *>))
        assertTrue(documents(snapshot).none { it["document"] == "source.mantra" })
        val candidate = catalog.successPreview(
            request(
                snapshot,
                mapOf(
                    "schema.mantra" to source(
                        snapshot,
                        "schema.mantra",
                    ).replace("(+ seed incoming)", "(* seed incoming)"),
                ),
                input = null,
            ).copy(inputs = listOf(TemplateInputText("seed", "12"))),
        )
        assertEquals(
            mapOf("n" to "36"),
            runValue(candidate, "answer"),
            "Root 12 * linked original (3 + 0); linked overlay would incorrectly yield zero",
        )
        assertEquals(mapOf("n" to "3"), runValue(candidate, "incoming"))
        assertEquals(
            WorkspaceProblem.REQUEST,
            assertFailsWith<WorkspaceException> {
                catalog.templatePreview(
                    "case.mantra",
                    request(snapshot, mapOf("schema.mantra" to source(snapshot, "schema.mantra")), input = null)
                        .copy(inputs = listOf(TemplateInputText("incoming", "99"))),
                )
            }.problem,
        )
    }
}
