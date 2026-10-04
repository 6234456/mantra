package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.ParticipatingSource
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.RunCancellationSource
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.CaseLink
import com.xqiou.mantra.core.model.CaseLinkMapping
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.SchemaReference
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.InputOrigin
import com.xqiou.mantra.core.view.NodeTrace
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Genuine resolver -> graph -> binder -> reusable SDK execution; no replacement calculation callback. */
class CaseGraphRunnerIntegrationTest {
    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text), SourceResolver { _, _ -> null })
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text))
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Package(
        val schemaText: String,
        val schema: Schema,
        var case: CaseData,
        var revision: String = "case-r1",
        val parameters: List<ParameterSet> = emptyList(),
    )

    private fun packageOf(schemaText: String, caseText: String, parameters: List<ParameterSet> = emptyList()) =
        Package(schemaText, schema(schemaText), case(caseText), parameters = parameters)

    private inner class MemoryResolver : CasePackageResolver {
        val packages = linkedMapOf<CanonicalCaseKey, Package>()
        val loads = mutableListOf<CanonicalCaseKey>()
        val identities = mutableListOf<CanonicalCaseKey>()
        var beforeIdentify: (CaseReference) -> Unit = {}
        fun put(name: String, value: Package): CanonicalCaseKey = key(name).also { packages[it] = value }
        fun key(name: String) = CanonicalCaseKey(Path.of("/memory").resolve(name).normalize().toString())

        override fun identify(reference: CaseReference, control: CaseLoadControl): CanonicalCaseKey {
            control.checkpoint()
            beforeIdentify(reference)
            val base = reference.fromCase?.value?.let { Path.of(it).parent } ?: Path.of("/memory")
            val result = CanonicalCaseKey(base.resolve(reference.path).normalize().toString())
            identities += result
            return result
        }

        override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage {
            control.checkpoint()
            loads += key
            val value =
                packages[key]
                    ?: throw MantraException(
                        listOf(Diagnostic(Severity.ERROR, "TEST-SOURCE-MISSING", "No virtual case $key")),
                    )
            val bytes = value.schemaText.toByteArray(Charsets.UTF_8).size.toLong()
            control.chargeParticipatingBytes(bytes)
            return PreparedCasePackage(
                key,
                value.case.id,
                value.schema.identity,
                value.schema,
                value.case,
                value.parameters,
                listOf(ParticipatingSource("${key.value}/schema", SourceRole.SCHEMA, hash(value.schemaText), bytes)),
                value.revision,
            )
        }
    }

    private fun link(
        path: String,
        schema: String,
        version: String = "1",
        from: String = "exported",
        to: String = "transferred",
        fromCoord: List<String> = emptyList(),
        toCoord: List<String> = emptyList(),
    ): CaseLink = CaseLink(
        path,
        SchemaReference(schema, version),
        listOf(
            CaseLinkMapping(
                InputAddress(from, fromCoord),
                InputAddress(to, toCoord),
                SourceLocation("case.mantra", 1, 1),
            ),
        ),
        SourceLocation("case.mantra", 1, 1),
    )

    private fun fixture(): MemoryResolver {
        val resolver = MemoryResolver()
        resolver.put(
            "source.mantra",
            packageOf(
                """(schema test/source {:version "1"}
            (input source-amount :decimal) (line exported "Exported" (+ source-amount 0)))""",
                "(case source (inputs {:source-amount 5}))",
            ),
        )
        val root =
            packageOf(
                """(schema test/root {:version "1"}
            (input transferred :decimal) (line answer "Answer" (+ transferred 1)))""",
                "(case root)",
            )
        root.case = root.case.copy(links = listOf(link("source.mantra", "test/source")))
        resolver.put("root.mantra", root)
        return resolver
    }

    private fun request(name: String = "root.mantra", limits: RunLimits = RunLimits()) =
        CaseRunRequest(CaseReference(name), CalculationOptions(limits = limits))

    @Test
    fun `canonical aliases load and execute one source and preserve two explicit mapping edges`() {
        val resolver = fixture()
        val root =
            packageOf(
                """(schema test/root {:version "1"}
            (input first-value :decimal) (input second-value :decimal)
            (line answer "Answer" (+ first-value second-value)))""",
                "(case root)",
            )
        root.case = root.case.copy(
            links = listOf(
                link("source.mantra", "test/source", to = "first-value"),
                link("./folder/../source.mantra", "test/source", to = "second-value"),
            ),
        )
        resolver.put("root.mantra", root)
        CaseGraphRunner(resolver).use { runner ->
            val run = runner.run(request())
            assertTrue(run.succeeded, run.diagnostics.toString())
            assertEquals(Value.num(10), assertNotNull(run.result).value("answer"))
            assertEquals(1, resolver.loads.count { it == resolver.key("source.mantra") })
            assertEquals(2, run.cases.size)
            assertEquals(2, run.edges.size)
            assertEquals(1, run.cases.getValue(resolver.key("source.mantra")).recalculation?.formulaEvaluations)
            assertEquals(2L, run.usage[RunCounter.CASES])
            assertEquals(2L, run.usage[RunCounter.LINK_MAPPINGS])
            val repeated = runner.run(request())
            assertTrue(repeated.succeeded)
            assertEquals(0L, repeated.usage[RunCounter.FORMULA_EXECUTIONS])
            assertTrue(repeated.cases.values.all { it.recalculation?.formulaEvaluations == 0 })
            assertTrue(repeated.usage[RunCounter.PARTICIPATING_BYTES] > 0)
        }
    }

    @Test
    fun `canonical back alias detects a case cycle before loading the same case again`() {
        val resolver = MemoryResolver()
        val declaration = "(schema test/chain {:version \"1\"} (input transferred :decimal))"
        val root = packageOf(declaration, "(case root)")
        root.case = root.case.copy(links = listOf(link("middle.mantra", "test/chain", from = "transferred")))
        val middle = packageOf(declaration, "(case middle)")
        middle.case =
            middle.case.copy(links = listOf(link("folder/../root.mantra", "test/chain", from = "transferred")))
        resolver.put("root.mantra", root)
        resolver.put("middle.mantra", middle)
        CaseGraphRunner(resolver).use { runner ->
            val run = runner.run(request())
            assertFalse(run.succeeded)
            assertNull(run.result)
            assertTrue(run.diagnostics.any { it.finding.code == "MANTRA-LINK-CYCLE" })
            assertEquals(listOf(resolver.key("root.mantra"), resolver.key("middle.mantra")), resolver.loads)
            assertEquals(0L, run.usage[RunCounter.FORMULA_EXECUTIONS])
        }
    }

    @Test
    fun `depth and unique-case ceilings are checked before the blocked source loader runs`() {
        val resolver = fixture()
        val source = resolver.packages.getValue(resolver.key("source.mantra"))
        source.case =
            source.case.copy(
                inputs = emptyMap(),
                links = listOf(link("leaf.mantra", "test/source", to = "source-amount")),
            )
        resolver.put("leaf.mantra", packageOf(source.schemaText, "(case leaf (inputs {:source-amount 9}))"))
        CaseGraphRunner(resolver).use { runner ->
            val depth = runner.run(request(limits = RunLimits(maxLinkDepth = 1)))
            assertEquals(RunCounter.LINK_DEPTH, depth.failure?.counter)
            assertEquals(2L, depth.failure?.attempted)
            assertTrue(resolver.key("leaf.mantra") !in resolver.loads)
            resolver.loads.clear()
            val count = runner.run(request(limits = RunLimits(maxCases = 2)))
            assertEquals(RunCounter.CASES, count.failure?.counter)
            assertEquals(3L, count.failure?.attempted)
            assertTrue(resolver.key("leaf.mantra") !in resolver.loads)
            assertEquals(2L, count.usage[RunCounter.CASES])
        }
    }

    @Test
    fun `mapping ceiling prevents source loading and source formula work`() {
        val resolver = fixture()
        val root = resolver.packages.getValue(resolver.key("root.mantra"))
        val first = link("source.mantra", "test/source")
        root.case = root.case.copy(links = listOf(first.copy(mappings = first.mappings + first.mappings)))
        CaseGraphRunner(resolver).use { runner ->
            val run = runner.run(request(limits = RunLimits(maxLinkMappings = 1)))
            assertEquals(RunCounter.LINK_MAPPINGS, run.failure?.counter)
            assertEquals(2L, run.failure?.attempted)
            assertEquals(listOf(resolver.key("root.mantra")), resolver.loads)
            assertEquals(0L, run.usage[RunCounter.FORMULA_EXECUTIONS])
            assertEquals(0L, run.usage[RunCounter.LINK_MAPPINGS])
        }
    }

    @Test
    fun `source and consumer share one actual formula budget rather than separate allowances`() {
        val resolver = fixture()
        CaseGraphRunner(resolver).use { runner ->
            val run = runner.run(request(limits = RunLimits(maxFormulaExecutions = 1)))
            assertFalse(run.succeeded)
            assertNull(run.result)
            assertEquals(RunCounter.FORMULA_EXECUTIONS, run.failure?.counter)
            assertEquals(2L, run.failure?.attempted)
            assertEquals(resolver.key("root.mantra").value, run.failure?.address?.caseKey)
            assertEquals("answer", run.failure?.address?.nodeId)
            assertEquals(1L, run.usage[RunCounter.FORMULA_EXECUTIONS])
            assertEquals(Value.num(5), run.cases.getValue(resolver.key("source.mantra")).view.value("exported"))
        }
    }

    @Test
    fun `exact source versions reject normalization while root and source use independent parameters`() {
        val resolver = MemoryResolver()
        val sourceParameters = Mantra.loadParameters(
            SourceText("source.params", "(parameters source (values {:factor 2}))"),
        )
        val rootParameters = Mantra.loadParameters(
            SourceText("root.params", "(parameters root (values {:factor 100}))"),
        )
        resolver.put(
            "source.mantra",
            packageOf(
                """(schema test/source {:version "01"}
            (param factor 1) (line exported "Exported" (* factor 10)))""",
                "(case source)",
                listOf(sourceParameters),
            ),
        )
        val root =
            packageOf(
                """(schema test/root {:version "1"}
            (param factor 1) (input transferred :decimal) (line answer "Answer" (+ transferred factor)))""",
                "(case root)",
                listOf(rootParameters),
            )
        root.case = root.case.copy(links = listOf(link("source.mantra", "test/source", version = "01")))
        resolver.put("root.mantra", root)
        CaseGraphRunner(resolver).use { runner ->
            val run = runner.run(request())
            assertTrue(run.succeeded, run.diagnostics.toString())
            assertEquals(Value.num(120), assertNotNull(run.result).value("answer"))
            assertEquals(Value.num(20), run.cases.getValue(resolver.key("source.mantra")).view.value("exported"))
            root.case = root.case.copy(links = listOf(link("source.mantra", "test/source", version = "1")))
            root.revision = "root-r2"
            val rejected = runner.run(request())
            assertFalse(rejected.succeeded)
            assertNull(rejected.result)
            assertTrue(rejected.diagnostics.any { it.finding.code == "MANTRA-LINK-VERSION" })
        }
    }

    @Test
    fun `upstream business errors keep linked amounts and source-owned validation failure`() {
        val resolver = fixture()
        val source =
            packageOf(
                """(schema test/source {:version "1"}
            (input source-amount :decimal) (line exported "Exported" source-amount)
            (check source-control "Source reconciliation" false))""",
                "(case source (inputs {:source-amount 5}))",
            )
        resolver.put("source.mantra", source)
        CaseGraphRunner(resolver).use { runner ->
            val run = runner.run(request())
            assertTrue(run.succeeded, run.diagnostics.toString())
            assertFalse(run.validationPassed)
            val result = assertNotNull(run.result)
            assertTrue(result.succeeded)
            assertFalse(result.validationPassed)
            assertEquals(Value.num(6), result.value("answer"))
            val inherited = result.diagnostics.single { it.category == DiagnosticCategory.BUSINESS }
            assertEquals(resolver.key("source.mantra").value, inherited.caseKey)
            assertEquals("source-control", inherited.nodeId)
            assertEquals(run.cases.getValue(resolver.key("source.mantra")).revision, inherited.caseRevision)
            assertTrue(
                run.diagnostics.any {
                    it.case == resolver.key("source.mantra") &&
                        it.finding.code == "MANTRA-CHECK-FAILED"
                },
            )
        }
    }

    @Test
    fun `source technical failure after a good run never supplies an old source or root result`() {
        val resolver = fixture()
        val source =
            packageOf(
                """(schema test/source {:version "1"}
            (input divisor :decimal) (line exported "Exported" (/ 10 divisor)))""",
                "(case source (inputs {:divisor 2}))",
            )
        resolver.put("source.mantra", source)
        CaseGraphRunner(resolver).use { runner ->
            val initial = runner.run(request())
            assertTrue(initial.succeeded)
            assertEquals(Value.num(6), assertNotNull(initial.result).value("answer"))
            source.case = source.case.copy(inputs = mapOf("divisor" to Value.num(0)))
            source.revision = "source-r2"
            val failed = runner.run(request())
            assertFalse(failed.succeeded)
            assertNull(failed.result)
            assertTrue(
                failed.diagnostics.any {
                    it.case == resolver.key("source.mantra") &&
                        it.finding.category == DiagnosticCategory.EVALUATION
                },
            )
            assertEquals(Value.Nil, failed.cases.getValue(resolver.key("source.mantra")).view.value("exported"))
            assertEquals(Value.num(6), initial.result.value("answer"))
            source.case = source.case.copy(inputs = mapOf("divisor" to Value.num(2)))
            source.revision = "source-r3"
            val recovered = runner.run(request())
            assertTrue(recovered.succeeded, recovered.diagnostics.toString())
            assertEquals(Value.num(6), assertNotNull(recovered.result).value("answer"))
        }
    }

    @Test
    fun `exact zero and false retain LINK presence while source nil is a technical failure`() {
        val resolver = MemoryResolver()
        val source =
            packageOf(
                """(schema test/source {:version "1"}
            (input exported :decimal {:optional true}) (input flag :boolean))""",
                "(case source (inputs {:exported 0 :flag false}))",
            )
        resolver.put("source.mantra", source)
        val root =
            packageOf(
                """(schema test/root {:version "1"}
            (input transferred :decimal {:default 99 :required true})
            (input enabled :boolean {:default true :required true}))""",
                "(case root)",
            )
        val numeric = link("source.mantra", "test/source")
        val boolean = link("source.mantra", "test/source", from = "flag", to = "enabled")
        root.case = root.case.copy(links = listOf(numeric.copy(mappings = numeric.mappings + boolean.mappings)))
        resolver.put("root.mantra", root)
        CaseGraphRunner(resolver).use { runner ->
            val run = runner.run(request())
            assertTrue(run.succeeded, run.diagnostics.toString())
            assertTrue(run.validationPassed)
            val result = assertNotNull(run.result)
            assertEquals(Value.num(0), result.value("transferred"))
            assertEquals(Value.Bool(false), result.value("enabled"))
            listOf("transferred", "enabled").forEach { id ->
                assertEquals(InputOrigin.LINK, (result.node(id).trace(emptyList()) as NodeTrace.Input).origin)
                assertTrue(result.view.inputProvided(id))
            }
            source.case = source.case.copy(inputs = source.case.inputs + ("exported" to Value.Nil))
            source.revision = "source-r2"
            val nil = runner.run(request())
            assertFalse(nil.succeeded)
            assertNull(nil.result)
            assertTrue(nil.diagnostics.any { it.finding.code == "MANTRA-LINK-UNDEFINED" })
        }
    }

    @Test
    fun `same amount source revision changes downstream revision and LINK trace without schema rebuild`() {
        val resolver = fixture()
        val source = resolver.packages.getValue(resolver.key("source.mantra"))
        CaseGraphRunner(resolver).use { runner ->
            val initial = runner.run(request())
            val initialRoot = assertNotNull(initial.result)
            val before = initialRoot.node("transferred").trace(emptyList()) as NodeTrace.Input
            source.revision = "source-r2"
            val changed = runner.run(request())
            assertTrue(changed.succeeded, changed.diagnostics.toString())
            val current = assertNotNull(changed.result)
            val trace = current.node("transferred").trace(emptyList()) as NodeTrace.Input
            assertNotEquals(
                initial.cases.getValue(resolver.key("root.mantra")).revision,
                changed.cases.getValue(resolver.key("root.mantra")).revision,
            )
            assertNotEquals(before.link?.revision, trace.link?.revision)
            assertEquals(changed.cases.getValue(resolver.key("source.mantra")).revision, trace.link?.revision)
            assertEquals(
                before.link?.revision,
                (initialRoot.node("transferred").trace(emptyList()) as NodeTrace.Input).link?.revision,
            )
            assertFalse(assertNotNull(changed.cases.getValue(resolver.key("root.mantra")).recalculation).fullRebuild)
            assertTrue(changed.cases.getValue(resolver.key("root.mantra")).recalculation!!.invalidatedTasks > 0)
            assertEquals(initialRoot.value("answer"), current.value("answer"))
        }
    }

    @Test
    fun `cancellation publishes no root and the next epoch uses the current source`() {
        val resolver = fixture()
        val root = resolver.packages.getValue(resolver.key("root.mantra"))
        val source = resolver.packages.getValue(resolver.key("source.mantra"))
        resolver.put("other.mantra", packageOf(source.schemaText, "(case other (inputs {:source-amount 0}))"))
        root.case = root.case.copy(links = root.case.links + link("other.mantra", "test/source", to = "other-value"))
        val replacement =
            packageOf(
                """(schema test/root {:version "1"}
            (input transferred :decimal) (input other-value :decimal)
            (line answer "Answer" (+ transferred other-value 1)))""",
                "(case root)",
            )
        replacement.case = root.case
        resolver.put("root.mantra", replacement)
        CaseGraphRunner(resolver).use { runner ->
            val initial = runner.run(request())
            assertTrue(initial.succeeded, initial.diagnostics.toString())
            val previous = assertNotNull(initial.result)
            source.case = source.case.copy(inputs = mapOf("source-amount" to Value.num(7)))
            source.revision = "source-r2"
            val cancellation = RunCancellationSource()
            resolver.beforeIdentify = { reference -> if (reference.path == "other.mantra") cancellation.cancel() }
            val failed = runner.run(
                CaseRunRequest(
                    CaseReference("root.mantra"),
                    CalculationOptions(control = RunControl(cancellation)),
                ),
            )
            assertFalse(failed.succeeded)
            assertEquals("MANTRA-RUN-CANCELLED", failed.failure?.code)
            assertNull(failed.result)
            val completedSource = failed.cases.getValue(resolver.key("source.mantra"))
            assertEquals(Value.num(7), completedSource.view.value("exported"))
            assertEquals(Value.num(6), previous.value("answer"))
            resolver.beforeIdentify = {}
            val next = runner.run(request())
            assertTrue(next.succeeded, next.diagnostics.toString())
            val result = assertNotNull(next.result)
            assertEquals(Value.num(8), result.value("answer"))
            assertEquals(0, next.cases.getValue(resolver.key("source.mantra")).recalculation?.formulaEvaluations)
            val linked = result.node("transferred").trace(emptyList()) as NodeTrace.Input
            assertEquals(completedSource.revision, linked.link?.revision)
            assertEquals(Value.num(6), previous.value("answer"))
        }
    }

    @Test
    fun `wrong-thread run and close leave owner cache intact and close rejects later requests`() {
        val resolver = fixture()
        val runner = CaseGraphRunner(resolver)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val initial = runner.run(request())
            workers.submit(
                Callable {
                    assertFailsWith<IllegalStateException> { runner.run(request()) }
                    assertFailsWith<IllegalStateException> { runner.close() }
                    assertEquals(Value.num(6), assertNotNull(initial.result).value("answer"))
                },
            ).get(10, TimeUnit.SECONDS)
            val repeated = runner.run(request())
            assertTrue(repeated.succeeded)
            assertEquals(0L, repeated.usage[RunCounter.FORMULA_EXECUTIONS])
            runner.close()
            runner.close()
            assertFailsWith<IllegalStateException> { runner.run(request()) }
            workers.submit(
                Callable {
                    assertEquals(Value.num(6), assertNotNull(repeated.result).value("answer"))
                },
            ).get(10, TimeUnit.SECONDS)
        } finally {
            runner.close()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `LRU eviction rebuilds the old schema runtime while its detached result remains readable`() {
        val resolver = MemoryResolver()
        val declaration = "(schema test/plain {:version \"1\"} (line source-amount \"Amount\" 5))"
        val first = resolver.put("first.mantra", packageOf(declaration, "(case first)"))
        resolver.put("second.mantra", packageOf(declaration, "(case second)"))
        CaseGraphRunner(resolver, sessionLimit = 1).use { runner ->
            val initial = runner.run(request("first.mantra"))
            assertTrue(runner.run(request("second.mantra")).succeeded)
            val reloaded = runner.run(request("first.mantra"))
            assertTrue(reloaded.cases.getValue(first).recalculation!!.fullRebuild)
            assertEquals(1, reloaded.cases.getValue(first).recalculation!!.formulaEvaluations)
            assertEquals(Value.num(5), assertNotNull(initial.result).value("source-amount"))
        }
    }
}
