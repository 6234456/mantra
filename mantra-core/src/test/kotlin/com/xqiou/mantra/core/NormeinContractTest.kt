package com.xqiou.mantra.core

import com.xqiou.normein.dsl.binding.DslFingerprintAlgorithm
import com.xqiou.normein.dsl.binding.DslFingerprintAuthority
import com.xqiou.normein.dsl.catalog.DslArityShape
import com.xqiou.normein.dsl.catalog.DslFunctionDocumentation
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceClassification
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceEntry
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceKind
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceStatus
import com.xqiou.normein.dsl.compiler.DslCompileRequest
import com.xqiou.normein.dsl.compiler.DslCompileResult
import com.xqiou.normein.dsl.compiler.DslNamedDefinition
import com.xqiou.normein.dsl.compiler.DslSemanticCompiler
import com.xqiou.normein.dsl.environment.DslAnalysisScope
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuildResult
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuilder
import com.xqiou.normein.dsl.environment.DslEnvironment
import com.xqiou.normein.dsl.environment.DslEnvironmentBuildResult
import com.xqiou.normein.dsl.environment.DslEnvironmentBuilder
import com.xqiou.normein.dsl.environment.DslRootDeclaration
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormReadResult
import com.xqiou.normein.dsl.form.DslFormReader
import com.xqiou.normein.dsl.identity.DslInputIdentity
import com.xqiou.normein.dsl.identity.DslInputLocatorResult
import com.xqiou.normein.dsl.identity.DslInputLocators
import com.xqiou.normein.dsl.language.DslNameCategory
import com.xqiou.normein.dsl.language.DslNameResult
import com.xqiou.normein.dsl.language.DslNames
import com.xqiou.normein.dsl.library.DslArtifactIdentity
import com.xqiou.normein.dsl.library.DslEvaluationStrategy
import com.xqiou.normein.dsl.library.DslFunctionResult
import com.xqiou.normein.dsl.library.DslFunctionSignature
import com.xqiou.normein.dsl.library.DslFunctionSpec
import com.xqiou.normein.dsl.library.DslLibraryDescriptor
import com.xqiou.normein.dsl.library.DslLibraryRequirement
import com.xqiou.normein.dsl.library.DslParameterType
import com.xqiou.normein.dsl.library.DslProviderConcurrency
import com.xqiou.normein.dsl.library.DslProviderManifestEntry
import com.xqiou.normein.dsl.library.DslProviderReproducibility
import com.xqiou.normein.dsl.library.dslFunctionHandler
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslInputCandidate
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.stdlib.NormeinStandardLibraries
import com.xqiou.normein.dsl.type.DslFieldPresence
import com.xqiou.normein.dsl.type.DslObjectField
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Contract of the pinned Normein kernel APIs Mantra relies on (form reader, typed roots, records, libraries). */
class NormeinContractTest {
    private val hash = "a".repeat(64)
    private val artifact =
        DslArtifactIdentity("application-code:mantra-spike", hash, hash, hash, "spike", listOf("mantra.spike"))

    private val doubleFn = DslFunctionSpec(
        name = "spike/double",
        semanticsVersion = "1",
        signatures = listOf(
            DslFunctionSignature(
                parameters = listOf(DslParameterType("x", DslType.Decimal)),
                returnType = DslType.Decimal,
            ),
        ),
        evaluationStrategy = DslEvaluationStrategy.EAGER,
        providerId = "mantra.spike",
        handler = dslFunctionHandler { args, _, _ ->
            val x = when (val v = args[0]) {
                is DslValue.DecimalValue -> v.value
                is DslValue.IntegerValue -> v.value.toBigDecimal()
                is DslValue.LongValue -> BigDecimal.valueOf(v.value)
                else -> error("number required")
            }
            DslFunctionResult.Value(DslValues.decimal(x.multiply(BigDecimal(2))))
        },
        documentation = DslFunctionDocumentation(
            category = "spike",
            summary = "Doubles a number.",
            classification = DslLanguageSurfaceClassification.DOMAIN_LIBRARY,
        ),
    )

    private val environment: DslEnvironment = run {
        val library = DslLibraryDescriptor(
            id = "mantra.spike",
            semanticsVersion = "1",
            dependencies = setOf(
                DslLibraryRequirement(
                    NormeinStandardLibraries.EXTENSION_LIBRARY_ID,
                    NormeinStandardLibraries.LIBRARY_SEMANTICS_VERSION,
                ),
            ),
            environmentTags = setOf("pure"),
            functions = listOf(doubleFn),
            providers = listOf(
                DslProviderManifestEntry(
                    "mantra.spike",
                    artifact,
                    DslProviderReproducibility.FIRST_PARTY_REVIEWED,
                    DslProviderConcurrency.THREAD_SAFE,
                    listOf(doubleFn.identity),
                    null,
                ),
            ),
            artifact = artifact,
        )
        val base = NormeinStandardLibraries.language
        val language = base.copy(
            surfaceManifest = base.surfaceManifest.copy(
                entries = (
                    base.surfaceManifest.entries + DslLanguageSurfaceEntry(
                        surfaceId = "function:mantra:spike/double",
                        sourceName = "spike/double",
                        kind = DslLanguageSurfaceKind.FUNCTION,
                        classification = DslLanguageSurfaceClassification.DOMAIN_LIBRARY,
                        arities = listOf(DslArityShape.Fixed(1)),
                        evaluationStrategy = DslEvaluationStrategy.EAGER,
                        status = DslLanguageSurfaceStatus.SUPPORTED,
                    )
                    ).sortedBy { it.surfaceId },
            ),
        )
        val built = DslEnvironmentBuilder.create(
            "mantra.spike",
            "1",
            language,
            NormeinStandardLibraries.pureExecutionProfile,
        )
            .addAll(NormeinStandardLibraries.descriptors)
            .add(library)
            .build()
        assertIs<DslEnvironmentBuildResult.Success>(built, built.toString()).environment
    }

    private fun fieldName(raw: String) = (DslNames.normalize(raw, DslNameCategory.FIELD) as DslNameResult.Valid).name

    private val personType = DslTypes.objectType(
        listOf(
            DslObjectField(fieldName("key"), DslType.Keyword, DslFieldPresence.REQUIRED),
            DslObjectField(fieldName("label"), DslType.Text, DslFieldPresence.REQUIRED),
        ),
    )

    private val personTypeId = com.xqiou.normein.dsl.type.DslTypeId(
        (DslNames.normalize("mantra", DslNameCategory.TYPE_COMPONENT) as DslNameResult.Valid).name,
        (DslNames.normalize("person", DslNameCategory.TYPE_COMPONENT) as DslNameResult.Valid).name,
    )
    private val personDefinition = com.xqiou.normein.dsl.type.DslTypeDefinition(personTypeId, personType)
    private val typeSchema = (
        com.xqiou.normein.dsl.type.DslTypeSchema.create(
            listOf(personDefinition),
        ) as com.xqiou.normein.dsl.type.DslTypeSchemaResult.Success
        ).schema

    private val scope: DslAnalysisScope = assertIs<DslAnalysisScopeBuildResult.Success>(
        DslAnalysisScopeBuilder.create("mantra.spike.scope", "1")
            .type(personDefinition)
            .root(DslRootDeclaration("bruttolohn", DslType.Decimal, DslFieldPresence.OPTIONAL))
            .root(DslRootDeclaration("werbungskosten", DslType.Decimal, DslFieldPresence.OPTIONAL))
            .root(
                DslRootDeclaration(
                    "per-person",
                    DslTypes.map(DslType.Keyword, DslType.Decimal),
                    DslFieldPresence.OPTIONAL,
                ),
            )
            .root(DslRootDeclaration("person", DslTypes.ref(personTypeId), DslFieldPresence.OPTIONAL))
            .build(),
    ).scope

    private fun compile(source: String, definitions: List<DslNamedDefinition> = emptyList()) =
        DslSemanticCompiler().compile(DslCompileRequest(source, namedDefinitions = definitions), environment, scope)

    private fun eval(
        source: String,
        roots: Map<String, DslValue>,
        definitions: List<DslNamedDefinition> = emptyList(),
    ): DslValue {
        val compiled = compile(source, definitions)
        val expression = assertIs<DslCompileResult.Success>(compiled, compiled.toString()).expression
        val outcome = DslEvaluationEngine().evaluate(
            DslEvaluationRequest(
                expression,
                environment,
                DslEvaluationInput(
                    roots = roots.map { (k, v) -> DslInputRootCandidate(k, DslInputCandidate.ControlledValue(v)) },
                    bindings = emptyList(),
                    inputIdentity = DslInputIdentity(
                        locator = assertIs<DslInputLocatorResult.Success>(
                            DslInputLocators.create("mantra", "spike", "case"),
                        ).locator,
                        snapshotVersion = "1",
                        fingerprintAuthority = DslFingerprintAuthority.KERNEL,
                        fingerprintAlgorithm = DslFingerprintAlgorithm.NORMEIN_CANONICAL_SHA_256_V1,
                        snapshotFingerprint = hash,
                    ),
                ),
                kernelArtifact = artifact,
            ),
        )
        return assertIs<DslEvaluationOutcome.Success<DslValue>>(outcome, outcome.toString()).value
    }

    private fun dec(v: String) = DslValues.decimal(BigDecimal(v))

    @Test
    fun `decimal arithmetic and required roots`() {
        val compiled = assertIs<DslCompileResult.Success>(
            compile("(- bruttolohn (max werbungskosten 1230))"),
        ).expression
        assertEquals(setOf("bruttolohn", "werbungskosten"), compiled.requiredRoots.keys)
        val value = eval(
            "(- bruttolohn (max werbungskosten 1230))",
            mapOf(
                "bruttolohn" to dec("60000.00"),
                "werbungskosten" to dec("800.00"),
            ),
        )
        assertEquals(BigDecimal("58770.00"), (value as DslValue.DecimalValue).value)
    }

    @Test
    fun `keyword maps and object roots`() {
        val map = DslValues.map(
            listOf(
                DslValues.keyword(null, "A") to dec("10"),
                DslValues.keyword(null, "B") to dec("32.5"),
            ),
        )
        assertEquals(
            BigDecimal("42.5"),
            (eval("(sum (vals per-person))", mapOf("per-person" to map)) as DslValue.DecimalValue).value,
        )
        val imported = DslValues.importStructuredHost(
            mapOf("key" to DslValues.keyword(null, "B"), "label" to "Person B"),
            DslTypes.ref(personTypeId),
            typeSchema,
        )
        println("imported=" + imported)
        val person = (imported as com.xqiou.normein.dsl.value.DslValueConstructionResult.Success).value
        val picked = eval("(get per-person person.key)", mapOf("per-person" to map, "person" to person))
        assertEquals(BigDecimal("32.5"), (picked as DslValue.DecimalValue).value)
        assertEquals("Person B", (eval("person.label", mapOf("person" to person)) as DslValue.TextValue).value)
    }

    @Test
    fun `domain function and named definitions`() {
        assertEquals(BigDecimal("21.0"), (eval("(spike/double 10.5)", emptyMap()) as DslValue.DecimalValue).value)
        val defs = listOf(DslNamedDefinition("halve", "(defn halve [^Decimal x] (/ x 2))"))
        assertEquals(
            BigDecimal("5"),
            (eval("(halve 10)", emptyMap(), defs) as DslValue.IntegerValue).value.toBigDecimal(),
        )
    }

    @Test
    fun `form reader exposes numbers and keywords as symbol atoms`() {
        val source = """
            (schema est/2025 {:title "ESt" :year 2025}
              ; comment
              (line zve "zu versteuerndes Einkommen" (- a b) {:round [0 :floor] :active true :rate 0.055}))
        """.trimIndent()
        val doc = assertIs<DslFormReadResult.Success>(DslFormReader().readDocument(source, "spike.clj")).document
        val root = doc.root as DslForm.Sequence
        val kinds = mutableListOf<String>()
        fun walk(form: DslForm) {
            when (form) {
                is DslForm.Atom -> kinds += "${form.kind}:${form.sourceText}"
                is DslForm.Sequence -> {
                    kinds += "${form.kind}("
                    form.values.forEach(::walk)
                    kinds += ")"
                }
                is DslForm.Postfix -> kinds += "POSTFIX:${source.substring(form.span.startOffset, form.span.endOffset)}"
            }
        }
        walk(root)
        println(kinds.joinToString(" "))
        println("comments=" + doc.comments.map { it.sourceText })
        val line = root.values[3] as DslForm.Sequence
        val formula = line.values[3]
        assertEquals("(- a b)", source.substring(formula.span.startOffset, formula.span.endOffset))
    }
}
