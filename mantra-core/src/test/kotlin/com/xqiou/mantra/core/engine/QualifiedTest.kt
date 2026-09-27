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
}
