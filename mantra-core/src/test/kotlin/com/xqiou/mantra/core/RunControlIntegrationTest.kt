package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellation
import com.xqiou.mantra.core.api.RunCancellationSource
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.LinkProvenance
import com.xqiou.mantra.core.model.SchemaReference
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.InputOrigin
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.normein.dsl.runtime.DslBudgetCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunControlIntegrationTest {
    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text), SourceResolver { _, _ -> null })
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text))
    private val scalar get() = schema("(schema test/scalar (input base :decimal) (line answer \"Answer\" (+ base 1)))")

    @Test
    fun `formula host ceiling stops both real routes at the addressed formula`() {
        val options = CalculationOptions(limits = RunLimits(maxFormulaExecutions = 0))
        val template = scalar
        val facts = case("(case c (inputs {:base 5}))")
        val ordinary = assertFailsWith<MantraException> { Mantra.calculate(template, facts, options = options) }
        val audit = assertFailsWith<MantraException> {
            Mantra.calculateForAudit(template, facts, calculationOptions = options)
        }
        listOf(ordinary, audit).forEach { failure ->
            assertEquals("MANTRA-RUN-LIMIT", failure.diagnostics.single().code)
            assertEquals(DiagnosticCategory.EVALUATION, failure.diagnostics.single().category)
            assertEquals("answer", failure.runFailure?.address?.nodeId)
            assertEquals(RunCounter.FORMULA_EXECUTIONS, failure.runFailure?.counter)
            assertEquals(1L, failure.runFailure?.attempted)
            assertEquals(0L, assertNotNull(failure.usage)[RunCounter.FORMULA_EXECUTIONS])
        }
    }

    @Test
    fun `narrowed coordinate product fails before visiting its first candidate`() {
        val template =
            schema(
                """(schema test/product
            (dimension a {:members [:A :B]}) (dimension b {:members [:X :Y :Z]})
            (line matrix "Matrix" 1 {:per [a b]}))""",
            )
        val failure = assertFailsWith<MantraException> {
            Mantra.calculate(template, options = CalculationOptions(limits = RunLimits(maxCoordinateProduct = 4)))
        }
        assertEquals(RunCounter.COORDINATE_PRODUCT, failure.runFailure?.counter)
        assertEquals(6L, failure.runFailure?.attempted)
        assertEquals(0L, assertNotNull(failure.usage)[RunCounter.COORDINATE_VISITS])
    }

    @Test
    fun `canceled unchanged recalculate publishes no stale success and next epoch remains reusable`() {
        val facts = case("(case c (inputs {:base 5}))")
        Mantra.openSession(scalar, facts).use { session ->
            val initial = session.result
            val cancellation = RunCancellationSource().also { it.cancel() }
            val failure = assertFailsWith<MantraException> {
                session.recalculate(facts, options = CalculationOptions(control = RunControl(cancellation)))
            }
            assertEquals("MANTRA-RUN-CANCELLED", failure.diagnostics.single().code)
            assertEquals(0L, assertNotNull(failure.usage)[RunCounter.FORMULA_EXECUTIONS])
            assertSame(initial, session.result)
            val next = session.recalculate(facts)
            assertTrue(next.succeeded)
            assertEquals(0, session.lastRun.formulaEvaluations)
            assertEquals(0L, assertNotNull(next.usage)[RunCounter.FORMULA_EXECUTIONS])
            assertEquals(0L, assertNotNull(next.usage)[RunCounter.TASKS])
            assertTrue(assertNotNull(initial.usage)[RunCounter.FORMULA_EXECUTIONS] > 0)
        }
    }

    @Test
    fun `cancellation at the publication boundary restores the public snapshot and rebuilds the rejected runtime`() {
        val facts = case("(case c (inputs {:base 5}))")
        Mantra.openSession(scalar, facts).use { session ->
            val published = session.result
            val owner = Thread.currentThread()
            val cancellation = RunCancellation { Thread.currentThread() === owner && session.result !== published }
            val changed = facts.copy(inputs = mapOf("base" to Value.num(7)))
            val failure = assertFailsWith<MantraException> {
                session.recalculate(changed, options = CalculationOptions(control = RunControl(cancellation)))
            }
            assertEquals("MANTRA-RUN-CANCELLED", failure.diagnostics.single().code)
            assertTrue(assertNotNull(failure.usage)[RunCounter.FORMULA_EXECUTIONS] > 0)
            assertSame(published, session.result)
            assertEquals(Value.num(6), published.value("answer"))
            val next = session.recalculate(changed)
            assertTrue(next.succeeded)
            assertTrue(session.lastRun.fullRebuild)
            assertEquals(Value.num(8), next.value("answer"))
            assertEquals(Value.num(6), published.value("answer"))
        }
    }

    @Test
    fun `lowered kernel profile rebuilds even unchanged successful facts`() {
        val facts = case("(case c (inputs {:base 5}))")
        Mantra.openSession(scalar, facts).use { session ->
            val strict = session.recalculate(
                facts,
                options = CalculationOptions(formulaLimits = mapOf(DslBudgetCounter.FUNCTION_CALLS to 0)),
            )
            assertTrue(session.lastRun.fullRebuild)
            assertFalse(strict.succeeded)
            assertEquals(Value.Nil, strict.value("answer"))
            assertTrue(strict.diagnostics.any { "DSL-LIMIT-FUNCTION-CALLS" in it.message })
            val normal = session.recalculate(facts, options = CalculationOptions())
            assertTrue(session.lastRun.fullRebuild)
            assertTrue(normal.succeeded)
            assertEquals(Value.num(6), normal.value("answer"))
        }
    }

    @Test
    fun `same amount new linked revision replaces provenance while old view remains frozen`() {
        val template =
            schema("(schema test/link (input base :decimal {:required true}) (line answer \"Answer\" (+ base 1)))")
        val address = InputAddress("base")
        val provenance =
            LinkProvenance(
                "source",
                "../source",
                "source-case",
                SchemaReference("source/schema", "01"),
                "revision-1",
                InputAddress("closing"),
            )
        val facts = case("(case c (inputs {:base 0}))").copy(linkInputs = mapOf(address to provenance))
        Mantra.openSession(template, facts).use { session ->
            val initial = session.result
            assertTrue(initial.validationPassed)
            val before = initial.node("base").trace(emptyList()) as NodeTrace.Input
            assertEquals(InputOrigin.LINK, before.origin)
            val next = session.recalculate(
                facts.copy(linkInputs = mapOf(address to provenance.copy(revision = "revision-2"))),
            )
            assertFalse(session.lastRun.fullRebuild)
            assertTrue(session.lastRun.invalidatedTasks > 0)
            assertTrue(session.lastRun.formulaEvaluations > 0)
            assertEquals("revision-2", (next.node("base").trace(emptyList()) as NodeTrace.Input).link?.revision)
            assertEquals("revision-1", before.link?.revision)
            assertEquals(Value.num(1), initial.value("answer"))
            assertEquals(Value.num(1), next.value("answer"))
        }
    }

    @Test
    fun `typed linked nil never falls back to an optional input default`() {
        val template = schema("(schema test/link (input base :decimal {:optional true :default 99}))")
        val provenance =
            LinkProvenance("source", "source", "c", SchemaReference("source", "1"), "rev", InputAddress("value"))
        val result = Mantra.calculate(
            template,
            case("(case c (inputs {:base nil}))").copy(linkInputs = mapOf(InputAddress("base") to provenance)),
        )
        assertFalse(result.succeeded)
        assertEquals(Value.Nil, result.value("base"))
        assertTrue(result.diagnostics.any { it.code == "MANTRA-LINK-UNDEFINED" })
    }

    @Test
    fun `literal input table rows are bounded before runtime and imported rows are not charged twice`() {
        val template = schema("(schema test/rows (input rows :table {:columns {:id :keyword}}))")
        val facts = case("(case c (inputs {:rows [{:id :A} {:id :B}]}))")
        val options = CalculationOptions(limits = RunLimits(maxInputRows = 1))
        val failure = assertFailsWith<MantraException> { Mantra.calculate(template, facts, options = options) }
        assertEquals(RunCounter.INPUT_ROWS, failure.runFailure?.counter)
        assertEquals(2L, failure.runFailure?.attempted)
        val imported = Mantra.calculate(
            template,
            facts.copy(inputOrigins = mapOf("rows" to mapOf("" to "source.csv"))),
            options = options,
        )
        assertTrue(imported.succeeded)
        assertEquals(0L, assertNotNull(imported.usage)[RunCounter.INPUT_ROWS])
    }

    @Test
    fun `read cells share a separate capped epoch and close freezes its usage`() {
        val result = Mantra.calculate(scalar, case("(case c (inputs {:base 5}))"))
        val calculationUsage = assertNotNull(result.usage)
        val reader = result.view.openReader(CalculationOptions(limits = RunLimits(maxCoordinateVisits = 2)))
        try {
            reader.chargeCoordinateVisits()
            reader.chargeCoordinateVisits()
            val failure = assertFailsWith<MantraException> { reader.chargeCoordinateVisits() }
            assertEquals(3L, failure.runFailure?.attempted)
        } finally {
            reader.close()
        }
        val usage = reader.usage
        assertEquals(2L, usage[RunCounter.COORDINATE_VISITS])
        reader.close()
        assertSame(usage, reader.usage)
        assertFailsWith<IllegalStateException> { reader.checkpoint() }
        assertSame(calculationUsage, result.usage)
    }

    @Test
    fun `fixed read slice visits one declared coordinate with product one`() {
        val template =
            schema(
                """(schema test/slice
            (dimension a {:members [:A :B]}) (dimension b {:members [:X :Y :Z]})
            (line matrix "Matrix" 1 {:per [a b]}))""",
            )
        val result = Mantra.calculate(template)
        result.view.openReader(
            CalculationOptions(limits = RunLimits(maxCoordinateProduct = 1, maxCoordinateVisits = 1)),
        ).use { reader ->
            assertEquals(listOf(listOf("B", "Y")), reader.coordinates("matrix", mapOf("a" to "B", "b" to "Y")))
            assertEquals(1L, reader.usage[RunCounter.COORDINATE_PRODUCT])
            assertEquals(1L, reader.usage[RunCounter.COORDINATE_VISITS])
        }
    }

    @Test
    fun `one reader shares its visit ceiling across two distinct immutable source views`() {
        val template = scalar
        val first = Mantra.calculate(template, case("(case first (inputs {:base 5}))"))
        val second = Mantra.calculate(template, case("(case second (inputs {:base 9}))"))
        first.view.openReader(CalculationOptions(limits = RunLimits(maxCoordinateVisits = 1))).use { reader ->
            assertEquals(Value.num(6), reader.reduce(first.view, "answer").value)
            val failure = assertFailsWith<MantraException> { reader.reduce(second.view, "answer") }
            assertEquals(RunCounter.COORDINATE_VISITS, failure.runFailure?.counter)
            assertEquals(2L, failure.runFailure?.attempted)
            assertEquals(1L, reader.usage[RunCounter.COORDINATE_VISITS])
        }
        assertTrue(first.succeeded)
        assertTrue(second.succeeded)
    }

    @Test
    fun `period domain respects a caller product ceiling before generating its members`() {
        val template =
            schema(
                """(schema test/domain-limit
            (dimension year {:periods {:start "2026-01-01" :unit :year :count 5}})
            (line closing "Closing" 1 {:per year}))""",
            )
        val failure = assertFailsWith<MantraException> {
            Mantra.calculate(template, options = CalculationOptions(limits = RunLimits(maxCoordinateProduct = 2)))
        }
        assertEquals(RunCounter.COORDINATE_PRODUCT, failure.runFailure?.counter)
        assertEquals("year", failure.runFailure?.address?.nodeId)
        assertEquals(5L, failure.runFailure?.attempted)
        assertEquals(0L, assertNotNull(failure.usage)[RunCounter.FORMULA_EXECUTIONS])
    }

    @Test
    fun `independent SDK calculation never ignores authored unresolved links`() {
        val template = schema("(schema test/linked (input amount :decimal {:default 99}))")
        val facts =
            case(
                """(case c {:schema test/linked}
            (links {:path "source.mantra" :schema source/schema :schema-version "1"
                :mappings [{:from {:node :closing :coord []} :to {:input :amount :coord []}}]}))""",
            )
        val failure = assertFailsWith<MantraException> { Mantra.calculate(template, facts) }
        assertEquals("MANTRA-LINK-UNDEFINED", failure.diagnostics.single().code)
        assertEquals(DiagnosticCategory.EVALUATION, failure.diagnostics.single().category)
    }

    @Test
    fun `true line and choice evaluation failures stay nil while guarded inactivity stays neutral`() {
        val template =
            schema(
                """(schema test/failure-values
            (line failed "Failed" (/ 1 0) {:op :info})
            (line inactive "Inactive" (/ 1 0) {:when false :op :info})
            (choice selected "Choice" {:rule :min :op :info}
                (option :ok "Ok" 1)
                (option :bad "Bad" (/ 1 0))))""",
            )
        val result = Mantra.calculate(template)
        assertFalse(result.succeeded)
        assertEquals(Value.Nil, result.value("failed"))
        assertTrue(result.node("failed").trace(emptyList()) is NodeTrace.Failed)
        assertEquals(Value.Nil, result.value("selected"))
        assertTrue(result.node("selected").trace(emptyList()) is NodeTrace.Failed)
        assertEquals(Value.ZERO, result.value("inactive"))
        assertTrue(result.node("inactive").trace(emptyList()) is NodeTrace.Inactive)
    }
}
