package com.xqiou.mantra.core

import com.xqiou.mantra.core.engine.MantraKernel
import com.xqiou.normein.dsl.authoring.DslAuthoringService
import com.xqiou.normein.dsl.authoring.DslCompletionRequest
import com.xqiou.normein.dsl.compiler.DslCompileRequest
import com.xqiou.normein.dsl.compiler.DslCompileResult
import com.xqiou.normein.dsl.compiler.DslCompiledExpression
import com.xqiou.normein.dsl.compiler.DslNamedDefinition
import com.xqiou.normein.dsl.compiler.DslSemanticCompiler
import com.xqiou.normein.dsl.compiler.DslSourcePosition
import com.xqiou.normein.dsl.environment.DslAnalysisScope
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuildResult
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuilder
import com.xqiou.normein.dsl.environment.DslRootDeclaration
import com.xqiou.normein.dsl.form.DslFormReadResult
import com.xqiou.normein.dsl.form.DslFormReader
import com.xqiou.normein.dsl.form.DslFormReaderLimits
import com.xqiou.normein.dsl.form.DslFormsReadResult
import com.xqiou.normein.dsl.language.DslLanguageVersions
import com.xqiou.normein.dsl.language.DslNameCategory
import com.xqiou.normein.dsl.language.DslNameResult
import com.xqiou.normein.dsl.language.DslNames
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslInputCandidate
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.stdlib.NormeinStandardLibraries
import com.xqiou.normein.dsl.trace.DslTraceNode
import com.xqiou.normein.dsl.trace.DslTraceNodeKind
import com.xqiou.normein.dsl.trace.DslTracePolicy
import com.xqiou.normein.dsl.type.DslFieldPresence
import com.xqiou.normein.dsl.type.DslObjectField
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypeDefinition
import com.xqiou.normein.dsl.type.DslTypeId
import com.xqiou.normein.dsl.type.DslTypeSchema
import com.xqiou.normein.dsl.type.DslTypeSchemaResult
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValueConstructionException
import com.xqiou.normein.dsl.value.DslValueConstructionResult
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Executable part of RFC 0001, section "Acceptance contracts": the behaviour of the pinned Normein
 * kernel that each RFC item is measured against. A failure after a kernel bump means that an item
 * changed state; follow that item's migration notes in docs/rfc/0001-normein-dsl-kernel-extensions.md
 * before adjusting the expectation. Only kernel behaviour is pinned here, never Mantra behaviour.
 */
class NormeinRfcContractTest {
    private val environment = MantraKernel.environment

    private fun name(raw: String, category: DslNameCategory) = (DslNames.normalize(raw, category) as DslNameResult.Valid).name

    private val rowTypeId = DslTypeId(name("contract", DslNameCategory.TYPE_COMPONENT), name("row", DslNameCategory.TYPE_COMPONENT))
    private val rowType = DslTypeDefinition(
        rowTypeId,
        DslTypes.objectType(
            listOf(
                DslObjectField(name("key", DslNameCategory.FIELD), DslType.Keyword, DslFieldPresence.REQUIRED),
                DslObjectField(name("n", DslNameCategory.FIELD), DslType.Integer, DslFieldPresence.REQUIRED),
                DslObjectField(name("amount", DslNameCategory.FIELD), DslTypes.nullable(DslType.Decimal), DslFieldPresence.OPTIONAL),
            ),
        ),
    )
    private val typeSchema = (DslTypeSchema.create(listOf(rowType)) as DslTypeSchemaResult.Success).schema

    private fun scope(vararg roots: Pair<String, DslType>, presence: DslFieldPresence = DslFieldPresence.OPTIONAL): DslAnalysisScope {
        val builder = DslAnalysisScopeBuilder.create("mantra.rfc-contract", "1").type(rowType)
        roots.forEach { (root, type) -> builder.root(DslRootDeclaration(root, type, presence)) }
        return assertIs<DslAnalysisScopeBuildResult.Success>(builder.build()).scope
    }

    private fun compile(
        source: String,
        scope: DslAnalysisScope,
        definitions: List<DslNamedDefinition> = emptyList(),
        logicalLocation: String? = null,
        expected: DslType = DslType.Any,
    ): DslCompileResult = DslSemanticCompiler().compile(
        DslCompileRequest(source, namedDefinitions = definitions, expectedType = expected, logicalLocation = logicalLocation),
        environment,
        scope,
    )

    private fun compiled(result: DslCompileResult): DslCompiledExpression = assertIs<DslCompileResult.Success>(result, result.toString()).expression

    private fun codes(result: DslCompileResult): List<String> = assertIs<DslCompileResult.Failure>(result, "expected a compile failure").diagnostics.map { it.code }

    private fun evaluate(expression: DslCompiledExpression, roots: Map<String, DslValue>, trace: DslTracePolicy = DslTracePolicy.NONE) =
        DslEvaluationEngine().evaluate(
            DslEvaluationRequest(
                expression,
                environment,
                DslEvaluationInput(
                    roots = roots.map { (root, value) -> DslInputRootCandidate(root, DslInputCandidate.ControlledValue(value)) },
                    bindings = emptyList(),
                    inputIdentity = MantraKernel.inputIdentity("rfc-contract", "rfc-contract"),
                    tracePolicy = trace,
                ),
                kernelArtifact = MantraKernel.kernelArtifact,
            ),
        )

    private fun value(outcome: DslEvaluationOutcome<DslValue>): DslValue = assertIs<DslEvaluationOutcome.Success<DslValue>>(outcome, outcome.toString()).value

    private fun dec(v: String) = DslValues.decimal(BigDecimal(v))

    private fun kw(v: String) = DslValues.keyword(null, v)

    private fun astNodes(node: DslTraceNode): Map<String, DslTraceNode> {
        val result = linkedMapOf<String, DslTraceNode>()
        fun walk(n: DslTraceNode) {
            if (n.kind == DslTraceNodeKind.AST_NODE) result.putIfAbsent(n.nodeId.value, n)
            n.children.forEach(::walk)
        }
        walk(node)
        return result
    }

    @Test
    fun `pin - kernel versions and public reader grammar`() {
        assertEquals(
            DslLanguageVersions(
                languageSemantics = "25", reader = "3", parser = "7", evaluator = "descriptor-kernel-7", standardLibrary = "33",
                normalizedAstApi = "3", canonicalization = "3", typeSystem = "7", artifactSchema = "1",
            ),
            NormeinStandardLibraries.language.versions,
            "Kernel versions changed: re-run every RFC 0001 contract and update the kernel baseline in the RFC header",
        )
        assertEquals("normein-clj-form-reader", DslFormReader.GRAMMAR_IDENTITY.id)
        assertEquals("2", DslFormReader.GRAMMAR_IDENTITY.version)
    }

    @Test
    fun `A - a document has exactly one root form and reader limits cannot be raised`() {
        val forms = assertIs<DslFormsReadResult.Success>(DslFormReader().readForms("(schema s)\n(case c)", "doc"))
        assertEquals(2, forms.document.forms.size)
        assertEquals(2 to 1, forms.document.forms[1].span.line to forms.document.forms[1].span.column)
        val failure = assertIs<DslFormReadResult.Failure>(DslFormReader().readDocument("(schema s)\n(case c)", "doc"))
        val diagnostic = failure.diagnostics.single()
        assertEquals("DSL-PARSE-TRAILING-TOKEN", diagnostic.code, "RFC 0001 A: multi-form reading changed; see migration A")
        assertEquals(2 to 1, diagnostic.span!!.line to diagnostic.span!!.column)
        assertFailsWith<IllegalArgumentException> { DslFormReaderLimits(maxTokens = 8_193) }
        assertFailsWith<IllegalArgumentException> { DslFormReaderLimits(maxSourceLength = 65_537) }
        assertFailsWith<IllegalArgumentException> { DslFormReaderLimits(maxNesting = 129) }
    }

    @Test
    fun `A - the token limit admits 511 typical line forms per document`() {
        fun document(lines: Int) = buildString {
            append("(schema s {:title \"x\"}\n (section s1 \"S\"\n")
            repeat(lines) { i -> append("  (line l$i \"Zeile $i\" (- a$i b$i) {:op :minus :kz \"$i\"})\n") }
            append("))")
        }
        val reader = DslFormReader()
        assertIs<DslFormReadResult.Success>(reader.readDocument(document(511), "capacity"))
        val over = assertIs<DslFormReadResult.Failure>(reader.readDocument(document(512), "capacity"))
        assertEquals(listOf("DSL-PARSE-TOKEN-LIMIT"), over.diagnostics.map { it.code })
    }

    @Test
    fun `B - diagnostics of an embedded expression are relative to that expression`() {
        val failure = assertIs<DslCompileResult.Failure>(compile("(+ a\n     zzz)", scope("a" to DslType.Decimal), logicalLocation = "line.zve"))
        val unknown = failure.diagnostics.first { it.code == "DSL-REF-UNKNOWN-SYMBOL" }
        assertEquals(2 to 6, unknown.span!!.line to unknown.span!!.column, "RFC 0001 B: spans are no longer relative; remove Mantra's re-anchoring (migration B)")
        assertEquals("line.zve", unknown.logicalLocation)

        val source = "(+ a\n     zzz)"
        val position = DslSourcePosition(41, 22, 1830, 1830 + source.length)
        val hosted = assertIs<DslCompileResult.Failure>(DslSemanticCompiler().compile(
            DslCompileRequest(source, logicalLocation = "line.zve", hostPosition = position),
            environment,
            scope("a" to DslType.Decimal),
        ))
        val hostedUnknown = hosted.diagnostics.first { it.code == "DSL-REF-UNKNOWN-SYMBOL" }
        assertEquals(42 to 6, hostedUnknown.span!!.line to hostedUnknown.span!!.column)
        assertEquals(1830 + unknown.span!!.startOffset, hostedUnknown.span!!.startOffset)
    }

    @Test
    fun `B - identities ignore logical location, layout and unreferenced definitions`() {
        val scope = scope("a" to DslType.Decimal)
        val base = compiled(compile("(+ a 1)", scope, logicalLocation = "x"))
        val moved = compiled(compile("(+ a 1)", scope, logicalLocation = "y"))
        val spaced = compiled(compile("(+  a   1)", scope, logicalLocation = "x"))
        val unused = compiled(compile("(+ a 1)", scope, listOf(DslNamedDefinition("unused", "(defn unused [^Decimal v] (* v 2))", "defn.unused")), "x"))
        listOf(moved, unused).forEach { other ->
            assertEquals(base.sourceFingerprint, other.sourceFingerprint)
            assertEquals(base.canonicalAstHash, other.canonicalAstHash)
            assertEquals(base.executionFingerprint, other.executionFingerprint)
        }
        assertFalse(base.sourceFingerprint == spaced.sourceFingerprint, "the source fingerprint covers the exact author text")
        assertEquals(base.canonicalAstHash, spaced.canonicalAstHash)
        assertEquals(base.executionFingerprint, spaced.executionFingerprint)

        val twice = compiled(compile("(twice a)", scope, listOf(DslNamedDefinition("twice", "(defn twice [^Decimal v] (* v 2))", "defn.twice"))))
        val thrice = compiled(compile("(twice a)", scope, listOf(DslNamedDefinition("twice", "(defn twice [^Decimal v] (* v 3))", "defn.twice"))))
        assertFalse(twice.sourceFingerprint == thrice.sourceFingerprint)
        assertFalse(twice.executionFingerprint == thrice.executionFingerprint)
    }

    @Test
    fun `C - records need a declared type and structural failures carry no field path`() {
        val scope = scope("row" to DslTypes.ref(rowTypeId))
        val map = DslValues.map(listOf(kw("key") to kw("A"), kw("n") to DslValues.integer(BigInteger.ONE)))
        val rejected = assertIs<DslEvaluationOutcome.Failure>(evaluate(compiled(compile("row.n", scope)), mapOf("row" to map)))
        assertTrue(rejected.diagnostics.all { it.code == "DSL-INPUT-ROOT-TYPE" }, rejected.diagnostics.toString())

        val integral = DslValues.importStructuredHost(mapOf("key" to kw("A"), "n" to dec("1")), DslTypes.ref(rowTypeId), typeSchema)
        val violation = assertIs<DslValueConstructionResult.Failure>(integral).violation
        assertEquals("DSL-VALUE-STRUCTURAL-TYPE", violation.code)
        assertEquals("$.n", violation.path, "Structural failures name the field")

        val record = DslValues.importStructuredHost(mapOf("key" to kw("A"), "n" to DslValues.integer(BigInteger.ONE)), DslTypes.ref(rowTypeId), typeSchema)
        val imported = assertIs<DslValueConstructionResult.Success>(record).value
        assertEquals(BigInteger.ONE, (value(evaluate(compiled(compile("row.n", scope)), mapOf("row" to imported))) as DslValue.IntegerValue).value)
    }

    @Test
    fun `C - value limits count every nested item`() {
        // Each record costs 1 + 2 x fields items against the 10,000-item limit: 1,428 rows of 3 fields.
        fun rows(count: Int) = List(count) { i -> mapOf("key" to kw("k$i"), "n" to DslValues.integer(i.toBigInteger()), "amount" to dec("1.5")) }
        val table = DslTypes.vector(DslTypes.ref(rowTypeId))
        assertIs<DslValueConstructionResult.Success>(DslValues.importStructuredHost(rows(1_428), table, typeSchema))
        val over = assertIs<DslValueConstructionResult.Failure>(DslValues.importStructuredHost(rows(1_429), table, typeSchema))
        assertEquals("DSL-VALUE-ITEM-LIMIT", over.violation.code)

        DslValues.vector(List(5_000) { dec("1") })
        val tooLong = assertFailsWith<DslValueConstructionException> { DslValues.vector(List(5_001) { dec("1") }) }
        assertEquals("DSL-VALUE-ITEM-LIMIT", tooLong.violation.code)
    }

    @Test
    fun `D - apply is typed Any and decimal rounding results are nullable`() {
        val scope = scope("m" to DslTypes.map(DslType.Keyword, DslType.Decimal), "x" to DslType.Decimal)
        assertEquals(DslType.Decimal, compiled(compile("(apply min (vals m))", scope)).inferredType)
        compiled(compile("(decimal/divide 1 (apply min (vals m)) 4)", scope))

        val rounded = compiled(compile("(decimal/round x 2)", scope)).inferredType
        assertEquals(DslType.Decimal, rounded)
        compiled(compile("(decimal/round x 2)", scope, expected = DslType.Decimal))
    }

    @Test
    fun `E - an absent optional root fails when read while explicit nil succeeds`() {
        val optional = compiled(compile("(if (nil? x) 0 x)", scope("x" to DslTypes.nullable(DslType.Decimal))))
        val absent = assertIs<DslEvaluationOutcome.Failure>(evaluate(optional, emptyMap()))
        val missing = absent.diagnostics.single()
        assertEquals("DSL-RUNTIME-MISSING-ROOT", missing.code, "RFC 0001 E changed; see migration E")
        assertEquals(1 to 11, missing.span!!.line to missing.span!!.column)
        assertEquals(BigInteger.ZERO, (value(evaluate(optional, mapOf("x" to DslValue.Nil))) as DslValue.IntegerValue).value)

        val required = compiled(compile("(if (nil? x) 0 x)", scope("x" to DslTypes.nullable(DslType.Decimal), presence = DslFieldPresence.REQUIRED)))
        val rejected = assertIs<DslEvaluationOutcome.Failure>(evaluate(required, emptyMap()))
        assertEquals(listOf("DSL-INPUT-MISSING-ROOT"), rejected.diagnostics.map { it.code })
    }

    @Test
    fun `F - a full trace renders scalar node results by canonical node path`() {
        val scope = scope("x" to DslType.Decimal, "handwerker" to DslType.Decimal)
        val expression = compiled(compile("(if (> x 10) (min (* 0.2 handwerker) 4000) 0)", scope))
        val outcome = assertIs<DslEvaluationOutcome.Success<DslValue>>(evaluate(expression, mapOf("x" to dec("1200"), "handwerker" to dec("1200")), DslTracePolicy.FULL))
        val nodes = astNodes(outcome.trace!!)
        assertEquals("240.0", nodes.getValue("0").resultSummary?.rendered)
        assertEquals("true", nodes.getValue("0.0").resultSummary?.rendered)
        assertEquals("240.0", nodes.getValue("0.1.1").resultSummary?.rendered)
        assertEquals("1200", nodes.getValue("0.1.1.2").resultSummary?.rendered)
        assertFalse("0.2" in nodes, "the branch not taken is absent from the trace")
        assertEquals(0 to 45, expression.normalizedAst.span.startOffset to expression.normalizedAst.span.endOffset)

        val collection = compiled(compile("(if (> x 10) :high {:a 1 :b 2})", scope))
        val collectionTrace = assertIs<DslEvaluationOutcome.Success<DslValue>>(evaluate(collection, mapOf("x" to dec("1"), "handwerker" to dec("0")), DslTracePolicy.FULL)).trace!!
        val root = astNodes(collectionTrace).getValue("0").resultSummary!!
        assertEquals("{:a 1, :b 2}", root.rendered)
        assertEquals(2L, root.itemCount)

        val capped = compiled(compile("(cap (* 0.2 handwerker))", scope, listOf(DslNamedDefinition("cap", "(defn cap [^Decimal v] (min v 4000))", "defn.cap"))))
        val cappedTrace = assertIs<DslEvaluationOutcome.Success<DslValue>>(evaluate(capped, mapOf("x" to dec("1"), "handwerker" to dec("30000")), DslTracePolicy.FULL)).trace!!
        assertEquals("4000", astNodes(cappedTrace).getValue("0.0.0").resultSummary?.rendered, "definition bodies are traced below the callable node")
        assertTrue(capped.sourceIndex.isNotEmpty(), "Trace node paths have a public source index")
    }

    @Test
    fun `G - bounded iteration is expressible with reduce over range within the budget`() {
        val scope = scope("net" to DslType.Decimal, "rate" to DslType.Decimal)
        listOf(
            "(loop [i 0] (if (< i 3) (recur (inc i)) i))",
            "(nth (iterate inc 0) 10)",
            "(reduce (fn [a x] (if (> a 10) (reduced a) (+ a x))) 0 (range 100))",
        ).forEach { source -> assertTrue("DSL-FUNCTION-UNKNOWN" in codes(compile(source, scope)), source) }

        val grossUp = compiled(compile("(reduce (fn [g _] (decimal/round (+ net (* rate g)) 2)) net (range 60))", scope))
        val grossed = value(evaluate(grossUp, mapOf("net" to dec("1000"), "rate" to dec("0.25"))))
        assertEquals(BigDecimal("1333.33"), (grossed as DslValue.DecimalValue).value)

        val inputs = mapOf("net" to dec("0"), "rate" to dec("0"))
        assertEquals(BigInteger("449985000"), (value(evaluate(compiled(compile("(reduce + 0 (range 30000))", scope)), inputs)) as DslValue.IntegerValue).value)
        val exhausted = assertIs<DslEvaluationOutcome.Failure>(evaluate(compiled(compile("(reduce + 0 (range 100001))", scope)), inputs))
        assertEquals(listOf("DSL-LIMIT-NUMERIC-OPERATIONS"), exhausted.diagnostics.map { it.code })
    }

    @Test
    fun `H - the authoring service works with a host analysis scope`() {
        val service = DslAuthoringService(environment, scope("bruttolohn" to DslType.Decimal, "werbungskosten" to DslType.Decimal))
        assertEquals(listOf("bruttolohn"), service.complete(DslCompletionRequest("(- brutto", 9)).items.map { it.label })
        assertEquals("alloc/pro-rata", service.hover("(alloc/pro-rata 1 {})", 3)?.symbol)
    }

    @Test
    fun `I - numeric literal rules of the kernel`() {
        val scope = scope()
        listOf(".5", "1.", "1e3", "-0.0").forEach { source ->
            assertEquals(DslType.Decimal, compiled(compile(source, scope)).inferredType, source)
        }
        assertEquals(DslType.Integer, compiled(compile("+7", scope)).inferredType)
        listOf("1.5M", "1N", "0x10", "1/2").forEach { source -> codes(compile(source, scope)) }
        assertEquals(listOf("DSL-VALUE-NUMERIC-SCALE-LIMIT"), codes(compile("0." + "0".repeat(1_000) + "1", scope)))
        val limit = assertFailsWith<DslValueConstructionException> { DslValues.decimal(BigDecimal("1E-1001")) }
        assertEquals("DSL-VALUE-NUMERIC-SCALE-LIMIT", limit.violation.code)
    }

    @Test
    fun `J - roots resolve by position and cannot carry a namespace`() {
        val scope = scope("amount" to DslType.Decimal)
        assertTrue("amount" in MantraKernel.callableNames)
        compiled(compile("(+ amount 1)", scope))
        compiled(compile("(amount \"12,50\")", scope))
        codes(compile("(+ mantra/amount 1)", scope))
    }
}
