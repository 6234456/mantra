package com.xqiou.mantra.server

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class WorkbenchTransportTest {
    @TempDir lateinit var root: Path

    @Test
    fun `stalled request bodies and excess queued requests close without blocking subsequent work`() {
        WorkbenchServer(root, 0).use { server ->
            server.bodyReadTimeoutMillis = 150
            server.start()
            val sockets = mutableListOf<Socket>()
            try {
                repeat(36) {
                    val socket = Socket("127.0.0.1", server.localPort).apply { soTimeout = 2500 }
                    sockets += socket
                    try {
                        socket.getOutputStream().write(
                            (
                                "GET /api/v1/workspace HTTP/1.1\r\n" +
                                    "Host: 127.0.0.1:${server.localPort}\r\nContent-Length: 100\r\n\r\na"
                                ).toByteArray(),
                        )
                        socket.getOutputStream().flush()
                    } catch (_: IOException) { /* An excess request may already be rejected. */ }
                }
                sockets.forEach { socket ->
                    try {
                        assertEquals(-1, socket.getInputStream().read(), "A stalled body must close without a response")
                    } catch (error: SocketTimeoutException) {
                        fail("Stalled request exceeded the transport deadline", error)
                    } catch (_: IOException) { /* Reset is also a completed connection close. */ }
                }
                HttpClient.newHttpClient().use { client ->
                    val response = client.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}/api/v1/workspace"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertEquals(200, response.statusCode())
                }
            } finally {
                sockets.forEach { it.close() }
            }
        }
    }
}
