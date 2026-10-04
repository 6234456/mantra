package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.ExplainStep
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceDisplayTest {
    @Test
    fun `missing trace summaries are visible without changing legitimate nil or rendered values`() {
        val schema = Mantra.loadSchema(
            SourceText("schema.mantra", "(schema test/display (line alpha \"Alpha\" 10))"),
            SourceResolver { _, _ -> null },
        )
        val view = CalculationView.of(Mantra.calculate(schema))
        val location = SourceLocation("schema.mantra", 1, 1)
        val trace = ExplainTrace(
            listOf(
                ExplainStep("missing", null, location),
                ExplainStep("nil", Value.Nil, location),
                ExplainStep("redacted", null, location, "[redacted]"),
            ),
            emptyList(),
            false,
        )
        val document = WorkbenchDocuments.explain(view, Render.defaultLayout(view), "alpha", emptyList(), trace)
        val steps = (document.getValue("steps") as List<*>).map { it as Map<*, *> }
        assertEquals("value unavailable", steps[0]["display"])
        assertEquals("", steps[1]["display"])
        assertEquals("[redacted]", steps[2]["display"])
        assertEquals(null, steps[0]["value"])
        assertEquals(null, steps[1]["value"])
    }
}
