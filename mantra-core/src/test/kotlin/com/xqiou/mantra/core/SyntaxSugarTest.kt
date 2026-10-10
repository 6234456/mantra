package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.ChoiceItem
import com.xqiou.mantra.core.model.ChoiceRule
import com.xqiou.mantra.core.model.LineItem
import com.xqiou.mantra.core.model.Op
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyntaxSugarTest {
    private fun schema(text: String) = Mantra.loadSchema(
        SourceText("sugar.mantra", text),
        SourceResolver { _, _ -> null },
    )

    @Test
    fun `signed and informational aliases retain values checkpoints and contribution traces`() {
        val sugar = """
            (schema test/signed
              (section main "Main"
                (line income "Income" 100)
                (subtract discount "Discount" 12.345 {:round 2 :reference "ledger"})
                (info explanation "Explanation" (* discount 10))
                (total subtotal "Subtotal")
                (subtract credit "Negative credit" -5 {:op :minus})
                (total final "Final")))
        """.trimIndent()
        val canonical = sugar.replace("(subtract discount", "(line discount")
            .replace(":round 2 :reference", ":op :minus :round 2 :reference")
            .replace(
                "(info explanation \"Explanation\" (* discount 10))",
                "(line explanation \"Explanation\" (* discount 10) {:op :info})",
            )
            .replace("(subtract credit", "(line credit")
        val actual = Mantra.calculate(schema(sugar))
        val expected = Mantra.calculate(schema(canonical))
        assertTrue(actual.succeeded, actual.diagnostics.toString())
        for (id in listOf("income", "discount", "explanation", "subtotal", "credit", "final")) {
            assertEquals(expected.value(id), actual.value(id), id)
            assertEquals(expected.node(id).trace(), actual.node(id).trace(), id)
        }
        assertEquals(Value.num("12.35"), actual.value("discount"))
        assertEquals(Value.num("123.50"), actual.value("explanation"))
        assertEquals(Value.num("87.65"), actual.value("subtotal"))
        assertEquals(Value.num("-5"), actual.value("credit"))
        assertEquals(Value.num("92.65"), actual.value("final"))
        val parts = assertIs<NodeTrace.Sum>(actual.node("subtotal").trace()).parts
        assertFalse(parts.any { it.id == "explanation" })
        val discount = (actual.schema.root.children.single() as SectionItem).children.filterIsInstance<LineItem>()
            .single { it.id == "discount" }
        assertEquals(Op.MINUS, discount.op)
        assertEquals("ledger", discount.presentation.reference)
    }

    @Test
    fun `aliases preserve dimension inheritance replacement and inherited applicability`() {
        val source = """
            (schema test/dimensions
              (dimension member {:members [:A :B]})
              (input enabled :boolean {:per member})
              (input source-amount :decimal {:per member})
              (section main "Main" {:per member :when enabled}
                (subtract discount "Discount" (* source-amount 0.1) {:round 2})
                (info global "Global" 9 {:per [] :when true})
                (total net "Net")))
        """.trimIndent()
        val canonical = source.replace("(subtract discount", "(line discount")
            .replace("{:round 2}", "{:op :minus :round 2}")
            .replace("(info global", "(line global")
            .replace("{:per [] :when true}", "{:op :info :per [] :when true}")
        val facts = Mantra.loadCase(
            SourceText(
                "case.mantra",
                "(case c (inputs {:enabled {:A true :B false} :source-amount {:A 12.345 :B 50}}))",
            ),
        )
        val actual = Mantra.calculate(schema(source), facts)
        val expected = Mantra.calculate(schema(canonical), facts)
        assertTrue(actual.succeeded, actual.diagnostics.toString())
        for (id in listOf("discount", "net")) {
            for (coord in listOf("A", "B")) {
                assertEquals(expected.value(id, coord), actual.value(id, coord))
                assertEquals(expected.node(id).trace(listOf(coord)), actual.node(id).trace(listOf(coord)))
            }
        }
        assertEquals(Value.num("1.23"), actual.value("discount", "A"))
        assertIs<NodeTrace.Inactive>(actual.node("discount").trace(listOf("B")))
        assertEquals(expected.value("global"), actual.value("global"))
    }

    @Test
    fun `choice aliases use unchanged applicability ties rounding and selected option traces`() {
        for ((head, rule) in listOf("choose-min" to "min", "choose-max" to "max")) {
            val sugar = """
                (schema test/choices
                  (section main "Main"
                    ($head selected "Selected" {:round 2 :op :info}
                      (option :first "First" 4.125)
                      (option :tie "Tie" 4.125)
                      (option :other "Other" 7.999)
                      (option :excluded "Excluded" -100 {:when false}))
                    (total result "Result")))
            """.trimIndent()
            val canonical = sugar.replace("($head selected", "(choice selected")
                .replace("{:round 2 :op :info}", "{:rule :$rule :round 2 :op :info}")
            val actual = Mantra.calculate(schema(sugar))
            val expected = Mantra.calculate(schema(canonical))
            assertTrue(actual.succeeded, actual.diagnostics.toString())
            assertEquals(expected.value("selected"), actual.value("selected"))
            assertEquals(expected.node("selected").trace(), actual.node("selected").trace())
            assertEquals(Value.num(0), actual.value("result"))
            val trace = assertIs<NodeTrace.Choice>(actual.node("selected").trace())
            assertEquals(if (rule == "min") "first" else "other", trace.selected)
            assertEquals(if (rule == "min") Value.num("4.13") else Value.num("8.00"), actual.value("selected"))
            val choice = (actual.schema.root.children.single() as SectionItem).children
                .filterIsInstance<ChoiceItem>().single()
            assertEquals(if (rule == "min") ChoiceRule.MIN else ChoiceRule.MAX, choice.rule)
        }
        val explicit = schema(
            "(schema test/same (choose-min selected \"Selected\" {:rule :min} " +
                "(option :a \"A\" 1) (option :b \"B\" 2)))",
        )
        assertEquals(Value.num(1), Mantra.calculate(explicit).value("selected"))
    }

    @Test
    fun `conflicting operations and rules reject at the authored option value`() {
        for ((body, option, code) in listOf(
            Triple("(subtract x \"X\" 1 {:op :plus})", ":plus", "MANTRA-SCHEMA-OP"),
            Triple("(info x \"X\" 1 {:op :minus})", ":minus", "MANTRA-SCHEMA-OP"),
            Triple(
                "(choose-min x \"X\" {:rule :max} (option :a \"A\" 1) (option :b \"B\" 2))",
                ":max",
                "MANTRA-CHOICE-RULE",
            ),
            Triple(
                "(choose-max x \"X\" {:rule :min} (option :a \"A\" 1) (option :b \"B\" 2))",
                ":min",
                "MANTRA-CHOICE-RULE",
            ),
        )) {
            val source = "(schema test/conflict\r\n  $body)"
            val failure = assertFailsWith<MantraException> { schema(source) }
            val diagnostic = failure.diagnostics.single { it.code == code }
            val location = assertNotNull(diagnostic.location)
            assertEquals(source.indexOf(option), location.startOffset)
            assertEquals(source.indexOf(option) + option.length, location.endOffset)
            assertEquals("sugar.mantra", location.source)
        }
    }

    @Test
    fun `source slices explain locations and extension ownership remain authored`() {
        val text = "(schema test/source\r\n  (info amount \"金额\"\r\n    (+ 1 2) {:op :info})\r\n" +
            "  (slot custom \"Custom\"))"
        val loaded = schema(text)
        val line = loaded.root.children.filterIsInstance<LineItem>().single()
        assertEquals("(+ 1 2)", line.formula.source)
        assertEquals(text.indexOf("(+ 1 2)"), line.formula.location.startOffset)
        assertEquals(3, line.formula.location.line)
        val audit = Mantra.calculateForAudit(loaded)
        val explanation = assertNotNull(assertIs<NodeTrace.Computed>(audit.node("amount").trace()).explanation)
        val step = explanation.steps.last()
        assertEquals("(+ 1 2)", step.text)
        assertEquals(line.formula.location, step.location)
        val caseText = "(case c (extend custom (subtract adjustment \"Adjustment\" 4) " +
            "(choose-max maximum \"Maximum\" (option :a \"A\" 1) (option :b \"B\" 2))))"
        val facts = Mantra.loadCase(SourceText("extension.mantra", caseText))
        val extension = facts.extensions.getValue("custom")
        assertTrue(extension.filterIsInstance<LineItem>().single().userDefined)
        assertTrue(extension.filterIsInstance<ChoiceItem>().single().userDefined)
        assertEquals("extension.mantra", extension.filterIsInstance<LineItem>().single().formula.location.source)
        val calculated = Mantra.calculate(loaded, facts)
        assertTrue(calculated.succeeded, calculated.diagnostics.toString())
        assertEquals(Value.num(4), calculated.value("adjustment"))
        assertEquals(Value.num(2), calculated.value("maximum"))
    }

    @Test
    fun `alias formula compilation errors retain the original CRLF document coordinate`() {
        val source = "(schema test/failure\r\n  (subtract amount \"金额\" (+ missing-root 1)))"
        val failure = assertFailsWith<MantraException> { Mantra.calculate(schema(source)) }
        val diagnostic = failure.diagnostics.first { it.message.contains("missing-root") }
        val location = assertNotNull(diagnostic.location)
        assertEquals("sugar.mantra", location.source)
        assertEquals(2, location.line)
        assertEquals(source.indexOf("missing-root"), location.startOffset)
        assertEquals(source.indexOf("missing-root") + "missing-root".length, location.endOffset)
    }
}
