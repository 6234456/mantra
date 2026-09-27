package com.xqiou.mantra.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class QualifiedTest {
    @Test
    fun `rewrites only symbol references and keeps source offsets`() {
        val source = """(list mantra/amount "mantra/text" :mantra/key ; mantra/comment
            #"mantra/regex" [mantra/other])"""
        val rewritten = Qualified.rewrite(source)
        assertEquals(
            """(list mantra_amount "mantra/text" :mantra/key ; mantra/comment
            #"mantra/regex" [mantra_other])""",
            rewritten,
        )
        assertEquals(source.length, rewritten.length)
    }

    @Test
    fun `rewrites qualified names in unfinished editor text without touching literals`() {
        val source = """(+ mantra/amount "mantra/text" ; mantra/comment
            mantra/other"""
        assertEquals(
            """(+ mantra_amount "mantra/text" ; mantra/comment
            mantra_other""",
            Qualified.rewrite(source),
        )
    }
}
