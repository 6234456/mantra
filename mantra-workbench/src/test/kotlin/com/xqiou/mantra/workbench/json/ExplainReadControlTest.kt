package com.xqiou.mantra.workbench.json

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.Render
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExplainReadControlTest {
    @Test fun `ordinary structured Explain charges recursive values in its caller read epoch`() {
        val schema = Mantra.loadSchema(
            SourceText(
                "schema.mantra",
                """
            (schema test/structured
              (input rows :table {:columns {:value :decimal}})
              (input seed :decimal))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val supplied = Mantra.loadCase(SourceText("case.mantra", "(case example {:schema \"test/structured\"})"))
            .copy(
                inputs = mapOf(
                    "rows" to Value.Vec(
                        List(100) {
                            Value.MapV(mapOf(Value.Kw("value") to Value.num(it.toString())))
                        },
                    ),
                    "seed" to Value.num("7"),
                ),
            )
        val view = Mantra.calculate(schema, supplied).view
        val layout = Render.defaultLayout(view)
        view.openReader(CalculationOptions(limits = RunLimits(maxHostScans = 20))).use { reader ->
            val scalar = WorkbenchDocuments.explain(view, layout, "seed", emptyList(), null, reader = reader)
            assertEquals(mapOf("n" to "7"), (scalar["result"] as Map<*, *>)["value"])
            assertTrue(reader.usage[RunCounter.HOST_SCANS] > 0)
            val failed = assertFailsWith<MantraException> {
                WorkbenchDocuments.explain(view, layout, "rows", emptyList(), null, reader = reader)
            }
            assertEquals("MANTRA-RUN-LIMIT", failed.diagnostics.single().code)
            assertEquals(RunCounter.HOST_SCANS, failed.runFailure?.counter)
        }
        assertEquals(100, (view.node("rows").value(emptyList()) as Value.Vec).items.size)
    }
}
