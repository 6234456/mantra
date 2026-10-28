package com.xqiou.mantra.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.xqiou.mantra.core.api.RuntimeVersions
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

class TemplateAcceptanceCliTest {
    @TempDir lateinit var directory: Path
    private val json = ObjectMapper()

    private fun workspace(): Path {
        val root = Files.createDirectory(directory.resolve("workspace"))
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema example/acceptance {:version "1" :mainline [main]}
              (input base-amount :decimal)
              (section main "Summary" {:panel true}
                (info answer "Answer" (* base-amount 2))
                (check nonnegative "Nonnegative" (>= base-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(root.resolve("layout.mantra"), "(layout example/layout {:locale \"de-DE\" :precision 2})")
        Files.writeString(
            root.resolve("positive.mantra"),
            "(case positive {:schema \"example/acceptance\" :layout \"example/layout\"} (inputs {:base-amount 10}))",
        )
        Files.writeString(
            root.resolve("negative.mantra"),
            "(case negative {:schema \"example/acceptance\" :layout \"example/layout\"} (inputs {:base-amount -2}))",
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

    private fun capture(root: Path): ObjectNode {
        val result = command("template-capture", "positive.mantra", "negative.mantra", "--workspace", "$root")
        assertEquals(0, result.status, result.stderr)
        return json.readTree(result.stdout) as ObjectNode
    }

    private fun completedSuite(root: Path): ObjectNode {
        val suite = capture(root)
        val schema = suite["documents"].single { it["role"].asText() == "schema" } as ObjectNode
        schema.put("text", schema["text"].asText().replace("(* base-amount 2)", "(* base-amount 3)"))
        val positive = suite["cases"][0] as ObjectNode
        positive.set<JsonNode>("inputs", json.readTree("""[{"node":"base-amount","text":"1,25"}]"""))
        positive.set<JsonNode>(
            "expect",
            json.readTree(
                """{"succeeded":true,"validationPassed":true,"values":[
                {"node":"answer","present":true,"value":{"n":"3.7500"},"display":"3,75","active":true},
                {"node":"nonnegative","present":true,"value":true,"validationPassed":true}],"diagnostics":[]}""",
            ),
        )
        (suite["cases"][1] as ObjectNode).set<JsonNode>(
            "expect",
            json.readTree(
                """{"succeeded":true,"validationPassed":false,"values":[
                {"node":"answer","present":true,"value":{"n":"-6"}},
                {"node":"nonnegative","present":true,"value":false,"validationPassed":false}]}""",
            ),
        )
        return suite
    }

    private fun save(suite: JsonNode): Path = directory.resolve("suite.json").also { Files.writeString(it, "$suite") }

    @Test fun `capture never generates expectations and pending cases cannot pass verification`() {
        val root = workspace()
        val suite = capture(root)
        assertTrue(suite["cases"].all { it["expect"].isNull })
        assertFalse(suite.has("run"))
        val result = command("template-verify", "${save(suite)}", "--workspace", "$root")
        assertEquals(2, result.status, result.stderr)
        val report = json.readTree(result.stdout)
        assertFalse(report["passed"].asBoolean())
        assertTrue(report["cases"].all { it["status"].asText() == "error" })
    }

    @Test fun `real dispatcher verifies shared candidate over two cases and writes only explicit report`() {
        val root = workspace()
        val before = Files.list(root).use { paths -> paths.toList().associateWith(Files::readAllBytes) }
        val suite = save(completedSuite(root))
        val report = directory.resolve("reports/acceptance.json")
        val result = command("template-verify", "$suite", "--workspace", "$root", "--out", "$report")
        assertEquals(0, result.status, result.stderr)
        assertEquals("", result.stdout)
        val tree = json.readTree(Files.readString(report))
        assertTrue(tree["passed"].asBoolean())
        assertEquals("mantra.template-acceptance-report/1", tree["format"].asText())
        assertEquals(RuntimeVersions.mantra, tree["engine"]["mantra"].asText())
        assertEquals(RuntimeVersions.normein, tree["engine"]["normein"].asText())
        assertTrue(tree["cases"].all { it["status"].asText() == "passed" })
        before.forEach { (file, bytes) -> assertContentEquals(bytes, Files.readAllBytes(file), "$file") }
        assertEquals(before.size, Files.list(root).use { it.count().toInt() })
    }

    @Test fun `an expected value mismatch returns three and retains actual report evidence`() {
        val root = workspace()
        val suite = completedSuite(root)
        (suite["cases"][0]["expect"]["values"][0]["value"] as ObjectNode).put("n", "999")
        val result = command("template-verify", "${save(suite)}", "--workspace", "$root")
        assertEquals(3, result.status, result.stderr)
        val report = json.readTree(result.stdout)
        assertFalse(report["passed"].asBoolean())
        assertEquals("failed", report["cases"][0]["status"].asText())
        assertEquals("passed", report["cases"][1]["status"].asText())
        assertTrue(report["cases"][0]["assertions"].any { !it["passed"].asBoolean() })
    }

    @Test fun `stale suite never passes despite expected failure and preserves original candidate text`() {
        val root = workspace()
        val suite = completedSuite(root)
        (suite["cases"][0]["expect"] as ObjectNode).put("succeeded", false)
        val file = save(suite)
        Files.writeString(
            root.resolve("schema.mantra"),
            Files.readString(root.resolve("schema.mantra")) + "\n; newer source",
        )
        val result = command("template-verify", "$file", "--workspace", "$root")
        assertEquals(2, result.status, result.stderr)
        assertFalse(json.readTree(result.stdout)["passed"].asBoolean())
        assertEquals(suite, json.readTree(Files.readString(file)))
    }

    @Test fun `suite and real source output targets are protected including hard links and parent aliases`() {
        val root = workspace()
        val file = save(completedSuite(root))
        val originalSuite = Files.readAllBytes(file)
        val source = root.resolve("schema.mantra")
        val originalSource = Files.readAllBytes(source)
        val hard = directory.resolve("source-hardlink.json")
        Files.createLink(hard, source)
        val symbolic = directory.resolve("source-symlink.json")
        Files.createSymbolicLink(symbolic, source)
        val parent = directory.resolve("workspace-alias")
        Files.createSymbolicLink(parent, root)
        listOf(file, source, hard, symbolic, parent.resolve("schema.mantra")).forEach { output ->
            val result = command("template-verify", "$file", "--workspace", "$root", "--out", "$output")
            assertEquals(2, result.status, "Output $output: ${result.stderr}")
            assertContentEquals(originalSuite, Files.readAllBytes(file))
            assertContentEquals(originalSource, Files.readAllBytes(source))
        }
        val capture = command("template-capture", "positive.mantra", "--workspace", "$root", "--out", "$source")
        assertEquals(2, capture.status, capture.stderr)
        assertContentEquals(originalSource, Files.readAllBytes(source))
        assertFalse(
            Files.list(directory).use { paths ->
                paths.anyMatch { it.fileName.toString().startsWith(".mantra-acceptance-") }
            },
        )
    }

    @Test fun `command usage and malformed suites have distinct stable exit codes`() {
        val root = workspace()
        assertEquals(1, command("template-verify", "--workspace", "$root").status)
        assertEquals(1, command("template-capture", "positive.mantra", "--workspace", "$root", "--out").status)
        assertEquals(
            1,
            command("template-capture", "positive.mantra", "--workspace", "$root", "--workspace", "$root").status,
        )
        assertEquals(1, command("template-verify", "a", "b", "--workspace", "$root").status)
        val invalid = directory.resolve("invalid.json")
        Files.writeString(invalid, "{not valid}")
        val result = command("template-verify", "$invalid", "--workspace", "$root")
        assertEquals(2, result.status, result.stderr)
        assertEquals("", result.stdout)
        val suite = completedSuite(root)
        (suite["cases"][0]["expect"]["values"][0] as ObjectNode)
            .set<JsonNode>("value", json.readTree("""{"date":"2025-02-30"}"""))
        assertEquals(2, command("template-verify", "${save(suite)}", "--workspace", "$root").status)
    }

    @Test fun `an unreadable fresh graph only permits a new report outside the workspace`() {
        val root = workspace()
        val suite = save(completedSuite(root))
        Files.writeString(root.resolve("schema.mantra"), "(schema broken")
        val existing = directory.resolve("existing.json")
        Files.writeString(existing, "keep this file")
        val inside = root.resolve("new-report.json")
        listOf(existing, inside).forEach { output ->
            val result = command("template-verify", "$suite", "--workspace", "$root", "--out", "$output")
            assertEquals(2, result.status, result.stderr)
            assertTrue(result.stderr.contains("Sources could not be fully captured"), result.stderr)
        }
        assertEquals("keep this file", Files.readString(existing))
        assertFalse(Files.exists(inside))
        val fresh = directory.resolve("new-report.json")
        val result = command("template-verify", "$suite", "--workspace", "$root", "--out", "$fresh")
        assertEquals(2, result.status, result.stderr)
        assertFalse(json.readTree(Files.readString(fresh))["passed"].asBoolean())
    }
}
