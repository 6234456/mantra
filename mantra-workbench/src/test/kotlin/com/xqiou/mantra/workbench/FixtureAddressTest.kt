package com.xqiou.mantra.workbench

import kotlin.test.Test
import kotlin.test.assertEquals

class FixtureAddressTest {
    @Test
    fun `projected node fixture keys match browser canonical addresses`() {
        assertEquals("aggregate%2Erate", Fixtures.addressPath(ExplainAddress("aggregate.rate")))
        assertEquals(
            "all%2Eamount@period%3D2025",
            Fixtures.addressPath(ExplainAddress("all.amount", listOf("period=2025"))),
        )
    }

    @Test
    fun `fixture components encode reserved punctuation without losing dimension boundaries`() {
        assertEquals(
            "amount@member%20%2F%2E%2A%27%28%29/B%2FC/%C3%A4",
            Fixtures.addressPath(ExplainAddress("amount", listOf("member /.*'()", "B/C", "ä"))),
        )
    }
}
