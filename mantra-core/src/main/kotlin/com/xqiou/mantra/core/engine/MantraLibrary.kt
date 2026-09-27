package com.xqiou.mantra.core.engine

import com.xqiou.normein.dsl.catalog.DslArityShape
import com.xqiou.normein.dsl.catalog.DslFunctionDocumentation
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceClassification
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceEntry
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceKind
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceStatus
import com.xqiou.normein.dsl.environment.DslLanguageDescriptor
import com.xqiou.normein.dsl.library.DslArtifactIdentity
import com.xqiou.normein.dsl.library.DslEvaluationStrategy
import com.xqiou.normein.dsl.library.DslFunctionHandler
import com.xqiou.normein.dsl.library.DslFunctionInvocationException
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
import com.xqiou.normein.dsl.runtime.DslBudgetCounter
import com.xqiou.normein.dsl.stdlib.NormeinStandardLibraries
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Domain-neutral calculation primitives that recur across tax and accounting schemas. They are a
 * regular Normein domain library composed next to the standard libraries; the Normein kernel itself
 * is not modified.
 *
 * | Function | Pattern |
 * | --- | --- |
 * | `alloc/pro-rata` | allocation key (Umlage, pro-rata allocation) with exact footing |
 * | `alloc/capped` | constrained allocation with per-member caps (IAS 36.105) |
 * | `alloc/waterfall` | allocation by priority (loss absorption order, caps) |
 * | `table/band` | banded lookup tables |
 * | `fin/pmt` | annuity payment (leases, loans) |
 * | `calc/stepwise` | banded rates (stufenweise Berechnung) |
 * | `dim/sum`, `dim/min`, `dim/max` | cross-footing and extrema over dimension members |
 * | `dim/rollup` | sum source members into one declared parent member |
 * | `fin/df`, `fin/npv` | discounting |
 */
object MantraLibrary {
    const val LIBRARY_ID: String = "mantra.calc"
    const val SEMANTICS_VERSION: String = "1"
    const val PROVIDER_ID: String = "mantra.calc.provider"

    private val numberType: DslType = DslTypes.union(DslType.Integer, DslType.Long, DslType.Decimal)
    private val numberMap: DslType = DslTypes.map(DslType.Keyword, numberType)
    private val decimalMap: DslType = DslTypes.map(DslType.Keyword, DslType.Decimal)

    val functions: List<DslFunctionSpec> = listOf(
        function(
            "alloc/pro-rata",
            "Allocates an amount over the keys of a weight map, rounded to a scale with the largest-remainder method so that the parts add up to the rounded amount.",
            signature("amount" to numberType, "weights" to numberMap, "scale" to numberType, returns = decimalMap),
        ) { args -> map(proRata(args[0].number(), args[1].numberMap(), args[2].scale())) },
        function(
            "alloc/capped",
            "Allocates an amount pro rata over a weight map but never beyond each key's cap; excess is re-allocated to uncapped keys (water-filling). Unallocatable excess is dropped.",
            signature("amount" to numberType, "weights" to numberMap, "caps" to numberMap, "scale" to numberType, returns = decimalMap),
        ) { args -> map(capped(args[0].number(), args[1].numberMap(), args[2].numberMap(), args[3].scale())) },
        function(
            "alloc/waterfall",
            "Allocates an amount over the keys of a capacity map in key order: each key receives min(capacity, remaining); a nil capacity takes everything left.",
            signature("amount" to numberType, "capacities" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)), returns = decimalMap),
        ) { args -> map(waterfall(args[0].number(), args[1].entries().map { (k, v) -> k to v.numberOrNull() })) },
        function(
            "table/band",
            "Looks up the value of the last row [threshold value] whose threshold is not above x (thresholds ascending); returns the default or nil below the first threshold.",
            signature("x" to numberType, "rows" to DslTypes.vector(DslTypes.vector(DslTypes.nullable(numberType))), returns = DslTypes.nullable(DslType.Decimal)),
            signature("x" to numberType, "rows" to DslTypes.vector(DslTypes.vector(DslTypes.nullable(numberType))), "default" to DslTypes.nullable(numberType), returns = DslTypes.nullable(DslType.Decimal)),
        ) { args -> band(args[0].number(), args[1], args.getOrNull(2)?.numberOrNull())?.let(DslValues::decimal) ?: DslValue.Nil },
        function(
            "fin/pmt",
            "Annuity payment per period for a present value pv over n periods at rate (end of period), rounded to scale; rate 0 gives pv/n.",
            signature("rate" to numberType, "n" to numberType, "pv" to numberType, "scale" to numberType, returns = DslType.Decimal),
        ) { args -> DslValues.decimal(pmt(args[0].number(), args[1].number().intValueExactOrFail("fin/pmt periods"), args[2].number(), args[3].scale())) },
        function(
            "calc/stepwise",
            "Applies banded rates: bands are [[upper-limit rate] ...] with nil as the open upper limit of the last band; returns the sum of (band slice x rate).",
            signature("amount" to numberType, "bands" to DslTypes.vector(DslTypes.vector(DslTypes.nullable(numberType))), returns = DslType.Decimal),
        ) { args -> DslValues.decimal(stepwise(args[0].number(), args[1])) },
        function(
            "dim/sum",
            "Sums the values of a member map (cross-footing over a dimension); nil values count as zero.",
            signature("values" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)), returns = DslType.Decimal),
        ) { args -> DslValues.decimal(args[0].entries().fold(BigDecimal.ZERO) { acc, (_, v) -> acc + (v.numberOrNull() ?: BigDecimal.ZERO) }) },
        function(
            "dim/rollup",
            "Sums a source-member amount map for one parent key using a declared source-to-parent relation map.",
            signature("values" to numberMap, "parents" to DslTypes.map(DslType.Keyword, DslType.Keyword), "target" to DslType.Keyword, returns = DslType.Decimal),
        ) { args ->
            val parents = args[1].entries().associate { (source, parent) -> keyText(source) to keyText(parent) }
            val target = keyText(args[2])
            DslValues.decimal(args[0].numberMap().fold(BigDecimal.ZERO) { acc, (source, value) ->
                val parent = parents[keyText(source)]
                    ?: fail("DSL-MANTRA-ROLLUP-KEY", "No parent relation for source member ${keyText(source)}")
                if (parent == target) acc + value else acc
            })
        },
        function(
            "dim/min",
            "Smallest non-nil value of a member map, or nil when the map has no values.",
            signature("values" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)), returns = DslTypes.nullable(DslType.Decimal)),
        ) { args -> args[0].entries().mapNotNull { (_, v) -> v.numberOrNull() }.minOrNull()?.let(DslValues::decimal) ?: DslValue.Nil },
        function(
            "dim/max",
            "Largest non-nil value of a member map, or nil when the map has no values.",
            signature("values" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)), returns = DslTypes.nullable(DslType.Decimal)),
        ) { args -> args[0].entries().mapNotNull { (_, v) -> v.numberOrNull() }.maxOrNull()?.let(DslValues::decimal) ?: DslValue.Nil },
        function(
            "fin/df",
            "Discount factor 1 / (1 + rate)^t rounded to scale (end-of-period convention).",
            signature("rate" to numberType, "t" to numberType, "scale" to numberType, returns = DslType.Decimal),
        ) { args ->
            val t = args[1].number().intValueExactOrFail("fin/df period")
            DslValues.decimal(BigDecimal.ONE.divide(BigDecimal.ONE.add(args[0].number()).pow(t, MathContext.DECIMAL128), args[2].scale(), RoundingMode.HALF_UP))
        },
        function(
            "fin/npv",
            "Present value of periodic cash flows at the end of periods 1..n, discounted at rate and rounded to scale.",
            signature("rate" to numberType, "flows" to DslTypes.vector(numberType), "scale" to numberType, returns = DslType.Decimal),
        ) { args ->
            val rate = BigDecimal.ONE.add(args[0].number())
            val flows = (args[1] as DslValue.VectorValue).values.map { it.number() }
            val pv = flows.foldIndexed(BigDecimal.ZERO) { index, acc, flow ->
                acc + flow.divide(rate.pow(index + 1, MathContext.DECIMAL128), MathContext.DECIMAL128)
            }
            DslValues.decimal(pv.setScale(args[2].scale(), RoundingMode.HALF_UP))
        },
    )

    fun extendLanguage(base: DslLanguageDescriptor): DslLanguageDescriptor = base.copy(
        surfaceManifest = base.surfaceManifest.copy(
            entries = (base.surfaceManifest.entries + functions.map { function ->
                DslLanguageSurfaceEntry(
                    surfaceId = "function:mantra:${function.name}",
                    sourceName = function.name,
                    kind = DslLanguageSurfaceKind.FUNCTION,
                    classification = DslLanguageSurfaceClassification.DOMAIN_LIBRARY,
                    arities = function.signatures.map { DslArityShape.Fixed(it.parameters.size) }.distinct(),
                    evaluationStrategy = function.evaluationStrategy,
                    status = DslLanguageSurfaceStatus.SUPPORTED,
                )
            }).sortedBy(DslLanguageSurfaceEntry::surfaceId),
        ),
    )

    val artifact: DslArtifactIdentity by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        functions.forEach { digest.update("${it.name}@${it.semanticsVersion}\n".toByteArray()) }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        DslArtifactIdentity("application-code:$LIBRARY_ID:$SEMANTICS_VERSION", sha, sha, sha, "mantra-$SEMANTICS_VERSION", listOf(PROVIDER_ID))
    }

    fun descriptor(): DslLibraryDescriptor = DslLibraryDescriptor(
        id = LIBRARY_ID,
        semanticsVersion = SEMANTICS_VERSION,
        dependencies = setOf(DslLibraryRequirement(NormeinStandardLibraries.EXTENSION_LIBRARY_ID, NormeinStandardLibraries.LIBRARY_SEMANTICS_VERSION)),
        environmentTags = setOf("pure"),
        functions = functions,
        providers = listOf(
            DslProviderManifestEntry(
                providerId = PROVIDER_ID,
                artifact = artifact,
                reproducibility = DslProviderReproducibility.FIRST_PARTY_REVIEWED,
                concurrency = DslProviderConcurrency.THREAD_SAFE,
                handlerIds = functions.map { it.identity }.sorted(),
                attestation = null,
            ),
        ),
        artifact = artifact,
    )

    // ── Algorithms (public for direct unit testing) ───────────────────────────────────────────

    /** Largest-remainder allocation; ties are resolved in key order for determinism. */
    fun proRata(amount: BigDecimal, weights: List<Pair<DslValue, BigDecimal>>, scale: Int): List<Pair<DslValue, BigDecimal>> {
        if (weights.isEmpty()) return emptyList()
        val totalWeight = weights.fold(BigDecimal.ZERO) { acc, (_, w) -> acc + w }
        if (totalWeight.signum() == 0) fail("DSL-MANTRA-ALLOC-ZERO-BASIS", "Allocation basis sums to zero")
        val target = amount.setScale(scale, RoundingMode.HALF_UP)
        val exact = weights.map { (key, w) -> key to amount.multiply(w).divide(totalWeight, MathContext.DECIMAL128) }
        val floored = exact.map { (key, v) -> key to v.setScale(scale, RoundingMode.FLOOR) }
        val unit = BigDecimal.ONE.movePointLeft(scale)
        var remaining = target - floored.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
        val result = floored.map { it.second }.toMutableList()
        val order = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it].second - floored[it].second }.thenBy { it },
        )
        var cursor = 0
        while (remaining.signum() > 0 && order.isNotEmpty()) {
            val index = order[cursor % order.size]
            result[index] = result[index] + unit
            remaining -= unit
            cursor++
        }
        return weights.mapIndexed { index, (key, _) -> key to result[index] }
    }

    /** Water-filling: members whose pro-rata share exceeds their cap are fixed at the cap. */
    fun capped(
        amount: BigDecimal,
        weights: List<Pair<DslValue, BigDecimal>>,
        caps: List<Pair<DslValue, BigDecimal>>,
        scale: Int,
    ): List<Pair<DslValue, BigDecimal>> {
        val capByKey = caps.associate { (k, v) -> keyText(k) to v.max(BigDecimal.ZERO) }
        val fixed = linkedMapOf<String, BigDecimal>()
        var open = weights.filter { (k, w) -> w.signum() > 0 && (capByKey[keyText(k)]?.signum() ?: 1) > 0 }
        weights.filter { it !in open }.forEach { (k, _) -> fixed[keyText(k)] = BigDecimal.ZERO }
        var remaining = amount
        while (open.isNotEmpty() && remaining.signum() > 0) {
            val total = open.fold(BigDecimal.ZERO) { acc, (_, w) -> acc + w }
            val overflow = open.filter { (k, w) ->
                val cap = capByKey[keyText(k)] ?: return@filter false
                remaining.multiply(w).divide(total, MathContext.DECIMAL128) > cap
            }
            if (overflow.isEmpty()) break
            overflow.forEach { (k, _) ->
                val cap = capByKey.getValue(keyText(k))
                fixed[keyText(k)] = cap
                remaining -= cap
            }
            open = open - overflow.toSet()
        }
        val openAllocation = if (open.isNotEmpty() && remaining.signum() > 0) proRata(remaining, open, scale) else open.map { it.first to BigDecimal.ZERO }
        val openByKey = openAllocation.associate { (k, v) -> keyText(k) to v }
        return weights.map { (k, _) ->
            val key = keyText(k)
            k to (fixed[key]?.setScale(scale, RoundingMode.HALF_UP) ?: openByKey[key] ?: BigDecimal.ZERO.setScale(scale))
        }
    }

    /** Sequential allocation in key order; `null` capacity means unlimited. */
    fun waterfall(amount: BigDecimal, capacities: List<Pair<DslValue, BigDecimal?>>): List<Pair<DslValue, BigDecimal>> {
        var remaining = amount.max(BigDecimal.ZERO)
        return capacities.map { (key, cap) ->
            val share = if (cap == null) remaining else remaining.min(cap.max(BigDecimal.ZERO))
            remaining -= share
            key to share
        }
    }

    fun band(x: BigDecimal, rows: DslValue, default: BigDecimal?): BigDecimal? {
        var value = default
        for (row in (rows as DslValue.VectorValue).values) {
            val parts = (row as DslValue.VectorValue).values
            if (parts.size != 2) fail("DSL-MANTRA-BAND-ROW", "Each band row must be [threshold value]")
            val threshold = parts[0].number()
            if (x >= threshold) value = parts[1].numberOrNull() else break
        }
        return value
    }

    fun pmt(rate: BigDecimal, periods: Int, pv: BigDecimal, scale: Int): BigDecimal {
        if (periods <= 0) fail("DSL-MANTRA-PMT-PERIODS", "fin/pmt needs a positive number of periods")
        if (rate.signum() == 0) return pv.divide(BigDecimal(periods), scale, RoundingMode.HALF_UP)
        val discount = BigDecimal.ONE.divide(BigDecimal.ONE.add(rate).pow(periods, MathContext.DECIMAL128), MathContext.DECIMAL128)
        return pv.multiply(rate).divide(BigDecimal.ONE - discount, MathContext.DECIMAL128).setScale(scale, RoundingMode.HALF_UP)
    }

    fun stepwise(amount: BigDecimal, bands: DslValue): BigDecimal {
        var lower = BigDecimal.ZERO
        var sum = BigDecimal.ZERO
        for (band in (bands as DslValue.VectorValue).values) {
            val parts = (band as DslValue.VectorValue).values
            if (parts.size != 2) fail("DSL-MANTRA-STEPWISE-BAND", "Each band must be [upper-limit rate]")
            val upper = parts[0].numberOrNull()
            val rate = parts[1].number()
            val top = if (upper == null) amount else amount.min(upper)
            if (top > lower) sum += (top - lower).multiply(rate)
            if (upper == null || amount <= upper) break
            lower = upper
        }
        return sum
    }

    // ── Conversion helpers ─────────────────────────────────────────────────────────────────────

    private fun DslValue.number(): BigDecimal = numberOrNull() ?: fail("DSL-MANTRA-NUMBER-REQUIRED", "A number is required")

    private fun DslValue.numberOrNull(): BigDecimal? = when (this) {
        is DslValue.DecimalValue -> value
        is DslValue.IntegerValue -> BigDecimal(value)
        is DslValue.LongValue -> BigDecimal.valueOf(value)
        else -> null
    }

    private fun DslValue.entries(): List<Pair<DslValue, DslValue>> = when (this) {
        is DslValue.MapValue -> entriesInIterationOrder.map { it.key to it.value }
        DslValue.Nil -> emptyList()
        else -> fail("DSL-MANTRA-MAP-REQUIRED", "A member map is required")
    }

    private fun DslValue.numberMap(): List<Pair<DslValue, BigDecimal>> = entries().map { (k, v) -> k to (v.numberOrNull() ?: BigDecimal.ZERO) }

    private fun DslValue.scale(): Int = number().intValueExactOrFail("scale")

    private fun BigDecimal.intValueExactOrFail(what: String): Int = try {
        intValueExact()
    } catch (_: ArithmeticException) {
        fail("DSL-MANTRA-INTEGER-REQUIRED", "The $what must be an integer")
    }

    private fun keyText(key: DslValue): String = when (key) {
        is DslValue.KeywordValue -> (key.namespace?.let { "$it/" } ?: "") + key.name
        is DslValue.TextValue -> key.value
        else -> key.numberOrNull()?.toPlainString() ?: key.toString()
    }

    private fun map(entries: List<Pair<DslValue, BigDecimal>>): DslValue =
        DslValues.map(entries.map { (k, v) -> k to DslValues.decimal(v) })

    private fun fail(code: String, message: String): Nothing = throw DslFunctionInvocationException(code, message)

    private fun signature(vararg parameters: Pair<String, DslType>, returns: DslType): DslFunctionSignature =
        DslFunctionSignature(parameters = parameters.map { (name, type) -> DslParameterType(name, type) }, returnType = returns)

    private fun function(name: String, summary: String, vararg signatures: DslFunctionSignature, body: (List<DslValue>) -> DslValue): DslFunctionSpec =
        DslFunctionSpec(
            name = name,
            semanticsVersion = SEMANTICS_VERSION,
            signatures = signatures.toList(),
            evaluationStrategy = DslEvaluationStrategy.EAGER,
            providerId = PROVIDER_ID,
            handler = handler(body),
            documentation = DslFunctionDocumentation(
                category = "mantra calculation primitives",
                summary = summary,
                classification = DslLanguageSurfaceClassification.DOMAIN_LIBRARY,
            ),
        )

    private fun handler(body: (List<DslValue>) -> DslValue): DslFunctionHandler = dslFunctionHandler { arguments, _, runtime ->
        runtime.charge(DslBudgetCounter.NUMERIC_OPERATIONS, arguments.size.toLong().coerceAtLeast(1L))
        DslFunctionResult.Value(body(arguments))
    }

    @Suppress("unused")
    private val ZERO_INT: BigInteger = BigInteger.ZERO
}
