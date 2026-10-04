package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatioAggregationTest {
    private fun schema(text: String) = Mantra.loadSchema(
        SourceText("ratio.mantra", text.trimIndent()),
        SourceResolver { _, _ -> null },
    )

    private fun case(text: String) = Mantra.loadCase(SourceText("ratio-case.mantra", text))

    private fun decimal(expected: String, actual: BigDecimal?) {
        assertEquals(0, BigDecimal(expected).compareTo(checkNotNull(actual)))
    }

    private fun template(options: String = ":round [8 :half-up]", rateOptions: String = ":op :info") = schema(
        """
        (schema test/ratio
          (dimension member {:members [:A :B]})
          (input charge :decimal {:per member})
          (input units :decimal {:per member})
          (section detail "Detail" {:per member}
            (line ratio "Ratio" (if (zero? units) nil (decimal/divide charge units 8))
              {$rateOptions :aggregate {:ratio [charge units] $options}})))
        """,
    )

    @Test
    fun `weighted ratio sums components and preserves individual member formulas`() {
        val result = Mantra.calculate(
            template(),
            case("(case c (inputs {:charge {:A 65400 :B 14400} :units {:A 240000 :B 80000}}))"),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        decimal("0.2725", result.decimal("ratio", "A"))
        decimal("0.18", result.decimal("ratio", "B"))
        decimal("0.249375", result.node("ratio").crossTotal())
        assertEquals(8, result.node("ratio").crossTotal()!!.scale())
    }

    @Test
    fun `ratio aggregate respects its own active member mask`() {
        val result = Mantra.calculate(
            template(rateOptions = ":op :info :when (= member.key :A)"),
            case("(case c (inputs {:charge {:A 20 :B 800} :units {:A 100 :B 100}}))"),
        )
        assertTrue(result.succeeded)
        decimal("0.2", result.node("ratio").crossTotal())
        assertFalse(result.node("ratio").isActive(listOf("B")))
    }

    @Test
    fun `zero denominator is undefined with a business warning`() {
        val result = Mantra.calculate(template(), case("(case c (inputs {:charge {:A 0 :B 0} :units {:A 0 :B 0}}))"))
        assertTrue(result.succeeded)
        assertTrue(result.validationPassed)
        assertNull(result.node("ratio").crossTotal())
        assertEquals(Value.Nil, result.value("ratio", "A"))
        assertEquals("MANTRA-AGGREGATE-ZERO-DENOMINATOR", result.diagnostics.single().code)
        assertEquals(DiagnosticCategory.BUSINESS, result.diagnostics.single().category)
        assertEquals(Severity.WARNING, result.diagnostics.single().severity)
    }

    @Test
    fun `strict aggregation permits finite division and rejects implicit rounding`() {
        val finite = Mantra.calculate(
            template(options = ""),
            case("(case c (inputs {:charge {:A 1 :B 1} :units {:A 4 :B 4}}))"),
        )
        assertTrue(finite.succeeded)
        decimal("0.25", finite.node("ratio").crossTotal())
        val repeating = Mantra.calculate(
            template(options = ""),
            case("(case c (inputs {:charge {:A 1 :B 1} :units {:A 3 :B 3}}))"),
        )
        assertFalse(repeating.succeeded)
        assertNull(repeating.node("ratio").crossTotal())
        assertEquals("MANTRA-AGGREGATE-DIVISION", repeating.diagnostics.single().code)
        assertEquals(DiagnosticCategory.EVALUATION, repeating.diagnostics.single().category)
    }

    @Test
    fun `ratios cross foot into totals using the component rule`() {
        val model = template(rateOptions = ":op :plus")
        val expanded = model.copy(
            root = model.root.copy(
                children = model.root.children + schema(
                    """(schema test/checkpoint (total checkpoint "Checkpoint"))""",
                ).root.children,
            ),
        )
        val result = Mantra.calculate(
            expanded,
            case("(case c (inputs {:charge {:A 20 :B 90} :units {:A 100 :B 300}}))"),
        )
        assertTrue(result.succeeded)
        decimal("0.275", result.decimal("checkpoint"))
        val total = assertIs<NodeTrace.Sum>(result.node("checkpoint").trace())
        decimal("0.275", total.parts.single().value)
    }

    @Test
    fun `undefined ratio contribution propagates through subsequent checkpoints`() {
        val base = template(rateOptions = ":op :plus")
        val extra =
            schema(
                """(schema test/checkpoints (total checkpoint "Checkpoint") (line addend "Addend" 7) (total final-value "Final"))""",
            )
        val result = Mantra.calculate(
            base.copy(
                root = base.root.copy(
                    children =
                    base.root.children + extra.root.children,
                ),
            ),
            case("(case c (inputs {:charge {:A 0 :B 0} :units {:A 0 :B 0}}))"),
        )
        assertTrue(result.succeeded)
        assertEquals(Value.Nil, result.value("checkpoint"))
        assertEquals(Value.Nil, result.value("final-value"))
        assertNull(result.node("checkpoint").crossTotal())
        assertNull(assertIs<NodeTrace.Sum>(result.node("checkpoint").trace()).parts.single().value)
    }

    @Test
    fun `ratio sources must be numeric and share the measure dimensions`() {
        val mismatch = schema(
            """
            (schema test/mismatch
              (dimension member {:members [:A]})
              (input charge :decimal {:per member})
              (input units :decimal)
              (line ratio "Ratio" 1 {:per member :aggregate {:ratio [charge units]}}))
            """,
        )
        val failure = assertFailsWith<MantraException> { Mantra.calculate(mismatch) }
        assertTrue(failure.diagnostics.any { it.code == "MANTRA-AGGREGATE-REFERENCE" })
        val wrongType = schema(
            """
            (schema test/wrong-type (input charge :text) (input units :decimal)
              (line ratio "Ratio" 1 {:aggregate {:ratio [charge units]}}))
            """,
        )
        assertFailsWith<MantraException> { Mantra.calculate(wrongType) }
    }

    @Test
    fun `partial cross footing aligns dimensions and keeps the ratio active mask`() {
        val result = Mantra.calculate(
            schema(
                """
            (schema test/partial-ratio
              (dimension bucket {:members [:X :Y]})
              (dimension member {:members [:A :B]})
              (input charge :decimal {:per [bucket member]})
              (input units :decimal {:per [bucket member]})
              (section grouped "Grouped" {:per bucket}
                (section detail "Detail" {:per [bucket member]}
                  (line ratio "Ratio" (decimal/divide charge units 8)
                    {:when (= member.key :A) :aggregate {:ratio [charge units] :round [8 :half-up]}}))
                (total checkpoint "Checkpoint")))
            """,
            ),
            case(
                "(case c (inputs {:charge {:X {:A 20 :B 30} :Y {:A 70 :B 10}} :units {:X {:A 100 :B 100} :Y {:A 100 :B 300}}}))",
            ),
        )
        assertTrue(result.succeeded, result.diagnostics.toString())
        decimal("0.2", result.decimal("checkpoint", "X"))
        decimal("0.7", result.decimal("checkpoint", "Y"))
        decimal("0.45", result.node("ratio").crossTotal())
    }
}
