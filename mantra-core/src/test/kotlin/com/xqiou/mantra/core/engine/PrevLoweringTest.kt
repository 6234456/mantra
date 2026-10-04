package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.model.Value
import com.xqiou.normein.dsl.compiler.DslCompileRequest
import com.xqiou.normein.dsl.compiler.DslNamedDefinition
import com.xqiou.normein.dsl.compiler.DslSourceIndexOrigin
import com.xqiou.normein.dsl.compiler.DslSourcePosition
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuildResult
import com.xqiou.normein.dsl.environment.DslAnalysisScopeBuilder
import com.xqiou.normein.dsl.environment.DslRootDeclaration
import com.xqiou.normein.dsl.runtime.DslEvaluationEngine
import com.xqiou.normein.dsl.runtime.DslEvaluationInput
import com.xqiou.normein.dsl.runtime.DslEvaluationOutcome
import com.xqiou.normein.dsl.runtime.DslEvaluationRequest
import com.xqiou.normein.dsl.runtime.DslInputCandidate
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.type.DslFieldPresence
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrevLoweringTest {
    private fun compile(
        source: String,
        definitions: List<DslNamedDefinition> = emptyList(),
        position: DslSourcePosition? = null,
    ): PrevCompileResult.Success {
        val result = PrevLowering().compile(
            DslCompileRequest(
                source,
                namedDefinitions = definitions,
                expectedType = Types.expected(com.xqiou.mantra.core.model.ValueType.DECIMAL),
                logicalLocation = "closing",
                hostPosition = position,
            ),
            MantraKernel.environment,
            PreviousTargetResolver { symbol ->
                assertEquals("closing", symbol.name.value)
                PreviousTarget("closing", "year", DslType.Decimal)
            },
        ) { extra ->
            val builder = DslAnalysisScopeBuilder.create("mantra.prev.test", "1")
                .root(DslRootDeclaration("seed", DslType.Decimal, DslFieldPresence.OPTIONAL))
            extra.forEach(builder::root)
            assertIs<DslAnalysisScopeBuildResult.Success>(builder.build()).scope
        }
        return assertIs<PrevCompileResult.Success>(result, result.toString())
    }

    private fun evaluate(
        compiled: PrevCompileResult.Success,
        first: Boolean,
        previous: DslValue = DslValue.Nil,
        seed: Int? = null,
    ): Value {
        val previousRoots = PrevLowering.runtimeRoots(compiled.bindings, { first }) { previous }
        val roots = previousRoots.toMutableList()
        seed?.let {
            val candidate = DslInputCandidate.ControlledValue(DslValues.decimal(BigDecimal(it)))
            roots += DslInputRootCandidate("seed", candidate)
        }
        val result = DslEvaluationEngine().evaluate(
            DslEvaluationRequest(
                compiled.expression,
                MantraKernel.environment,
                DslEvaluationInput(
                    roots,
                    emptyList(),
                    MantraKernel.inputIdentity("prev-test", "$first/$previous/$seed"),
                ),
                MantraKernel.kernelArtifact,
            ),
        )
        val success = assertIs<DslEvaluationOutcome.Success<DslValue>>(result, result.toString())
        return Values.fromDsl(success.value)
    }

    @Test
    fun `unknown global prev compiles with captured lexical fallback and nullable typed prior`() {
        val compiled = compile("(let [x 8] (+ (prev closing (+ seed x)) 1))")
        assertEquals(1, compiled.bindings.size)
        assertEquals(Value.num(19), evaluate(compiled, true, seed = 10))
        assertEquals(Value.num(42), evaluate(compiled, false, DslValues.decimal(BigDecimal(41))))
    }

    @Test
    fun `later nil and omitted fallback roots never invoke initial expression`() {
        val compiled = compile("(prev closing (/ seed 0))")
        assertEquals(Value.Nil, evaluate(compiled, false))
        assertEquals(Value.num(9), evaluate(compiled, false, DslValues.decimal(BigDecimal(9))))
    }

    @Test
    fun `named function fallback is lazy and source ownership survives final path shifts`() {
        val definition = "(defn initial [^Decimal x] (+ seed x))"
        val source = "(prev closing (initial 2))"
        val compiled = compile(
            source,
            listOf(
                DslNamedDefinition(
                    "initial",
                    definition,
                    "defn.initial",
                    hostPosition = DslSourcePosition(20, 5, 400, 400 + definition.length),
                ),
            ),
            DslSourcePosition(4, 3, 100, 100 + source.length),
        )
        assertEquals(Value.num(12), evaluate(compiled, true, seed = 10))
        assertEquals(Value.num(7), evaluate(compiled, false, DslValues.decimal(BigDecimal(7))))
        val expectedOrigin = DslSourceIndexOrigin.NamedDefinition("initial", "defn.initial")
        val namedEntry = compiled.authorSourceIndex.values.any {
            it.origin == expectedOrigin && it.span.startOffset >= 400
        }
        assertTrue(namedEntry)
        assertEquals(100, compiled.bindings.single().callSpan.startOffset)
        val mainOrigin = DslSourceIndexOrigin.Expression("closing")
        assertEquals(mainOrigin, compiled.bindings.single().sourceOrigin)
    }

    @Test
    fun `lexical and named definitions may shadow host prev operation`() {
        val local = compile("(let [prev (fn [^Decimal x] (+ x 1))] (prev 4))")
        assertTrue(local.bindings.isEmpty())
        assertEquals(Value.num(5), evaluate(local, true))
        val definition = DslNamedDefinition("prev", "(defn prev [^Decimal x] (+ x 2))")
        val named = compile("(prev 4)", listOf(definition))
        assertTrue(named.bindings.isEmpty())
        assertEquals(Value.num(6), evaluate(named, true))
    }

    @Test
    fun `named definitions containing global prev and nested fallbacks retain typed compilation`() {
        val source = "(defn advance [] (prev closing (+ seed 1)))"
        val definition = DslNamedDefinition("advance", source)
        val named = compile("(advance)", listOf(definition))
        assertEquals(Value.num(11), evaluate(named, true, seed = 10))
        assertEquals(Value.num(20), evaluate(named, false, DslValues.decimal(BigDecimal(20))))
        assertIs<DslSourceIndexOrigin.NamedDefinition>(named.bindings.single().sourceOrigin)
        val nested = compile("(prev closing (prev closing seed))")
        assertEquals(2, nested.bindings.size)
        assertEquals(Value.num(10), evaluate(nested, true, seed = 10))
        assertEquals(Value.num(20), evaluate(nested, false, DslValues.decimal(BigDecimal(20))))
        assertEquals(1, nested.bindings.count { it.firstFallbackGuards.isNotEmpty() })
    }

    @Test
    fun `named calls retain first guards and unconditional dependency occurrences`() {
        val definition = DslNamedDefinition("initial", "(defn initial [] (+ seed 1))")
        val firstOnly = compile("(prev closing (initial))", listOf(definition))
        val rootNames = mapOf("seed" to "seed")
        val dependencies = FormulaDependencies.collect(
            firstOnly.expression.normalizedAst,
            rootNames,
            emptySet(),
            emptyMap(),
            firstOnly.bindings,
        )
        val firstGuards = setOf(firstOnly.bindings.single().id)
        val seedGuards = dependencies.single { it.rootName == "seed" }.firstFallbackGuards
        assertEquals(firstGuards, seedGuards)
        val mixed = compile("(+ seed (prev closing (initial)))", listOf(definition))
        val mixedDependencies = FormulaDependencies.collect(
            mixed.expression.normalizedAst,
            rootNames,
            emptySet(),
            emptyMap(),
            mixed.bindings,
        ).filter { it.rootName == "seed" }
        val expectedGuards = setOf(emptySet(), setOf(mixed.bindings.single().id))
        val actualGuards = mixedDependencies.map { it.firstFallbackGuards }.toSet()
        assertEquals(expectedGuards, actualGuards)
    }
}
