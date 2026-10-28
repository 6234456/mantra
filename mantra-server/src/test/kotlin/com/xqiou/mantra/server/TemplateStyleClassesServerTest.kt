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

/** Real HTTP boundary, schema validation, reversible source edits and exact engine preview. */
class TemplateStyleClassesServerTest {
    @TempDir lateinit var temporary: Path
    private val json = ObjectMapper()
    private val route = "/api/v1/cases/case.mantra"
    private val originalLayout = """
        (layout styles/paper {:preset :ifrs-schedule :style-preset [:utilities :working-paper]
          :title "Original 📄" :custom "keep"}
          ;; Keep this source comment and CRLF.
          (style-class :reviewed {:weight :bold ;; Preserve the declaration comment 📄.
                                  :tone :muted :fill :none})
          (style-class :result {:tone :muted})
          (style {:class :reviewed} {:use :reviewed})
          (style {:class :result} {:use :result})
          (style {:class FutureTag} {:fill :subtle})
          (style {:class :future/tag} {:fill :subtle}))
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
            (schema styles/example {:version "1" :mainline [main]}
              (include "helpers.mantra")
              (input base-amount :decimal)
              (section main "Main" {:panel true}
                (info answer "Answer" (* base-amount 2) {:class :reviewed})))
            """.trimIndent(),
        )
        Files.writeString(workspace.resolve("helpers.mantra"), "(fragment (defn twice [^Decimal value] (* value 2)))")
        Files.writeString(workspace.resolve("layout.mantra"), originalLayout)
        Files.writeString(
            workspace.resolve("case.mantra"),
            "(case sample {:schema \"styles/example\" :layout \"styles/paper\"} (inputs {:base-amount 10}))\r\n",
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
        suffix: String = "template-style-classes",
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
        operations: List<Map<String, Any?>> = emptyList(),
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

    private fun upsert(
        name: String,
        weight: String? = null,
        tone: String? = null,
        fill: String? = null,
    ): Map<String, Any?> = mapOf(
        "op" to "upsert",
        "name" to name,
        "style" to mapOf("weight" to weight, "tone" to tone, "fill" to fill),
    )

    private fun validate(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        val errors = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/template-style-classes.schema.json"),
        ).validate(response.body(), InputFormat.JSON)
        assertTrue(errors.isEmpty(), errors.toString())
        return json.readTree(response.body())
    }

    private fun styleClass(response: JsonNode, name: String): JsonNode =
        response["data"]["classes"].single { it["name"].asText() == name }

    private fun assertFiles(session: Session) {
        assertEquals(session.original.keys, Files.list(session.workspace).use { it.toList().toSet() })
        session.original.forEach { (path, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(path), path.toString())
        }
    }

    private fun assertPatches(response: JsonNode, before: String): String {
        var changed = before
        response["data"]["patches"].forEach { patch ->
            val start = patch["startOffset"].intValue()
            val end = patch["endOffset"].intValue()
            assertEquals(patch["removedText"].asText(), changed.substring(start, end))
            changed = changed.replaceRange(start, end, patch["text"].asText())
        }
        assertEquals(response["data"]["source"]["text"].asText(), changed)
        var restored = changed
        response["data"]["patches"].reversed().forEach { patch ->
            val start = patch["startOffset"].intValue()
            restored =
                restored.replaceRange(start, start + patch["text"].asText().length, patch["removedText"].asText())
        }
        assertEquals(before, restored)
        return changed
    }

    @Test fun `catalog validates engine presets local contributions and layout-scoped symbol references`() {
        withSession { session ->
            val result = validate(session.request(body(session).toString()))
            assertEquals(session.snapshot["revision"], result["revision"])
            assertEquals(session.snapshot["data"]["baseRevisions"], result["data"]["baseRevisions"])
            assertEquals("submitted-layout", result["data"]["usageScope"].asText())
            assertEquals(listOf("utilities", "working-paper"), result["data"]["stylePresets"].map(JsonNode::asText))
            assertEquals(originalLayout, result["data"]["source"]["text"].asText())
            assertTrue(result["data"]["patches"].isEmpty)
            val strong = styleClass(result, "strong")
            assertTrue(strong["local"].isNull)
            assertEquals("bold", strong["inherited"]["weight"].asText())
            assertTrue(strong["inherited"]["tone"].isNull)
            val variance = styleClass(result, "variance")
            assertEquals("bold", variance["inherited"]["weight"].asText())
            assertEquals("accent", variance["inherited"]["tone"].asText())
            assertEquals("subtle", variance["inherited"]["fill"].asText())
            val overridden = styleClass(result, "result")
            assertEquals("accent", overridden["inherited"]["tone"].asText())
            assertEquals("muted", overridden["local"]["tone"].asText())
            assertEquals("muted", overridden["effective"]["tone"].asText())
            assertEquals("accent", overridden["effective"]["fill"].asText())
            listOf("FutureTag", "future/tag").forEach { name ->
                val item = styleClass(result, name)
                assertFalse(item["editable"].booleanValue())
                assertTrue(item["reason"].asText().isNotBlank())
                val reference = item["references"].single()
                assertEquals("selector", reference["kind"].asText())
                val token = originalLayout.substring(
                    reference["startOffset"].intValue(),
                    reference["endOffset"].intValue(),
                )
                assertEquals(if (name == "FutureTag") name else ":$name", token)
            }
            assertFiles(session)
        }
    }

    @Test fun `upserts preserve exact source and explicit defaults while real preview keeps the exact calculation`() {
        withSession { session ->
            val result = validate(
                session.request(
                    body(
                        session,
                        listOf(upsert("reviewed", "normal", "default", "none"), upsert("empty-definition")),
                    ).toString(),
                ),
            )
            val changed = assertPatches(result, originalLayout)
            assertTrue(changed.contains(";; Preserve the declaration comment 📄.\r\n"))
            assertTrue(changed.contains(";; Keep this source comment and CRLF.\r\n"))
            assertTrue(changed.contains(":custom \"keep\""))
            assertEquals(
                json.readTree("""{"weight":"normal","tone":"default","fill":"none"}"""),
                styleClass(result, "reviewed")["local"],
            )
            assertEquals(
                json.readTree("""{"weight":null,"tone":null,"fill":null}"""),
                styleClass(result, "empty-definition")["local"],
            )
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
            val data = json.readTree(preview.body())["data"]
            assertEquals("20", data["run"]["values"]["answer"][""]["value"]["n"].asText())
            val row = data["paper"]["tables"].flatMap { it["rows"].toList() }.single { it["node"].asText() == "answer" }
            row["cells"].forEach { cell ->
                assertEquals("normal", cell["style"]["weight"].asText())
                assertEquals("default", cell["style"]["tone"].asText())
                assertEquals("none", cell["style"]["fill"].asText())
            }
            assertFiles(session)
        }
    }

    @Test fun `removal retains preset fallback but blocks an unresolved use dependency`() {
        withSession { session ->
            val catalog = validate(session.request(body(session).toString()))
            assertFalse(styleClass(catalog, "reviewed")["canRemove"].booleanValue())
            assertTrue(styleClass(catalog, "reviewed")["removeReason"].asText().isNotBlank())
            val blocked = session.request(
                body(session, listOf(mapOf("op" to "remove", "name" to "reviewed"))).toString(),
            )
            assertEquals(422, blocked.statusCode(), blocked.body())
            assertTrue(
                json.readTree(blocked.body())["error"]["diagnostics"].any {
                    it["code"].asText() ==
                        "MANTRA-TEMPLATE-STYLE"
                },
            )
            assertTrue(styleClass(catalog, "result")["canRemove"].booleanValue())
            val reset =
                validate(session.request(body(session, listOf(mapOf("op" to "remove", "name" to "result"))).toString()))
            val changed = assertPatches(reset, originalLayout)
            assertTrue(changed.contains("(style {:class :result} {:use :result})"))
            val restored = styleClass(reset, "result")
            assertTrue(restored["local"].isNull)
            assertEquals(restored["inherited"], restored["effective"])
            assertFalse(restored["canRemove"].booleanValue())
            assertFiles(session)
        }
    }

    @Test fun `omitting local properties preserves empty definitions and unchanged operations return no patches`() {
        withSession { session ->
            val unchanged = validate(
                session.request(body(session, listOf(upsert("reviewed", "bold", "muted", "none"))).toString()),
            )
            assertTrue(unchanged["data"]["patches"].isEmpty)
            val cleared = validate(session.request(body(session, listOf(upsert("reviewed"))).toString()))
            val changed = assertPatches(cleared, originalLayout)
            assertTrue(changed.contains(";; Preserve the declaration comment 📄.\r\n"))
            val local = styleClass(cleared, "reviewed")["local"]
            assertFalse(local.isNull)
            listOf("weight", "tone", "fill").forEach { assertTrue(local[it].isNull) }
            assertFalse(styleClass(cleared, "reviewed")["canRemove"].booleanValue())
            assertFiles(session)
        }
    }

    @Test fun `strict JSON finite declaration types method host and token checks reject unsupported requests`() {
        withSession { session ->
            val valid = body(session).toString()
            assertEquals(403, session.request(valid, suppliedToken = null).statusCode())
            assertEquals(403, session.request(valid, suppliedToken = "wrong").statusCode())
            assertEquals(405, session.request(method = "GET").statusCode())
            assertEquals(405, session.request(valid, method = "HEAD").statusCode())
            assertEquals(400, session.request(valid, "template-style-classes?x=1").statusCode())
            val malformed = listOf(
                valid.dropLast(1) + ",\"draftSequence\":5}",
                "$valid {}",
                body(session, extra = mapOf("path" to "layout.mantra")).toString(),
                body(session, extra = mapOf("baseRevisions" to emptyMap<String, String>())).toString(),
                body(session, extra = mapOf("draftSequence" to -1)).toString(),
                body(session, extra = mapOf("draftSequence" to 1.5)).toString(),
                body(session, extra = mapOf("draftSequence" to 9_007_199_254_740_992L)).toString(),
                body(session, extra = mapOf("handle" to "bad")).toString(),
                body(session, listOf(mapOf("op" to "unknown", "name" to "reviewed"))).toString(),
                body(session, listOf(mapOf("op" to "upsert", "name" to "reviewed"))).toString(),
                body(session, listOf(mapOf("op" to "remove", "name" to "reviewed", "style" to null))).toString(),
                body(session, listOf(mapOf("op" to "upsert", "name" to "reviewed", "style" to null))).toString(),
                body(
                    session,
                    listOf(mapOf("op" to "upsert", "name" to "reviewed", "style" to mapOf("weight" to "bold"))),
                ).toString(),
                body(session, listOf(upsert("reviewed", "BOLD"))).toString(),
                body(session, listOf(upsert("reviewed") + mapOf("offset" to 0))).toString(),
                body(session, listOf(upsert("FutureTag"))).toString(),
                body(session, listOf(upsert("future/tag"))).toString(),
                body(session, listOf(upsert(":reviewed"))).toString(),
                body(session, List(2) { upsert("reviewed") }).toString(),
                body(session, List(17) { upsert("class-$it") }).toString(),
                body(session, listOf(upsert("reviewed"))).toString().replace("\"weight\":null", "\"weight\":false"),
                body(
                    session,
                    listOf(upsert("reviewed")),
                ).toString().replace("\"tone\":null", "\"tone\":null,\"tone\":\"accent\""),
            )
            malformed.forEach { assertEquals(400, session.request(it).statusCode(), it) }
            assertEquals(413, session.request(body(session, text = " ".repeat(65_537)).toString()).statusCode())
            val status = Socket("127.0.0.1", session.server.localPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    (
                        "GET $route/template-style-classes HTTP/1.1\r\n" +
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

    @Test fun `identity syntax declaration ambiguity and edited source budgets fail without file writes`() {
        withSession { session ->
            listOf(
                "(layout",
                originalLayout.replace("styles/paper", "other/paper"),
                originalLayout.replace("(style-class :result", "(style-class :reviewed"),
                originalLayout.replace(":fill :none", ":fill :none :fill :accent"),
            ).forEach { text ->
                assertEquals(422, session.request(body(session, text = text).toString()).statusCode(), text)
            }
            assertEquals(
                413,
                session.request(body(session, listOf(upsert("x".repeat(65_536)))).toString()).statusCode(),
            )
            assertFiles(session)
        }
    }

    @Test fun `all captured source revisions and root handles are bound even when an external source breaks`() {
        withSession { session ->
            listOf("schema.mantra", "case.mantra", "helpers.mantra").forEach { name ->
                assertEquals(
                    400,
                    session.request(
                        body(session, extra = mapOf("handle" to source(session, name)["handle"].asText())).toString(),
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

    @Test fun `package hosts provide no template style classes route and preserve stored bytes`() {
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
                val token = Regex("name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"")
                    .find(index.body())!!.groupValues[1]
                val path = "/api/v1/package-cases/old%2Fcases%2Fdemo.mantra/template-style-classes"
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
