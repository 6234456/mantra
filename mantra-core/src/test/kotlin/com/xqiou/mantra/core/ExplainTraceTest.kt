package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExplainTraceTest {
    @Test fun `full trace identifies the selected if branch`() {
        val schema = Mantra.loadSchema(SourceText("choice.mantra", """
            (schema app/branch {:mainline [main]}
              (section main "Main" {:panel true} (line selected "Selected" (if flag (+ 4 6) 20)))
              (input flag :boolean))
        """.trimIndent()), SourceResolver { _, _ -> null })
        val case = Mantra.loadCase(SourceText("case.mantra", "(case one {:schema \"app/branch\"} (inputs {:flag true}))"))
        val result = Mantra.calculateForExplain(schema, case, emptyList(), "selected")
        assertEquals(Value.Num(BigDecimal.TEN), result.value("selected"))
        assertTrue(assertNotNull(result.explainTrace).branches.any { it.text == "(+ 4 6)" && it.selected })
    }

    @Test fun `one selected ESt value captures bounded source steps without changing its result`() {
        val directory = Path.of("apps/de-est")
        val schema = Mantra.loadSchema(directory.resolve("schema.mantra"))
        val case = Mantra.loadCase(directory.resolve("case-mustermann.mantra"))
        val ordinary = Mantra.calculate(schema, case)
        val explained = Mantra.calculateForExplain(schema, case, emptyList(), "ermaessigung-35a")
        assertEquals(ordinary.value("ermaessigung-35a"), explained.value("ermaessigung-35a"))
        assertEquals(Value.Num(BigDecimal("740.0")), explained.value("ermaessigung-35a"))
        val trace = assertNotNull(explained.explainTrace)
        assertFalse(trace.truncated)
        assertTrue(trace.steps.isNotEmpty())
        assertTrue(trace.steps.any { it.value == Value.Num(BigDecimal("240.0")) && it.text.contains("haushaltsnahe-dienstleistungen") })
        assertTrue(trace.steps.any { it.value == Value.Num(BigDecimal("500.0")) && it.text.contains("handwerkerleistungen") })
        assertTrue(trace.steps.all { it.location.source.endsWith("schema.mantra") && it.location.startOffset != null })
        val inner = trace.steps.indexOfFirst { it.text.startsWith("(- tarifliche-est") }
        val outer = trace.steps.indexOfFirst { it.text.startsWith("(max 0") }
        assertTrue(inner >= 0 && outer > inner)
    }

    @Test fun `named tariff function reports the executed cond branch and child first steps`() {
        val directory = Path.of("apps/de-est")
        val result = Mantra.calculateForExplain(Mantra.loadSchema(directory.resolve("schema.mantra")),
            Mantra.loadCase(directory.resolve("case-mustermann.mantra")), emptyList(), "tarifliche-est")
        val trace = assertNotNull(result.explainTrace)
        assertTrue(trace.steps.any { it.location.line == 86 && it.text.startsWith("(let [z ") })
        assertTrue(trace.branches.any { it.location.line == 86 && it.text.startsWith("(let [z ") })
        assertFalse(trace.branches.any { it.text.trimStart().startsWith("(cond") })
        val inner = trace.steps.indexOfFirst { it.text == "(- x tarif-g2)" }
        val outer = trace.steps.indexOfFirst { it.text == "(/ (- x tarif-g2) 10000)" }
        assertTrue(inner >= 0 && outer > inner)
    }
}
