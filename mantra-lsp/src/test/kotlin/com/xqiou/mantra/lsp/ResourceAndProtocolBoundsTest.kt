package com.xqiou.mantra.lsp

import com.fasterxml.jackson.core.JsonProcessingException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceAndProtocolBoundsTest {
    @TempDir lateinit var temporary: Path

    @Test fun `growing source is rejected after at most one byte beyond its permitted retained capture`() {
        val reads = AtomicInteger()
        val growing = object : java.io.InputStream() {
            override fun read(): Int {
                reads.incrementAndGet()
                return 'x'.code
            }
        }
        var checkpoints = 0
        assertFailsWith<IllegalArgumentException> { captureSource(growing, 16) { checkpoints++ } }
        assertEquals(17, reads.get())
        assertTrue(checkpoints > 0)
        assertEquals(
            "1234567890",
            String(
                captureSource(ByteArrayInputStream("1234567890".toByteArray()), 10) {
                },
                Charsets.UTF_8,
            ),
        )
        assertEquals(0, captureSource(ByteArrayInputStream(byteArrayOf()), 0) {}.size)
    }

    @Test fun `bounded reads retain current valid UTF8 and reject malformed or aggregate oversize sources`() {
        val first = temporary.resolve("one.mantra")
        val second = temporary.resolve("two.mantra")
        val text = "; 😀\n(schema example/one)\n"
        Files.writeString(first, text)
        Files.writeString(second, "(schema example/two)\n")
        val confined =
            ConfinedSources(listOf(temporary), DocumentEpoch(0, emptyMap()), {
            }, maxBytes = text.toByteArray().size.toLong())
        assertEquals(text, confined.read(first.toUri().toString()).text)
        assertFailsWith<IllegalArgumentException> { confined.read(second.toUri().toString()) }
        Files.write(first, byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<java.nio.charset.CharacterCodingException> {
            ConfinedSources(listOf(temporary), DocumentEpoch(0, emptyMap()), {}).read(first.toUri().toString())
        }
    }

    @Test fun `one RPC frame cannot contain duplicate dispatch keys nested duplicates or a second JSON document`() {
        fun read(body: String): com.fasterxml.jackson.databind.JsonNode? {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
            return JsonRpcFraming(ByteArrayInputStream(header + bytes), ByteArrayOutputStream()).read()
        }
        listOf(
            """{"jsonrpc":"2.0","method":"initialize","method":"shutdown","id":1}""",
            """{"jsonrpc":"2.0","method":"initialize", "params":{
                "rootUri":"file:/first","rootUri":"file:/second"},"id":1}""",
            """{"jsonrpc":"2.0","method":"initialize","id":1} {"jsonrpc":"2.0","method":"shutdown","id":2}""",
        ).forEach { body -> assertFailsWith<JsonProcessingException>(body) { read(body) } }
        assertEquals(
            "initialize",
            read("""{"jsonrpc":"2.0","method":"initialize","params":{},"id":1}   """)!!.get("method").textValue(),
        )
    }
}
