package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphInspector
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
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaseGraphInspectorTest {
    private fun schema(text: String) =
        Mantra.loadSchema(SourceText("schema.mantra", text), SourceResolver { _, _ -> null })
    private fun case(text: String) = Mantra.loadCase(SourceText("case.mantra", text))
    private val location = SourceLocation("case.mantra", 1, 1)
    private data class Package(
        val source: String,
        val schema: Schema,
        var case: CaseData,
        val parameters: List<ParameterSet>,
    )

    private inner class Resolver : CasePackageResolver {
        val packages = linkedMapOf<CanonicalCaseKey, Package>()
        val loads = mutableListOf<CanonicalCaseKey>()
        var retainedControl: CaseLoadControl? = null
        var rejectIdentification = false
        fun key(name: String) = CanonicalCaseKey(Path.of("/memory").resolve(name).normalize().toString())
        fun put(
            name: String,
            text: String,
            facts: CaseData = case("(case c)"),
            parameters: List<ParameterSet> = emptyList(),
        ) = key(name).also { packages[it] = Package(text, schema(text), facts, parameters) }

        override fun identify(reference: CaseReference, control: CaseLoadControl): CanonicalCaseKey {
            control.checkpoint()
            if (rejectIdentification) {
                throw MantraException(
                    listOf(
                        Diagnostic(
                            Severity.ERROR,
                            "TEST-PATH-DENIED",
                            "Root capability denied",
                        ),
                    ),
                )
            }
            retainedControl = control
            val base = reference.fromCase?.value?.let { Path.of(it).parent } ?: Path.of("/memory")
            return CanonicalCaseKey(base.resolve(reference.path).normalize().toString())
        }

        override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage {
            control.checkpoint()
            loads += key
            val pkg = packages[key] ?: throw MantraException(
                listOf(
                    Diagnostic(
                        Severity.ERROR,
                        "TEST-MISSING-PATH",
                        "No virtual package $key",
                    ),
                ),
            )
            val bytes = pkg.source.toByteArray(Charsets.UTF_8)
            control.chargeParticipatingBytes(bytes.size.toLong())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return PreparedCasePackage(
                key,
                pkg.case.id,
                pkg.schema.identity,
                pkg.schema,
                pkg.case,
                pkg.parameters,
                listOf(ParticipatingSource("${key.value}/schema", SourceRole.SCHEMA, hash, bytes.size.toLong())),
                "revision-1",
            )
        }
    }

    private fun link(
        path: String = "source.mantra",
        version: String = "1",
        from: InputAddress = InputAddress("exported"),
        to: InputAddress = InputAddress("transferred"),
    ) = CaseLink(
        path,
        SchemaReference("test/source", version),
        listOf(CaseLinkMapping(from, to, location)),
        location,
    )

    private fun fixture(sourceFormula: String = "(/ 1 0)"): Resolver = Resolver().also { resolver ->
        resolver.put(
            "source.mantra",
            """(schema test/source {:version "1"}
            (param factor 1) (line exported "Exported" $sourceFormula)
            (check business "Business" false))""",
        )
        resolver.put(
            "root.mantra",
            """(schema test/root {:version "1"}
            (input transferred :decimal {:required true}) (line answer "Answer" (+ transferred 1)))""",
            case("(case root)").copy(links = listOf(link())),
        )
    }

    @Test
    fun `static graph inspection compiles source failure without running formulas or business checks`() {
        val resolver = fixture()
        val inspected = CaseGraphInspector(resolver).inspect(
            CaseReference("root.mantra"),
            CalculationOptions(limits = RunLimits(maxFormulaExecutions = 0, maxTasks = 0)),
        )
        assertTrue(inspected.succeeded, inspected.diagnostics.toString())
        assertEquals(2, inspected.cases.size)
        assertEquals(1, inspected.edges.size)
        assertEquals(0L, inspected.usage[RunCounter.FORMULA_EXECUTIONS])
        assertEquals(0L, inspected.usage[RunCounter.TASKS])
        assertEquals(2L, inspected.usage[RunCounter.CASES])
        assertTrue(inspected.usage[RunCounter.PARTICIPATING_BYTES] > 0)
        assertTrue(
            inspected.diagnostics.none {
                it.finding.category in
                    setOf(DiagnosticCategory.BUSINESS, DiagnosticCategory.EVALUATION)
            },
        )
        assertTrue(assertNotNull(inspected.view).node("answer").values.isEmpty())
        val source = inspected.cases.getValue(resolver.key("source.mantra")).view
        assertTrue(source.node("exported").values.isEmpty())
        assertTrue(source.node("business").validations.isEmpty())
        assertFailsWith<IllegalStateException> { assertNotNull(resolver.retainedControl).checkpoint() }
        CaseGraphRunner(resolver).use { runner ->
            val evaluated = runner.run(CaseRunRequest(CaseReference("root.mantra")))
            assertFalse(evaluated.succeeded)
            assertNull(evaluated.result)
            assertTrue(evaluated.diagnostics.any { it.finding.category == DiagnosticCategory.EVALUATION })
        }
    }

    @Test
    fun `direct inspection is controlled and accepts unresolved links while every execution entry rejects them`() {
        val resolver = fixture()
        val root = resolver.packages.getValue(resolver.key("root.mantra"))
        val view = Mantra.inspect(
            root.schema,
            root.case,
            options = CalculationOptions(limits = RunLimits(maxFormulaExecutions = 0)),
        )
        assertEquals(root.case.links, view.case.links)
        assertTrue(view.node("answer").values.isEmpty())
        listOf<() -> Unit>(
            { Mantra.calculate(root.schema, root.case) },
            { Mantra.calculateForAudit(root.schema, root.case) },
            { Mantra.openSession(root.schema, root.case).use { } },
        ).forEach { action ->
            val error = assertFailsWith<MantraException> { action() }
            assertTrue(error.diagnostics.any { it.code == "MANTRA-LINK-UNDEFINED" })
        }
        val cancellation = RunCancellationSource().also { it.cancel() }
        val error = assertFailsWith<MantraException> {
            Mantra.inspect(root.schema, root.case, options = CalculationOptions(control = RunControl(cancellation)))
        }
        assertEquals("MANTRA-RUN-CANCELLED", error.diagnostics.single().code)
        assertEquals(0L, assertNotNull(error.usage)[RunCounter.FORMULA_EXECUTIONS])
    }

    @Test
    fun `canonical aliases share one source binding and retain its own parameter selection`() {
        val resolver = Resolver()
        val sourceParameters = Mantra.loadParameters(
            SourceText("source-params.mantra", "(parameters source-params (value factor 2))"),
        )
        val rootParameters = Mantra.loadParameters(
            SourceText("root-params.mantra", "(parameters root-params (value factor 100))"),
        )
        resolver.put(
            "source.mantra",
            """(schema test/source {:version "1"}
            (param factor 1) (line exported "Exported" (* factor 3)))""",
            parameters = listOf(sourceParameters),
        )
        resolver.put(
            "root.mantra",
            """(schema test/root {:version "1"}
            (param factor 1) (input first-value :decimal) (input second-value :decimal)
            (line answer "Answer" (+ first-value second-value factor)))""",
            case("(case root)").copy(
                links = listOf(
                    link(to = InputAddress("first-value")),
                    link("./folder/../source.mantra", to = InputAddress("second-value")),
                ),
            ),
            listOf(rootParameters),
        )
        val inspected = CaseGraphInspector(resolver).inspect(CaseReference("root.mantra"))
        assertTrue(inspected.succeeded, inspected.diagnostics.toString())
        assertEquals(1, resolver.loads.count { it == resolver.key("source.mantra") })
        assertEquals(2, inspected.edges.size)
        assertEquals(
            Value.num(2),
            inspected.cases.getValue(resolver.key("source.mantra")).view.node("factor").parameterValue,
        )
        assertEquals(Value.num(100), assertNotNull(inspected.view).node("factor").parameterValue)
        assertEquals(0L, inspected.usage[RunCounter.FORMULA_EXECUTIONS])
    }

    @Test
    fun `bad exact versions and missing paths fail statically with source ownership`() {
        val resolver = fixture()
        val root = resolver.packages.getValue(resolver.key("root.mantra"))
        root.case = root.case.copy(links = listOf(link(version = "01")))
        val inspector = CaseGraphInspector(resolver)
        val wrong = inspector.inspect(CaseReference("root.mantra"))
        assertFalse(wrong.succeeded)
        assertEquals("MANTRA-LINK-VERSION", wrong.diagnostics.single().finding.code)
        assertEquals(resolver.key("source.mantra"), wrong.diagnostics.single().case)
        root.case = root.case.copy(links = listOf(link("missing.mantra")))
        val missing = inspector.inspect(CaseReference("root.mantra"))
        assertFalse(missing.succeeded)
        assertNull(missing.view)
        assertEquals(resolver.key("missing.mantra"), missing.diagnostics.single().case)
        assertEquals(0L, missing.usage[RunCounter.FORMULA_EXECUTIONS])
        resolver.rejectIdentification = true
        val rejected = inspector.inspect(CaseReference("root.mantra"))
        assertFalse(rejected.succeeded)
        assertNull(rejected.view)
        assertEquals("TEST-PATH-DENIED", rejected.diagnostics.single().finding.code)
        assertEquals(0L, rejected.usage[RunCounter.CASES])
    }

    @Test
    fun `canonical cycles depth case and mapping limits are checked before repeated or blocked loads`() {
        val resolver = fixture("5")
        val source = resolver.packages.getValue(resolver.key("source.mantra"))
        source.case = source.case.copy(
            links = listOf(
                CaseLink(
                    "folder/../root.mantra",
                    SchemaReference("test/root", "1"),
                    listOf(CaseLinkMapping(InputAddress("answer"), InputAddress("exported"), location)),
                    location,
                ),
            ),
        )
        val inspector = CaseGraphInspector(resolver)
        val cycle = inspector.inspect(CaseReference("root.mantra"))
        assertFalse(cycle.succeeded)
        assertEquals("MANTRA-LINK-CYCLE", cycle.diagnostics.single().finding.code)
        assertEquals(1, resolver.loads.count { it == resolver.key("root.mantra") })
        source.case = source.case.copy(links = emptyList())
        listOf(
            RunLimits(maxLinkDepth = 0) to RunCounter.LINK_DEPTH,
            RunLimits(maxCases = 1) to RunCounter.CASES,
            RunLimits(maxLinkMappings = 0) to RunCounter.LINK_MAPPINGS,
        ).forEach { (limits, counter) ->
            resolver.loads.clear()
            val blocked = inspector.inspect(CaseReference("root.mantra"), CalculationOptions(limits = limits))
            assertFalse(blocked.succeeded)
            assertEquals(counter, blocked.failure?.counter)
            assertEquals(listOf(resolver.key("root.mantra")), resolver.loads)
            assertEquals(0L, blocked.usage[RunCounter.FORMULA_EXECUTIONS])
        }
    }

    @Test
    fun `static mappings reject known type coordinate target and explicit nil conflicts`() {
        val resolver = fixture("5")
        val source = resolver.packages.getValue(resolver.key("source.mantra"))
        resolver.put(
            "source.mantra",
            """(schema test/source {:version "1"}
            (line exported "Exported" false {:type :boolean}))""",
        )
        val inspector = CaseGraphInspector(resolver)
        assertEquals(
            "MANTRA-LINK-TYPE",
            inspector.inspect(CaseReference("root.mantra")).diagnostics.single().finding.code,
        )
        resolver.packages[resolver.key("source.mantra")] = source
        val root = resolver.packages.getValue(resolver.key("root.mantra"))
        root.case = root.case.copy(links = listOf(link(from = InputAddress("unknown"))))
        assertEquals(
            "MANTRA-LINK-ADDRESS",
            inspector.inspect(CaseReference("root.mantra")).diagnostics.single().finding.code,
        )
        root.case = root.case.copy(links = listOf(link(to = InputAddress("answer"))))
        assertEquals(
            "MANTRA-LINK-ADDRESS",
            inspector.inspect(CaseReference("root.mantra")).diagnostics.single().finding.code,
        )
        root.case = root.case.copy(links = listOf(link(to = InputAddress("transferred", listOf("A")))))
        assertEquals(
            "MANTRA-LINK-ADDRESS",
            inspector.inspect(CaseReference("root.mantra")).diagnostics.single().finding.code,
        )
        root.case = root.case.copy(links = listOf(link()), inputs = mapOf("transferred" to Value.Nil))
        assertEquals(
            "MANTRA-LINK-CONFLICT",
            inspector.inspect(CaseReference("root.mantra")).diagnostics.single().finding.code,
        )
        root.case = root.case.copy(links = listOf(link(), link("./source.mantra")), inputs = emptyMap())
        assertEquals(
            "MANTRA-LINK-CONFLICT",
            inspector.inspect(CaseReference("root.mantra")).diagnostics.single().finding.code,
        )
    }

    @Test
    fun `dynamic target coordinates are deferred until the actual linked domain is evaluated`() {
        val resolver = Resolver()
        resolver.put("source.mantra", "(schema test/source {:version \"1\"} (line exported \"Exported\" 5))")
        resolver.put(
            "root.mantra",
            """(schema test/root {:version "1"}
            (input rows :table {:columns {:id :keyword}})
            (dimension member {:from rows :key :id})
            (input transferred :decimal {:per member}))""",
            case(
                "(case root (inputs {:rows []}))",
            ).copy(links = listOf(link(to = InputAddress("transferred", listOf("Future"))))),
        )
        val inspected = CaseGraphInspector(resolver).inspect(CaseReference("root.mantra"))
        assertTrue(inspected.succeeded, inspected.diagnostics.toString())
        assertTrue(assertNotNull(inspected.view).members.isEmpty())
        assertTrue(inspected.view!!.node("transferred").values.isEmpty())
        CaseGraphRunner(resolver).use { runner ->
            val evaluated = runner.run(CaseRunRequest(CaseReference("root.mantra")))
            assertFalse(evaluated.succeeded)
            assertTrue(evaluated.diagnostics.any { it.finding.code == "MANTRA-LINK-UNDEFINED" })
        }
    }

    @Test
    fun `source compilation errors retain actual ownership without interpreting them as runtime failure`() {
        val resolver = fixture("(missing-function 5)")
        val inspected = CaseGraphInspector(resolver).inspect(CaseReference("root.mantra"))
        assertFalse(inspected.succeeded)
        assertNull(inspected.view)
        assertTrue(inspected.diagnostics.isNotEmpty())
        assertTrue(
            inspected.diagnostics.all {
                it.case == resolver.key("source.mantra") &&
                    it.finding.category == DiagnosticCategory.STRUCTURAL
            },
        )
        assertEquals(0L, inspected.usage[RunCounter.FORMULA_EXECUTIONS])
    }

    @Test
    fun `unknown statically declared period or member coordinates are rejected without evaluating activity`() {
        val resolver = Resolver()
        resolver.put(
            "source.mantra",
            """(schema test/source {:version "1"}
            (dimension year {:periods {:start "2026-01-01" :unit :year :count 2}})
            (line exported "Exported" 5 {:per year :when false}))""",
        )
        resolver.put(
            "root.mantra",
            """(schema test/root {:version "1"}
            (dimension member {:members [:A]}) (input transferred :decimal {:per member}))""",
            case("(case root)").copy(
                links = listOf(
                    link(
                        from = InputAddress("exported", listOf("P3")),
                        to = InputAddress("transferred", listOf("A")),
                    ),
                ),
            ),
        )
        val inspector = CaseGraphInspector(resolver)
        assertEquals(
            "MANTRA-LINK-ADDRESS",
            inspector.inspect(CaseReference("root.mantra")).diagnostics.single().finding.code,
        )
        val root = resolver.packages.getValue(resolver.key("root.mantra"))
        root.case = root.case.copy(
            links = listOf(
                link(
                    from = InputAddress("exported", listOf("P2")),
                    to = InputAddress("transferred", listOf("Missing")),
                ),
            ),
        )
        assertEquals(
            "MANTRA-LINK-ADDRESS",
            inspector.inspect(CaseReference("root.mantra")).diagnostics.single().finding.code,
        )
        root.case = root.case.copy(
            links = listOf(
                link(
                    from = InputAddress("exported", listOf("P2")),
                    to = InputAddress("transferred", listOf("A")),
                ),
            ),
        )
        val validDeclaration = inspector.inspect(CaseReference("root.mantra"))
        assertTrue(validDeclaration.succeeded, validDeclaration.diagnostics.toString())
        assertEquals(0L, validDeclaration.usage[RunCounter.FORMULA_EXECUTIONS])
    }
}
