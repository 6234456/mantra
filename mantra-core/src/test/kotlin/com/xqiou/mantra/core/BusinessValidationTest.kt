package com.xqiou.mantra.core

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessValidationTest {
    private fun schema(text: String) = Mantra.loadSchema(
        SourceText("schema.mantra", text.trimIndent()),
        SourceResolver { _, _ -> null },
    )

    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text.trimIndent()))

    private fun decimal(expected: String, actual: BigDecimal?) {
        assertEquals(0, BigDecimal(expected).compareTo(checkNotNull(actual)))
    }

    @Test
    fun `business errors do not change values or calculation success`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/check
                  (line amount "Amount" 100)
                  (check positive "Positive amount" (> mantra/amount 200))
                  (total result "Result"))
                """,
            ),
        )
        assertTrue(result.succeeded)
        assertFalse(result.validationPassed)
        decimal("100", result.decimal("result"))
        assertEquals(Value.Bool(false), result.value("positive"))
        assertEquals(
            "MANTRA-CHECK-FAILED",
            result.diagnostics.single {
                it.category == DiagnosticCategory.BUSINESS
            }.code,
        )
        assertEquals("positive", result.diagnostics.single { it.category == DiagnosticCategory.BUSINESS }.nodeId)
    }

    @Test
    fun `warning checks retain successful business validation`() {
        val result = Mantra.calculate(
            schema("""(schema test/warning (check caution "Caution" false {:severity :warning}))"""),
        )
        assertTrue(result.succeeded)
        assertTrue(result.validationPassed)
        assertEquals(Severity.WARNING, result.diagnostics.single().severity)
    }

    @Test
    fun `checks inherit dimension and section conditions`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/check-dims
                  (dimension member {:members [:A :B]})
                  (input amount :decimal {:per member})
                  (section selected "Selected" {:per member :when (= member.key :A)}
                    (check nonnegative "Nonnegative" (>= mantra/amount 0))))
                """,
            ),
            case("(case c (inputs {:amount {:A -1 :B -2}}))"),
        )
        assertTrue(result.succeeded)
        assertEquals(listOf("A"), result.diagnostics.single { it.category == DiagnosticCategory.BUSINESS }.coord)
        assertTrue(result.node("nonnegative").isActive(listOf("A")))
        assertFalse(result.node("nonnegative").isActive(listOf("B")))
    }

    @Test
    fun `reconciliation keeps both sides and includes the tolerance boundary`() {
        val template = schema(
            """
            (schema test/reconcile
              (input observed :decimal)
              (reconcile match "Match" observed 100 {:tolerance 0.01}))
            """,
        )
        val boundary = Mantra.calculate(template, case("(case c (inputs {:observed 100.01}))"))
        assertTrue(boundary.validationPassed)
        decimal("0.01", boundary.decimal("match"))
        val outside = Mantra.calculate(template, case("(case c (inputs {:observed 100.0100000001}))"))
        assertTrue(outside.succeeded)
        assertFalse(outside.validationPassed)
        decimal("0.0100000001", outside.decimal("match"))
        assertEquals("MANTRA-RECONCILE-FAILED", outside.diagnostics.single().code)
    }

    @Test
    fun `conditional required accepts explicit zero and reports implicit or default values`() {
        val template = schema(
            """
            (schema test/required
              (input enabled :boolean {:default true})
              (input supplied :decimal {:default 0 :required-when enabled})
              (line answer "Answer" (+ supplied 10)))
            """,
        )
        val missing = Mantra.calculate(template, case("(case c)"))
        assertTrue(missing.succeeded)
        assertFalse(missing.validationPassed)
        decimal("10", missing.decimal("answer"))
        assertEquals("MANTRA-INPUT-REQUIRED", missing.diagnostics.single().code)
        assertTrue(Mantra.calculate(template, case("(case c (inputs {:supplied 0}))")).validationPassed)
        assertTrue(Mantra.calculate(template, case("(case c (inputs {:enabled false}))")).validationPassed)
    }

    @Test
    fun `conditional required may depend on a downstream derived value without a false cycle`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/required-downstream
                  (input supplied :decimal {:required-when (> derived 0)})
                  (line derived "Derived" (+ supplied 1)))
                """,
            ),
        )
        assertTrue(result.succeeded)
        assertFalse(result.validationPassed)
        decimal("1", result.decimal("derived"))
    }

    @Test
    fun `table minimum rows is business validation and keeps the empty table usable`() {
        val result = Mantra.calculate(
            schema(
                """
                (schema test/minimum
                  (input rows :table {:columns {:amount :decimal} :min-rows 1})
                  (line answer "Answer" (sum (map (fn [row] row.amount) rows))))
                """,
            ),
        )
        assertTrue(result.succeeded)
        assertFalse(result.validationPassed)
        decimal("0", result.decimal("answer"))
        assertEquals("MANTRA-INPUT-MIN-ROWS", result.diagnostics.single().code)
    }

    @Test
    fun `conditional table columns keep row coordinates and point at the missing value`() {
        val source = """
            (case c (inputs {:rows [{:mode :pooled :target nil :amount 2}
                                   {:mode :direct :target nil :amount 3}]}))
        """.trimIndent()
        val result = Mantra.calculate(
            schema(
                """
                (schema test/column-required
                  (input rows :table
                    {:columns {:mode :keyword
                               :target {:type :keyword? :required-when (= row.mode :direct)}
                               :amount :decimal}})
                  (line answer "Answer" (sum (map (fn [row] row.amount) rows))))
                """,
            ),
            case(source),
        )
        assertTrue(result.succeeded)
        assertFalse(result.validationPassed)
        decimal("5", result.decimal("answer"))
        val finding = result.diagnostics.single()
        assertEquals("rows", finding.nodeId)
        assertEquals(1, finding.rowIndex)
        assertEquals("target", finding.column)
        assertEquals("nil", source.substring(finding.location!!.startOffset!!, finding.location!!.endOffset!!))
    }

    @Test
    fun `input ranges are business checks while input types stay execution failures`() {
        val template =
            schema("""(schema test/input (input amount :decimal {:min 0}) (line answer "Answer" mantra/amount))""")
        val negative = Mantra.calculate(template, case("(case c (inputs {:amount -1}))"))
        assertTrue(negative.succeeded)
        assertFalse(negative.validationPassed)
        decimal("-1", negative.decimal("answer"))
        val wrongType = Mantra.calculate(template, case("""(case c (inputs {:amount "wrong"}))"""))
        assertFalse(wrongType.succeeded)
        assertTrue(wrongType.diagnostics.any { it.code == "MANTRA-INPUT-TYPE" })
    }

    @Test
    fun `checks require boolean expressions and cannot become numeric dependencies`() {
        assertFailsWith<MantraException> {
            Mantra.calculate(schema("""(schema test/type (check check-value "Check" 1))"""))
        }
        assertFailsWith<MantraException> {
            Mantra.calculate(
                schema(
                    """(schema test/reference (check check-value "Check" true) (line answer "Answer" check-value))""",
                ),
            )
        }
    }

    @Test
    fun `invalid reconciliation tolerance and non-table minimum rows are rejected`() {
        assertFailsWith<MantraException> {
            schema("""(schema test/tolerance (reconcile check-value "Check" 1 1 {:tolerance -0.1}))""")
        }
        assertFailsWith<MantraException> {
            schema("(schema test/rows (input amount :decimal {:min-rows 1}))")
        }
    }

    @Test
    fun `explicit non-keyword validation severity cannot silently use the default`() {
        for (value in listOf("\"warning\"", "false", "nil", "1", ":info")) {
            for (node in listOf("(check check-value \"Check\" true", "(reconcile check-value \"Check\" 1 1")) {
                val failure =
                    assertFailsWith<MantraException> { schema("(schema test/severity $node {:severity $value}))") }
                assertTrue(
                    failure.diagnostics.any {
                        it.code == "MANTRA-CHECK-SEVERITY" &&
                            it.category == DiagnosticCategory.STRUCTURAL
                    },
                )
            }
        }
    }
}
