package com.xqiou.mantra.lsp

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(10)
class ProtocolTest {
    @TempDir lateinit var directory: Path

    private class Client(factory: ((List<Path>) -> LanguageAnalysisEngine)? = null) : AutoCloseable {
        private val serverInput = PipedInputStream(1_048_576)
        private val send = PipedOutputStream(serverInput)
        private val clientInput = PipedInputStream(1_048_576)
        private val serverOutput = PipedOutputStream(clientInput)
        private val frames = JsonRpcFraming(clientInput, send)
        val notifications = mutableListOf<JsonNode>()
        private val server = if (factory == null) {
            LanguageServer(serverInput, serverOutput, {})
        } else {
            LanguageServer(serverInput, serverOutput, {}, factory)
        }
        private val thread = Thread { server.serve() }.apply {
            isDaemon = true
            start()
        }
        fun send(value: Any) {
            frames.write(frames.mapper.valueToTree(value))
        }
        fun response(id: Any): JsonNode {
            while (true) {
                val message = requireNotNull(frames.read())
                if (message.get("id") == frames.mapper.valueToTree<JsonNode>(id)) return message
                notifications.add(message)
            }
        }
        fun request(id: Any, method: String, params: Any = emptyMap<String, Any>()): JsonNode {
            send(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
            return response(id)
        }
        fun notify(method: String, params: Any) =
            send(mapOf("jsonrpc" to "2.0", "method" to method, "params" to params))
        override fun close() {
            try {
                server.close()
            } finally {
                send.close()
                serverInput.close()
                serverOutput.close()
                clientInput.close()
                thread.join(2_000)
                check(!thread.isAlive) { "Task-owned protocol thread survived cleanup" }
            }
        }
    }

    @Test fun `real framed handshake document navigation rename shutdown and exit`() {
        val file = directory.resolve("schema.mantra")
        val source = "(schema test/protocol {:version \"1\"} (input source-value :decimal) (line result " +
            "\"Result\" (+ source-value 1)))"
        Files.writeString(file, source)
        val uri = file.toRealPath().toUri().toString()
        Client().use { client ->
            val initialized = client.request(1, "initialize", mapOf("rootUri" to directory.toUri().toString()))
            assertEquals("utf-16", initialized.path("result").path("capabilities").path("positionEncoding").asText())
            client.notify(
                "textDocument/didOpen",
                mapOf(
                    "textDocument" to mapOf(
                        "uri" to uri,
                        "languageId" to "mantra",
                        "version" to 7,
                        "text" to source,
                    ),
                ),
            )
            val at = source.lastIndexOf("source-value")
            val position = mapOf("line" to 0, "character" to at)
            val params = mapOf("textDocument" to mapOf("uri" to uri), "position" to position)
            val definition = client.request("definition", "textDocument/definition", params)
            assertEquals(1, definition.path("result").size())
            assertEquals(
                source.indexOf("source-value"),
                definition.path("result")[0].path("range").path("start").path("character").asInt(),
            )
            val renamed = client.request(3, "textDocument/rename", params + ("newName" to "renamed-source"))
            val document = renamed.path("result").path("documentChanges")[0]
            assertEquals(7, document.path("textDocument").path("version").asInt())
            assertEquals(2, document.path("edits").size())
            assertEquals(source, Files.readString(file))
            assertTrue(client.request(4, "shutdown").path("result").isNull)
            client.notify("exit", emptyMap<String, Any>())
        }
    }

    @Test fun `queued cancellation accepts string IDs without timing a compiler`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = object : LanguageAnalysisEngine {
            override fun analyze(epoch: DocumentEpoch, checkpoint: () -> Unit): WorkspaceAnalysis {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                checkpoint()
                return WorkspaceAnalysis(epoch, epoch.overlays, emptyList(), emptyList(), emptyList(), emptyList())
            }
            override fun verifySources(analysis: WorkspaceAnalysis, checkpoint: () -> Unit) {
                checkpoint()
            }
        }
        Client { engine }.use { client ->
            client.request(1, "initialize", emptyMap<String, Any>())
            client.notify(
                "textDocument/didOpen",
                mapOf(
                    "textDocument" to mapOf(
                        "uri" to "untitled:x.mantra",
                        "languageId" to "mantra",
                        "version" to 1,
                        "text" to "(schema x)",
                    ),
                ),
            )
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            client.send(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to "pending",
                    "method" to "textDocument/hover",
                    "params" to mapOf(
                        "textDocument" to mapOf("uri" to "untitled:x.mantra"),
                        "position" to mapOf("line" to 0, "character" to 1),
                    ),
                ),
            )
            client.notify("$/cancelRequest", mapOf("id" to "pending"))
            // A rejected envelope is handled on the input loop, without waiting for the
            // semantic worker. Its response proves that the earlier cancellation was received.
            client.send(mapOf("jsonrpc" to "1.0", "id" to "ack", "method" to "unknown"))
            assertEquals(-32600, client.response("ack").path("error").path("code").asInt())
            release.countDown()
            val cancelled = client.response("pending")
            assertEquals(-32800, cancelled.path("error").path("code").asInt())
        }
    }

    @Test fun `broken current overlay publishes its version and clears on close`() {
        val file = directory.resolve("schema.mantra")
        Files.writeString(file, "(schema x)")
        val uri = file.toRealPath().toUri().toString()
        Client().use { client ->
            client.request(1, "initialize", mapOf("rootUri" to directory.toUri().toString()))
            client.notify(
                "textDocument/didOpen",
                mapOf(
                    "textDocument" to mapOf(
                        "uri" to uri,
                        "version" to 1,
                        "text" to "(schema x)",
                    ),
                ),
            )
            client.notify(
                "textDocument/didChange",
                mapOf(
                    "textDocument" to mapOf("uri" to uri, "version" to 2),
                    "contentChanges" to listOf(mapOf("text" to "(schema x")),
                ),
            )
            client.request(
                2,
                "textDocument/definition",
                mapOf(
                    "textDocument" to mapOf("uri" to uri),
                    "position" to mapOf("line" to 0, "character" to 1),
                ),
            )
            val current = client.notifications.last { it.path("method").asText() == "textDocument/publishDiagnostics" }
            assertEquals(2, current.path("params").path("version").asInt())
            assertEquals("MANTRA-READ-SYNTAX", current.path("params").path("diagnostics")[0].path("code").asText())
            client.notify("textDocument/didClose", mapOf("textDocument" to mapOf("uri" to uri)))
            client.request(3, "unknown")
            assertEquals(
                0,
                client.notifications.last {
                    it.path("method").asText() == "textDocument/publishDiagnostics"
                }
                    .path("params").path("diagnostics").size(),
            )
        }
    }
}
