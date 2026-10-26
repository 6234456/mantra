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

/** Real HTTP owner discovery, exact patches and same-engine Paper evidence, without source writes. */
class TemplateSchemaPropertiesServerTest {
    @TempDir lateinit var temporary: Path
    private val json = ObjectMapper()
    private val route = "/api/v1/cases/case.mantra"
    private val originalSchema = """
        (schema properties/example {:version "1" :mainline [main]}
          (include "helpers.mantra")
          (input base-amount :decimal)
          (section main "Main" {:panel true :custom "keep"}
            ;; Preserve this comment 📄 and CRLF.
            (info answer "Answer" (* base-amount 2)
              {:class :reviewed ;; Preserve the class comment.
               :note "keep"})
            (note "Source note 📄" {:class :reviewed})
            (info zero-row "Zero" 0)
            (info inactive-row "Inactive" 5 {:when false})))
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
        Files.writeString(workspace.resolve("schema.mantra"), originalSchema)
        Files.writeString(
            workspace.resolve("helpers.mantra"),
            "(fragment (defn twice [^Decimal value] (* value 2)) (info shared-row \"Shared\" 1))",
        )
        Files.writeString(
            workspace.resolve("layout.mantra"),
            """
            (layout properties/paper {:locale "de-DE" :precision 2 :hide-zero true}
              (style-class :reviewed {:weight :bold :tone :muted :fill :none})
              (style-class :final {:weight :normal :tone :accent :fill :subtle})
              (style {:class :reviewed} {:use :reviewed})
              (style {:class :final} {:use :final})
              (table main :label :value))
            """.trimIndent(),
        )
        Files.writeString(
            workspace.resolve("case.mantra"),
            "(case sample {:schema \"properties/example\" :layout \"properties/paper\"} (inputs {:base-amount 10}))",
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
                val response = get("$route/template-sources")
                assertEquals(200, response.statusCode(), response.body())
                val original = Files.list(workspace).use { paths -> paths.toList().associateWith(Files::readAllBytes) }
                test(Session(workspace, server, client, token, json.readTree(response.body()), original))
            }
        }
    }

    private fun Session.requestBytes(
        body: ByteArray,
        suffix: String = "template-schema-properties",
        method: String = "POST",
        suppliedToken: String? = token,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$route/$suffix"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
        suppliedToken?.let { request.header("X-Mantra-Token", it) }
        request.method(
            method,
            if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body),
        )
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun Session.request(
        body: String = "",
        suffix: String = "template-schema-properties",
        method: String = "POST",
        suppliedToken: String? = token,
    ): HttpResponse<String> = requestBytes(body.toByteArray(Charsets.UTF_8), suffix, method, suppliedToken)

    private fun source(session: Session, name: String = "schema.mantra"): JsonNode =
        session.snapshot["data"]["documents"].single { it["document"].asText() == name }

    private fun body(
        session: Session,
        operations: List<Map<String, Any?>> = emptyList(),
        text: String = originalSchema,
        extra: Map<String, Any?> = emptyMap(),
    ): ObjectNode = json.valueToTree(
        linkedMapOf(
            "baseRevision" to session.snapshot["revision"].asText(),
            "baseRevisions" to session.snapshot["data"]["baseRevisions"],
            "draftSequence" to 7,
            "handle" to source(session)["handle"].asText(),
            "text" to text,
            "operations" to operations,
        ) + extra,
    )

    private fun validate(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        val errors = registry.getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/template-schema-properties.schema.json"),
        ).validate(response.body(), InputFormat.JSON)
        assertTrue(errors.isEmpty(), errors.toString())
        return json.readTree(response.body())
    }

    private fun owner(
        response: JsonNode,
        node: String?,
        property: String,
        document: String = "schema.mantra",
    ): JsonNode = response["data"]["owners"].single {
        (if (it["node"].isNull) null else it["node"].asText()) == node &&
            it["property"].asText() == property && it["document"].asText() == document
    }

    private fun edit(owner: JsonNode, op: String, value: Any? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("op" to op, "owner" to owner["handle"].asText()).apply {
            when (op) {
                "setText" -> put("text", value)
                "setClasses" -> put("classes", value)
            }
        }

    private fun assertFiles(session: Session) {
        assertEquals(session.original.keys, Files.list(session.workspace).use { it.toList().toSet() })
        session.original.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path), "$path") }
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
            restored = restored.replaceRange(
                start,
                start + patch["text"].asText().length,
                patch["removedText"].asText(),
            )
        }
        assertEquals(before, restored)
        return changed
    }

    @Test fun `inspect lists hidden and included declarations with exact root source ownership`() {
        withSession { session ->
            val result = validate(session.request(body(session).toString()))
            assertEquals("schema-properties", result["data"]["scope"].asText())
            assertEquals("presentation-items", result["data"]["coverage"].asText())
            assertEquals("properties/example", result["data"]["schemaId"].asText())
            assertEquals(session.snapshot["revision"], result["revision"])
            assertEquals(session.snapshot["data"]["baseRevisions"], result["data"]["baseRevisions"])
            assertTrue(result["data"]["patches"].isEmpty)
            val label = owner(result, "answer", "label")
            val range = label["range"]
            assertEquals(
                "\"Answer\"",
                originalSchema.substring(range["startOffset"].intValue(), range["endOffset"].intValue()),
            )
            assertEquals("Answer", label["value"].asText())
            assertTrue(label["editable"].asBoolean())
            assertFalse(label["canUnset"].asBoolean())
            assertTrue(owner(result, "zero-row", "label")["editable"].asBoolean())
            assertTrue(owner(result, "inactive-row", "label")["editable"].asBoolean())
            val shared = owner(result, "shared-row", "label", "helpers.mantra")
            assertFalse(shared["editable"].asBoolean())
            assertTrue(shared["reason"].asText().isNotBlank())
            assertTrue(owner(result, null, "text")["value"].asText().contains("📄"))
            val repeated = validate(session.request(body(session).toString()))
            assertEquals(label["handle"], owner(repeated, "answer", "label")["handle"])
            assertFiles(session)
        }
    }

    @Test fun `batch label note and classes produce reversible patches and real unchanged values`() {
        withSession { session ->
            val inspected = validate(session.request(body(session).toString()))
            val label = "Result \"quoted\" 中文 📄\nsecond line"
            val operations = listOf(
                edit(owner(inspected, "answer", "label"), "setText", label),
                edit(owner(inspected, "answer", "classes"), "setClasses", listOf("final", "future-tag")),
                edit(owner(inspected, null, "text"), "setText", "Updated note 中文"),
            )
            val result = validate(session.request(body(session, operations).toString()))
            val changed = assertPatches(result, originalSchema)
            assertTrue(changed.contains(";; Preserve this comment 📄 and CRLF.\r\n"))
            assertTrue(changed.contains(";; Preserve the class comment.\r\n"))
            assertTrue(changed.contains(":custom \"keep\""))
            assertTrue(changed.contains("(* base-amount 2)"))
            assertEquals(label, owner(result, "answer", "label")["value"].asText())
            assertEquals(
                listOf("final", "future-tag"),
                owner(result, "answer", "classes")["value"].map { it["kw"].asText() },
            )
            assertFalse(owner(inspected, "answer", "label")["handle"] == owner(result, "answer", "label")["handle"])
            val previewBody = mapOf(
                "baseRevision" to session.snapshot["revision"].asText(),
                "baseRevisions" to session.snapshot["data"]["baseRevisions"],
                "draftSequence" to 8,
                "documents" to listOf(mapOf("handle" to source(session)["handle"].asText(), "text" to changed)),
                "inputs" to emptyList<Any>(),
            )
            val preview = session.request(json.writeValueAsString(previewBody), "template-preview")
            assertEquals(200, preview.statusCode(), preview.body())
            val data = json.readTree(preview.body())["data"]
            assertEquals("20", data["run"]["values"]["answer"][""]["value"]["n"].asText())
            val row = data["paper"]["tables"].flatMap { it["rows"].toList() }.single { it["node"].asText() == "answer" }
            assertTrue(row["cells"].any { it["text"].asText() == label })
            row["cells"].forEach { cell ->
                assertEquals("normal", cell["style"]["weight"].asText())
                assertEquals("accent", cell["style"]["tone"].asText())
                assertEquals("subtle", cell["style"]["fill"].asText())
            }
            assertEquals(400, session.request(body(session, operations.take(1), changed).toString()).statusCode())
            assertFiles(session)
        }
    }

    @Test fun `explicit empty classes unset and same text keep declared state distinct`() {
        withSession { session ->
            val inspected = validate(session.request(body(session).toString()))
            val absent = owner(inspected, "zero-row", "classes")
            assertFalse(absent["declared"].asBoolean())
            assertTrue(absent["value"].isNull)
            val explicit = validate(
                session.request(body(session, listOf(edit(absent, "setClasses", emptyList<String>()))).toString()),
            )
            val changed = assertPatches(explicit, originalSchema)
            val now = owner(explicit, "zero-row", "classes")
            assertTrue(now["declared"].asBoolean())
            assertTrue(now["value"].isEmpty)
            val removed = validate(session.request(body(session, listOf(edit(now, "unset")), changed).toString()))
            assertPatches(removed, changed)
            assertFalse(owner(removed, "zero-row", "classes")["declared"].asBoolean())
            val sameLabel = edit(owner(inspected, "answer", "label"), "setText", "Answer")
            val unchanged = validate(session.request(body(session, listOf(sameLabel)).toString()))
            assertTrue(unchanged["data"]["patches"].isEmpty)
            assertEquals(originalSchema, unchanged["data"]["source"]["text"].asText())
            assertFiles(session)
        }
    }

    @Test fun `strict request shapes UTF8 and transport controls reject invalid operations`() {
        withSession { session ->
            val inspected = validate(session.request(body(session).toString()))
            val label = owner(inspected, "answer", "label")
            val classes = owner(inspected, "answer", "classes")
            val valid = body(session).toString()
            assertEquals(403, session.request(valid, suppliedToken = null).statusCode())
            assertEquals(403, session.request(valid, suppliedToken = "wrong").statusCode())
            assertEquals(405, session.request(method = "GET").statusCode())
            assertEquals(405, session.request(method = "HEAD").statusCode())
            assertEquals(400, session.request(valid, "template-schema-properties?unexpected=1").statusCode())
            val malformed = listOf(
                valid.dropLast(1) + ",\"draftSequence\":9}",
                valid.dropLast(1) + ",\"draftSequen\\u0063e\":9}",
                "$valid {}",
                body(session, extra = mapOf("path" to "schema.mantra")).toString(),
                body(session, extra = mapOf("draftSequence" to -1)).toString(),
                body(session, extra = mapOf("draftSequence" to 0.5)).toString(),
                body(session, extra = mapOf("draftSequence" to 9_007_199_254_740_992L)).toString(),
                body(session, extra = mapOf("baseRevisions" to emptyMap<String, String>())).toString(),
                body(session, extra = mapOf("handle" to "invalid")).toString(),
                body(session, listOf(edit(label, "setText") + mapOf("offset" to 0))).toString(),
                body(session, listOf(edit(label, "setText", false))).toString(),
                body(session, listOf(edit(label, "setText", "a"), edit(label, "setText", "b"))).toString(),
                body(session, listOf(mapOf("op" to "setText", "owner" to label["handle"].asText()))).toString(),
                body(session, listOf(edit(classes, "setClasses", listOf("reviewed", "reviewed")))).toString(),
                body(session, listOf(edit(classes, "setClasses", listOf(":reviewed")))).toString(),
                body(session, listOf(edit(classes, "setClasses", listOf("FutureTag")))).toString(),
                body(session, listOf(edit(classes, "setClasses", List(65) { "class-$it" }))).toString(),
                body(session, List(33) { mapOf("op" to "unset", "owner" to "%064x".format(it + 1)) }).toString(),
                body(session, listOf(edit(classes, "unset") + mapOf("classes" to null))).toString(),
                body(session, listOf(mapOf("op" to "remove", "owner" to classes["handle"].asText()))).toString(),
            )
            malformed.forEach { assertEquals(400, session.request(it).statusCode(), it) }
            val invalidUtf8 = valid.replace("properties/example", "properties/☀example").toByteArray(Charsets.UTF_8)
            val index = invalidUtf8.indexOf(0xe2.toByte())
            assertTrue(index >= 0)
            invalidUtf8[index + 1] = 0xff.toByte()
            assertEquals(400, session.requestBytes(invalidUtf8).statusCode())
            assertEquals(413, session.request(body(session, text = " ".repeat(65_537)).toString()).statusCode())
            val oversized = body(session, listOf(edit(label, "setText", "x".repeat(65_537))))
            assertEquals(413, session.request(oversized.toString()).statusCode())
            val bodyLimit = Socket("127.0.0.1", session.server.localPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    (
                        "POST $route/template-schema-properties HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:${session.server.localPort}\r\nX-Mantra-Token: ${session.token}\r\n" +
                            "Content-Length: 1048577\r\nConnection: close\r\n\r\n"
                        ).toByteArray(Charsets.UTF_8),
                )
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
            }
            assertEquals(413, bodyLimit)
            assertFiles(session)
        }
    }

    @Test fun `owner permissions source identity and syntax cannot bypass authoritative validation`() {
        withSession { session ->
            val inspected = validate(session.request(body(session).toString()))
            listOf("case.mantra", "layout.mantra", "helpers.mantra").forEach { name ->
                val wrongHandle = body(session, extra = mapOf("handle" to source(session, name)["handle"].asText()))
                assertEquals(400, session.request(wrongHandle.toString()).statusCode())
            }
            val shared = owner(inspected, "shared-row", "label", "helpers.mantra")
            val label = owner(inspected, "answer", "label")
            val classes = owner(inspected, "answer", "classes")
            val rejected = listOf(
                edit(shared, "setText", "readonly"),
                edit(label, "unset"),
                edit(label, "setClasses", listOf("reviewed")),
                edit(classes, "setText", "wrong type"),
                mapOf("op" to "unset", "owner" to "0".repeat(64)),
            )
            rejected.forEach { assertEquals(400, session.request(body(session, listOf(it)).toString()).statusCode()) }
            listOf(
                "(schema",
                originalSchema.replace("properties/example", "other/example"),
                originalSchema.replace("helpers.mantra", "other.mantra"),
                originalSchema.replace(":class :reviewed", ":class :reviewed :class :final"),
            ).forEach { text ->
                val response = session.request(body(session, text = text).toString())
                assertEquals(422, response.statusCode(), response.body())
            }
            assertFiles(session)
        }
    }

    @Test fun `binding-invalid drafts remain editable but still fail a real candidate preview`() {
        withSession { session ->
            val broken = originalSchema.replace("(* base-amount 2)", "(* missing-input 2)")
            val inspected = validate(session.request(body(session, text = broken).toString()))
            val operation = edit(owner(inspected, "answer", "label"), "setText", "Draft")
            val changed = validate(session.request(body(session, listOf(operation), broken).toString()))
            val text = assertPatches(changed, broken)
            val request = mapOf(
                "baseRevision" to session.snapshot["revision"].asText(),
                "baseRevisions" to session.snapshot["data"]["baseRevisions"],
                "draftSequence" to 8,
                "documents" to listOf(mapOf("handle" to source(session)["handle"].asText(), "text" to text)),
                "inputs" to emptyList<Any>(),
            )
            assertEquals(422, session.request(json.writeValueAsString(request), "template-preview").statusCode())
            assertFiles(session)
        }
    }

    @Test fun `full source revisions detect external changes including deleted or broken includes`() {
        withSession { session ->
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

    @Test fun `JSON escaped unpaired surrogates reject source and text without rejecting valid emoji`() {
        withSession { session ->
            val inspected = validate(session.request(body(session).toString()))
            val label = owner(inspected, "answer", "label")
            for (escaped in listOf("\\uD800", "\\uDC00")) {
                val sourcePayload = body(session).toString()
                    .replace("Preserve this comment", "$escaped Preserve this comment")
                assertTrue(sourcePayload.contains(escaped))
                assertEquals(sourcePayload, sourcePayload.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
                val sourceRejected = session.request(sourcePayload)
                assertEquals(422, sourceRejected.statusCode(), sourceRejected.body())
                val operation = edit(label, "setText", "Replacement marker")
                val textPayload = body(session, listOf(operation)).toString()
                    .replace("Replacement marker", "Before $escaped after")
                assertTrue(textPayload.contains(escaped))
                assertEquals(textPayload, textPayload.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
                val textRejected = session.request(textPayload)
                assertEquals(422, textRejected.statusCode(), textRejected.body())
            }
            val validOperation = edit(label, "setText", "Replacement marker")
            val validPayload = body(session, listOf(validOperation)).toString()
                .replace("Replacement marker", "Emoji: \\uD83D\\uDCC4")
            val accepted = validate(session.request(validPayload))
            assertEquals("Emoji: 📄", owner(accepted, "answer", "label")["value"].asText())
            assertFiles(session)
        }
    }

    @Test fun `package hosts provide no schema property editing route or storage writes`() {
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
                val path = "/api/v1/package-cases/old%2Fcases%2Fdemo.mantra/template-schema-properties"
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
