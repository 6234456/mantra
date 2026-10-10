package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperBrowsingServerTest {
    @TempDir lateinit var root: Path
    private val json = ObjectMapper()

    private data class Response(val status: Int, val body: String)

    private fun get(server: WorkbenchServer, path: String): Response =
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().apply {
                write(
                    (
                        "GET $path HTTP/1.1\r\nHost: 127.0.0.1:${server.localPort}\r\n" +
                            "Connection: close\r\nContent-Length: 0\r\n\r\n"
                        ).toByteArray(),
                )
                flush()
            }
            socket.shutdownOutput()
            val text = socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
            Response(text.lineSequence().first().split(' ')[1].toInt(), text.substringAfter("\r\n\r\n"))
        }

    private fun paper(server: WorkbenchServer, path: String, packaged: Boolean): JsonNode {
        val response = get(server, path)
        assertEquals(200, response.status, response.body)
        val envelope = json.readTree(response.body)
        return if (packaged) envelope.path("data").path("document") else envelope
    }

    private fun rows(paper: JsonNode): List<JsonNode> = paper.path("data").path("tables").flatMap { it.path("rows") }

    private fun verify(server: WorkbenchServer, base: String, packaged: Boolean) {
        val original = paper(server, "$base?panel=result", packaged)
        val expanded = paper(server, "$base?panel=result&includeZero=true", packaged)
        assertEquals(original.path("revision"), expanded.path("revision"))
        assertTrue(original.path("data").path("browsing").path("hideZero").asBoolean())
        assertFalse(original.path("data").path("browsing").path("includeZero").asBoolean())
        assertTrue(expanded.path("data").path("browsing").path("includeZero").asBoolean())
        assertFalse(rows(original).any { it.path("node").asText() == "blank" })
        assertTrue(rows(expanded).any { it.path("node").asText() == "blank" })
        assertFalse(rows(expanded).any { it.path("node").asText() in setOf("inactive", "concealed") })
        assertEquals(
            rows(original).single { it.path("node").asText() == "answer" }.path("cells"),
            rows(expanded).single { it.path("node").asText() == "answer" }.path("cells"),
        )
        assertEquals(original, paper(server, "$base?panel=result&includeZero=false", packaged))
        for (query in listOf(
            "includeZero=1",
            "includeZero=TRUE",
            "includeZero=",
            "includeZero=true&includeZero=false",
        )) {
            assertEquals(400, get(server, "$base?$query").status, query)
        }
        assertEquals(400, get(server, base.removeSuffix("paper") + "run?includeZero=true").status)
    }

    @Test
    fun `legacy HTTP zero expansion preserves the document and validates flags strictly`() {
        Files.writeString(
            root.resolve("schema.mantra"),
            """
            (schema test/browsing
              (section result "Result" {:display :schedule}
                (line answer "Answer" 10)
                (line blank "Blank" 0)
                (line inactive "Inactive" 9 {:when false})
                (line concealed "Concealed" 13 {:hidden true})))
            """.trimIndent(),
        )
        val case = "(case c {:schema \"test/browsing\"})"
        Files.writeString(root.resolve("case.mantra"), case)
        WorkbenchServer(root, 0).start().use { server ->
            verify(server, "/api/v1/cases/case.mantra/paper", false)
        }
        assertEquals(case, Files.readString(root.resolve("case.mantra")))
    }

    @Test
    fun `captured package HTTP zero expansion preserves read-only resources and validates flags strictly`() {
        val fixture = PackageHttpFixture(root.resolve("capture"))
        val workspace = Files.createDirectories(root.resolve("workspace"))
        WorkbenchServer(workspace, 0, packageWorkspace = fixture.catalog(editable = false, zeroRows = true)).start()
            .use { server ->
                verify(server, "/api/v1/package-cases/old%2Fcases%2Fdemo.mantra/paper", true)
            }
        assertEquals(0, fixture.store.writes)
        assertEquals(fixture.original, fixture.store.values.getValue("root"))
    }
}
