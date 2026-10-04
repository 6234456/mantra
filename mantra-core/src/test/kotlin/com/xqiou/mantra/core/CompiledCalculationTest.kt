package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellation
import com.xqiou.mantra.core.api.RunCancellationSource
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunFailureKind
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.LinkProvenance
import com.xqiou.mantra.core.model.SchemaReference
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.InputOrigin
import com.xqiou.mantra.core.view.NodeTrace
import com.xqiou.normein.dsl.runtime.DslBudgetCounter
import java.math.BigDecimal
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompiledCalculationTest {
    private fun schema(text: String) = Mantra.loadSchema(
        SourceText("schema.mantra", text.trimIndent()),
        SourceResolver { _, _ -> null },
    )
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text.trimIndent()))
    private fun number(expected: String, actual: Value) =
        assertEquals(0, BigDecimal(expected).compareTo((actual as Value.Num).value))
    private fun scalar(id: String, value: Long): CaseData = CaseData.empty(id).copy(
        source = "$id.mantra",
        inputs = mapOf("base" to Value.num(value)),
    )

    @Test
    fun `different typed case IDs execute fresh rows while physically reusing checked plans and sessions`() {
        val compiled = Mantra.compile(
            schema(
                """
            (schema test/compiled
              (param multiplier 2)
              (input base :decimal)
              (line answer "Answer" (* base multiplier)))
        """,
            ),
        )
        val compilation = compiled.compilation
        assertEquals(1L, compilation.formulaCount)
        assertEquals(1L, compilation.executionPlanCompilations)
        assertTrue(compilation.syntaxCompilerCalls > 0)
        assertTrue(compilation.semanticCompilerCalls > 0)
        val worker = compiled.openSession()
        val oldest = worker.calculate(scalar("case-0", 7))
        try {
            for (index in 1..40) {
                val next = worker.calculate(scalar("case-$index", index.toLong()))
                assertEquals("case-$index", next.case.id)
                assertNotSame(oldest, next)
                number((index * 2).toString(), next.value("answer"))
                assertNull((next.node("answer").trace(emptyList()) as NodeTrace.Computed).explanation)
            }
            number("14", oldest.value("answer"))
            assertEquals("case-0", oldest.case.id)
            assertEquals(compilation, compiled.compilation)
            assertEquals(1L, worker.statistics.sessionOpens)
            assertEquals(0L, worker.statistics.executionPlanCompilations)
            assertEquals(41L, worker.statistics.rowCycles)
            assertEquals(41L, worker.statistics.physicalRowPreparations)
            assertEquals(0L, worker.statistics.valueOnlyEvidenceMaterializations)
        } finally {
            worker.close()
        }
        assertEquals(1L, worker.statistics.sessionCloseAttempts)
        assertEquals(worker.statistics.sessionOpens, worker.statistics.successfulSessionCloses)
        assertFailsWith<IllegalStateException> { worker.calculate(scalar("closed", 1)) }
        worker.close()
        assertEquals(1L, worker.statistics.sessionCloseAttempts)
    }

    @Test
    fun `equal values still rebind link revisions positions explicit zero false and current parameter layers`() {
        val compiled = Mantra.compile(
            schema(
                """
            (schema test/receipts {:version "01"}
              (param multiplier 2)
              (input base :decimal {:default 0 :required-when true})
              (input enabled :boolean {:default true})
              (line answer "Answer" (* base multiplier)))
        """,
            ),
        )
        val from = InputAddress("source-value")
        val receipt =
            LinkProvenance("memory:source", "source.case", "source", SchemaReference("source/schema", "1"), "r1", from)
        val initial = scalar("a", 0).copy(
            inputs = mapOf("base" to Value.ZERO, "enabled" to Value.Bool(false)),
            linkInputs = mapOf(InputAddress("base") to receipt, InputAddress("enabled") to receipt),
        )
        val parameters = ParameterSet(
            "p1",
            emptyMap(),
            mapOf("multiplier" to Value.num(3)),
            emptyMap(),
            SourceLocation("p1", 1, 1),
        )
        compiled.openSession().use { worker ->
            val first = worker.calculate(initial, listOf(parameters))
            assertTrue(first.validationPassed)
            assertEquals(Value.Bool(false), first.value("enabled"))
            assertEquals(InputOrigin.LINK, (first.node("enabled").trace(emptyList()) as NodeTrace.Input).origin)
            val moved = initial.copy(
                id = "b",
                source = "b.mantra",
                linkInputs = initial.linkInputs.mapValues { (_, source) ->
                    source.copy(revision = "r2")
                },
                inputLocations = mapOf("base" to SourceLocation("b.mantra", 9, 3)),
            )
            val second = worker.calculate(
                moved,
                listOf(parameters.copy(id = "p2", values = mapOf("multiplier" to Value.num(4)))),
            )
            assertEquals("r2", (second.node("base").trace(emptyList()) as NodeTrace.Input).link?.revision)
            assertEquals("r1", (first.node("base").trace(emptyList()) as NodeTrace.Input).link?.revision)
            assertEquals("p2", (second.node("multiplier").trace(emptyList()) as NodeTrace.Param).source)
            assertEquals("p1", (first.node("multiplier").trace(emptyList()) as NodeTrace.Param).source)
            number("4", second.value("multiplier"))
            val missing = worker.calculate(CaseData.empty("c"))
            assertTrue(missing.succeeded)
            assertFalse(missing.validationPassed)
            assertEquals(InputOrigin.DEFAULT, (missing.node("base").trace(emptyList()) as NodeTrace.Input).origin)
            assertEquals("MANTRA-INPUT-REQUIRED", missing.diagnostics.single().code)
        }
    }

    @Test
    fun `fresh domains clear absent members prior values applicability and business findings`() {
        val compiled = Mantra.compile(
            schema(
                """
            (schema test/compiled-domain
              (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
              (input rows :table {:min-rows 1 :columns {:id :keyword :name :text :seed :decimal :enabled :boolean}})
              (dimension asset {:from rows :key :id :title :name})
              (line closing "Closing" (+ (prev closing asset.seed) 1)
                {:per [year asset] :aggregate {:last year} :when asset.enabled :op :info})
              (check nonnegative "Nonnegative" (>= asset.seed 0) {:per asset}))
        """,
            ),
        )
        val a =
            case(
                """(case a (inputs {:rows [{:id :A :name "A" :seed -3 :enabled true} {:id :B :name "B" :seed 20 :enabled true}]}))""",
            )
        val b =
            case(
                """(case b (inputs {:rows [{:id :C :name "C" :seed 40 :enabled true} {:id :D :name "D" :seed 99 :enabled false}]}))""",
            )
        compiled.openSession().use { worker ->
            val first = worker.calculate(a)
            assertTrue(first.succeeded, first.diagnostics.joinToString("\n"))
            assertFalse(first.validationPassed)
            number("22", first.value("closing", "P2", "B"))
            val second = worker.calculate(b)
            assertTrue(second.succeeded, second.diagnostics.joinToString("\n"))
            assertTrue(second.validationPassed)
            assertEquals(listOf("C", "D"), second.members.getValue("asset").map { it.key })
            assertEquals(Value.Nil, second.value("closing", "P2", "B"))
            number("42", second.value("closing", "P2", "C"))
            assertTrue(second.node("closing").trace(listOf("P2", "D")) is NodeTrace.Inactive)
            number("42", second.view.reduce("closing").value!!)
            number("22", first.value("closing", "P2", "B"))
            val empty = worker.calculate(CaseData.empty("empty").copy(inputs = mapOf("rows" to Value.Vec(emptyList()))))
            assertTrue(empty.succeeded)
            assertFalse(empty.validationPassed)
            assertTrue(empty.members.getValue("asset").isEmpty())
            number("0", empty.view.reduce("closing").value!!)
            assertEquals(listOf("MANTRA-INPUT-MIN-ROWS"), empty.diagnostics.map { it.code })
        }
    }

    @Test
    fun `incompatible identities types functions extensions and bindings reject without a hidden recompile`() {
        val declared =
            schema(
                """
            (schema test/shape {:version "01"}
              (param multiplier 2)
              (input base :decimal)
              (formula-slot answer "Answer" (* base multiplier)))
        """,
            )
        val compiled = Mantra.compile(declared)
        val good = scalar("good", 4)
        val custom = case("(case c (inputs {:base 4}) (bind answer (+ base multiplier)))")
        val named = case("(case c (defn increment [x] (+ x 1)) (inputs {:base 4}))")
        val invalid = listOf(
            good.copy(schemaId = "other/schema"),
            good.copy(meta = mapOf("schema-version" to Value.Text("1"))),
            good.copy(params = mapOf("multiplier" to Value.Text("wrong"))),
            good.copy(formulaBindings = custom.formulaBindings),
            good.copy(functions = named.functions),
            // Even an unknown empty extension changes the authored compilation contract.
            good.copy(extensions = mapOf("unregistered" to emptyList())),
        )
        compiled.openSession().use { worker ->
            val first = worker.calculate(good)
            invalid.forEach { facts ->
                val error = assertFailsWith<MantraException> { worker.calculate(facts) }
                assertTrue(
                    error.diagnostics.any {
                        it.code in
                            setOf("MANTRA-COMPILE-INCOMPATIBLE", "MANTRA-CASE-SCHEMA-VERSION")
                    },
                )
                assertSame(first, worker.result)
            }
            number("10", worker.calculate(scalar("last", 5)).value("answer"))
            assertEquals(1L, worker.statistics.sessionOpens)
            assertEquals(0L, worker.statistics.executionPlanCompilations)
            assertEquals(1L, compiled.compilation.executionPlanCompilations)
        }
    }

    @Test
    fun `collection parameter root types reject leaf shape changes even when host kind remains ANY`() {
        val declared =
            schema(
                """
            (schema test/collection-types
              (param rates {:A 2})
              (input base :decimal)
              (line answer "Answer" (* base (get rates :A 0))))
        """,
            )
        val compiled = Mantra.compile(declared)
        fun rate(value: Value) = mapOf("rates" to Value.MapV(mapOf(Value.Kw("A") to value)))
        compiled.openSession().use { worker ->
            number("21", worker.calculate(scalar("valid", 7).copy(params = rate(Value.num(3)))).value("answer"))
            val prior = worker.result
            val failure = assertFailsWith<MantraException> {
                worker.calculate(scalar("wrong-leaf", 7).copy(params = rate(Value.Text("not-a-number"))))
            }
            assertTrue(failure.diagnostics.any { it.code == "MANTRA-COMPILE-INCOMPATIBLE" })
            assertSame(prior, worker.result)
            number("28", worker.calculate(scalar("valid-again", 7).copy(params = rate(Value.num(4)))).value("answer"))
            number(
                "0",
                worker.calculate(
                    scalar("empty-map", 7).copy(params = mapOf("rates" to Value.MapV(emptyMap()))),
                ).value("answer"),
            )
            assertEquals(1L, worker.statistics.sessionOpens)
            assertEquals(0L, worker.statistics.executionPlanCompilations)
        }
    }

    @Test
    fun `compatible narrowing of an open collection root is accepted without changing the checked scope`() {
        val compiled = Mantra.compile(
            schema("""(schema test/open-type (param entries {}) (line answer "Answer" (count entries)))"""),
        )
        val facts = CaseData.empty("filled").copy(
            params = mapOf(
                "entries" to Value.MapV(mapOf(Value.Kw("A") to Value.num(2), Value.Kw("B") to Value.num(3))),
            ),
        )
        number("2", compiled.calculate(facts).value("answer"))
        number("0", compiled.calculate(CaseData.empty("empty")).value("answer"))
        assertEquals(1L, compiled.compilation.executionPlanCompilations)
    }

    @Test
    fun `formula profile changes rebuild owner sessions without recompiling the template`() {
        val compiled = Mantra.compile(
            schema("""(schema test/profile (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        val constrained = CalculationOptions(formulaLimits = mapOf(DslBudgetCounter.FUNCTION_CALLS to 10L))
        val worker = compiled.openSession()
        try {
            number("2", worker.calculate(scalar("default", 1)).value("answer"))
            number("3", worker.calculate(scalar("lower-profile", 2), options = constrained).value("answer"))
            number("4", worker.calculate(scalar("same-profile", 3)).value("answer"))
            assertEquals(2L, worker.statistics.sessionOpens)
            assertEquals(1L, worker.statistics.sessionCloseAttempts)
            assertEquals(3L, worker.statistics.rowCycles)
            assertEquals(0L, worker.statistics.executionPlanCompilations)
            assertEquals(1L, compiled.compilation.executionPlanCompilations)
        } finally {
            worker.close()
        }
        assertEquals(2L, worker.statistics.sessionCloseAttempts)
    }

    @Test
    fun `template retains checked bindings but not seed facts and accepts current facts directly`() {
        val declared =
            schema("""(schema test/custom (input base :decimal) (formula-slot answer "Answer" (+ base 1)))""")
        val bindings = case("""(case prototype (inputs {:base 999}) (bind answer (* base 3)))""")
        val compiled = Mantra.compile(declared, bindings)
        assertTrue(compiled.template.plan.case.inputs.isEmpty())
        assertTrue(compiled.template.plan.case.inputCells.isEmpty())
        val current = bindings.copy(id = "actual", inputs = mapOf("base" to Value.num(7)), source = "actual.case")
        val result = compiled.calculate(current)
        number("21", result.value("answer"))
        assertEquals("actual", result.case.id)
        assertFailsWith<MantraException> { compiled.calculate(scalar("missing-binding", 7)) }
    }

    @Test
    fun `compile snapshots structure defaults and input results before caller collections can mutate`() {
        val original =
            schema(
                """(schema test/snapshots (param multiplier 2) (input base :decimal) (line answer "Answer" (* base multiplier)))""",
            )
        val mutableMeta = original.meta.attributes.toMutableMap()
        val mutableParams = original.params.toMutableList()
        val compiled = Mantra.compile(
            original.copy(meta = original.meta.copy(attributes = mutableMeta), params = mutableParams),
        )
        mutableMeta["version"] = Value.Text("changed-after-compile")
        mutableParams.clear()
        assertNull(compiled.schema.version)
        assertEquals(com.xqiou.mantra.core.model.ValueType.DECIMAL, compiled.parameterTypes["multiplier"])
        assertTrue(compiled.kernel.environmentFingerprint.isNotBlank())
        assertTrue(compiled.kernel.buildRevision.isNotBlank())
        assertFailsWith<UnsupportedOperationException> {
            (compiled.parameterTypes as MutableMap)["multiplier"] = com.xqiou.mantra.core.model.ValueType.TEXT
        }
        val facts = mutableMapOf("base" to Value.num(7))
        val result = compiled.calculate(CaseData.empty("actual").copy(inputs = facts))
        facts["base"] = Value.num(99)
        number("14", result.value("answer"))
        number("7", result.case.inputs.getValue("base"))
        number("16", compiled.calculate(scalar("next", 8)).value("answer"))
    }

    @Test
    fun `technical formula failure returns current Nil then next case recovers without recompilation`() {
        val compiled = Mantra.compile(
            schema("""(schema test/fail (input divisor :decimal) (line quotient "Quotient" (/ 10 divisor)))"""),
        )
        fun input(id: String, n: Long) = CaseData.empty(id).copy(inputs = mapOf("divisor" to Value.num(n)))
        compiled.openSession().use { worker ->
            val first = worker.calculate(input("good", 2))
            number("5", first.value("quotient"))
            val failed = worker.calculate(input("bad", 0))
            assertEquals("bad", failed.case.id)
            assertFalse(failed.succeeded)
            assertEquals(Value.Nil, failed.value("quotient"))
            assertTrue(failed.node("quotient").trace(emptyList()) is NodeTrace.Failed)
            val recovered = worker.calculate(input("recovered", 5))
            assertTrue(recovered.succeeded)
            assertTrue(recovered.diagnostics.isEmpty())
            number("2", recovered.value("quotient"))
            number("5", first.value("quotient"))
            assertEquals(1L, worker.statistics.sessionOpens)
            assertEquals(3L, worker.statistics.rowCycles)
        }
    }

    @Test
    fun `publication cancellation preserves previous snapshot and rebuilds only sessions for the next case`() {
        val compiled = Mantra.compile(
            schema("""(schema test/cancel (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        compiled.openSession().use { worker ->
            val published = worker.calculate(scalar("good", 1))
            var observedEvaluator = false
            val cancellation = RunCancellation {
                val inEvaluator = Thread.currentThread().stackTrace.any {
                    it.className ==
                        "com.xqiou.mantra.core.engine.Evaluator"
                }
                if (inEvaluator) observedEvaluator = true
                observedEvaluator && !inEvaluator
            }
            val rejected = assertFailsWith<MantraException> {
                worker.calculate(
                    scalar("cancelled", 9),
                    options = CalculationOptions(control = RunControl(cancellation)),
                )
            }
            assertEquals(RunFailureKind.CANCELLED, rejected.runFailure?.kind)
            assertSame(published, worker.result)
            number("2", published.value("answer"))
            number("4", worker.calculate(scalar("recovered", 3)).value("answer"))
            assertEquals(2L, worker.statistics.sessionOpens)
            assertEquals(1L, worker.statistics.sessionCloseAttempts)
            assertEquals(0L, worker.statistics.executionPlanCompilations)
            assertEquals(1L, compiled.compilation.executionPlanCompilations)
        }
    }

    @Test
    fun `pre cancellation and case local host budget reject without publishing stale output`() {
        val compiled = Mantra.compile(
            schema("""(schema test/limit (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        compiled.openSession().use { worker ->
            val first = worker.calculate(scalar("first-case", 2))
            val cancel = RunCancellationSource().also { it.cancel() }
            assertEquals(
                RunFailureKind.CANCELLED,
                assertFailsWith<MantraException> {
                    worker.calculate(scalar("cancelled", 3), options = CalculationOptions(control = RunControl(cancel)))
                }.runFailure?.kind,
            )
            val limit = assertFailsWith<MantraException> {
                worker.calculate(
                    scalar("limited", 4),
                    options = CalculationOptions(limits = RunLimits(maxFormulaExecutions = 0)),
                )
            }
            assertEquals(RunFailureKind.LIMIT, limit.runFailure?.kind)
            assertSame(first, worker.result)
            number("6", worker.calculate(scalar("recovered", 5)).value("answer"))
            assertEquals(0L, worker.statistics.executionPlanCompilations)
        }
    }

    @Test
    fun `audit and value only agree while evidence and source identity belong to the current case`() {
        val declared =
            schema(
                """
            (schema test/evidence
              (input base :decimal {:required-when true})
              (section visible "Visible" {:when (>= base 0)}
                (choice selected "Selected" {:rule :max :op :info}
                  (option :positive "Positive" (+ base 1) {:when true})
                  (option :negative "Negative" (- base 1)))
                (reconcile matched "Matched" selected (+ base 1))
                (check acceptable "Acceptable" (> selected 0))))
        """,
            )
        val compiled = Mantra.compile(declared)
        val current = scalar("current", 6)
        val direct = Mantra.calculateForAudit(declared, current)
        val full = compiled.calculateForAudit(current)
        val cheap = compiled.calculate(current)
        assertEquals(direct.diagnostics, full.diagnostics)
        assertEquals(full.diagnostics, cheap.diagnostics)
        full.nodes.forEach { (id, node) -> assertEquals(node.values, cheap.node(id).values) }
        assertEquals("current", full.case.id)
        val selected = full.node("selected").trace(emptyList()) as NodeTrace.Choice
        assertNotNull(selected.options.first().explanation)
        assertTrue(selected.options.first().explanation!!.steps.any { it.eventId != null })
        assertNull((cheap.node("selected").trace(emptyList()) as NodeTrace.Choice).options.first().explanation)
        val explained = compiled.calculateForExplain(scalar("later", 8), node = "selected")
        assertEquals("later", explained.case.id)
        assertNotNull(explained.explainTrace)
        number("9", explained.value("selected"))
    }

    @Test
    fun `two workers concurrently share a template but never share execution sessions`() {
        val compiled = Mantra.compile(
            schema("""(schema test/workers (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map { workerId ->
                pool.submit(
                    Callable {
                        val worker = compiled.openSession()
                        try {
                            val first = worker.calculate(scalar("worker-$workerId-first", workerId.toLong()))
                            val second = worker.calculate(scalar("worker-$workerId-second", workerId.toLong() * 10))
                            number((workerId + 1).toString(), first.value("answer"))
                            second
                        } finally {
                            worker.close()
                            assertEquals(1L, worker.statistics.sessionOpens)
                            assertEquals(1L, worker.statistics.sessionCloseAttempts)
                            assertEquals(2L, worker.statistics.rowCycles)
                            assertEquals(0L, worker.statistics.executionPlanCompilations)
                        }
                    },
                )
            }
            val results = calls.map { it.get(10, TimeUnit.SECONDS) }
            number("11", results[0].value("answer"))
            number("21", results[1].value("answer"))
            assertEquals(1L, compiled.compilation.executionPlanCompilations)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `wrong thread worker calls reject before changing lifecycle and detached results remain readable`() {
        val compiled = Mantra.compile(
            schema("""(schema test/owner (input base :decimal) (line answer "Answer" (+ base 1)))"""),
        )
        val worker = compiled.openSession()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = worker.calculate(scalar("owner", 2))
            pool.submit(
                Callable {
                    assertFailsWith<IllegalStateException> { worker.calculate(scalar("wrong", 3)) }
                    assertFailsWith<IllegalStateException> { worker.close() }
                    number("3", first.value("answer"))
                },
            ).get(10, TimeUnit.SECONDS)
            assertSame(first, worker.result)
            number("5", worker.calculate(scalar("owner-again", 4)).value("answer"))
            assertEquals(1L, worker.statistics.sessionOpens)
        } finally {
            worker.close()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
