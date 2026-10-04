package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.FormulaAuthoring
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.normein.dsl.authoring.DslCompletionItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FormulaAuthoringPeriodTest {
    private fun authoring(extra: String = "", formula: String = "(prev closing seed)"): FormulaAuthoring {
        val schema = Mantra.loadSchema(
            SourceText(
                "period.mantra",
                """
            (schema test/authoring
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 3}})
              (input seed :decimal)
              $extra
              (formula-slot closing "Closing" $formula {:per year :uses [closing seed]}))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        return FormulaAuthoring.forFormulaSlot(schema, CaseData.empty(), emptyList(), "closing")
    }

    @Test
    fun `period formula checks and documentation share host prev semantics without exposing implementation`() {
        val tooling = authoring()
        assertTrue(tooling.check("(prev mantra/closing (+ seed 2))").isEmpty())
        assertTrue(
            tooling.complete("(pre", 4).items.any {
                it.kind == DslCompletionItemKind.FUNCTION &&
                    it.label == "prev"
            },
        )
        val hover = assertNotNull(tooling.hover("(prev closing seed)", 2))
        assertEquals("prev", hover.symbol)
        assertTrue(hover.documentation.orEmpty().contains("first period"))
        assertFalse(tooling.complete("(mantra-internal/", 17).items.any { it.label.startsWith("mantra-internal/") })
    }

    @Test
    fun `invalid previous targets and arity retain editable source spans`() {
        val tooling = authoring()
        val invalid = tooling.check("(prev seed 3)")
        assertEquals("DSL-MANTRA-PREV", invalid.single().code)
        assertNotNull(invalid.single().span)
        assertTrue(invalid.single().message.contains("shared continuous period"))
        assertEquals("DSL-MANTRA-PREV", tooling.check("(prev closing)").single().code)
        assertTrue(tooling.check("(prev closing \"wrong type\")").isNotEmpty())
    }

    @Test
    fun `named fallback definitions and lexical or named prev shadowing remain valid while editing`() {
        val namedFallback = authoring("(defn carry [] (prev closing (+ seed 1)))", "(carry)")
        assertTrue(namedFallback.check("(carry)").isEmpty())
        assertTrue(namedFallback.check("(let [prev (fn [^Decimal x] (+ x 1))] (prev 4))").isEmpty())
        val namedShadow = authoring("(defn prev [^Decimal x] (+ x 2))", "(prev seed)")
        assertTrue(namedShadow.check("(prev seed)").isEmpty())
        assertFalse(namedShadow.complete("(pre", 4).items.any { it.label == "prev" })
    }
}
