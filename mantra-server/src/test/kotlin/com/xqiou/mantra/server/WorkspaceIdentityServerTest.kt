package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceIdentityServerTest {
    @TempDir lateinit var temporary: Path
    private val json = ObjectMapper()
    private val route = "/api/v1/template-recovery-context"

    private fun workspace(): Path = Files.createTempDirectory(temporary, "workspace-").also { root ->
        val sources = mapOf(
            "schema.mantra" to """
                (schema identity/http {:version "1" :mainline [main]}
                  (input base-amount :decimal)
                  (section main "Main" {:panel true}
                    (info answer "Answer" (* base-amount 2))))
            """.trimIndent(),
            "layout.mantra" to "(layout identity/paper {})",
            "case.mantra" to "(case example {:schema \"identity/http\" :layout \"identity/paper\"}" +
                " (inputs {:base-amount 10}))",
        )
        sources.forEach { (name, text) -> Files.writeString(root.resolve(name), text) }
    }

    private fun ui(): Path = Files.createTempDirectory(temporary, "ui-").also { path ->
        Files.writeString(path.resolve("index.html"), "<html><head></head><body>Workbench</body></html>")
    }

    private fun HttpClient.request(
        server: WorkbenchServer,
        path: String,
        token: String? = null,
        method: String = "GET",
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
        if (token != null) request.header("X-Mantra-Token", token)
        return send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpClient.token(server: WorkbenchServer): String =
        Regex("name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"")
            .find(request(server, "/").body())!!.groupValues[1]

    private fun data(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())["data"]
    }

    @Test fun `separate session-bound recovery identity leaves legacy wire and source bytes unchanged`() {
        val root = workspace()
        val original = Files.list(root).use { files -> files.toList().associateWith(Files::readAllBytes) }
        var firstIdentity: String? = null
        repeat(2) {
            WorkbenchServer(root, 0, ui()).start().use { server ->
                HttpClient.newHttpClient().use { client ->
                    val token = client.token(server)
                    val workspace = data(client.request(server, "/api/v1/workspace"))
                    val snapshot = data(client.request(server, "/api/v1/cases/case.mantra/template-sources"))
                    assertEquals(
                        setOf("cases", "schemas", "parameters", "layouts", "diagnostics"),
                        workspace.fieldNames().asSequence().toSet(),
                    )
                    assertEquals(
                        setOf("document", "baseRevisions", "documents"),
                        snapshot.fieldNames().asSequence().toSet(),
                    )
                    val reply = client.request(server, route, token)
                    val context = data(reply)
                    val identity = context["workspaceId"].asText()
                    assertTrue(identity.matches(Regex("[0-9a-f]{64}")))
                    assertEquals(setOf("workspaceId"), context.fieldNames().asSequence().toSet())
                    assertEquals(identity, json.readTree(reply.body())["revision"].asText())
                    assertFalse(reply.body().contains(root.toRealPath().toString()))
                    if (firstIdentity == null) firstIdentity = identity else assertEquals(firstIdentity, identity)
                }
            }
        }
        original.forEach { (path, before) -> assertContentEquals(before, Files.readAllBytes(path)) }
    }

    @Test fun `recovery context requires current session token and exact GET route without query`() {
        WorkbenchServer(workspace(), 0, ui()).start().use { server ->
            HttpClient.newHttpClient().use { client ->
                val token = client.token(server)
                assertEquals(403, client.request(server, route).statusCode())
                assertEquals(403, client.request(server, route, "not-the-session-token").statusCode())
                assertEquals(400, client.request(server, "$route?extra=1", token).statusCode())
                listOf("HEAD", "POST", "PUT").forEach { method ->
                    assertEquals(405, client.request(server, route, token, method).statusCode(), method)
                }
                assertEquals(404, client.request(server, "$route/", token).statusCode())
                assertEquals(200, client.request(server, route, token).statusCode())
            }
        }
    }

    @Test fun `recovery context remains available when a saved baseline cannot be captured`() {
        val root = workspace()
        Files.writeString(root.resolve("schema.mantra"), "(schema unclosed")
        WorkbenchServer(root, 0, ui()).start().use { server ->
            HttpClient.newHttpClient().use { client ->
                val token = client.token(server)
                assertEquals(422, client.request(server, "/api/v1/cases/case.mantra/template-sources").statusCode())
                val identity = data(client.request(server, route, token))["workspaceId"].asText()
                assertTrue(identity.matches(Regex("[0-9a-f]{64}")))
                assertEquals("(schema unclosed", Files.readString(root.resolve("schema.mantra")))
            }
        }
    }

    @Test fun `package hosts do not expose file recovery context`() {
        val fixture = PackageHttpFixture(temporary.resolve("capture"))
        WorkbenchServer(workspace(), 0, ui(), packageWorkspace = fixture.catalog()).start().use { server ->
            HttpClient.newHttpClient().use { client ->
                val token = client.token(server)
                assertEquals(404, client.request(server, route, token).statusCode())
            }
        }
        assertEquals(0, fixture.store.writes)
        assertEquals(fixture.original, fixture.store.values.getValue("root"))
    }
}
