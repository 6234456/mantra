package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.RunCounter
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
import com.xqiou.mantra.core.view.NodeTrace
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
    val traces: Map<Coord, NodeTrace> = emptyMap(),
)

/** Exact reductions. A cache is owned by one evaluation or one detached immutable view. */
internal class ReductionService(
    private val nodes: (String) -> ReductionNode,
    private val members: (String) -> List<Member>,
    private val sink: DiagnosticSink? = null,
    private val parents: (String, RunContext) -> Pair<String, Map<String, String>>? = { _, _ -> null },
) {
    private data class Scope(val id: String, val fixed: Map<String, String>)
    private val cache = ConcurrentHashMap<Scope, AggregationResult>()
    private data class Parent(val relation: Pair<String, Map<String, String>>?)
    private val relationCache = ConcurrentHashMap<String, Parent>()
    private val scopes = CoordSpace(members, Member::key) { id, context ->
        parent(id, context)?.let { CoordSpace.ParentRelation(it.first, it.second) }
    }
    private fun parent(id: String, context: RunContext) =
        relationCache.computeIfAbsent(id) { Parent(parents(id, context)) }.relation

    fun reduce(id: String, fixed: Map<String, String> = emptyMap(), context: RunContext): AggregationResult {
        context.checkpoint()
        val source = nodes(id)
        val related = source.dims.flatMap { dim ->
            generateSequence(dim) { parent(it, context)?.first }.toList()
        }.toSet()
        val aligned = fixed.filterKeys { it in related }.toMap()
        scopes.validateFixed(related, aligned, context)
        return cache.computeIfAbsent(Scope(id, aligned)) { compute(source, aligned, context).snapshot() }
    }

    fun clear() {
        cache.clear()
        relationCache.clear()
        scopes.clear()
    }

    fun scopeCoordinates(dims: List<String>, fixed: Map<String, String>, context: RunContext): List<Coord> =
        scopes.coordinates(dims, fixed, context)

    fun validateFixed(dimensions: Set<String>, fixed: Map<String, String>, context: RunContext) =
        scopes.validateFixed(dimensions, fixed, context)

    fun coordinates(id: String, fixed: Map<String, String>, context: RunContext): List<Coord> =
        scopeCoordinates(nodes(id).dims, fixed, context)

    fun coordinate(id: String, fixed: Map<String, String>, context: RunContext): Coord? =
        scopes.coordinate(nodes(id).dims, fixed, context)

    private fun compatible(
        dimension: String,
        member: String,
        fixed: Map<String, String>,
        context: RunContext,
    ): Boolean {
        var dim = dimension
        var key = member
        while (true) {
            context.charge(RunCounter.HOST_SCANS)
            if (fixed[dim]?.let { it != key } == true) return false
            val relation = parent(dim, context) ?: return true
            key = relation.second[key] ?: return false
            dim = relation.first
        }
    }

    private fun compute(source: ReductionNode, fixed: Map<String, String>, context: RunContext): AggregationResult {
        if (source.validation || !source.type.isNumeric) return AggregationResult(null, null)
        val folded = source.dims.filter { it !in fixed }
        if (folded.isEmpty()) {
            val coord = coordinate(source.id, fixed, context) ?: return AggregationResult(Value.ZERO, null)
            return AggregationResult(source.values[coord] ?: Value.Nil, null)
        }
        if (source.aggregate == AggregateRule.NONE) return AggregationResult(null, null)
        val candidates = scopeCoordinates(source.dims, fixed, context)
        source.ratio?.let { return ratio(source, fixed, candidates, it, context) }
        val policy = source.boundary
        val periodKeys = policy?.let { boundary ->
            members(boundary.dimension).map {
                context.charge(RunCounter.HOST_SCANS)
                it.key
            }
                .filter { compatible(boundary.dimension, it, fixed, context) }
        }.orEmpty()
        val selected = if (policy != null && policy.dimension in folded) {
            val periodIndex = source.dims.indexOf(policy.dimension)
            val boundaryKey = when (policy.boundary) {
                BoundaryAggregation.Boundary.FIRST -> periodKeys.firstOrNull()
                BoundaryAggregation.Boundary.LAST -> periodKeys.lastOrNull()
            }
            candidates.filter {
                context.charge(RunCounter.HOST_SCANS)
                it[periodIndex] == boundaryKey
            }
        } else {
            candidates
        }
        val applicable = selected.filter {
            context.charge(RunCounter.HOST_SCANS)
            source.active[it] == true
        }
        val undefined = applicable.any {
            context.charge(RunCounter.HOST_SCANS)
            isUndefined(source, it)
        }
        val result = if (undefined) {
            Value.Nil
        } else {
            Value.Num(
                applicable.fold(BigDecimal.ZERO) { sum, at ->
                    context.charge(RunCounter.HOST_SCANS)
                    sum + ((source.values[at] as? Value.Num)?.value ?: BigDecimal.ZERO)
                },
            )
        }
        val truncated = candidates.size > MAX_MEMBERS
        val chosen = selected.map {
            context.charge(RunCounter.HOST_SCANS)
            it
        }.toSet()
        val details = candidates.take(MAX_MEMBERS).map { at ->
            context.charge(RunCounter.HOST_SCANS)
            AggregateContribution(at, source.values[at] ?: Value.Nil, source.active[at] == true, at in chosen)
        }
        val reason = if (undefined) "selected-value-undefined" else null
        val trace = if (policy != null && policy.dimension in folded) {
            BoundaryAggregateTrace(
                source.dims, fixed, policy.dimension, policy.boundary, periodKeys,
                selected.take(MAX_MEMBERS).map { at ->
                    context.charge(RunCounter.HOST_SCANS)
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
        context: RunContext,
    ): AggregationResult {
        val included = candidates.filter {
            context.charge(RunCounter.HOST_SCANS)
            source.active[it] == true
        }
        val numeratorNode = nodes(ratio.numerator)
        val denominatorNode = nodes(ratio.denominator)
        val undefined = included.any { at ->
            context.charge(RunCounter.HOST_SCANS)
            source.traces[at] is NodeTrace.Failed ||
                isUndefined(numeratorNode, at) || isUndefined(denominatorNode, at)
        }
        fun number(id: String, at: Coord): BigDecimal {
            context.charge(RunCounter.HOST_SCANS)
            return (nodes(id).values[at] as? Value.Num)?.value ?: BigDecimal.ZERO
        }
        val numerator = included.fold(BigDecimal.ZERO) { sum, at -> sum + number(ratio.numerator, at) }
        val denominator = included.fold(BigDecimal.ZERO) { sum, at -> sum + number(ratio.denominator, at) }
        var reason: String? = null
        val result = when {
            undefined -> {
                reason = "selected-value-undefined"
                null
            }
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
                context.charge(RunCounter.HOST_SCANS)
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

    /** Optional nil is additive zero; a failed or explicitly undefined calculation is not. */
    private fun isUndefined(source: ReductionNode, at: Coord): Boolean = source.active[at] == true &&
        (
            source.traces[at] is NodeTrace.Failed || source.values[at] == Value.Nil &&
                (source.total || source.boundary != null || source.ratio != null || source.undefinedValues)
            )

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
