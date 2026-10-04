package com.xqiou.mantra.lsp

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class FrameRejected(message: String) : RuntimeException(message)

/** No line-oriented JSON, platform charset, unbounded read or stdout logging. */
class JsonRpcFraming(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxBytes: Int = 1_048_576,
    private val maxHeaderBytes: Int = 8_192,
) {
    val mapper = ObjectMapper(
        JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(maxBytes).maxNumberLength(100).build(),
        ).build(),
    ).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun read(): JsonNode? {
        var headerBytes = 0
        fun line(): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) {
                    if (headerBytes == 0 && bytes.size() == 0) return null
                    throw EOFException("Truncated JSON-RPC header")
                }
                requireAscii(byte)
                if (++headerBytes > maxHeaderBytes) throw FrameRejected("Header byte limit exceeded")
                if (byte == 10) {
                    val raw = bytes.toByteArray()
                    if (raw.isEmpty() || raw.last() != 13.toByte()) throw FrameRejected("Headers require CRLF")
                    return String(raw, 0, raw.size - 1, Charsets.US_ASCII)
                }
                bytes.write(byte)
            }
        }
        val headers = linkedMapOf<String, String>()
        val first = line() ?: return null
        var current = first
        while (current.isNotEmpty()) {
            val colon = current.indexOf(':')
            if (colon <= 0) throw FrameRejected("Malformed JSON-RPC header")
            val key = current.substring(0, colon).lowercase(java.util.Locale.ROOT)
            if (headers.put(key, current.substring(colon + 1).trim()) != null) throw FrameRejected("Duplicate header")
            current = line() ?: throw EOFException("Truncated JSON-RPC headers")
        }
        val lengthText = headers["content-length"] ?: throw FrameRejected("Missing Content-Length")
        if (!lengthText.matches(Regex("[0-9]{1,8}"))) throw FrameRejected("Invalid Content-Length")
        val length = lengthText.toIntOrNull() ?: throw FrameRejected("Invalid Content-Length")
        if (length !in 1..maxBytes) throw FrameRejected("JSON-RPC body byte limit exceeded")
        headers["content-type"]?.let(::validateContentType)
        val body = input.readNBytes(length)
        if (body.size != length) throw EOFException("Truncated JSON-RPC body")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            throw FrameRejected("JSON-RPC body is not valid UTF-8")
        }
        // A String parser cannot sniff a different byte encoding or replace malformed bytes.
        return mapper.readTree(text) ?: throw FrameRejected("Empty JSON-RPC body")
    }

    @Synchronized fun write(message: JsonNode) {
        val body = mapper.writeValueAsBytes(message)
        if (body.size > maxBytes) throw FrameRejected("JSON-RPC response byte limit exceeded")
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }
    private fun requireAscii(byte: Int) {
        if (byte > 127 || byte < 32 && byte != 13 && byte != 10 && byte != 9) {
            throw FrameRejected("JSON-RPC header is not ASCII")
        }
    }

    /** Parse every MIME parameter, including optional whitespace and quoted values. */
    private fun validateContentType(value: String) {
        val parts = mutableListOf<String>()
        var start = 0
        var quoted = false
        var escaped = false
        value.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                quoted && character == '\\' -> escaped = true
                character == '"' -> quoted = !quoted
                character == ';' && !quoted -> {
                    parts.add(value.substring(start, index))
                    start = index + 1
                }
            }
        }
        if (quoted || escaped) throw FrameRejected("Malformed Content-Type parameter")
        parts.add(value.substring(start))
        if (parts.first().isBlank()) throw FrameRejected("Malformed Content-Type")
        val token = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        var charsetSeen = false
        parts.drop(1).forEach { parameter ->
            val equal = parameter.indexOf('=')
            if (equal < 1) throw FrameRejected("Malformed Content-Type parameter")
            val name = parameter.substring(0, equal).trim()
            if (!token.matches(name)) throw FrameRejected("Malformed Content-Type parameter name")
            val raw = parameter.substring(equal + 1).trim()
            val decoded = if (raw.startsWith('"')) {
                if (raw.length < 2 || !raw.endsWith('"')) throw FrameRejected("Malformed quoted parameter")
                val text = StringBuilder()
                var escape = false
                raw.substring(1, raw.lastIndex).forEach { character ->
                    when {
                        escape -> {
                            text.append(character)
                            escape = false
                        }
                        character == '\\' -> escape = true
                        character == '"' -> throw FrameRejected("Malformed quoted parameter")
                        else -> text.append(character)
                    }
                }
                if (escape) throw FrameRejected("Malformed quoted parameter escape")
                text.toString()
            } else {
                if (!token.matches(raw)) throw FrameRejected("Malformed Content-Type parameter value")
                raw
            }
            if (name.equals("charset", true)) {
                if (charsetSeen) throw FrameRejected("Duplicate Content-Type charset")
                charsetSeen = true
                if (!decoded.equals("utf-8", true) && !decoded.equals("utf8", true)) {
                    throw FrameRejected("Only UTF-8 JSON-RPC bodies are supported")
                }
            }
        }
    }
}
