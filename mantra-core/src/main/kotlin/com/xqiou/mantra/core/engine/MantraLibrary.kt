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
 * | `calc/converge` | bounded adjacent-delta iteration with a pure Decimal callback |
 * | `dim/sum`, `dim/min`, `dim/max` | cross-footing and extrema over dimension members |
 * | `dim/rollup` | sum source members into one declared parent member |
 * | `fin/df`, `fin/npv` | discounting |
 */
internal object MantraLibrary {
    const val LIBRARY_ID: String = "mantra.calc"
    const val SEMANTICS_VERSION: String = "2"
    const val PROVIDER_ID: String = "mantra.calc.provider"

    private val numberType: DslType = DslTypes.union(DslType.Integer, DslType.Long, DslType.Decimal)
    private val numberMap: DslType = DslTypes.map(DslType.Keyword, numberType)
    private val decimalMap: DslType = DslTypes.map(DslType.Keyword, DslType.Decimal)

    val functions: List<DslFunctionSpec> = listOf(
        PrevLowering.selectHelperSpec(),
        convergeSpec(numberType, SEMANTICS_VERSION, PROVIDER_ID),
        function(
            "alloc/pro-rata",
            "Allocates an amount over the keys of a weight map, rounded to a scale " +
                "with the largest-remainder method so that the parts add up to the rounded amount.",
            signature("amount" to numberType, "weights" to numberMap, "scale" to numberType, returns = decimalMap),
        ) { args, work ->
            map(proRata(args[0].number(work), args[1].numberMap(work), args[2].scale(work), work), work)
        },
        function(
            "alloc/capped",
            "Allocates an amount pro rata over a weight map but never beyond each " +
                "key's cap; excess is re-allocated to uncapped keys (water-filling). " +
                "Unallocatable excess is dropped.",
            signature(
                "amount" to numberType,
                "weights" to numberMap,
                "caps" to numberMap,
                "scale" to numberType,
                returns = decimalMap,
            ),
        ) { args, work ->
            map(
                capped(
                    args[0].number(work),
                    args[1].numberMap(work),
                    args[2].numberMap(work),
                    args[3].scale(work),
                    work,
                ),
                work,
            )
        },
        function(
            "alloc/waterfall",
            "Allocates an amount over the keys of a capacity map in key order: each " +
                "key receives min(capacity, remaining); a nil capacity takes everything left.",
            signature(
                "amount" to numberType,
                "capacities" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)),
                returns = decimalMap,
            ),
        ) { args, work ->
            map(
                waterfall(
                    args[0].number(work),
                    work.scanned(args[1].entries(work)).map { (k, v) ->
                        work.temporary()
                        k to v.numberOrNull(work)
                    },
                    work,
                ),
                work,
            )
        },
        function(
            "table/band",
            "Looks up the value of the last row [threshold value] whose threshold is " +
                "not above x (thresholds ascending); returns the default or nil below the " +
                "first threshold.",
            signature(
                "x" to numberType,
                "rows" to DslTypes.vector(DslTypes.vector(DslTypes.nullable(numberType))),
                returns = DslTypes.nullable(DslType.Decimal),
            ),
            signature(
                "x" to numberType,
                "rows" to DslTypes.vector(DslTypes.vector(DslTypes.nullable(numberType))),
                "default" to DslTypes.nullable(numberType),
                returns = DslTypes.nullable(DslType.Decimal),
            ),
        ) { args, work ->
            band(args[0].number(work), args[1], args.getOrNull(2)?.numberOrNull(work), work)?.let(DslValues::decimal)
                ?: DslValue.Nil
        },
        function(
            "fin/pmt",
            "Annuity payment per period for a present value pv over n periods at rate " +
                "(end of period), rounded to scale; rate 0 gives pv/n.",
            signature(
                "rate" to numberType,
                "n" to numberType,
                "pv" to numberType,
                "scale" to numberType,
                returns = DslType.Decimal,
            ),
        ) { args, work ->
            DslValues.decimal(
                pmt(
                    args[0].number(work),
                    args[1].number(work).intValueExactOrFail("fin/pmt periods", work),
                    args[2].number(work),
                    args[3].scale(work),
                    work,
                ),
            )
        },
        function(
            "calc/stepwise",
            "Applies banded rates: bands are [[upper-limit rate] ...] with nil as the " +
                "open upper limit of the last band; returns the sum of (band slice x rate).",
            signature(
                "amount" to numberType,
                "bands" to DslTypes.vector(DslTypes.vector(DslTypes.nullable(numberType))),
                returns = DslType.Decimal,
            ),
        ) { args, work -> DslValues.decimal(stepwise(args[0].number(work), args[1], work)) },
        function(
            "dim/sum",
            "Sums the values of a member map (cross-footing over a dimension); nil values count as zero.",
            signature(
                "values" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)),
                returns = DslType.Decimal,
            ),
        ) { args, work ->
            DslValues.decimal(
                work.scanned(args[0].entries(work)).fold(BigDecimal.ZERO) { acc, (_, v) ->
                    work.numeric()
                    acc +
                        (v.numberOrNull(work) ?: BigDecimal.ZERO)
                },
            )
        },
        function(
            "dim/rollup",
            "Reduces source members into one parent: sum, or first/last in the " +
                "explicitly supplied declared period order.",
            signature(
                "values" to numberMap,
                "parents" to DslTypes.map(DslType.Keyword, DslType.Keyword),
                "target" to DslType.Keyword,
                returns = DslType.Decimal,
            ),
            signature(
                "values" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)),
                "parents" to DslTypes.map(DslType.Keyword, DslType.Keyword),
                "target" to DslType.Keyword,
                "boundary" to DslType.Keyword,
                "period-order" to DslTypes.union(DslTypes.sequence(DslType.Keyword), DslTypes.vector(DslType.Keyword)),
                returns = DslTypes.nullable(DslType.Decimal),
            ),
        ) { args, work ->
            val parents = work.scanned(args[1].entries(work)).associate { (source, parent) ->
                work.temporary()
                keyText(source) to keyText(parent)
            }
            val target = keyText(args[2])
            if (args.size == 5) {
                val order = when (val sequence = args[4]) {
                    is DslValue.VectorValue -> sequence.values
                    is DslValue.SequentialValue -> sequence.values
                    else -> fail("DSL-MANTRA-ROLLUP-ORDER", "Period order must be a concrete ordered sequence")
                }.let {
                    work.scanned(it).map { key ->
                        work.temporary()
                        keyText(key)
                    }
                }
                if (work.scanned(order).distinct().size != order.size ||
                    work.scanned(parents.keys).any { !work.contains(order, it) }
                ) {
                    fail("DSL-MANTRA-ROLLUP-ORDER", "Period order must contain each related period exactly once")
                }
                work.scanned(args[0].entries(work)).forEach { (source, _) ->
                    if (keyText(source) !in
                        parents
                    ) {
                        fail("DSL-MANTRA-ROLLUP-KEY", "No parent relation for source member ${keyText(source)}")
                    }
                }
                val scoped = work.scanned(order).filter {
                    (parents[it] == target).also { retained -> if (retained) work.temporary() }
                }
                val selected = when (keyText(args[3])) {
                    "first" -> scoped.firstOrNull()
                    "last" -> scoped.lastOrNull()
                    else -> fail("DSL-MANTRA-ROLLUP-BOUNDARY", "Boundary must be :first or :last")
                }
                val value = work.scanned(args[0].entries(work)).firstOrNull { keyText(it.first) == selected }?.second
                if (selected == null) {
                    DslValues.decimal(BigDecimal.ZERO)
                } else {
                    value?.numberOrNull(work)?.let(DslValues::decimal) ?: DslValue.Nil
                }
            } else {
                DslValues.decimal(
                    work.scanned(args[0].numberMap(work)).fold(BigDecimal.ZERO) { acc, (source, value) ->
                        val parent = parents[keyText(source)]
                            ?: fail("DSL-MANTRA-ROLLUP-KEY", "No parent relation for source member ${keyText(source)}")
                        if (parent == target) {
                            work.numeric()
                            acc + value
                        } else {
                            acc
                        }
                    },
                )
            }
        },
        function(
            "dim/min",
            "Smallest non-nil value of a member map, or nil when the map has no values.",
            signature(
                "values" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)),
                returns = DslTypes.nullable(DslType.Decimal),
            ),
        ) { args, work ->
            extreme(args[0].entries(work), minimum = true, work = work)?.let(DslValues::decimal)
                ?: DslValue.Nil
        },
        function(
            "dim/max",
            "Largest non-nil value of a member map, or nil when the map has no values.",
            signature(
                "values" to DslTypes.map(DslType.Keyword, DslTypes.nullable(numberType)),
                returns = DslTypes.nullable(DslType.Decimal),
            ),
        ) { args, work ->
            extreme(args[0].entries(work), minimum = false, work = work)?.let(DslValues::decimal)
                ?: DslValue.Nil
        },
        function(
            "fin/df",
            "Discount factor 1 / (1 + rate)^t rounded to scale (end-of-period convention).",
            signature("rate" to numberType, "t" to numberType, "scale" to numberType, returns = DslType.Decimal),
        ) { args, work ->
            val t = args[1].number(work).intValueExactOrFail("fin/df period", work)
            work.numeric(3)
            DslValues.decimal(
                BigDecimal.ONE.divide(
                    BigDecimal.ONE.add(args[0].number(work)).pow(t, MathContext.DECIMAL128),
                    args[2].scale(work),
                    RoundingMode.HALF_UP,
                ),
            )
        },
        function(
            "fin/npv",
            "Present value of periodic cash flows at the end of periods 1..n, " +
                "discounted at rate and rounded to scale.",
            signature(
                "rate" to numberType,
                "flows" to DslTypes.vector(numberType),
                "scale" to numberType,
                returns = DslType.Decimal,
            ),
        ) { args, work ->
            work.numeric()
            val rate = BigDecimal.ONE.add(args[0].number(work))
            val flows = work.scanned((args[1] as DslValue.VectorValue).values).map {
                work.temporary()
                it.number(work)
            }
            val pv = work.scanned(flows).foldIndexed(BigDecimal.ZERO) { index, acc, flow ->
                work.numeric(3)
                acc + flow.divide(rate.pow(index + 1, MathContext.DECIMAL128), MathContext.DECIMAL128)
            }
            work.numeric()
            DslValues.decimal(pv.setScale(args[2].scale(work), RoundingMode.HALF_UP))
        },
    )

    fun extendLanguage(base: DslLanguageDescriptor): DslLanguageDescriptor = base.copy(
        surfaceManifest = base.surfaceManifest.copy(
            entries = (
                base.surfaceManifest.entries + functions.map { function ->
                    DslLanguageSurfaceEntry(
                        surfaceId = "function:mantra:${function.name}",
                        sourceName = function.name,
                        kind = DslLanguageSurfaceKind.FUNCTION,
                        classification = DslLanguageSurfaceClassification.DOMAIN_LIBRARY,
                        arities = function.signatures.map { DslArityShape.Fixed(it.parameters.size) }.distinct(),
                        evaluationStrategy = function.evaluationStrategy,
                        status = DslLanguageSurfaceStatus.SUPPORTED,
                    )
                }
                ).sortedBy(DslLanguageSurfaceEntry::surfaceId),
        ),
    )

    val artifact: DslArtifactIdentity by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        functions.forEach { digest.update("${it.name}@${it.semanticsVersion}\n".toByteArray()) }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        DslArtifactIdentity(
            "application-code:$LIBRARY_ID:$SEMANTICS_VERSION",
            sha,
            sha,
            sha,
            "mantra-$SEMANTICS_VERSION",
            listOf(PROVIDER_ID),
        )
    }

    fun descriptor(): DslLibraryDescriptor = DslLibraryDescriptor(
        id = LIBRARY_ID,
        semanticsVersion = SEMANTICS_VERSION,
        dependencies = setOf(
            DslLibraryRequirement(
                NormeinStandardLibraries.EXTENSION_LIBRARY_ID,
                NormeinStandardLibraries.LIBRARY_SEMANTICS_VERSION,
            ),
        ),
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
    fun proRata(
        amount: BigDecimal,
        weights: List<Pair<DslValue, BigDecimal>>,
        scale: Int,
        work: LibraryWork = LibraryWork.NONE,
    ): List<Pair<DslValue, BigDecimal>> {
        if (weights.isEmpty()) return emptyList()
        val totalWeight = work.scanned(weights).fold(BigDecimal.ZERO) { acc, (_, w) ->
            work.numeric()
            acc + w
        }
        if (totalWeight.signum() == 0) fail("DSL-MANTRA-ALLOC-ZERO-BASIS", "Allocation basis sums to zero")
        work.numeric()
        val target = amount.setScale(scale, RoundingMode.HALF_UP)
        val exact = work.scanned(weights).map { (key, w) ->
            work.numeric(2)
            work.temporary()
            key to amount.multiply(w).divide(totalWeight, MathContext.DECIMAL128)
        }
        val floored = work.scanned(exact).map { (key, v) ->
            work.numeric()
            work.temporary()
            key to v.setScale(scale, RoundingMode.FLOOR)
        }
        work.numeric()
        val unit = BigDecimal.ONE.movePointLeft(scale)
        work.numeric()
        var remaining = target - work.scanned(floored).fold(BigDecimal.ZERO) { acc, (_, v) ->
            work.numeric()
            acc + v
        }
        val result = work.scanned(floored).map {
            work.temporary()
            it.second
        }.toMutableList()
        work.temporary(exact.size.toLong())
        val order = exact.indices.sortedWith { left, right ->
            work.iteration()
            work.numeric(3)
            val compared = (exact[right].second - floored[right].second).compareTo(
                exact[left].second - floored[left].second,
            )
            if (compared == 0) left.compareTo(right) else compared
        }
        var cursor = 0
        while (remaining.signum() > 0 && order.isNotEmpty()) {
            work.iteration()
            work.numeric(2)
            val index = order[cursor % order.size]
            result[index] = result[index] + unit
            remaining -= unit
            cursor++
        }
        return work.scanned(weights).mapIndexed { index, (key, _) ->
            work.temporary()
            key to result[index]
        }
    }

    /** Water-filling: members whose pro-rata share exceeds their cap are fixed at the cap. */
    fun capped(
        amount: BigDecimal,
        weights: List<Pair<DslValue, BigDecimal>>,
        caps: List<Pair<DslValue, BigDecimal>>,
        scale: Int,
        work: LibraryWork = LibraryWork.NONE,
    ): List<Pair<DslValue, BigDecimal>> {
        val capByKey = work.scanned(caps).associate { (k, v) ->
            work.numeric()
            work.temporary()
            keyText(k) to v.max(BigDecimal.ZERO)
        }
        val fixed = linkedMapOf<String, BigDecimal>()
        var open = work.scanned(weights).filter { (k, w) ->
            val retain = w.signum() > 0 && (capByKey[keyText(k)]?.signum() ?: 1) > 0
            if (retain) work.temporary()
            retain
        }
        val excluded = work.scanned(weights).filter {
            (!work.contains(open, it)).also { retained -> if (retained) work.temporary() }
        }
        work.scanned(excluded).forEach { (k, _) ->
            work.temporary()
            fixed[keyText(k)] = BigDecimal.ZERO
        }
        var remaining = amount
        while (open.isNotEmpty() && remaining.signum() > 0) {
            work.iteration()
            val total = work.scanned(open).fold(BigDecimal.ZERO) { acc, (_, w) ->
                work.numeric()
                acc + w
            }
            val overflow = work.scanned(open).filter { (k, w) ->
                val cap = capByKey[keyText(k)] ?: return@filter false
                work.numeric(3)
                (remaining.multiply(w).divide(total, MathContext.DECIMAL128) > cap).also { retained ->
                    if (retained) work.temporary()
                }
            }
            if (overflow.isEmpty()) break
            work.scanned(overflow).forEach { (k, _) ->
                work.numeric()
                val cap = capByKey.getValue(keyText(k))
                work.temporary()
                fixed[keyText(k)] = cap
                remaining -= cap
            }
            work.temporary(overflow.size.toLong())
            val closed = work.scanned(overflow).toSet()
            open = work.scanned(open).filter {
                (it !in closed).also { retained -> if (retained) work.temporary() }
            }
        }
        val openAllocation = if (open.isNotEmpty() &&
            remaining.signum() > 0
        ) {
            proRata(remaining, open, scale, work)
        } else {
            work.scanned(open).map {
                work.temporary()
                it.first to BigDecimal.ZERO
            }
        }
        val openByKey = work.scanned(openAllocation).associate { (k, v) ->
            work.temporary()
            keyText(k) to v
        }
        return work.scanned(weights).map { (k, _) ->
            work.temporary()
            val key = keyText(k)
            val value = fixed[key]?.let { amount ->
                work.numeric()
                amount.setScale(scale, RoundingMode.HALF_UP)
            } ?: openByKey[key] ?: run {
                work.numeric()
                BigDecimal.ZERO.setScale(scale)
            }
            k to value
        }
    }

    /** Sequential allocation in key order; `null` capacity means unlimited. */
    fun waterfall(
        amount: BigDecimal,
        capacities: List<Pair<DslValue, BigDecimal?>>,
        work: LibraryWork = LibraryWork.NONE,
    ): List<Pair<DslValue, BigDecimal>> {
        work.numeric()
        var remaining = amount.max(BigDecimal.ZERO)
        return work.scanned(capacities).map { (key, cap) ->
            work.numeric(if (cap == null) 1 else 3)
            work.temporary()
            val share = if (cap == null) remaining else remaining.min(cap.max(BigDecimal.ZERO))
            remaining -= share
            key to share
        }
    }

    fun band(x: BigDecimal, rows: DslValue, default: BigDecimal?, work: LibraryWork = LibraryWork.NONE): BigDecimal? {
        var value = default
        for (row in work.scanned((rows as DslValue.VectorValue).values)) {
            val parts = (row as DslValue.VectorValue).values
            if (parts.size != 2) fail("DSL-MANTRA-BAND-ROW", "Each band row must be [threshold value]")
            val threshold = parts[0].number(work)
            work.numeric()
            if (x >= threshold) value = parts[1].numberOrNull(work) else break
        }
        return value
    }

    fun pmt(
        rate: BigDecimal,
        periods: Int,
        pv: BigDecimal,
        scale: Int,
        work: LibraryWork = LibraryWork.NONE,
    ): BigDecimal {
        if (periods <= 0) fail("DSL-MANTRA-PMT-PERIODS", "fin/pmt needs a positive number of periods")
        if (rate.signum() == 0) {
            work.numeric()
            return pv.divide(BigDecimal(periods), scale, RoundingMode.HALF_UP)
        }
        work.numeric(7)
        val discount = BigDecimal.ONE.divide(
            BigDecimal.ONE.add(rate).pow(periods, MathContext.DECIMAL128),
            MathContext.DECIMAL128,
        )
        return pv.multiply(
            rate,
        ).divide(BigDecimal.ONE - discount, MathContext.DECIMAL128).setScale(scale, RoundingMode.HALF_UP)
    }

    fun stepwise(amount: BigDecimal, bands: DslValue, work: LibraryWork = LibraryWork.NONE): BigDecimal {
        var lower = BigDecimal.ZERO
        var sum = BigDecimal.ZERO
        for (band in work.scanned((bands as DslValue.VectorValue).values)) {
            val parts = (band as DslValue.VectorValue).values
            if (parts.size != 2) fail("DSL-MANTRA-STEPWISE-BAND", "Each band must be [upper-limit rate]")
            val upper = parts[0].numberOrNull(work)
            val rate = parts[1].number(work)
            val top = if (upper == null) {
                amount
            } else {
                work.numeric()
                amount.min(upper)
            }
            work.numeric()
            if (top > lower) {
                work.numeric(3)
                sum += (top - lower).multiply(rate)
            }
            if (upper == null) break
            work.numeric()
            if (amount <= upper) break
            lower = upper
        }
        return sum
    }

    private fun extreme(entries: List<Pair<DslValue, DslValue>>, minimum: Boolean, work: LibraryWork): BigDecimal? {
        var selected: BigDecimal? = null
        for ((_, value) in work.scanned(entries)) {
            val number = value.numberOrNull(work) ?: continue
            val previous = selected
            if (previous == null) {
                selected = number
            } else {
                work.numeric()
                if (if (minimum) number < previous else number > previous) selected = number
            }
        }
        return selected
    }

    // ── Conversion helpers ─────────────────────────────────────────────────────────────────────

    private fun DslValue.number(work: LibraryWork = LibraryWork.NONE): BigDecimal =
        numberOrNull(work) ?: fail("DSL-MANTRA-NUMBER-REQUIRED", "A number is required")

    private fun DslValue.numberOrNull(work: LibraryWork = LibraryWork.NONE): BigDecimal? = when (this) {
        is DslValue.DecimalValue -> value
        is DslValue.IntegerValue -> {
            work.conversion()
            BigDecimal(value)
        }
        is DslValue.LongValue -> {
            work.conversion()
            BigDecimal.valueOf(value)
        }
        else -> null
    }

    private fun DslValue.entries(work: LibraryWork = LibraryWork.NONE): List<Pair<DslValue, DslValue>> = when (this) {
        is DslValue.MapValue -> work.scanned(entriesInIterationOrder).map {
            work.temporary()
            it.key to it.value
        }
        DslValue.Nil -> emptyList()
        else -> fail("DSL-MANTRA-MAP-REQUIRED", "A member map is required")
    }

    private fun DslValue.numberMap(work: LibraryWork = LibraryWork.NONE): List<Pair<DslValue, BigDecimal>> =
        work.scanned(entries(work)).map { (k, v) ->
            work.temporary()
            k to
                (v.numberOrNull(work) ?: BigDecimal.ZERO)
        }

    private fun DslValue.scale(work: LibraryWork = LibraryWork.NONE): Int =
        number(work).intValueExactOrFail("scale", work)

    private fun BigDecimal.intValueExactOrFail(what: String, work: LibraryWork): Int {
        work.conversion()
        return try {
            intValueExact()
        } catch (_: ArithmeticException) {
            fail("DSL-MANTRA-INTEGER-REQUIRED", "The $what must be an integer")
        }
    }

    private fun keyText(key: DslValue): String = when (key) {
        is DslValue.KeywordValue -> (key.namespace?.let { "$it/" } ?: "") + key.name
        is DslValue.TextValue -> key.value
        else -> key.numberOrNull()?.toPlainString() ?: key.toString()
    }

    private fun map(entries: List<Pair<DslValue, BigDecimal>>, work: LibraryWork = LibraryWork.NONE): DslValue =
        DslValues.map(
            work.scanned(entries).map { (k, v) ->
                work.temporary(2)
                k to
                    DslValues.decimal(v)
            },
        )

    private fun fail(code: String, message: String): Nothing = throw DslFunctionInvocationException(code, message)

    private fun signature(vararg parameters: Pair<String, DslType>, returns: DslType): DslFunctionSignature =
        DslFunctionSignature(
            parameters = parameters.map { (name, type) ->
                DslParameterType(name, type)
            },
            returnType = returns,
        )

    private fun function(
        name: String,
        summary: String,
        vararg signatures: DslFunctionSignature,
        body: (List<DslValue>, LibraryWork) -> DslValue,
    ): DslFunctionSpec = DslFunctionSpec(
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

    private fun handler(body: (List<DslValue>, LibraryWork) -> DslValue): DslFunctionHandler = dslFunctionHandler {
            arguments,
            _,
            runtime,
        ->
        runtime.checkpoint()
        DslFunctionResult.Value(body(arguments, LibraryWork(runtime)))
    }

    @Suppress("unused")
    private val ZERO_INT: BigInteger = BigInteger.ZERO
}
