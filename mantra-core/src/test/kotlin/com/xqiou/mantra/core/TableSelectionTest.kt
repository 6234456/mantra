package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.FormulaAuthoring
import com.xqiou.mantra.core.api.FunctionCatalog
import com.xqiou.mantra.core.engine.LibraryWork
import com.xqiou.mantra.core.engine.TableSelection
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.normein.dsl.library.DslFunctionInvocationException
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TableSelectionTest {
    private fun schema(body: String) = Mantra.loadSchema(
        SourceText("selection.mantra", "(schema test/selection {} $body)"),
        SourceResolver { _, _ -> null },
    )

    private fun kw(value: String) = DslValues.keyword(null, value)
    private fun record(vararg entries: Pair<String, DslValue>) =
        DslValues.map(entries.map { kw(it.first) to it.second })
    private val none = LibraryWork.NONE

    @Test
    fun `recognized movements and energy readings match independent arithmetic and expanded valid formulas`() {
        // Locked independent arithmetic: movement = 1000 - 250 + 1000 = 1750;
        // selected energy = 12.125 + 7.875 + 12.125 = 32.125; count = 3 each.
        val model = schema(
            """
            (param movements [{:item :Plant :period :P1 :amount 1000}
                              {:item :Plant :period :P1 :amount -250}
                              {:item :Plant :period :P2 :amount 800}
                              {:item :Plant :period :P1 :amount 1000}])
            (param readings [{:meter :North :period :P1 :amount 12.125}
                             {:meter :South :period :P1 :amount 80}
                             {:meter :North :period :P1 :amount 7.875}
                             {:meter :North :period :P1 :amount 12.125}])
            (line movement "Movement" (table/sum-where movements {:item :Plant :period :P1} :amount) {:op :info})
            (line movement-old "Movement expanded"
              (sum (map (fn [row] (if (and (= row.item :Plant) (= row.period :P1)) row.amount 0)) movements))
              {:op :info})
            (line energy "Energy" (table/sum-where readings {:meter :North :period :P1} :amount) {:op :info})
            (line energy-old "Energy expanded"
              (sum (map (fn [row] (if (and (= row.meter :North) (= row.period :P1)) row.amount 0)) readings))
              {:op :info})
            (line movement-count "Movement count" (table/count-where movements {:item :Plant :period :P1})
              {:op :info})
            (line energy-count "Energy count" (table/count-where readings {:meter :North :period :P1})
              {:op :info})
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(1750), result.value("movement"))
        assertEquals(Value.num("32.125"), result.value("energy"))
        assertEquals(result.value("movement-old"), result.value("movement"))
        assertEquals(result.value("energy-old"), result.value("energy"))
        assertEquals(Value.num(3), result.value("movement-count"))
        assertEquals(Value.num(3), result.value("energy-count"))
        val plain = Mantra.calculate(model)
        listOf("movement", "energy", "movement-count", "energy-count").forEach {
            assertEquals(result.value(it), plain.value(it))
        }
        val trace = assertNotNull((result.node("energy").trace() as NodeTrace.Computed).explanation)
        assertEquals("(table/sum-where readings {:meter :North :period :P1} :amount)", trace.steps.last().text)
        assertEquals(result.value("energy"), trace.steps.last().value)
        assertEquals("selection.mantra", trace.steps.last().location.source)
        assertNotNull(trace.steps.last().eventId)
    }

    @Test
    fun `typed predicates distinguish missing nil empty text zero false text and keyword`() {
        val model = schema(
            """
            (param facts [{:key nil} {} {:key ""} {:key false} {:key 0}
                          {:key 1} {:key 1.00} {:key "1"} {:key :One} {:key "One"} {:key "one"}])
            (line nil-count "Nil" (table/count-where facts {:key nil}) {:op :info})
            (line empty-count "Empty" (table/count-where facts {:key ""}) {:op :info})
            (line false-count "False" (table/count-where facts {:key false}) {:op :info})
            (line zero-count "Zero" (table/count-where facts {:key 0}) {:op :info})
            (line number-count "Number" (table/count-where facts {:key 1.0}) {:op :info})
            (line text-count "Text" (table/count-where facts {:key "One"}) {:op :info})
            (line keyword-count "Keyword" (table/count-where facts {:key :One}) {:op :info})
            (line all-count "All" (table/count-where facts {}) {:op :info})
            (line none-count "None" (table/count-where facts {:key :Absent}) {:op :info})
            (line no-amount "No amount inspected"
              (table/sum-where facts {:key :Absent} :amount) {:op :info})
            (line empty-sum "Empty sum" (table/sum-where [] {} :amount) {:op :info})
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(model)
        assertTrue(result.succeeded, result.diagnostics.toString())
        listOf("nil-count", "empty-count", "false-count", "zero-count", "text-count", "keyword-count").forEach {
            assertEquals(Value.num(1), result.value(it), it)
        }
        assertEquals(Value.num(2), result.value("number-count"))
        assertEquals(Value.num(11), result.value("all-count"))
        listOf("none-count", "no-amount", "empty-sum").forEach { assertEquals(Value.num(0), result.value(it), it) }
    }

    @Test
    fun `matched nil missing and text amounts fail instead of adopting permissive standard sum coercion`() {
        listOf("{}", "{:amount nil}", "{:amount \"1.25\"}", "{:amount false}").forEach { row ->
            val result = Mantra.calculate(schema("(line answer \"Answer\" (table/sum-where [$row] {} :amount))"))
            assertFalse(result.succeeded, row)
            assertTrue(
                result.diagnostics.any {
                    "DSL-MANTRA-TABLE-NUMBER" in it.message
                },
                result.diagnostics.toString(),
            )
        }
        val nested = Mantra.calculate(schema("(line answer \"Answer\" (table/count-where [] {:key []}))"))
        assertFalse(nested.succeeded)
        assertTrue(nested.diagnostics.any { "DSL-MANTRA-TABLE-CRITERIA" in it.message })
    }

    @Test
    fun `concrete sequences exact long integer decimal and every record shape are validated`() {
        val rows = DslValues.sequential(
            listOf(
                record("key" to DslValues.long(1), "amount" to DslValues.decimal(BigDecimal("0.0000001"))),
                record("key" to DslValues.integer(1), "amount" to DslValues.decimal(BigDecimal("999.0000002"))),
            ),
        )
        val criteria = record("key" to DslValues.decimal(BigDecimal("1.00")))
        val sum = assertIs<DslValue.DecimalValue>(TableSelection.sum(rows, criteria, kw("amount"), none))
        assertEquals(BigDecimal("999.0000003"), sum.value)
        assertEquals("2", assertIs<DslValue.IntegerValue>(TableSelection.count(rows, criteria, none)).value.toString())
        val malformed = DslValues.vector(
            listOf(record("key" to kw("Other")), DslValues.map(listOf(DslValues.text("key") to kw("Other")))),
        )
        assertFailsWith<DslFunctionInvocationException> { TableSelection.count(malformed, criteria, none) }
        assertFailsWith<DslFunctionInvocationException> { TableSelection.count(DslValue.Nil, criteria, none) }
        assertFailsWith<DslFunctionInvocationException> { TableSelection.count(rows, DslValue.Nil, none) }
        assertFailsWith<DslFunctionInvocationException> {
            TableSelection.sum(rows, criteria, DslValues.text("amount"), none)
        }
    }

    @Test
    fun `typed table queries consume normalized fields and sessions update actual criteria and record values`() {
        val model = schema(
            """
            (input target :keyword {:default :A})
            (input facts :table {:columns {:key :keyword? :amount :decimal}})
            (line selected "Selected" (table/sum-where facts {:key target} :amount) {:op :info})
            (line selected-count "Count" (table/count-where facts {:key target}) {:op :info})
            (line nil-count "Nil count" (table/count-where facts {:key nil}) {:op :info})
            """.trimIndent(),
        )
        val original = Mantra.loadCase(
            SourceText(
                "facts.mantra",
                """(case c (inputs {:facts [{:key :A :amount 10} {:key :B :amount 20}
                                            {:key nil :amount 3} {:amount 9}]}))""",
            ),
        )
        val audit = Mantra.calculateForAudit(model, original)
        assertTrue(audit.succeeded, audit.diagnostics.toString())
        assertEquals(Value.num(10), audit.value("selected"))
        // Existing input conversion fills an omitted optional key with nil before formula evaluation.
        assertEquals(Value.num(2), audit.value("nil-count"))
        Mantra.openSession(model, original).use { session ->
            assertEquals(audit.value("selected"), session.result.value("selected"))
            val changed = original.copy(inputs = original.inputs + ("target" to Value.Kw("B")))
            assertEquals(Value.num(20), session.recalculate(changed).value("selected"))
            val editedRows = assertIs<Value.Vec>(changed.inputs.getValue("facts")).items +
                Value.MapV(mapOf(Value.Kw("key") to Value.Kw("B"), Value.Kw("amount") to Value.num("5.25")))
            val edited = changed.copy(inputs = changed.inputs + ("facts" to Value.Vec(editedRows)))
            val next = session.recalculate(edited)
            assertTrue(next.succeeded, next.diagnostics.toString())
            assertEquals(Value.num("25.25"), next.value("selected"))
            assertEquals(Value.num(2), next.value("selected-count"))
        }
    }

    @Test
    fun `typed table numeric defaults and optional nil follow existing input conversion before selection`() {
        val model = schema(
            """
            (input ordinary :table {:columns {:amount :decimal}})
            (input optional-amounts :table {:columns {:amount :decimal?}})
            (line normalized "Normalized" (table/sum-where ordinary {} :amount) {:op :info})
            (line optional-count "Optional nil" (table/count-where optional-amounts {:amount nil}) {:op :info})
            (line strict "Strict sum" (table/sum-where optional-amounts {} :amount) {:op :info})
            """.trimIndent(),
        )
        val facts = Mantra.loadCase(
            SourceText(
                "defaults.mantra",
                "(case c (inputs {:ordinary [{} {:amount nil}] :optional-amounts [{} {:amount nil}]}))",
            ),
        )
        // Required numeric absence is already zero; optional absence is already nil. The helper
        // neither reconstructs authored presence nor changes those existing normalization rules.
        val result = Mantra.calculate(model, facts)
        assertFalse(result.succeeded)
        assertEquals(Value.num(0), result.value("normalized"))
        assertEquals(Value.num(2), result.value("optional-count"))
        assertTrue(result.diagnostics.any { "DSL-MANTRA-TABLE-NUMBER" in it.message }, result.diagnostics.toString())
    }

    @Test
    fun `lazy producers are not coerced into records and ignored nested producers are not forced`() {
        val valid = schema(
            """
            (input divisor :decimal {:default 0})
            (line answer "Answer"
              (table/count-where [{:key :A :unused (map (fn [entry] (/ 1 divisor)) [1])}] {:key :A}))
            """.trimIndent(),
        )
        val result = Mantra.calculateForAudit(valid)
        assertTrue(result.succeeded, result.diagnostics.toString())
        assertEquals(Value.num(1), result.value("answer"))
        val producer = schema(
            """
            (input divisor :decimal {:default 0})
            (line answer "Answer"
              (table/count-where (map (fn [entry] {:amount (/ 1 divisor)}) [1]) {}))
            """.trimIndent(),
        )
        val failed = Mantra.calculate(producer)
        assertFalse(failed.succeeded)
        assertTrue(failed.diagnostics.any { "DSL-MANTRA-TABLE-ROWS" in it.message }, failed.diagnostics.toString())
        assertFalse(failed.diagnostics.any { "DIVIDE-BY-ZERO" in it.message })
    }

    @Test
    fun `catalog and generic formula tooling discover both functions`() {
        val names = setOf("table/sum-where", "table/count-where")
        assertTrue(names.all { name -> FunctionCatalog.functions.any { it.name == name && it.summary.isNotBlank() } })
        val model = schema("(formula-slot answer \"Answer\" 0)")
        val authoring = FormulaAuthoring.forFormulaSlot(model, CaseData.empty(), emptyList(), "answer")
        val completions = authoring.complete("(table/", 7).items.map { it.label }
        assertTrue(names.all { it in completions }, completions.toString())
        assertTrue(authoring.check("(table/sum-where [{:amount 2}] {} :amount)").isEmpty())
        assertTrue(authoring.check("(table/count-where [{:amount 2}] {})").isEmpty())
        names.forEach { assertNotNull(authoring.hover("($it [] {})", 3)) }
    }
}
