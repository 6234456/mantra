package com.xqiou.mantra.core.data

import com.xqiou.mantra.core.model.Value
import java.math.BigDecimal

/**
 * Minimal JSON reader producing Mantra [Value]s. Numbers are parsed exactly into BigDecimal (no
 * floating point), object keys become keywords so JSON data has the same shape as case literals.
 */
object Json {
    class JsonException(message: String) : RuntimeException(message)

    fun parse(text: String): Value = Parser(text).run {
        val value = value()
        skipWhitespace()
        if (pos != text.length) fail("unexpected trailing content")
        value
    }

    private class Parser(val text: String) {
        var pos = 0

        fun fail(message: String): Nothing {
            val line = text.substring(0, pos.coerceAtMost(text.length)).count { it == '\n' } + 1
            throw JsonException("JSON: $message at line $line")
        }

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun value(): Value {
            skipWhitespace()
            if (pos >= text.length) fail("unexpected end")
            return when (val ch = text[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> Value.Text(string())
                't' -> literal("true", Value.Bool(true))
                'f' -> literal("false", Value.Bool(false))
                'n' -> literal("null", Value.Nil)
                else -> if (ch == '-' || ch.isDigit()) number() else fail("unexpected character '$ch'")
            }
        }

        fun literal(word: String, value: Value): Value {
            if (!text.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return value
        }

        fun obj(): Value {
            pos++
            val entries = linkedMapOf<Value, Value>()
            skipWhitespace()
            if (text.getOrNull(pos) == '}') {
                pos++
                return Value.MapV(entries)
            }
            while (true) {
                skipWhitespace()
                if (text.getOrNull(pos) != '"') fail("expected object key")
                val key = string()
                skipWhitespace()
                if (text.getOrNull(pos) != ':') fail("expected ':'")
                pos++
                entries[Value.Kw(key)] = value()
                skipWhitespace()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return Value.MapV(entries)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun array(): Value {
            pos++
            val items = mutableListOf<Value>()
            skipWhitespace()
            if (text.getOrNull(pos) == ']') {
                pos++
                return Value.Vec(items)
            }
            while (true) {
                items += value()
                skipWhitespace()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return Value.Vec(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                when (val ch = text[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val esc = text.getOrNull(pos++)) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val hex = text.substring(pos, (pos + 4).coerceAtMost(text.length))
                                out.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                                pos += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    else -> out.append(ch)
                }
            }
        }

        fun number(): Value {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            return try {
                Value.Num(BigDecimal(text.substring(start, pos)))
            } catch (_: NumberFormatException) {
                fail("bad number")
            }
        }
    }
}
