package com.xqiou.mantra.workbench

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.read.SourceText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagnosticSourceContextTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var directory: Path
    private val json = ObjectMapper()

    @Test fun `real diagnostic context matches strict schema and shared UI golden`() {
        val catalog = workspaceCatalog(Path.of("apps"))
        val case = "ifrs-income-taxes/case-unreconciled.mantra"
        val diagnostics = catalog.document(case, "diagnostics")
        val actual = catalog.envelope(catalog.sourceContext(case, 0, diagnostics.revision))
        val golden = Path.of(
            "mantra-workbench/src/test/resources/golden/ifrs-income-taxes-case-unreconciled-5198903e/source-context.json",
        )
        assertEquals(json.readTree(Files.readString(golden)), json.readTree(actual))
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/source-context.schema.json"),
        )
        assertTrue(schema.validate(actual, InputFormat.JSON).isEmpty())
    }

    private fun linkedWorkspace() {
        Files.writeString(
            directory.resolve("source-schema.mantra"),
            """
            (schema test/source {:version "1"}
              (input source-amount :decimal)
              (section main "Source"
                (line exported "Exported" source-amount)
                (check positive "Positive" (> source-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(
            directory.resolve("consumer-schema.mantra"),
            """
            (schema test/consumer {:version "1"}
              (input received :decimal)
              (section main "Consumer" (line result "Result" received)))
            """.trimIndent(),
        )
        Files.writeString(
            directory.resolve("source.mantra"),
            "(case source {:schema \"test/source\" :schema-version \"1\"} (inputs {:source-amount -1}))",
        )
        Files.writeString(
            directory.resolve("consumer.mantra"),
            """
            (case consumer {:schema "test/consumer" :schema-version "1"}
              (links {:path "source.mantra" :schema "test/source" :schema-version "1"
                :mappings [{:from {:node exported :coord []} :to {:input received :coord []}}]}))
            """.trimIndent(),
        )
        Files.writeString(directory.resolve("unrelated.mantra"), "(parameters unrelated {:for \"test/source\"})")
        Files.writeString(directory.resolve("private.txt"), "private data must never be displayed")
    }

    @Test fun `linked diagnostic uses its owning source revision and rejects same-value upstream changes`() {
        linkedWorkspace()
        val catalog = workspaceCatalog(directory)
        val diagnostics = catalog.document("consumer.mantra", "diagnostics")
        val finding = (diagnostics.data["diagnostics"] as List<*>).single() as Map<*, *>
        val context = catalog.sourceContext("consumer.mantra", 0, diagnostics.revision)
        assertEquals("source.mantra", context.data["case"])
        assertEquals(finding["caseRevision"], context.data["revision"])
        assertEquals(finding["location"], context.data["location"])
        assertContains(json.writeValueAsString(context.data), "Positive")
        assertFalse(json.writeValueAsString(context.data).contains("private data"))
        val source = directory.resolve("source.mantra")
        Files.writeString(source, Files.readString(source) + "\n;; same values, new captured source\n")
        val changed = catalog.document("consumer.mantra", "diagnostics")
        assertNotEquals(diagnostics.revision, changed.revision)
        val stale = assertFailsWith<WorkspaceException> {
            catalog.sourceContext("consumer.mantra", 0, diagnostics.revision)
        }
        assertEquals(WorkspaceProblem.CONFLICT, stale.problem)
        assertEquals(changed.revision, stale.currentRevision)
        assertEquals(changed.revision, catalog.sourceContext("consumer.mantra", 0, changed.revision).revision)
    }

    @Test fun `invalid selectors and symlink escapes never disclose source text`() {
        linkedWorkspace()
        val catalog = workspaceCatalog(directory)
        val revision = catalog.document("consumer.mantra", "diagnostics").revision
        assertEquals(
            WorkspaceProblem.REQUEST,
            assertFailsWith<WorkspaceException> { catalog.sourceContext("consumer.mantra", -1, revision) }.problem,
        )
        assertEquals(
            WorkspaceProblem.NOT_FOUND,
            assertFailsWith<WorkspaceException> { catalog.sourceContext("consumer.mantra", 8, revision) }.problem,
        )
        assertEquals(
            WorkspaceProblem.REQUEST,
            assertFailsWith<WorkspaceException> { catalog.sourceContext("consumer.mantra", 0, "") }.problem,
        )
        val schema = directory.resolve("source-schema.mantra")
        val outside = directory.parent.resolve("outside-${directory.fileName}.mantra")
        Files.writeString(outside, Files.readString(schema))
        try {
            Files.delete(schema)
            Files.createSymbolicLink(schema, outside)
            assertFailsWith<WorkspaceException> { catalog.sourceContext("consumer.mantra", 0, revision) }
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test fun `line projection respects CRLF Unicode UTF16 spans and bounded windows`() {
        val text = "first\r\nα🙂 target\r\nlast"
        val start = text.indexOf("target")
        val location = SourceLocation("schema.mantra", 2, start - text.indexOf('α') + 1, start, start + 6)
        val data = DiagnosticSources.excerpt(SourceText("schema.mantra", text), location, "case.mantra", "a".repeat(64))
        val line = (data["lines"] as List<*>)[1] as Map<*, *>
        assertEquals("α🙂 target", line["text"])
        val shown = line["text"] as String
        assertEquals("target", shown.substring(line["highlightStart"] as Int, line["highlightEnd"] as Int))
        assertEquals(false, data["truncated"])
        val long = "α🙂".repeat(200) + "target" + "🙂".repeat(400)
        val longStart = long.indexOf("target")
        val clipped = DiagnosticSources.excerpt(
            SourceText("schema.mantra", long),
            SourceLocation("schema.mantra", 1, longStart + 1, longStart, longStart + 6),
            "case.mantra",
            "a".repeat(64),
        )
        val window = (clipped["lines"] as List<*>).single() as Map<*, *>
        val contents = window["text"] as String
        assertTrue(contents.length <= 512)
        assertFalse(contents.first().isLowSurrogate())
        assertFalse(contents.last().isHighSurrogate())
        assertEquals("target", contents.substring(window["highlightStart"] as Int, window["highlightEnd"] as Int))
        assertEquals(true, clipped["truncated"])
        val point = DiagnosticSources.excerpt(
            SourceText("schema.mantra", "α🙂 target"),
            SourceLocation("schema.mantra", 1, 2),
            "case.mantra",
            "a".repeat(64),
        )
        val pointLine = (point["lines"] as List<*>).single() as Map<*, *>
        assertEquals(
            "🙂",
            (pointLine["text"] as String).substring(
                pointLine["highlightStart"] as Int,
                pointLine["highlightEnd"] as Int,
            ),
        )
        val multiline = (1..20).joinToString("\n") { "line $it" }
        val spanStart = multiline.indexOf("line 10")
        val limited = DiagnosticSources.excerpt(
            SourceText("schema.mantra", multiline),
            SourceLocation("schema.mantra", 10, 1, spanStart, multiline.length),
            "case.mantra",
            "a".repeat(64),
        )
        assertEquals(7, (limited["lines"] as List<*>).size)
        assertEquals(true, limited["truncated"])
    }

    @Test fun `incorrect source identities and offsets are rejected rather than moved`() {
        val source = SourceText("schema.mantra", "α🙂 target")
        for (location in listOf(
            SourceLocation("private.txt", 1, 1, 0, 1),
            SourceLocation("schema.mantra", 1, 2, 4, 5),
            SourceLocation("schema.mantra", 1, 3, 2, 3),
            SourceLocation("schema.mantra", 20, 1),
        )) {
            assertEquals(
                WorkspaceProblem.NOT_FOUND,
                assertFailsWith<WorkspaceException> {
                    DiagnosticSources.excerpt(source, location, "case.mantra", "a".repeat(64))
                }.problem,
            )
        }
    }

    @Test fun `diagnostic selectors preserve planning warning order after cached and fresh requests`() {
        linkedWorkspace()
        val schema = directory.resolve("source-schema.mantra")
        Files.writeString(
            schema,
            Files.readString(schema).replace("source-amount", "amount").replace("\" amount)", "\" mantra/amount)")
                .replace("(> amount 0)", "(> mantra/amount 0)"),
        )
        val source = directory.resolve("source.mantra")
        Files.writeString(source, Files.readString(source).replace(":source-amount", ":amount"))
        val catalog = workspaceCatalog(directory)
        // The ordinary session is warmed before the diagnostics resource is requested.
        repeat(2) { catalog.document("source.mantra", "run") }
        val first = catalog.document("source.mantra", "diagnostics")
        val firstFindings = first.data["diagnostics"] as List<*>
        assertEquals(
            listOf("MANTRA-ID-SHADOWED", "MANTRA-CHECK-FAILED"),
            firstFindings.map {
                (it as Map<*, *>)["code"]
            },
        )
        repeat(3) {
            catalog.document("source.mantra", "run")
            val current = catalog.document("source.mantra", "diagnostics")
            assertEquals(first, current)
            firstFindings.forEachIndexed { index, finding ->
                val context = catalog.sourceContext("source.mantra", index, first.revision)
                assertEquals((finding as Map<*, *>)["location"], context.data["location"])
                assertEquals(first.revision, context.revision)
            }
        }
        // Source business findings retain their independent ownership in consumer requests.
        val linked = catalog.document("consumer.mantra", "diagnostics")
        val finding = (linked.data["diagnostics"] as List<*>).single() as Map<*, *>
        assertEquals("MANTRA-CHECK-FAILED", finding["code"])
        val context = catalog.sourceContext("consumer.mantra", 0, linked.revision)
        assertEquals("source.mantra", context.data["case"])
        assertEquals(finding["location"], context.data["location"])
    }
}
