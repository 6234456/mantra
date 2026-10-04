package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.AggregateRule
import com.xqiou.mantra.core.model.BoundaryAggregation
import com.xqiou.mantra.core.model.RatioAggregation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.AggregateContribution
import com.xqiou.mantra.core.view.AggregationResult
import com.xqiou.mantra.core.view.BoundaryAggregateTrace
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.Member
import com.xqiou.mantra.core.view.RatioAggregateTrace
import com.xqiou.mantra.core.view.RatioContribution
import com.xqiou.mantra.core.view.SumAggregateTrace
import com.xqiou.mantra.core.view.snapshot
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** Metadata/value adapter used identically for live evaluation and detached public views. */
internal data class ReductionNode(
    val id: String,
    val dims: List<String>,
    val type: ValueType,
    val aggregate: AggregateRule,
    val ratio: RatioAggregation?,
    val boundary: BoundaryAggregation?,
    val values: Map<Coord, Value>,
    val active: Map<Coord, Boolean>,
    val location: SourceLocation,
    val validation: Boolean = false,
    val total: Boolean = false,
    val undefinedValues: Boolean = false,
)

/** Exact reductions. A cache is owned by one evaluation or one detached immutable view. */
internal class ReductionService(
    private val nodes: (String) -> ReductionNode,
    private val members: (String) -> List<Member>,
    private val sink: DiagnosticSink? = null,
    private val parents: (String) -> Pair<String, Map<String, String>>? = { null },
) {
    private data class Scope(val id: String, val fixed: Map<String, String>)
    private val cache = ConcurrentHashMap<Scope, AggregationResult>()
    private data class Parent(val relation: Pair<String, Map<String, String>>?)
    private val relationCache = ConcurrentHashMap<String, Parent>()
    private data class MemberKeys(val domain: List<Member>, val keys: Set<String>)
    private val memberKeyCache = ConcurrentHashMap<String, MemberKeys>()
    private fun memberKeys(id: String): Set<String> {
        val domain = members(id)
        return memberKeyCache.compute(id) { _, cached ->
            if (cached?.domain === domain) cached else MemberKeys(domain, domain.map { it.key }.toSet())
        }!!.keys
    }
    private fun parent(id: String) = relationCache.computeIfAbsent(id) { Parent(parents(id)) }.relation

    fun reduce(id: String, fixed: Map<String, String> = emptyMap()): AggregationResult {
        val source = nodes(id)
        val related = source.dims.flatMap { dim ->
            generateSequence(dim) { parent(it)?.first }.toList()
        }.toSet()
        val aligned = fixed.filterKeys { it in related }.toMap()
        aligned.forEach { (dim, key) ->
            require(key in memberKeys(dim)) { "Unknown member $key of $dim" }
        }
        return cache.computeIfAbsent(Scope(id, aligned)) { compute(source, aligned).snapshot() }
    }

    fun clear() {
        cache.clear()
        relationCache.clear()
        memberKeyCache.clear()
    }

    fun coordinates(id: String, fixed: Map<String, String>): List<Coord> {
        val source = nodes(id)
        return coords(source.dims, fixed).filter { coord ->
            source.dims.withIndex().all { (index, dim) -> compatible(dim, coord[index], fixed) }
        }
    }

    fun coordinate(id: String, fixed: Map<String, String>): Coord? {
        val source = nodes(id)
        if (source.dims.any { it !in fixed }) return null
        val coord = source.dims.map(fixed::getValue)
        return coord.takeIf { source.dims.withIndex().all { (index, dim) -> compatible(dim, coord[index], fixed) } }
    }

    private fun compatible(dimension: String, member: String, fixed: Map<String, String>): Boolean {
        var dim = dimension
        var key = member
        while (true) {
            if (fixed[dim]?.let { it != key } == true) return false
            val relation = parent(dim) ?: return true
            key = relation.second[key] ?: return false
            dim = relation.first
        }
    }

    private fun compute(source: ReductionNode, fixed: Map<String, String>): AggregationResult {
        if (source.validation || !source.type.isNumeric) return AggregationResult(null, null)
        val folded = source.dims.filter { it !in fixed }
        if (folded.isEmpty()) {
            val coord = coordinate(source.id, fixed) ?: return AggregationResult(Value.ZERO, null)
            return AggregationResult(source.values[coord] ?: Value.Nil, null)
        }
        if (source.aggregate == AggregateRule.NONE) return AggregationResult(null, null)
        val candidates = coords(source.dims, fixed).filter { at ->
            source.dims.withIndex().all { (index, dim) -> compatible(dim, at[index], fixed) }
        }
        source.ratio?.let { return ratio(source, fixed, candidates, it) }
        val policy = source.boundary
        val periodKeys = policy?.let { boundary ->
            members(boundary.dimension).map { it.key }.filter { compatible(boundary.dimension, it, fixed) }
        }.orEmpty()
        val selected = if (policy != null && policy.dimension in folded) {
            val periodIndex = source.dims.indexOf(policy.dimension)
            val boundaryKey = when (policy.boundary) {
                BoundaryAggregation.Boundary.FIRST -> periodKeys.firstOrNull()
                BoundaryAggregation.Boundary.LAST -> periodKeys.lastOrNull()
            }
            candidates.filter { it[periodIndex] == boundaryKey }
        } else {
            candidates
        }
        val applicable = selected.filter { source.active[it] == true }
        val undefined = applicable.any {
            source.values[it] == Value.Nil &&
                (source.total || source.boundary != null || source.ratio != null || source.undefinedValues)
        }
        val result = if (undefined) {
            Value.Nil
        } else {
            Value.Num(
                applicable.fold(BigDecimal.ZERO) { sum, at ->
                    sum + ((source.values[at] as? Value.Num)?.value ?: BigDecimal.ZERO)
                },
            )
        }
        val truncated = candidates.size > MAX_MEMBERS
        val chosen = selected.toSet()
        val details = candidates.take(MAX_MEMBERS).map { at ->
            AggregateContribution(at, source.values[at] ?: Value.Nil, source.active[at] == true, at in chosen)
        }
        val reason = if (undefined) "selected-value-undefined" else null
        val trace = if (policy != null && policy.dimension in folded) {
            BoundaryAggregateTrace(
                source.dims, fixed, policy.dimension, policy.boundary, periodKeys,
                selected.take(MAX_MEMBERS).map { at ->
                    AggregateContribution(at, source.values[at] ?: Value.Nil, source.active[at] == true, true)
                },
                selected.size, details, candidates.size, applicable.size,
                (result as? Value.Num)?.value, reason, truncated,
            )
        } else {
            SumAggregateTrace(
                source.dims,
                fixed,
                details,
                candidates.size,
                applicable.size,
                (result as? Value.Num)?.value,
                reason,
                truncated,
            )
        }
        if (policy != null && policy.dimension in folded) truncated(source, fixed, truncated)
        return AggregationResult(result, trace)
    }

    private fun ratio(
        source: ReductionNode,
        fixed: Map<String, String>,
        candidates: List<Coord>,
        ratio: RatioAggregation,
    ): AggregationResult {
        val included = candidates.filter { source.active[it] == true }
        fun number(id: String, at: Coord) = (nodes(id).values[at] as? Value.Num)?.value ?: BigDecimal.ZERO
        val numerator = included.fold(BigDecimal.ZERO) { sum, at -> sum + number(ratio.numerator, at) }
        val denominator = included.fold(BigDecimal.ZERO) { sum, at -> sum + number(ratio.denominator, at) }
        var reason: String? = null
        val result = when {
            included.isEmpty() -> {
                reason = "no-active-members"
                null
            }
            denominator.signum() == 0 -> {
                reason = "zero-denominator"
                sink?.warning(
                    "MANTRA-AGGREGATE-ZERO-DENOMINATOR",
                    "Ratio ${source.id} has a zero aggregate denominator",
                    source.location,
                    source.id,
                    source.dims.filter { it in fixed }.map(fixed::getValue),
                    category = DiagnosticCategory.BUSINESS,
                )
                null
            }
            else -> try {
                ratio.rounding?.let { numerator.divide(denominator, it.scale, it.mode) }
                    ?: numerator.divide(denominator)
            } catch (_: ArithmeticException) {
                reason = "rounding-required"
                sink?.error(
                    "MANTRA-AGGREGATE-DIVISION",
                    "Ratio ${source.id} requires an explicit rounding rule",
                    source.location,
                    source.id,
                    source.dims.filter { it in fixed }.map(fixed::getValue),
                    category = DiagnosticCategory.EVALUATION,
                )
                null
            }
        }
        val truncated = candidates.size > MAX_MEMBERS
        val trace = RatioAggregateTrace(
            ratio.numerator, ratio.denominator, source.dims, fixed,
            candidates.take(MAX_MEMBERS).map { at ->
                RatioContribution(
                    at,
                    number(ratio.numerator, at),
                    number(ratio.denominator, at),
                    source.active[at] == true,
                )
            },
            candidates.size, included.size, numerator, denominator, ratio.rounding, result, reason, truncated,
        )
        truncated(source, fixed, truncated)
        return AggregationResult(result?.let(Value::Num) ?: Value.Nil, trace)
    }

    private fun coords(dims: List<String>, fixed: Map<String, String>): List<Coord> =
        dims.fold(listOf(emptyList())) { partial, dim ->
            val selected = fixed[dim]
            val keys = if (selected == null) {
                members(dim).map { it.key }
            } else {
                if (selected in memberKeys(dim)) listOf(selected) else emptyList()
            }
            partial.flatMap { prefix -> keys.map { prefix + it } }
        }

    private fun truncated(source: ReductionNode, fixed: Map<String, String>, truncated: Boolean) {
        if (truncated) {
            sink?.warning(
                "MANTRA-AUDIT-TRUNCATED",
                "Reduction ${source.id} retains $MAX_MEMBERS member details",
                source.location,
                source.id,
                source.dims.filter { it in fixed }.map(fixed::getValue),
                category = DiagnosticCategory.EVALUATION,
            )
        }
    }

    private companion object {
        const val MAX_MEMBERS = 64
    }
}
