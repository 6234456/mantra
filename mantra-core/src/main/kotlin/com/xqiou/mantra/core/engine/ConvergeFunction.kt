package com.xqiou.mantra.core.engine

import com.xqiou.normein.dsl.catalog.DslFunctionDocumentation
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceClassification
import com.xqiou.normein.dsl.library.DslEvaluationStrategy
import com.xqiou.normein.dsl.library.DslFunctionInvocationException
import com.xqiou.normein.dsl.library.DslFunctionResult
import com.xqiou.normein.dsl.library.DslFunctionSignature
import com.xqiou.normein.dsl.library.DslFunctionSpec
import com.xqiou.normein.dsl.library.DslParameterType
import com.xqiou.normein.dsl.library.dslFunctionHandler
import com.xqiou.normein.dsl.runtime.DslBudgetCounter
import com.xqiou.normein.dsl.runtime.DslFunctionRuntime
import com.xqiou.normein.dsl.type.DslFunctionTypeSignature
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal

/** Pure bounded convergence; invocation and trace execution belong to Normein. */
internal fun convergeSpec(number: DslType, semanticsVersion: String, providerId: String): DslFunctionSpec =
    DslFunctionSpec(
        name = "calc/converge",
        semanticsVersion = semanticsVersion,
        signatures = listOf(
            DslFunctionSignature(
                parameters = listOf(
                    DslParameterType(
                        "f",
                        DslTypes.function(
                            listOf(DslFunctionTypeSignature(parameters = listOf(DslType.Decimal), returnType = number)),
                        ),
                    ),
                    DslParameterType("init", number),
                    DslParameterType("max-iterations", number),
                    DslParameterType("tolerance", number),
                ),
                returnType = DslType.Decimal,
            ),
        ),
        evaluationStrategy = DslEvaluationStrategy.EAGER,
        providerId = providerId,
        handler = dslFunctionHandler { arguments, _, runtime ->
            DslFunctionResult.Value(converge(arguments, runtime))
        },
        documentation = DslFunctionDocumentation(
            category = "mantra calculation primitives",
            summary = "Returns the first next=f(current) whose adjacent absolute delta is within tolerance. " +
                "The callback receives Decimal; monetary rounding is owned by the callback. " +
                "No value is returned when the exact integral 1..1000 iteration limit is exhausted.",
            classification = DslLanguageSurfaceClassification.DOMAIN_LIBRARY,
        ),
    )

private fun converge(arguments: List<DslValue>, runtime: DslFunctionRuntime): DslValue {
    var current = convergenceNumber(arguments[1], runtime)
    val maximumValue = convergenceNumber(arguments[2], runtime)
    runtime.charge(DslBudgetCounter.CONVERSIONS)
    val maximum = try {
        maximumValue.intValueExact()
    } catch (_: ArithmeticException) {
        throw DslFunctionInvocationException("DSL-MANTRA-CALC-ITERATIONS", "Iteration limit must be integral 1..1000")
    }
    if (maximum !in 1..1000) {
        throw DslFunctionInvocationException("DSL-MANTRA-CALC-ITERATIONS", "Iteration limit must be integral 1..1000")
    }
    val tolerance = convergenceNumber(arguments[3], runtime)
    if (tolerance.signum() < 0) {
        throw DslFunctionInvocationException("DSL-MANTRA-CALC-TOLERANCE", "Convergence tolerance must be nonnegative")
    }
    repeat(maximum) {
        runtime.checkpoint()
        runtime.charge(DslBudgetCounter.ITERATIONS)
        // Strict arity and real callback trace; never leading-argument invocation or replay.
        val next = convergenceNumber(runtime.invokeCallable(arguments[0], listOf(DslValues.decimal(current))), runtime)
        runtime.charge(DslBudgetCounter.NUMERIC_OPERATIONS, 3)
        if (next.subtract(current).abs().compareTo(tolerance) <= 0) return DslValues.decimal(next)
        current = next
    }
    throw DslFunctionInvocationException(
        "DSL-MANTRA-CALC-NOT-CONVERGED",
        "Adjacent delta did not reach tolerance within $maximum iterations",
    )
}

private fun convergenceNumber(value: DslValue, runtime: DslFunctionRuntime): BigDecimal = when (value) {
    is DslValue.DecimalValue -> value.value
    is DslValue.IntegerValue -> {
        runtime.charge(DslBudgetCounter.CONVERSIONS)
        BigDecimal(value.value)
    }
    is DslValue.LongValue -> {
        runtime.charge(DslBudgetCounter.CONVERSIONS)
        BigDecimal.valueOf(value.value)
    }
    else -> throw DslFunctionInvocationException("DSL-MANTRA-CALC-NUMBER", "Convergence needs a non-nil scalar number")
}
