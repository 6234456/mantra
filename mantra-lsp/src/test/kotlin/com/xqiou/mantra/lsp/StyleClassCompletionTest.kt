package com.xqiou.mantra.lsp

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(10)
class StyleClassCompletionTest {
    @TempDir lateinit var directory: Path

    private class Client : AutoCloseable {
        private val input = PipedInputStream(1_048_576)
        private val send = PipedOutputStream(input)
        private val receive = PipedInputStream(1_048_576)
        private val output = PipedOutputStream(receive)
        private val framing = JsonRpcFraming(receive, send)
        private val server = LanguageServer(input, output, {})
        private val thread = Thread { server.serve() }.apply {
            isDaemon = true
            start()
        }

        fun request(id: Int, method: String, params: Any): JsonNode {
            framing.write(
                framing.mapper.valueToTree(
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to id,
                        "method" to method,
                        "params" to params,
                    ),
                ),
            )
            while (true) {
                val response = requireNotNull(framing.read())
                if (response.path("id").isInt && response.path("id").asInt() == id) return response
            }
        }

        fun notify(method: String, params: Any) {
            framing.write(framing.mapper.valueToTree(mapOf("jsonrpc" to "2.0", "method" to method, "params" to params)))
        }

        override fun close() {
            try {
                server.close()
            } finally {
                send.close()
                input.close()
                output.close()
                receive.close()
                thread.join(2_000)
                check(!thread.isAlive) { "Style completion protocol thread survived cleanup" }
            }
        }
    }

    @Test
    fun `real LSP completion exposes the layout class form with exact replacement span`() {
        val text = """(layout test/style-completion {:style-preset :utilities}
  (style-class :important {:weight :bold :tone :accent})
  (style {:class :important} {:use :strong}))"""
        val file = directory.resolve("layout.mantra")
        Files.writeString(file, text)
        val uri = file.toRealPath().toUri().toString()
        Client().use { client ->
            client.request(1, "initialize", mapOf("rootUri" to directory.toUri().toString()))
            client.notify(
                "textDocument/didOpen",
                mapOf("textDocument" to mapOf("uri" to uri, "languageId" to "mantra", "version" to 1, "text" to text)),
            )
            // The document is valid while the caret is inside its authored form head.
            val offset = text.indexOf("style-class") + "style-cl".length
            val position = LineIndex(text).position(offset)
            val response = client.request(
                2,
                "textDocument/completion",
                mapOf(
                    "textDocument" to mapOf("uri" to uri),
                    "position" to mapOf("line" to position.line, "character" to position.character),
                ),
            )
            val completions = response.path("result").path("items")
            val form = completions.single { it.path("label").asText() == "style-class" }
            assertEquals("style-class", form.path("textEdit").path("newText").asText())
            assertEquals(1, form.path("textEdit").path("range").path("start").path("line").asInt())
            assertEquals(3, form.path("textEdit").path("range").path("start").path("character").asInt())
            assertEquals(11, form.path("textEdit").path("range").path("end").path("character").asInt())
            assertTrue(form.path("documentation").asText().isNotBlank())
            assertEquals(text, Files.readString(file))
            assertTrue(client.request(3, "shutdown", emptyMap<String, Any>()).path("result").isNull)
            client.notify("exit", emptyMap<String, Any>())
        }
    }
}
