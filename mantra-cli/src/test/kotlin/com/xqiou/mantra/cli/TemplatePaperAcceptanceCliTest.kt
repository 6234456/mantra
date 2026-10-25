package com.xqiou.mantra.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplatePaperAcceptanceCliTest {
    @TempDir lateinit var directory: Path
    private val json = ObjectMapper()

    private fun workspace(): Path {
        val root = Files.createDirectory(directory.resolve("workspace"))
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema example/paper-check {:version "1" :mainline [main]}
              (input base-amount :decimal)
              (section main "Summary" {:panel true}
                (info answer "Answer" (* base-amount 2) {:class :custom})
                (info zero-row "Zero" 0)))
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("layout.mantra"),
            """
            (layout example/paper-check-layout {:title "Saved" :locale "de-DE" :precision 2 :hide-zero true}
              (style-class :custom {:weight :bold :tone :accent :fill :subtle})
              (style {:class :custom} {:use [:custom]})
              (table main (col :label {:header "Item"}) (col :value {:header "Amount"})))
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("case.mantra"),
            "(case example {:schema \"example/paper-check\" :layout \"example/paper-check-layout\"} (inputs {:base-amount 10}))",
        )
        return root
    }

    private data class Output(val status: Int, val stdout: String, val stderr: String)

    private fun command(vararg args: String): Output {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val status = PrintStream(stdout, true, Charsets.UTF_8).use { out ->
            PrintStream(stderr, true, Charsets.UTF_8).use { err -> executeCli(arrayOf(*args), out, err) }
        }
        return Output(status, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    private fun capture(root: Path, vararg extra: String): Output =
        command("template-capture", "case.mantra", "--workspace", "$root", *extra)

    private fun filled(root: Path): ObjectNode {
        val captured = capture(root, "--format", "2")
        assertEquals(0, captured.status, captured.stderr)
        val suite = json.readTree(captured.stdout) as ObjectNode
        for (item in suite["documents"]) {
            val doc = item as ObjectNode
            doc.put(
                "text",
                if (doc["role"].asText() == "schema") {
                    doc["text"].asText().replace("(* base-amount 2)", "(* base-amount 3)")
                } else {
                    doc["text"].asText().replace("Saved", "Candidate").replace(":precision 2", ":precision 3")
                },
            )
        }
        (suite["cases"][0] as ObjectNode).set<JsonNode>(
            "expect",
            json.readTree(
                """
                {"succeeded":true,"validationPassed":true,
                 "values":[{"node":"answer","present":true,"value":{"n":"30"}}],
                 "paper":{"title":"Candidate",
                   "tables":[{"id":"main","present":true,"style":"tiered",
                     "columns":[{"id":"label","header":"Item"},{"id":"value","header":"Amount"}]}],
                   "cells":[
                     {"table":"main","column":"value","address":{"node":"answer","coord":[]},
                      "present":true,"text":"30,000","editable":false,
                      "style":{"weight":"bold","tone":"accent","fill":"subtle"}},
                     {"table":"main","column":"label","row":{"node":"answer","kind":"value"},
                      "present":true,"text":"Answer"},
                     {"table":"main","column":"value","address":{"node":"zero-row","coord":[]},"present":false}]}}
                """.trimIndent(),
            ),
        )
        return suite
    }

    private fun save(suite: JsonNode): Path = directory.resolve("suite.json").also { Files.writeString(it, "$suite") }

    private fun validate(payload: String, name: String) {
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/$name.schema.json"))
        val errors = schema.validate(payload, InputFormat.JSON)
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun `default and explicit capture one remain identical while two requires an explicit option`() {
        val root = workspace()
        val legacy = capture(root)
        val explicit = capture(root, "--format", "1")
        val newer = capture(root, "--format", "2")
        assertEquals(0, legacy.status, legacy.stderr)
        assertEquals(legacy, explicit)
        assertEquals(0, newer.status, newer.stderr)
        val v2 = json.readTree(newer.stdout) as ObjectNode
        assertTrue(v2["cases"][0]["expect"].isNull)
        validate(newer.stdout, "template-acceptance-v2")
        v2.put("format", "mantra.template-acceptance/1")
        assertEquals(json.readTree(legacy.stdout), v2)
        assertEquals(1, capture(root, "--format", "3").status)
        val suite = save(json.readTree(legacy.stdout))
        assertEquals(1, command("template-verify", "$suite", "--workspace", "$root", "--format", "2").status)
    }

    @Test fun `real v2 preview validates displayed text styles column order and hidden zero without source writes`() {
        val root = workspace()
        val before = Files.list(root).use { paths -> paths.toList().associateWith(Files::readAllBytes) }
        val suite = filled(root)
        validate("$suite", "template-acceptance-v2")
        val result = command("template-verify", "${save(suite)}", "--workspace", "$root")
        assertEquals(0, result.status, result.stderr + result.stdout)
        validate(result.stdout, "template-acceptance-report-v2")
        val report = json.readTree(result.stdout)
        assertEquals("mantra.template-acceptance-report/2", report["format"].asText())
        assertEquals("Candidate", report["cases"][0]["paper"]["title"].asText())
        assertTrue(report["cases"][0]["assertions"].any { it["path"].asText().contains("/paper/") })
        before.forEach { (file, bytes) -> assertContentEquals(bytes, Files.readAllBytes(file)) }
        assertEquals(before.size, Files.list(root).use { it.count().toInt() })
    }

    @Test fun `wrong displayed text fails while a misspelled parent column is a technical error`() {
        val root = workspace()
        val suite = filled(root)
        val cell = suite["cases"][0]["expect"]["paper"]["cells"][0] as ObjectNode
        cell.put("text", "999,000")
        val mismatch = command("template-verify", "${save(suite)}", "--workspace", "$root")
        assertEquals(3, mismatch.status, mismatch.stderr + mismatch.stdout)
        val mismatchReport = json.readTree(mismatch.stdout)
        assertFalse(mismatchReport["passed"].asBoolean())
        assertEquals("Candidate", mismatchReport["cases"][0]["paper"]["title"].asText())
        validate(mismatch.stdout, "template-acceptance-report-v2")
        cell.put("column", "misspelled-column")
        val error = command("template-verify", "${save(suite)}", "--workspace", "$root")
        assertEquals(2, error.status, error.stderr + error.stdout)
        assertEquals("error", json.readTree(error.stdout)["cases"][0]["status"].asText())
        validate(error.stdout, "template-acceptance-report-v2")
    }
}
