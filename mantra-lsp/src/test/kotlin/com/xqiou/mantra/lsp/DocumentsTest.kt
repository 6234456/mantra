package com.xqiou.mantra.lsp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentsTest {
    @Test fun `UTF16 CRLF and sequential changes preserve exact offsets`() {
        val store = Documents()
        val uri = "untitled:test.mantra"
        store.open(uri, "A😀\r\nB", 1)
        val original = store.snapshot()
        assertEquals(3, original.overlays.getValue(uri).lines.offset(Position(0, 3)))
        assertEquals(5, original.overlays.getValue(uri).lines.offset(Position(1, 0)))
        assertFailsWith<IllegalArgumentException> { original.overlays.getValue(uri).lines.offset(Position(0, 2)) }
        store.change(
            uri,
            2,
            listOf(
                Change(Range(Position(0, 1), Position(0, 3)), "C"),
                Change(Range(Position(1, 0), Position(1, 1)), "D"),
            ),
        )
        assertEquals("AC\r\nD", store.snapshot().overlays.getValue(uri).text)
        assertFalse(store.current(original.revision))
        assertEquals("A😀\r\nB", original.overlays.getValue(uri).text)
    }

    @Test fun `invalid change is atomic and stale versions are rejected`() {
        val store = Documents(maxChars = 8)
        store.open("untitled:x", "abc", 1)
        assertFailsWith<IllegalArgumentException> {
            store.change(
                "untitled:x",
                2,
                listOf(Change(null, "xy"), Change(Range(Position(0, 9), Position(0, 10)), "z")),
            )
        }
        assertEquals("abc", store.snapshot().overlays.getValue("untitled:x").text)
        assertFailsWith<IllegalArgumentException> { store.change("untitled:x", 1, listOf(Change(null, "a"))) }
        store.close("untitled:x")
        assertTrue(store.snapshot().overlays.isEmpty())
    }
}
