package com.xqiou.mantra.lsp

import com.fasterxml.jackson.core.JsonProcessingException
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentTypeTest {
    private val body = """{"jsonrpc":"2.0","id":1,"result":"😀"}""".toByteArray(Charsets.UTF_8)

    private fun read(contentType: String?) = JsonRpcFraming(
        ByteArrayInputStream(
            buildString {
                append("Content-Length: ${body.size}\r\n")
                if (contentType != null) append("Content-Type: $contentType\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII) + body,
        ),
        ByteArrayOutputStream(),
    ).read()

    @Test fun `missing charset and both UTF8 spellings preserve actual multibyte body`() {
        val accepted = listOf(
            null,
            "application/vscode-jsonrpc",
            "application/vscode-jsonrpc; charset=utf-8",
            "application/vscode-jsonrpc; CHARSET = UTF8",
            "application/vscode-jsonrpc; charset\t=\t\"utf-8\"",
            "application/vscode-jsonrpc; extra=plain; charset=utf8",
            "application/vscode-jsonrpc; extra=\"semicolon; charset=shift_jis\"; charset=utf-8",
        )
        accepted.forEach { assertEquals("😀", read(it)?.path("result")?.asText()) }
    }

    @Test fun `all parameters reject unsupported spaced and repeated charset declarations`() {
        val refused = listOf(
            "application/vscode-jsonrpc; charset = shift_jis",
            "application/vscode-jsonrpc; extra=plain; charset=latin1",
            "application/vscode-jsonrpc; charset=utf-8; charset=shift_jis",
            "application/vscode-jsonrpc; charset=utf8; CHARSET=utf-8",
            "application/vscode-jsonrpc; charset=\"\"",
            "application/vscode-jsonrpc; charset=\"utf-8",
            "application/vscode-jsonrpc; charset=\"utf-8\"junk",
            "application/vscode-jsonrpc; charset=utf 8",
            "application/vscode-jsonrpc; charset",
            "application/vscode-jsonrpc; charset=utf-8;",
        )
        refused.forEach { assertFailsWith<FrameRejected>(it) { read(it) } }
    }

    @Test fun `body cannot use Jackson byte sniffing or replacement to accept UTF16 and invalid UTF8`() {
        val text = body.toString(Charsets.UTF_8)
        val invalid = listOf(
            text.toByteArray(Charsets.UTF_16),
            text.toByteArray(Charsets.UTF_16LE),
            text.toByteArray(Charsets.UTF_16BE),
            "{\"jsonrpc\":\"2.0\",\"result\":\"".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray(),
        )
        invalid.forEach { encoded ->
            val header = "Content-Length: ${encoded.size}\r\n" +
                "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n"
            val transport = JsonRpcFraming(
                ByteArrayInputStream(header.toByteArray(Charsets.US_ASCII) + encoded),
                ByteArrayOutputStream(),
            )
            val failure = assertFails { transport.read() }
            assertTrue(failure is FrameRejected || failure is JsonProcessingException)
        }
        assertEquals("😀", read("application/vscode-jsonrpc; charset=utf-8")?.path("result")?.asText())
    }
}
