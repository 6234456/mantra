package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaperPreviewServerTest {
    @TempDir lateinit var temporary: Path
    private val json = ObjectMapper()
    private val base = "/api/v1/cases/case.mantra"
    private val route = "$base/preview-paper"
    private val registry by lazy {
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
    }

    private data class Session(
        val workspace: Path,
        val server: WorkbenchServer,
        val client: HttpClient,
        val token: String,
        val revision: String,
        val originalBytes: Map<String, ByteArray>,
    )

    private fun files(workspace: Path): Map<String, ByteArray> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate {
            workspace.relativize(it).toString() to Files.readAllBytes(it)
        }
    }

    private fun assertFiles(session: Session, expected: Map<String, ByteArray> = session.originalBytes) {
        val actual = files(session.workspace)
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertContentEquals(bytes, actual.getValue(path), path) }
    }

    private fun withSession(test: (Session) -> Unit) {
        val workspace = Files.createDirectory(temporary.resolve("workspace"))
        Files.writeString(
            workspace.resolve("schema.mantra"),
            """
            (schema preview/example {:title "Preview example" :mainline [result]}
              (input base-amount :decimal)
              (section result "Result" {:display :schedule :panel true}
                (field base-amount "Amount")
                (formula-slot answer "Answer" (* base-amount 2) {:uses [base-amount]})
                (line blank "Blank" 0)
                (check positive "Nonnegative amount" (>= base-amount 0)))
              (section extra "Extra" {:panel true}
                (line other "Other" 55)))
            """.trimIndent(),
        )
        Files.writeString(
            workspace.resolve("case.mantra"),
            """
            (case example {:schema "preview/example" :title "Original case"}
              ;; Preserve this UTF-8 comment: 测试.
              (inputs {:base-amount 10}))
            """.trimIndent().replace("\n", "\r\n") + "\r\n",
        )
        val ui = Files.createDirectory(temporary.resolve("ui"))
        Files.writeString(ui.resolve("index.html"), "<html><head></head><body>Workbench</body></html>")
        WorkbenchServer(workspace, 0, ui).start().use { server ->
            HttpClient.newHttpClient().use { client ->
                fun get(path: String) = client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val index = get("/")
                assertEquals(200, index.statusCode())
                val token = Regex("name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"")
                    .find(index.body())!!.groupValues[1]
                val baseline = get("$base/run")
                assertEquals(200, baseline.statusCode(), baseline.body())
                val revision = json.readTree(baseline.body())["revision"].asText()
                test(Session(workspace, server, client, token, revision, files(workspace)))
            }
        }
    }

    private fun Session.request(
        body: String = "",
        path: String = route,
        method: String = "POST",
        suppliedToken: String? = token,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
        suppliedToken?.let { builder.header("X-Mantra-Token", it) }
        builder.method(
            method,
            if (body.isEmpty()) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(body)
            },
        )
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** HttpClient removes a trailing empty query; use the literal HTTP request target here. */
    private fun Session.rawPostStatus(path: String, body: String): Int =
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 15_000
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:${server.localPort}\r\n" +
                "X-Mantra-Token: $token\r\nContent-Type: application/json\r\n" +
                "Connection: close\r\nContent-Length: ${bytes.size}\r\n\r\n"
            socket.getOutputStream().apply {
                write(headers.toByteArray(Charsets.UTF_8))
                write(bytes)
                flush()
            }
            socket.shutdownOutput()
            socket.getInputStream().readAllBytes().toString(Charsets.UTF_8).lineSequence().first().split(' ')[1].toInt()
        }

    private fun set(amount: String): Map<String, Any?> = mapOf(
        "op" to "setInput",
        "address" to mapOf("case" to null, "node" to "base-amount", "coord" to emptyList<String>()),
        "value" to mapOf("n" to amount),
    )

    private fun body(
        revision: String,
        sequence: Long = 7,
        operations: List<Map<String, Any?>> = listOf(set("12")),
        options: Map<String, Any?> = emptyMap(),
    ): String = json.writeValueAsString(
        linkedMapOf("baseRevision" to revision, "operations" to operations, "draftSequence" to sequence) + options,
    )

    private fun validate(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        val schema = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/preview-paper.schema.json"),
        )
        val errors = schema.validate(response.body(), InputFormat.JSON)
        assertTrue(errors.isEmpty(), "preview-paper: $errors")
        return json.readTree(response.body())
    }

    private fun number(data: JsonNode, node: String) = data["run"]["values"][node][""]["value"]["n"].asText()
    private fun rows(paper: JsonNode) = paper["tables"].flatMap { it["rows"].toList() }
    private fun assertPaperValue(data: JsonNode, node: String) {
        val row = rows(data["paper"]).single { it["node"].asText() == node }
        val display = data["run"]["values"][node][""]["display"].asText()
        assertTrue(row["cells"].any { it["text"].asText() == display }, row.toString())
    }

    @Test fun `authenticated preview returns matching fresh candidate Paper run and difference without writing`() {
        withSession { session ->
            val candidate = validate(session.request(body(session.revision)))
            assertEquals(session.revision, candidate["revision"].asText())
            val data = candidate["data"]
            assertEquals("case.mantra", data["document"].asText())
            assertTrue(data["preview"].asBoolean())
            assertEquals(7, data["draftSequence"].asInt())
            assertTrue(data["succeeded"].asBoolean())
            assertTrue(data["validationPassed"].asBoolean())
            assertNotEquals(session.revision, data["proposedRevision"].asText())
            assertEquals(data["proposedRevision"], data["run"]["caseGraph"]["cases"][0]["revision"])
            assertEquals("12", number(data, "base-amount"))
            assertEquals("24", number(data, "answer")) // Independent expectation: 12 * 2.
            assertPaperValue(data, "answer")
            val answer = data["difference"]["changes"].flatMap { it["items"].toList() }
                .single { it["node"].asText() == "answer" }
            assertEquals("20", answer["base"]["n"].asText())
            assertEquals("24", answer["variant"]["n"].asText())
            assertEquals("4", answer["delta"]["n"].asText())
            assertFalse(data["paper"]["browsing"]["includeZero"].asBoolean())
            assertFalse(rows(data["paper"]).any { it["node"].asText() == "blank" })
            assertTrue(data["paper"]["audit"].any { it["explanation"]["steps"]?.isEmpty == false })
            val saved = session.request(path = "$base/run", method = "GET")
            assertEquals(200, saved.statusCode(), saved.body())
            val persisted = json.readTree(saved.body())
            assertEquals(session.revision, persisted["revision"].asText())
            assertEquals("20", persisted["data"]["values"]["answer"][""]["value"]["n"].asText())
            for (action in listOf("undo", "redo")) {
                val response = session.request("""{"baseRevision":"${session.revision}"}""", "$base/$action")
                assertEquals(400, response.statusCode(), response.body())
            }
            assertFiles(session)
        }
    }

    @Test fun `sequence boundaries panel selection and zero expansion stay in the candidate projection`() {
        withSession { session ->
            for (sequence in listOf(0L, 9_007_199_254_740_991L)) {
                val candidate = validate(
                    session.request(
                        body(
                            session.revision,
                            sequence,
                            options = mapOf("panel" to "result", "includeZero" to true),
                        ),
                    ),
                )
                val data = candidate["data"]
                assertEquals(sequence, data["draftSequence"].asLong())
                assertEquals(listOf("result"), data["paper"]["tables"].map { it["id"].asText() })
                assertTrue(data["paper"]["browsing"]["includeZero"].asBoolean())
                assertTrue(data["paper"]["browsing"]["hideZero"].asBoolean())
                assertTrue(rows(data["paper"]).any { it["node"].asText() == "blank" })
                assertEquals("24", number(data, "answer"))
                assertPaperValue(data, "answer")
            }
            val missing = session.request(body(session.revision, options = mapOf("panel" to "missing")))
            assertEquals(404, missing.statusCode(), missing.body())
            assertFiles(session)
        }
    }

    @Test fun `formula bindings and business findings use the same nonpersistent candidate path`() {
        withSession { session ->
            val operation = mapOf("op" to "bindFormula", "id" to "answer", "formula" to "(+ base-amount 3)")
            val formula = validate(session.request(body(session.revision, operations = listOf(operation))))["data"]
            assertEquals("13", number(formula, "answer")) // Independent expectation: 10 + 3.
            assertPaperValue(formula, "answer")
            val business = validate(session.request(body(session.revision, operations = listOf(set("-2")))))["data"]
            assertTrue(business["succeeded"].asBoolean())
            assertFalse(business["validationPassed"].asBoolean())
            assertTrue(business["diagnostics"].any { it["code"].asText() == "MANTRA-CHECK-FAILED" })
            assertEquals("-4", number(business, "answer"))
            assertPaperValue(business, "answer")
            assertFiles(session)
        }
    }

    @Test fun `technical invalidity returns 422 diagnostics and leaves all original bytes intact`() {
        withSession { session ->
            val invalidInput = set("12") + ("value" to "wrong decimal type")
            val invalidFormula = mapOf("op" to "bindFormula", "id" to "answer", "formula" to "(+ missing 1)")
            for (operation in listOf(invalidInput, invalidFormula)) {
                val response = session.request(body(session.revision, operations = listOf(operation)))
                assertEquals(422, response.statusCode(), response.body())
                val error = json.readTree(response.body())["error"]
                assertEquals("MANTRA-WORKBENCH-DOCUMENT", error["code"].asText())
                assertTrue(error["diagnostics"].size() > 0)
                assertTrue(error["diagnostics"].any { it["category"].asText() != "business" })
                assertFiles(session)
            }
        }
    }

    @Test fun `runtime failure remains an unsuccessful candidate without replacing saved values`() {
        withSession { session ->
            val operation = mapOf("op" to "bindFormula", "id" to "answer", "formula" to "(/ base-amount 0)")
            val candidate = validate(session.request(body(session.revision, operations = listOf(operation))))["data"]
            assertFalse(candidate["succeeded"].asBoolean())
            assertFalse(candidate["run"]["succeeded"].asBoolean())
            assertTrue(candidate["diagnostics"].any { it["category"].asText() == "evaluation" })
            val saved = session.request(path = "$base/run", method = "GET")
            assertEquals(200, saved.statusCode(), saved.body())
            assertEquals("20", json.readTree(saved.body())["data"]["values"]["answer"][""]["value"]["n"].asText())
            assertFiles(session)
        }
    }

    @Test fun `strict preview parser rejects malformed duplicate trailing and unsafe request fields`() {
        withSession { session ->
            val valid = body(session.revision)
            val root = json.readTree(valid)
            val invalid = mutableListOf("", "{", "null", "[]", "$valid {}")
            for (field in listOf("baseRevision", "operations", "draftSequence")) {
                invalid += json.writeValueAsString(
                    root.deepCopy<ObjectNode>().apply {
                        remove(field)
                    },
                )
            }
            for (sequence in listOf("-1", "9007199254740992", "9223372036854775808", "1.0", "1.5", "\"7\"", "null")) {
                invalid += valid.replace("\"draftSequence\":7", "\"draftSequence\":$sequence")
            }
            invalid += valid.replace("\"draftSequence\":7", "\"draftSequence\":7,\"draftSequence\":8")
            invalid += valid.replace("\"n\":\"12\"", "\"n\":\"12\",\"n\":\"13\"")
            invalid += body(session.revision, options = mapOf("panel" to ""))
            invalid += body(session.revision, options = mapOf("panel" to "   "))
            invalid += body(session.revision, options = mapOf("panel" to null))
            invalid += body(session.revision, options = mapOf("includeZero" to "true"))
            invalid += body(session.revision, options = mapOf("includeZero" to 1))
            invalid += body(session.revision, options = mapOf("includeZero" to null))
            invalid += body(session.revision, options = mapOf("source" to "outside.mantra"))
            invalid += body("not-a-revision")
            invalid += body(session.revision, operations = emptyList())
            invalid += body(session.revision, operations = List(101) { set("12") })
            invalid += body(session.revision, operations = listOf(set("12") + ("extra" to true)))
            invalid += body(session.revision, operations = listOf(set("12") + ("value" to 12)))
            for (request in invalid) {
                val response = session.request(request)
                assertEquals(400, response.statusCode(), request + "\n" + response.body())
                assertEquals("MANTRA-WORKBENCH-REQUEST", json.readTree(response.body())["error"]["code"].asText())
            }
            assertFiles(session)
        }
    }

    @Test fun `same-valued schema change rejects stale full graph revision without preview writes`() {
        withSession { session ->
            val schema = session.workspace.resolve("schema.mantra")
            Files.writeString(schema, Files.readString(schema) + "\n;; Same values, different participating bytes.\n")
            val current = files(session.workspace)
            val response = session.request(body(session.revision))
            assertEquals(409, response.statusCode(), response.body())
            val error = json.readTree(response.body())["error"]
            assertEquals("MANTRA-WORKBENCH-CONFLICT", error["code"].asText())
            assertNotEquals(session.revision, error["currentRevision"].asText())
            assertTrue(Regex("[0-9a-f]{64}").matches(error["currentRevision"].asText()))
            assertFiles(session, current)
        }
    }

    @Test fun `preview requires its actual session token and rejects query or unsupported methods`() {
        withSession { session ->
            val valid = body(session.revision)
            for (token in listOf(null, "invalid-session-token")) {
                val response = session.request(valid, suppliedToken = token)
                assertEquals(403, response.statusCode(), response.body())
                assertEquals("MANTRA-WORKBENCH-TOKEN", json.readTree(response.body())["error"]["code"].asText())
            }
            assertEquals(400, session.rawPostStatus("$route?", valid))
            for (query in listOf("?panel=result", "?includeZero=true", "?draftSequence=7", "?unexpected=true")) {
                val response = session.request(valid, path = route + query)
                assertEquals(400, response.statusCode(), query + "\n" + response.body())
            }
            assertEquals(501, session.request(path = route, method = "GET").statusCode())
            for (method in listOf("PUT", "DELETE", "PATCH")) {
                assertEquals(405, session.request(valid, method = method).statusCode(), method)
            }
            assertFiles(session)
        }
    }
}
