package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.workbench.ExportBudget
import com.xqiou.mantra.workbench.packages.PackageWorkspaceCatalog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Exercises actual loopback HTTP, middleware, captured package graph and migration writer. */
class PackageWorkbenchServerTest {
    @TempDir lateinit var temporary: Path
    private val json = ObjectMapper()
    private val rootKey = "old/cases/demo.mantra"
    private val targetKey = "new/cases/demo.mantra"
    private fun route(action: String, key: String = rootKey): String =
        "/api/v1/package-cases/${key.replace("/", "%2F")}/$action"

    private data class Response(val status: Int, val headers: String, val bytes: ByteArray) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    private fun request(
        server: WorkbenchServer,
        path: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = byteArrayOf(),
        host: String = "127.0.0.1:${server.localPort}",
    ): Response = Socket("127.0.0.1", server.localPort).use { socket ->
        socket.soTimeout = 15_000
        val message = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (headers.keys.none { it.equals("Content-Length", true) || it.equals("Transfer-Encoding", true) }) {
                append("Content-Length: ${body.size}\r\n")
            }
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(message.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
        socket.shutdownOutput()
        val bytes = socket.getInputStream().readAllBytes()
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val boundary = raw.indexOf("\r\n\r\n")
        check(boundary >= 0) { "Response did not contain a complete HTTP header" }
        val header = raw.substring(0, boundary)
        Response(
            header.lineSequence().first().split(' ')[1].toInt(),
            header,
            bytes.copyOfRange(boundary + 4, bytes.size),
        )
    }

    private fun server(host: PackageWorkspaceCatalog, budget: ExportBudget = ExportBudget()): WorkbenchServer {
        val workspace = temporary.resolve("http-workspace")
        val ui = temporary.resolve("http-ui")
        Files.createDirectories(workspace)
        Files.createDirectories(ui)
        Files.writeString(ui.resolve("index.html"), "<!doctype html><html><head></head><body>Test</body></html>")
        return WorkbenchServer(workspace, 0, ui, budget, host).start()
    }

    private fun session(server: WorkbenchServer): String {
        val index = request(server, "/")
        assertEquals(200, index.status)
        assertContains(index.text, "mantra-package-workspace")
        return Regex("name=\"mantra-session-token\" content=\"([0-9a-f]{64})\"")
            .find(index.text)?.groupValues?.get(1) ?: error("Server did not emit its real session token")
    }

    private fun post(
        server: WorkbenchServer,
        action: String,
        value: Any,
        token: String,
        key: String = rootKey,
    ): Response = request(
        server,
        route(action, key),
        "POST",
        mapOf(
            "X-Mantra-Token" to token,
            "Content-Type" to "application/json",
        ),
        json.writeValueAsBytes(value),
    )

    private fun tree(response: Response): JsonNode {
        assertEquals(200, response.status, response.text)
        validate(response.text)
        return json.readTree(response.text)
    }
    private fun run(server: WorkbenchServer, key: String = rootKey) = tree(request(server, route("run", key)))
    private fun revision(server: WorkbenchServer) = run(server).path("revision").asText()
    private fun answer(run: JsonNode) = run.path("data").path("document").path("data")
        .path("values").path("answer").path("").path("value")
    private fun assertAnswer(expected: String, run: JsonNode) {
        val numeric = answer(run).path("n")
        assertTrue(numeric.isTextual, "Expected a serialized decimal value: ${answer(run)}")
        assertEquals(0, expected.toBigDecimal().compareTo(numeric.asText().toBigDecimal()))
    }
    private fun preview(server: WorkbenchServer, token: String): JsonNode = tree(
        post(
            server,
            "migration-preview",
            mapOf("baseRevision" to revision(server), "targetCase" to targetKey),
            token,
        ),
    )

    private fun validate(body: String) {
        val files = Files.list(Path.of("docs/workbench/schema")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }
        val schemas = files.associate { file ->
            "https://mantra.local/workbench/schema/${file.fileName}" to Files.readString(file)
        }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/packages.schema.json"))
        val errors = schema.validate(body, InputFormat.JSON)
        assertTrue(errors.isEmpty(), "Package contract rejected actual response: $errors")
    }

    @Test fun `package routes inherit loopback token method and actual byte body limits`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog()).use { server ->
            val token = session(server)
            assertEquals(200, request(server, "/api/v1/packages").status)
            assertEquals(403, request(server, "/api/v1/packages", host = "evil.example:${server.localPort}").status)
            val body = json.writeValueAsBytes(mapOf("baseRevision" to revision(server), "targetCase" to targetKey))
            assertEquals(403, request(server, route("migration-preview"), "POST", body = body).status)
            assertEquals(
                403,
                request(
                    server,
                    route("migration-preview"),
                    "POST",
                    headers = mapOf("X-Mantra-Token" to "wrong"),
                    body = body,
                ).status,
            )
            assertEquals(405, request(server, "/api/v1/packages", "DELETE").status)
            val tooLarge = request(
                server,
                route("migration-preview"),
                "POST",
                mapOf("X-Mantra-Token" to token, "Content-Length" to "1048577"),
            )
            assertEquals(413, tooLarge.status)
            // Exercise the actual stream cap without relying on a declared Content-Length.
            val data = "x".repeat(1_048_577)
            val chunked = "${data.length.toString(16)}\r\n$data\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)
            val streamed = request(
                server,
                route("migration-preview"),
                "POST",
                mapOf("X-Mantra-Token" to token, "Transfer-Encoding" to "chunked"),
                chunked,
            )
            assertEquals(413, streamed.status)
            assertEquals(0, fixture.store.writes)
        }
    }

    @Test fun `strict wrapper preserves actual dated parameter source binding and wire four`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog()).use { server ->
            val index = tree(request(server, "/api/v1/packages"))
            assertEquals("mantra.packages/1", index.path("contract").asText())
            assertEquals(2, index.path("data").path("packages").size())
            assertTrue(index.path("data").path("packages").all { it.path("readOnly").asBoolean() })
            val current = run(server)
            assertAnswer("10", current)
            assertEquals("mantra.workbench/4", current.path("data").path("document").path("contract").asText())
            assertEquals(current.path("revision"), current.path("data").path("document").path("revision"))
            val parameters = tree(request(server, route("parameters")))
            val source = parameters.path("data").path("parameterSources").single()
            assertEquals("2026-06-30", source.path("effectiveDate").asText())
            assertEquals("2027-01-01", source.path("validUntil").asText())
            assertTrue(source.path("endExclusive").asBoolean())
            assertTrue(source.path("validForDate").asBoolean())
            assertEquals("host/http", source.path("schema").asText())
            assertEquals(64, source.path("sha256").asText().length)
            val binding = parameters.path("data").path("binding")
            assertTrue(binding.path("editableCase").asBoolean())
            assertTrue(binding.path("resourcesReadOnly").asBoolean())
            for (name in listOf("structure", "paper", "diagnostics", "sources", "export-preview")) {
                tree(request(server, route(name)))
            }
            tree(request(server, route("explain") + "?address=answer"))
        }
    }

    @Test fun `migration preview requires its reviewed token commits once and restores prior history`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog()).use { server ->
            val token = session(server)
            val oldRevision = revision(server)
            val candidate = preview(server, token)
            assertEquals(fixture.original, fixture.store.values.getValue("root"))
            assertEquals(0, fixture.store.writes)
            val reviewToken = candidate.path("data").path("reviewToken").asText()
            val rejected = post(server, "migration-apply", mapOf("reviewToken" to "0".repeat(64)), token)
            assertEquals(409, rejected.status)
            assertEquals(0, fixture.store.writes)
            val applied = tree(post(server, "migration-apply", mapOf("reviewToken" to reviewToken), token))
            assertAnswer("30", applied)
            assertEquals(1, fixture.store.writes)
            assertNotEquals(oldRevision, applied.path("revision").asText())
            assertContains(fixture.store.values.getValue("root"), ":enabled false")
            assertContains(fixture.store.values.getValue("root"), ":host-package")
            assertEquals(409, post(server, "migration-apply", mapOf("reviewToken" to reviewToken), token).status)
            val undo = tree(post(server, "undo", mapOf("baseRevision" to revision(server)), token))
            assertAnswer("10", undo)
            assertEquals(fixture.original, fixture.store.values.getValue("root"))
            val redo = tree(post(server, "redo", mapOf("baseRevision" to revision(server)), token))
            assertAnswer("30", redo)
        }
    }

    @Test fun `source byte CAS changed at commit rejects apply without overwriting an external writer`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog()).use { server ->
            val token = session(server)
            val candidate = preview(server, token)
            val external = fixture.original.replace(":base-value 5", ":base-value 8")
            fixture.store.beforeCommit = { fixture.store.values["root"] = external }
            val response = post(
                server,
                "migration-apply",
                mapOf(
                    "reviewToken" to
                        candidate.path("data").path("reviewToken").asText(),
                ),
                token,
            )
            assertEquals(400, response.status, response.text)
            assertContains(response.text, "Source byte CAS conflict")
            assertEquals(external, fixture.store.values.getValue("root"))
            assertEquals(0, fixture.store.writes)
            assertAnswer("16", run(server))
        }
    }

    @Test fun `participating source graph CAS changes while root source bytes stay identical`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog(linked = true)).use { server ->
            val token = session(server)
            val initial = run(server)
            assertAnswer("20", initial)
            val rootBytes = fixture.store.values.getValue("root")
            val candidate = preview(server, token)
            fixture.store.beforeCommit = {
                fixture.store.values["source"] = fixture.source + "\n;; independently reviewed source\n"
            }
            val response = post(
                server,
                "migration-apply",
                mapOf(
                    "reviewToken" to
                        candidate.path("data").path("reviewToken").asText(),
                ),
                token,
            )
            assertEquals(409, response.status, response.text)
            assertEquals(rootBytes, fixture.store.values.getValue("root"))
            assertEquals(0, fixture.store.writes)
            val current = run(server)
            assertEquals(answer(initial), answer(current))
            assertNotEquals(initial.path("revision"), current.path("revision"))
            assertEquals(
                current.path("revision").asText(),
                json.readTree(response.text)
                    .path("error").path("currentRevision").asText(),
            )
        }
    }

    @Test fun `technical current failure has new revision diagnostics and no old Explain or PDF evidence`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog()).use { server ->
            val old = run(server)
            assertTrue(old.path("data").path("succeeded").asBoolean())
            fixture.store.values["root"] = fixture.original.replace(":divisor 1", ":divisor 0")
            val failed = run(server)
            assertFalse(failed.path("data").path("succeeded").asBoolean())
            assertNotEquals(old.path("revision"), failed.path("revision"))
            assertNotEquals(answer(old), answer(failed))
            assertTrue(
                failed.path("data").path("diagnostics").any {
                    it.path("severity").asText() == "error" && it.path("category").asText() != "business"
                },
            )
            val explain = tree(request(server, route("explain") + "?address=answer"))
            assertFalse(explain.path("data").path("succeeded").asBoolean())
            assertTrue(explain.path("data").path("document").isNull)
            val exported = request(server, route("export.pdf"))
            assertEquals(422, exported.status)
            assertFalse(exported.text.startsWith("%PDF-"))
        }
    }

    @Test fun `captured imports remain immutable after JAR removal and contradictory disk data`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        val catalog = fixture.catalog(editable = false, imported = true)
        fixture.jars.forEach(Files::delete)
        val disk = temporary.resolve("http-workspace/data/facts.csv")
        Files.createDirectories(disk.parent)
        Files.writeString(disk, "key;value\nbase-value;999\n")
        server(catalog).use { server ->
            val captured = run(server)
            assertAnswer("14", captured)
            Files.delete(disk)
            val afterDeletion = run(server)
            assertEquals(answer(captured), answer(afterDeletion))
            assertEquals(captured.path("revision"), afterDeletion.path("revision"))
            val escaped = request(server, route("run", "../outside.mantra"))
            assertEquals(422, escaped.status)
            assertFalse(escaped.text.contains("999"))
        }
    }

    @Test fun `PDF is a binary response once with security headers and no JSON append`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog()).use { server ->
            val pdf = request(server, route("export.pdf"))
            assertEquals(200, pdf.status)
            assertContains(pdf.headers.lowercase(), "content-type: application/pdf")
            assertContains(pdf.headers.lowercase(), "x-content-type-options: nosniff")
            assertContains(pdf.headers.lowercase(), "cache-control: no-store")
            assertTrue(pdf.bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)))
            assertTrue(pdf.text.trimEnd().endsWith("%%EOF"))
            val contentLength = Regex("(?i)content-length: ([0-9]+)").find(pdf.headers)!!.groupValues[1].toInt()
            assertEquals(pdf.bytes.size, contentLength)
        }
    }

    @Test fun `package exports inherit explicitly configured host byte and cell budgets`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        // Requires root's shared export-budget integration; current pre-fix code incorrectly returns 200.
        server(fixture.catalog(), ExportBudget(maxBytes = 64, maxCells = 1, maxSheets = 1)).use { server ->
            for (format in listOf("xlsx", "pdf", "html", "txt")) {
                val response = request(server, route("export.$format"))
                assertEquals(413, response.status, "$format: ${response.text.take(120)}")
            }
            assertEquals(413, request(server, route("export-preview")).status)
            assertEquals(0, fixture.store.writes)
        }
    }

    @Test fun `strict package JSON rejects duplicate trailing and unknown fields before write`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog()).use { server ->
            val token = session(server)
            val root = revision(server)
            for (body in listOf(
                """{"baseRevision":"$root","targetCase":"$targetKey","unknown":true}""",
                """{"baseRevision":"$root","baseRevision":"$root","targetCase":"$targetKey"}""",
                """{"baseRevision":"$root","targetCase":"$targetKey"} {}""",
            )) {
                val response = request(
                    server,
                    route("migration-preview"),
                    "POST",
                    mapOf("X-Mantra-Token" to token),
                    body.toByteArray(Charsets.UTF_8),
                )
                assertEquals(400, response.status, response.text)
            }
            assertEquals(400, request(server, "/api/v1/packages?unexpected=1").status)
            assertEquals(400, request(server, route("paper") + "?panel=result&panel=result").status)
            assertEquals(0, fixture.store.writes)
        }
    }

    @Test fun `package schema rejects invented metadata and missing source ownership fields`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        server(fixture.catalog()).use { server ->
            val index = tree(request(server, "/api/v1/packages"))
            val extra = index.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            extra.put("invented", true)
            assertFailsWith<AssertionError> { validate(extra.toString()) }
            val parameters = tree(request(server, route("parameters")))
            val incomplete = parameters.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            val source = incomplete.path("data").path("parameterSources").single()
                as com.fasterxml.jackson.databind.node.ObjectNode
            source.remove("endExclusive")
            assertFailsWith<AssertionError> { validate(incomplete.toString()) }
            val incompatible = index.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            incompatible.put("contract", "mantra.packages/0")
            assertFailsWith<AssertionError> { validate(incompatible.toString()) }
        }
    }
}
