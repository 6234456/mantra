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

class TemplatePreviewServerTest {
    @TempDir lateinit var temporary: Path
    private val json = ObjectMapper()
    private val route = "/api/v1/cases/case.mantra"
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
        val snapshot: JsonNode,
        val original: Map<Path, ByteArray>,
    )

    private fun withSession(test: (Session) -> Unit) {
        val workspace = Files.createDirectory(temporary.resolve("workspace"))
        Files.writeString(
            workspace.resolve("schema.mantra"),
            """
            (schema draft/example {:version "1" :mainline [main]}
              (include "helpers.mantra")
              (input base-amount :decimal)
              (section main "Main" {:panel true}
                (field base-amount "Amount" {:op :info})
                (info answer "Answer" (* base-amount 2))
                (check positive "Nonnegative" (>= base-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(workspace.resolve("helpers.mantra"), "(fragment (defn twice [^Decimal value] (* value 2)))")
        Files.writeString(
            workspace.resolve("layout.mantra"),
            "(layout draft/layout {:title \"Original\" :locale \"de-DE\"})",
        )
        Files.writeString(
            workspace.resolve("case.mantra"),
            "(case original {:schema \"draft/example\" :layout \"draft/layout\"}" +
                "\r\n  (inputs {:base-amount 10}))\r\n",
        )
        val ui = Files.createDirectory(temporary.resolve("ui"))
        Files.writeString(ui.resolve("index.html"), "<html><head></head><body>Workbench</body></html>")
        WorkbenchServer(workspace, 0, ui).start().use { server ->
            HttpClient.newHttpClient().use { client ->
                fun get(path: String) = client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val token = Regex("name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"")
                    .find(get("/").body())!!.groupValues[1]
                val snapshot = validate(get("$route/template-sources"), "template-sources")
                val originals = Files.list(workspace).use { paths -> paths.toList().associateWith(Files::readAllBytes) }
                test(Session(workspace, server, client, token, snapshot, originals))
            }
        }
    }

    private fun Session.request(
        body: String = "",
        suffix: String = "template-preview",
        method: String = "POST",
        suppliedToken: String? = token,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$route/$suffix"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
        suppliedToken?.let { request.header("X-Mantra-Token", it) }
        request.method(
            method,
            if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body),
        )
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun source(session: Session, name: String) =
        session.snapshot["data"]["documents"].single { it["document"].asText() == name }

    private fun body(
        session: Session,
        changes: Map<String, String> = emptyMap(),
        input: String = "12",
        extra: Map<String, Any?> = emptyMap(),
    ): ObjectNode = json.valueToTree(
        linkedMapOf(
            "baseRevision" to session.snapshot["revision"].asText(),
            "baseRevisions" to session.snapshot["data"]["baseRevisions"],
            "draftSequence" to 9,
            "documents" to
                changes.map { (name, text) ->
                    mapOf("handle" to source(session, name)["handle"].asText(), "text" to text)
                },
            "inputs" to listOf(mapOf("node" to "base-amount", "text" to input)),
            "panel" to "main",
        ) + extra,
    )

    private fun validate(response: HttpResponse<String>, name: String = "template-preview"): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        val errors = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/$name.schema.json"))
            .validate(response.body(), InputFormat.JSON)
        assertTrue(errors.isEmpty(), "$name: $errors")
        return json.readTree(response.body())
    }

    private fun assertFiles(session: Session) {
        val files = Files.list(session.workspace).use { it.toList() }
        assertEquals(session.original.keys, files.toSet())
        session.original.forEach { (path, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(path), path.toString())
        }
    }

    @Test fun `real schema layout inputs and Explain share one isolated candidate response`() {
        withSession { session ->
            val changes = mapOf(
                "schema.mantra" to
                    source(session, "schema.mantra")["text"].asText().replace("(* base-amount 2)", "(* base-amount 3)"),
                "layout.mantra" to source(session, "layout.mantra")["text"].asText().replace("Original", "Draft title"),
            )
            val candidate =
                validate(
                    session.request(
                        body(session, changes, extra = mapOf("explain" to mapOf("node" to "answer"))).toString(),
                    ),
                )
            val data = candidate["data"]
            assertEquals(session.snapshot["revision"], candidate["revision"])
            assertEquals(9, data["draftSequence"].intValue())
            assertNotEquals(candidate["revision"], data["proposedRevision"])
            assertEquals("36", data["run"]["values"]["answer"][""]["value"]["n"].asText())
            assertEquals("Draft title", data["paper"]["title"].asText())
            assertEquals(data["proposedRevision"], data["explain"]["revision"])
            assertTrue(data["explain"]["steps"].size() > 0)
            assertTrue(data["difference"]["changes"].size() > 0)
            assertFiles(session)
            val saved = json.readTree(session.request(suffix = "run", method = "GET").body())
            assertEquals("20", saved["data"]["values"]["answer"][""]["value"]["n"].asText())
            assertEquals(session.snapshot["revision"], saved["revision"])
            assertEquals(
                400,
                session.request(
                    json.writeValueAsString(mapOf("baseRevision" to saved["revision"].asText())),
                    "undo",
                ).statusCode(),
            )
        }
    }

    @Test fun `input parsing follows candidate locale and type and findings remain nonblocking`() {
        withSession { session ->
            val english =
                mapOf("layout.mantra" to source(session, "layout.mantra")["text"].asText().replace("de-DE", "en-US"))
            val candidate = validate(session.request(body(session, english, "1.25").toString()))
            assertEquals("2.50", candidate["data"]["run"]["values"]["answer"][""]["value"]["n"].asText())
            val textSchema = source(session, "schema.mantra")["text"].asText().replace(":decimal", ":text")
                .replace("(* base-amount 2)", "7").replace("(>= base-amount 0)", "true")
            val text =
                validate(session.request(body(session, mapOf("schema.mantra" to textSchema), "hello").toString()))
            assertEquals("hello", text["data"]["run"]["values"]["base-amount"][""]["value"].asText())
            val finding = validate(session.request(body(session, input = "-1").toString()))
            assertTrue(finding["data"]["succeeded"].booleanValue())
            assertFalse(finding["data"]["validationPassed"].booleanValue())
            assertFiles(session)
        }
    }

    @Test fun `invalid source diagnostics conflict and runtime partial evidence preserve saved files`() {
        withSession { session ->
            val original = source(session, "schema.mantra")["text"].asText()
            val invalid = session.request(
                body(
                    session,
                    mapOf("schema.mantra" to original.replace("(* base-amount 2)", "missing-symbol")),
                ).toString(),
            )
            assertEquals(422, invalid.statusCode(), invalid.body())
            assertTrue(
                json.readTree(invalid.body())["error"]["diagnostics"].any {
                    it["location"]?.get("document")?.asText() ==
                        "schema.mantra"
                },
            )
            val runtime =
                validate(
                    session.request(
                        body(
                            session,
                            mapOf("schema.mantra" to original.replace("(* base-amount 2)", "(/ base-amount 0)")),
                        ).toString(),
                    ),
                )
            assertFalse(runtime["data"]["succeeded"].booleanValue())
            assertEquals("12", runtime["data"]["run"]["values"]["base-amount"][""]["value"]["n"].asText())
            assertTrue(runtime["data"]["diagnostics"].size() > 0)
            val missing = body(session)
            (missing["baseRevisions"] as ObjectNode).remove("helpers.mantra")
            assertEquals(409, session.request(missing.toString()).statusCode())
            assertFiles(session)
            Files.writeString(
                session.workspace.resolve("helpers.mantra"),
                Files.readString(session.workspace.resolve("helpers.mantra")) + "\n; external",
            )
            assertEquals(409, session.request(body(session).toString()).statusCode())
        }
    }

    @Test fun `token route shape duplicate keys trailing data and safe integer bounds are enforced`() {
        withSession { session ->
            val valid = body(session).toString()
            assertEquals(403, session.request(valid, suppliedToken = null).statusCode())
            assertEquals(405, session.request(suffix = "template-preview", method = "GET").statusCode())
            assertEquals(405, session.request(valid, "template-sources").statusCode())
            assertEquals(400, session.request(valid, "template-preview?panel=main").statusCode())
            assertEquals(400, session.request(suffix = "template-sources?x=1", method = "GET").statusCode())
            val malformed = listOf(
                valid.dropLast(1) + ",\"draftSequence\":10}",
                "$valid {}",
                body(session, extra = mapOf("path" to "schema.mantra")).toString(),
                body(session, extra = mapOf("draftSequence" to 9_007_199_254_740_992L)).toString(),
                body(session, extra = mapOf("draftSequence" to 1.5)).toString(),
                body(session, extra = mapOf("includeZero" to "false")).toString(),
                body(
                    session,
                    extra = mapOf("explain" to mapOf("node" to "answer", "case" to "linked.mantra")),
                ).toString(),
                body(
                    session,
                    extra = mapOf("inputs" to listOf(mapOf("node" to "base-amount", "value" to 12))),
                ).toString(),
                body(session, extra = mapOf("documents" to emptyList<Any>(), "inputs" to emptyList<Any>())).toString(),
            )
            malformed.forEach { assertEquals(400, session.request(it).statusCode(), it) }
            assertEquals(
                413,
                session.request(body(session, mapOf("schema.mantra" to " ".repeat(65_537))).toString()).statusCode(),
            )
            assertFiles(session)
        }
    }

    @Test fun `read-only handles identity changes and unknown addresses are rejected`() {
        withSession { session ->
            val schema = source(session, "schema.mantra")["text"].asText()
            listOf(
                schema.replace("draft/example", "other/example"),
                schema.replace("\"1\"", "\"2\""),
                schema.replace("helpers.mantra", "outside.mantra"),
            )
                .forEach {
                    assertEquals(
                        422,
                        session.request(body(session, mapOf("schema.mantra" to it)).toString()).statusCode(),
                    )
                }
            assertEquals(
                400,
                session.request(
                    body(session, mapOf("case.mantra" to source(session, "case.mantra")["text"].asText())).toString(),
                ).statusCode(),
            )
            assertEquals(
                400,
                session.request(
                    body(
                        session,
                        extra = mapOf("documents" to listOf(mapOf("handle" to "0".repeat(64), "text" to schema))),
                    ).toString(),
                ).statusCode(),
            )
            assertEquals(
                404,
                session.request(body(session, extra = mapOf("panel" to "missing")).toString()).statusCode(),
            )
            assertEquals(
                404,
                session.request(
                    body(session, extra = mapOf("explain" to mapOf("node" to "missing"))).toString(),
                ).statusCode(),
            )
            assertFiles(session)
            Files.writeString(session.workspace.resolve("schema.mantra"), "(schema")
            assertEquals(422, session.request(suffix = "template-sources", method = "GET").statusCode())
        }
    }
}
