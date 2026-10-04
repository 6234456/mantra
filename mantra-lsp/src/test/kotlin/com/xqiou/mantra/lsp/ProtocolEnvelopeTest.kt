package com.xqiou.mantra.lsp

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Timeout(10)
class ProtocolEnvelopeTest {
    private class Client : AutoCloseable {
        private val serverInput = PipedInputStream(1_048_576)
        private val inputWriter = PipedOutputStream(serverInput)
        private val clientInput = PipedInputStream(1_048_576)
        private val serverOutput = PipedOutputStream(clientInput)
        private val frames = JsonRpcFraming(clientInput, inputWriter)
        private val server = LanguageServer(serverInput, serverOutput, {})
        private val exitCode = AtomicInteger(-1)
        private val thread = Thread { exitCode.set(server.serve()) }.apply {
            isDaemon = true
            start()
        }

        fun send(value: Any) = frames.write(frames.mapper.valueToTree(value))
        fun receive(): JsonNode = assertNotNull(frames.read())
        fun request(id: Any, method: String, params: Any = emptyMap<String, Any>()): JsonNode {
            send(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
            val response = receive()
            assertEquals(frames.mapper.valueToTree<JsonNode>(id), response.get("id"))
            return response
        }

        fun finish() {
            assertTrue(request("shutdown", "shutdown").path("result").isNull)
            send(mapOf("jsonrpc" to "2.0", "method" to "exit"))
            thread.join(2_000)
            assertFalse(thread.isAlive)
            assertEquals(0, exitCode.get())
        }

        override fun close() {
            try {
                server.close()
            } finally {
                try {
                    inputWriter.close()
                    serverInput.close()
                    serverOutput.close()
                    clientInput.close()
                } finally {
                    thread.join(2_000)
                    check(!thread.isAlive) { "Task-owned language protocol thread remains" }
                }
            }
        }
    }

    @Test fun `invalid nonrequests receive null ID errors and usable invalid request IDs stay correlated`() {
        Client().use { client ->
            val malformed = listOf(
                emptyList<Any>(),
                mapOf("jsonrpc" to "1.0", "method" to "unknown"),
                mapOf("jsonrpc" to "2.0"),
                mapOf("jsonrpc" to "2.0", "method" to 7),
                mapOf("jsonrpc" to 2.0, "method" to "unknown"),
                mapOf("jsonrpc" to "2.0", "method" to "unknown", "id" to emptyMap<String, Any>()),
            )
            malformed.forEach { value ->
                client.send(value)
                val reply = client.receive()
                assertEquals("2.0", reply.path("jsonrpc").asText())
                assertTrue(reply.has("id") && reply.path("id").isNull)
                assertEquals(-32600, reply.path("error").path("code").asInt())
            }
            client.send(mapOf("jsonrpc" to "1.0", "id" to "ack", "method" to "unknown"))
            val correlated = client.receive()
            assertEquals("ack", correlated.path("id").asText())
            assertEquals(-32600, correlated.path("error").path("code").asInt())
            assertTrue(client.request(1, "initialize").has("result"))
            client.finish()
        }
    }

    @Test fun `cancel and exit requests are rejected without changing valid notification behavior`() {
        Client().use { client ->
            assertTrue(client.request(1, "initialize").has("result"))
            val cancel = client.request("bad-cancel", "$/cancelRequest", mapOf("id" to "absent"))
            assertEquals(-32600, cancel.path("error").path("code").asInt())
            val exit = client.request("bad-exit", "exit")
            assertEquals(-32600, exit.path("error").path("code").asInt())
            // A following ordered request proves these valid notifications emitted no extra reply.
            val notification = mapOf(
                "jsonrpc" to "2.0",
                "method" to "$/cancelRequest",
                "params" to mapOf("id" to "absent"),
            )
            client.send(notification)
            client.send(mapOf("jsonrpc" to "2.0", "method" to "unknown/clientNotification"))
            val alive = client.request("alive", "unknown/request")
            assertEquals(-32601, alive.path("error").path("code").asInt())
            client.finish()
        }
    }
}
