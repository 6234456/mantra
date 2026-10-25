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
import kotlin.test.assertTrue

/** Exercises real HTTP, exact source patches, the declared schema and unchanged workspace files. */
class TemplateLayoutSettingsServerTest {
    @TempDir lateinit var temporary: Path
    private val json = ObjectMapper()
    private val route = "/api/v1/cases/case.mantra"
    private val originalLayout = """
        (layout settings/paper {:preset :ifrs-schedule
          ;; Keep 📄 comments, explicit zero, false and empty text.
          :title "Original 📄" :precision 0 :grouping false :zero "" :custom "keep"}
          (style-class :reviewed {:weight :bold}))
    """.trimIndent().replace("\n", "\r\n") + "\r\n"
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
        val workspace = Files.createTempDirectory(temporary, "workspace-")
        Files.writeString(
            workspace.resolve("schema.mantra"),
            """
            (schema settings/example {:version "1" :mainline [main]}
              (include "helpers.mantra")
              (input base-amount :decimal)
              (section main "Main" {:panel true}
                (info answer "Answer" (* base-amount 2))))
            """.trimIndent(),
        )
        Files.writeString(workspace.resolve("helpers.mantra"), "(fragment (defn twice [^Decimal value] (* value 2)))")
        Files.writeString(workspace.resolve("layout.mantra"), originalLayout)
        Files.writeString(
            workspace.resolve("case.mantra"),
            "(case sample {:schema \"settings/example\" :layout \"settings/paper\"} (inputs {:base-amount 10}))\r\n",
        )
        val ui = Files.createTempDirectory(temporary, "ui-")
        Files.writeString(ui.resolve("index.html"), "<html><head></head><body>Workbench</body></html>")
        WorkbenchServer(workspace, 0, ui).start().use { server ->
            HttpClient.newHttpClient().use { client ->
                fun get(path: String) = client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val token = Regex("name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"")
                    .find(get("/").body())!!.groupValues[1]
                val snapshot = get("$route/template-sources")
                assertEquals(200, snapshot.statusCode(), snapshot.body())
                val original = Files.list(workspace).use { paths -> paths.toList().associateWith(Files::readAllBytes) }
                test(Session(workspace, server, client, token, json.readTree(snapshot.body()), original))
            }
        }
    }

    private fun Session.request(
        body: String = "",
        suffix: String = "template-layout-settings",
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
        operations: List<Map<String, String>> = emptyList(),
        text: String = source(session, "layout.mantra")["text"].asText(),
        extra: Map<String, Any?> = emptyMap(),
    ): ObjectNode = json.valueToTree(
        linkedMapOf(
            "baseRevision" to session.snapshot["revision"].asText(),
            "baseRevisions" to session.snapshot["data"]["baseRevisions"],
            "draftSequence" to 4,
            "handle" to source(session, "layout.mantra")["handle"].asText(),
            "text" to text,
            "operations" to operations,
        ) + extra,
    )

    private fun validate(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        val errors = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/template-layout-settings.schema.json"),
        ).validate(response.body(), InputFormat.JSON)
        assertTrue(errors.isEmpty(), errors.toString())
        return json.readTree(response.body())
    }

    private fun property(response: JsonNode, name: String): JsonNode =
        response["data"]["properties"].single { it["id"].asText() == name }

    private fun assertFiles(session: Session) {
        assertEquals(session.original.keys, Files.list(session.workspace).use { it.toList().toSet() })
        session.original.forEach { (path, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(path), path.toString())
        }
    }

    @Test fun `catalogue preserves explicit zero false empty text and preset inheritance without writing`() {
        withSession { session ->
            val result = validate(session.request(body(session).toString()))
            assertEquals(session.snapshot["revision"], result["revision"])
            assertEquals(session.snapshot["data"]["baseRevisions"], result["data"]["baseRevisions"])
            assertEquals("layout", result["data"]["scope"].asText())
            assertEquals("ifrs-schedule", result["data"]["preset"].asText())
            assertEquals(14, result["data"]["properties"].size())
            assertTrue(result["data"]["patches"].isEmpty)
            assertEquals(originalLayout, result["data"]["source"]["text"].asText())
            assertEquals("0", property(result, "precision")["explicitValue"]["n"].asText())
            assertTrue(property(result, "precision")["declared"].booleanValue())
            assertFalse(property(result, "grouping")["explicitValue"].booleanValue())
            assertTrue(property(result, "grouping")["inheritedValue"].booleanValue())
            assertEquals("", property(result, "zero")["explicitValue"].asText())
            assertFalse(property(result, "subtitle")["declared"].booleanValue())
            assertTrue(property(result, "subtitle")["explicitValue"].isNull)
            assertFiles(session)
        }
    }

    @Test fun `set operations are exact reversible source patches and leave calculated values unchanged`() {
        withSession { session ->
            val operations = listOf(
                mapOf("op" to "set", "property" to "title", "text" to "Draft \"quoted\" 📄\nsecond line"),
                mapOf("op" to "set", "property" to "precision", "text" to "3"),
                mapOf("op" to "set", "property" to "grouping", "text" to "true"),
            )
            val result = validate(session.request(body(session, operations).toString()))
            var changed = originalLayout
            result["data"]["patches"].forEach { patch ->
                val start = patch["startOffset"].intValue()
                val end = patch["endOffset"].intValue()
                assertEquals(patch["removedText"].asText(), changed.substring(start, end))
                changed = changed.replaceRange(start, end, patch["text"].asText())
            }
            assertEquals(result["data"]["source"]["text"].asText(), changed)
            assertTrue(changed.contains(";; Keep 📄 comments, explicit zero, false and empty text.\r\n"))
            assertTrue(changed.contains(":custom \"keep\""))
            assertTrue(changed.contains("(style-class :reviewed {:weight :bold})"))
            var restored = changed
            result["data"]["patches"].reversed().forEach { patch ->
                val start = patch["startOffset"].intValue()
                restored =
                    restored.replaceRange(start, start + patch["text"].asText().length, patch["removedText"].asText())
            }
            assertEquals(originalLayout, restored)
            assertEquals("3", property(result, "precision")["effectiveValue"]["n"].asText())
            val previewBody = mapOf(
                "baseRevision" to session.snapshot["revision"].asText(),
                "baseRevisions" to session.snapshot["data"]["baseRevisions"],
                "draftSequence" to 5,
                "documents" to listOf(
                    mapOf("handle" to source(session, "layout.mantra")["handle"].asText(), "text" to changed),
                ),
                "inputs" to emptyList<Any>(),
            )
            val preview = session.request(json.writeValueAsString(previewBody), "template-preview")
            assertEquals(200, preview.statusCode(), preview.body())
            assertEquals(
                "20",
                json.readTree(preview.body())["data"]["run"]["values"]["answer"][""]["value"]["n"].asText(),
            )
            assertTrue(json.readTree(preview.body())["data"]["difference"]["changes"].isEmpty)
            assertFiles(session)
        }
    }

    @Test fun `unset restores inheritance and repeated no-op settings produce no patch`() {
        withSession { session ->
            val unchanged = validate(
                session.request(
                    body(session, listOf(mapOf("op" to "set", "property" to "precision", "text" to "0"))).toString(),
                ),
            )
            assertTrue(unchanged["data"]["patches"].isEmpty)
            val reset = validate(
                session.request(
                    body(
                        session,
                        listOf("precision", "grouping", "zero").map {
                            mapOf("op" to "unset", "property" to it)
                        },
                    ).toString(),
                ),
            )
            listOf("precision", "grouping", "zero").forEach { name ->
                val item = property(reset, name)
                assertFalse(item["declared"].booleanValue())
                assertEquals(item["inheritedValue"], item["effectiveValue"])
            }
            assertTrue(property(reset, "grouping")["effectiveValue"].booleanValue())
            assertFiles(session)
        }
    }

    @Test fun `strict request shapes and transport middleware reject unsupported submissions`() {
        withSession { session ->
            val valid = body(session).toString()
            assertEquals(403, session.request(valid, suppliedToken = null).statusCode())
            assertEquals(403, session.request(valid, suppliedToken = "wrong").statusCode())
            assertEquals(405, session.request(method = "GET").statusCode())
            assertEquals(405, session.request(valid, method = "HEAD").statusCode())
            assertEquals(400, session.request(valid, "template-layout-settings?x=1").statusCode())
            val malformed = listOf(
                valid.dropLast(1) + ",\"draftSequence\":5}",
                "$valid {}",
                body(session, extra = mapOf("path" to "layout.mantra")).toString(),
                body(session, extra = mapOf("draftSequence" to -1)).toString(),
                body(session, extra = mapOf("draftSequence" to 1.5)).toString(),
                body(session, extra = mapOf("draftSequence" to 9_007_199_254_740_992L)).toString(),
                body(session, extra = mapOf("handle" to "bad")).toString(),
                body(session, listOf(mapOf("op" to "unknown", "property" to "precision"))).toString(),
                body(session, listOf(mapOf("op" to "unset", "property" to "precision", "text" to "0"))).toString(),
                body(session, listOf(mapOf("op" to "set", "property" to "precision"))).toString(),
                body(
                    session,
                    listOf(mapOf("op" to "set", "property" to "precision", "text" to "2", "offset" to "0")),
                ).toString(),
                body(session, List(2) { mapOf("op" to "set", "property" to "precision", "text" to "2") }).toString(),
                body(session, List(17) { mapOf("op" to "unset", "property" to "p$it") }).toString(),
                body(session, listOf(mapOf("op" to "set", "property" to "unsupported", "text" to "2"))).toString(),
            )
            malformed.forEach { assertEquals(400, session.request(it).statusCode(), it) }
            assertEquals(413, session.request(body(session, text = " ".repeat(65_537)).toString()).statusCode())
            val status = Socket("127.0.0.1", session.server.localPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    (
                        "GET $route/template-layout-settings HTTP/1.1\r\n" +
                            "Host: evil.example:${session.server.localPort}\r\nConnection: close\r\n\r\n"
                        ).toByteArray(),
                )
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
            }
            assertEquals(403, status)
            assertFiles(session)
        }
    }

    @Test fun `invalid values and source identity fail without normalizing manual attributes`() {
        withSession { session ->
            val invalid = listOf(
                "precision" to "19", "precision" to "-1", "precision" to "1.5", "precision" to "1e0",
                "grouping" to "TRUE", "negative" to "other", "language" to "zh", "locale" to "", "locale" to "!!!",
            )
            invalid.forEach { (property, text) ->
                val response = session.request(
                    body(
                        session,
                        listOf(
                            mapOf(
                                "op" to "set",
                                "property" to property,
                                "text" to text,
                            ),
                        ),
                    ).toString(),
                )
                assertEquals(422, response.statusCode(), response.body())
                assertTrue(json.readTree(response.body())["error"]["diagnostics"].size() > 0)
            }
            listOf(
                "(layout",
                originalLayout.replace("settings/paper", "other/paper"),
                originalLayout.replace(":precision 0", ":precision 0 :precision 2"),
            )
                .forEach { assertEquals(422, session.request(body(session, text = it).toString()).statusCode()) }
            val manual = originalLayout.replace(":precision 0", ":precision 99").replace(":zero \"\"", ":zero nil")
            val inspected = validate(session.request(body(session, text = manual).toString()))
            listOf("precision", "zero").forEach {
                assertFalse(property(inspected, it)["editable"].booleanValue())
                assertTrue(property(inspected, it)["canUnset"].booleanValue())
                assertTrue(property(inspected, it)["reason"].isTextual)
            }
            assertEquals(manual, inspected["data"]["source"]["text"].asText())
            assertFiles(session)
        }
    }

    @Test fun `read-only handles incomplete revisions and changed invalid baselines never write`() {
        withSession { session ->
            listOf("schema.mantra", "case.mantra", "helpers.mantra").forEach {
                assertEquals(
                    400,
                    session.request(
                        body(session, extra = mapOf("handle" to source(session, it)["handle"].asText())).toString(),
                    ).statusCode(),
                )
            }
            assertEquals(
                400,
                session.request(body(session, extra = mapOf("handle" to "0".repeat(64))).toString()).statusCode(),
            )
            val incomplete = body(session)
            (incomplete["baseRevisions"] as ObjectNode).remove("helpers.mantra")
            assertEquals(409, session.request(incomplete.toString()).statusCode())
            assertFiles(session)
            Files.writeString(session.workspace.resolve("helpers.mantra"), "(fragment")
            assertEquals(409, session.request(body(session).toString()).statusCode())
        }
        withSession { session ->
            Files.delete(session.workspace.resolve("helpers.mantra"))
            assertEquals(409, session.request(body(session).toString()).statusCode())
        }
    }

    @Test fun `package routes do not expose layout settings or mutate package cases`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        val workspace = Files.createDirectory(temporary.resolve("packages-workspace"))
        val ui = Files.createDirectory(temporary.resolve("packages-ui"))
        Files.writeString(ui.resolve("index.html"), "<html><head></head><body>Workbench</body></html>")
        WorkbenchServer(workspace, 0, ui, packageWorkspace = fixture.catalog()).start().use { server ->
            HttpClient.newHttpClient().use { client ->
                val index = client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val token = Regex(
                    "name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"",
                ).find(index.body())!!.groupValues[1]
                val path = "/api/v1/package-cases/old%2Fcases%2Fdemo.mantra/template-layout-settings"
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path"))
                    .header("X-Mantra-Token", token).POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(404, response.statusCode(), response.body())
            }
        }
        assertEquals(0, fixture.store.writes)
        assertEquals(fixture.original, fixture.store.values.getValue("root"))
    }
}
