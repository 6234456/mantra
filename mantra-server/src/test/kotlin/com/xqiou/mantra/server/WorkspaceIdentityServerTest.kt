package com.xqiou.mantra.server

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

    @Test fun `workspace identity is stable public metadata with unchanged private sources`() {
        val root = Files.createDirectory(temporary.resolve("workspace"))
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
        val bytes = sources.keys.associateWith { Files.readAllBytes(root.resolve(it)) }
        var firstIdentity: String? = null
        repeat(2) {
            WorkbenchServer(root, 0).start().use { server ->
                HttpClient.newHttpClient().use { client ->
                    fun get(path: String): String {
                        val reply = client.send(
                            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                        assertEquals(200, reply.statusCode(), reply.body())
                        assertFalse(reply.body().contains(root.toRealPath().toString()))
                        return reply.body()
                    }
                    val workspace = json.readTree(get("/api/v1/workspace"))
                    val snapshot = json.readTree(get("/api/v1/cases/case.mantra/template-sources"))
                    val identity = workspace["data"]["workspaceId"].asText()
                    assertTrue(identity.matches(Regex("[0-9a-f]{64}")))
                    assertEquals(identity, snapshot["data"]["workspaceId"].asText())
                    if (firstIdentity == null) firstIdentity = identity else assertEquals(firstIdentity, identity)
                }
            }
        }
        bytes.forEach { (name, before) -> assertContentEquals(before, Files.readAllBytes(root.resolve(name))) }
    }
}
