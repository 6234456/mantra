package com.xqiou.mantra.lsp

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FramingTest {
    @Test fun `frame lengths count UTF8 bytes and tolerate chunked reads`() {
        val output = ByteArrayOutputStream()
        val writer = JsonRpcFraming(ByteArrayInputStream(byteArrayOf()), output)
        val message = writer.mapper.readTree("""{"jsonrpc":"2.0","id":"😀","result":"你好"}""")
        writer.write(message)
        val bytes = output.toByteArray()
        val chunked = object : java.io.InputStream() {
            private val delegate = ByteArrayInputStream(bytes)
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                delegate.read(buffer, offset, minOf(length, 2))
        }
        assertEquals(message, JsonRpcFraming(chunked, ByteArrayOutputStream()).read())
        val prefix = "Content-Length: ${writer.mapper.writeValueAsBytes(message).size}"
        assertTrue(String(bytes, Charsets.UTF_8).startsWith(prefix))
    }

    @Test fun `malformed duplicate oversize and truncated frames fail before unbounded growth`() {
        fun read(text: String, bound: Int = 1024) =
            JsonRpcFraming(ByteArrayInputStream(text.toByteArray()), ByteArrayOutputStream(), bound).read()
        assertFailsWith<FrameRejected> { read("Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}") }
        assertFailsWith<FrameRejected> { read("Content-Length: 9999999\r\n\r\n", 32) }
        assertFailsWith<FrameRejected> { read("Content-Length: -1\r\n\r\n") }
        assertFailsWith<EOFException> { read("Content-Length: 10\r\n\r\n{}") }
        assertFailsWith<FrameRejected> { read("Content-Length: 2\n\n{}") }
    }
}
