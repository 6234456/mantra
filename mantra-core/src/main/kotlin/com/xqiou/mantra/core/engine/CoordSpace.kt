package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.RunCounter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Enumerates declared coordinate scopes without evaluating formulas or retaining a run epoch.
 *
 * [members] must return the currently published, non-mutating domain list. Replacing a derived
 * domain publishes a different list. The generic member adapter avoids creating a second member
 * model; integration supplies the existing Member::key accessor.
 *
 * Internal fixed values unknown to a direct axis produce an empty scope. Public view entry points
 * must first validate every fixed dimension/member, preserving their existing argument errors.
 * Callers must still ensure all requested domains through MemberGraph before entering this class,
 * even when a direct fixed value will make the resulting scope empty.
 */
internal class CoordSpace<M : Any>(
    private val members: (String) -> List<M>,
    private val memberKey: (M) -> String,
    private val parents: (String, RunContext) -> ParentRelation? = { _, _ -> null },
) {
    /** Immutable relation metadata for one child dimension; keys map child to parent member. */
    data class ParentRelation(val parentDimension: String, val parentKeys: Map<String, String>)

    private class Axis<M>(val domain: List<M>, val keys: List<String>, val keySet: Set<String>)

    // Detached views may be read concurrently. Each dimension retains only its current index.
    // The live evaluator is thread-confined but can use this same implementation.
    private val axes = ConcurrentHashMap<String, Axis<M>>()

    private fun axis(dimension: String, context: RunContext): Axis<M> {
        context.checkpoint()
        val domain = members(dimension)
        return axes.compute(dimension) { _, previous ->
            if (previous?.domain === domain) {
                previous
            } else {
                val ordered = ArrayList<String>()
                val unique = LinkedHashSet<String>()
                domain.forEach { member ->
                    context.charge(RunCounter.HOST_SCANS)
                    val key = memberKey(member)
                    check(unique.add(key)) { "Duplicate published member $key in $dimension" }
                    ordered.add(key)
                }
                Axis(
                    domain,
                    Collections.unmodifiableList(ordered),
                    Collections.unmodifiableSet(unique),
                )
            }
        }!!
    }

    /** Counts and visits the already narrowed product, in declared member order. */
    fun coordinates(dimensions: List<String>, fixed: Map<String, String>, context: RunContext): List<List<String>> {
        require(dimensions.distinct().size == dimensions.size) { "Duplicate coordinate dimension" }
        val choices = dimensions.map { dimension ->
            val current = axis(dimension, context)
            val selected = fixed[dimension]
            when {
                selected == null -> current.keys
                selected in current.keySet -> listOf(selected)
                else -> emptyList()
            }
        }
        val product = context.coordinateProduct(choices.map { it.size.toLong() })
        context.preflight(RunCounter.COORDINATE_VISITS, product)
        if (product == 0L) return emptyList()

        val result = ArrayList<List<String>>()
        val indices = IntArray(dimensions.size)
        val relations = HashMap<String, ParentRelation?>()
        var remaining = product
        while (remaining > 0L) {
            // Charge before constructing the candidate. Rejected ancestor candidates are work too.
            context.charge(RunCounter.COORDINATE_VISITS)
            val candidate = choices.mapIndexed { index, values -> values[indices[index]] }
            if (
                dimensions.indices.all { index ->
                    compatible(dimensions[index], candidate[index], fixed, context, relations)
                }
            ) {
                result.add(Collections.unmodifiableList(candidate))
            }
            remaining--
            if (remaining > 0L) {
                var position = indices.lastIndex
                while (position >= 0) {
                    indices[position]++
                    if (indices[position] < choices[position].size) break
                    indices[position] = 0
                    position--
                }
            }
        }
        return Collections.unmodifiableList(result)
    }

    /** Complete direct assignments select a coordinate only if all ancestor constraints agree. */
    fun coordinate(dimensions: List<String>, fixed: Map<String, String>, context: RunContext): List<String>? {
        if (dimensions.any { it !in fixed }) return null
        val choices = dimensions.map { dimension ->
            val current = axis(dimension, context)
            fixed.getValue(dimension).takeIf { it in current.keySet } ?: return null
        }
        context.coordinateProduct(List(dimensions.size) { 1L })
        context.charge(RunCounter.COORDINATE_VISITS)
        val relations = HashMap<String, ParentRelation?>()
        if (
            dimensions.indices.any { index ->
                !compatible(dimensions[index], choices[index], fixed, context, relations)
            }
        ) {
            return null
        }
        return Collections.unmodifiableList(choices)
    }

    /** A view with an unresolved declared dimension must retain "Unknown member", not NoSuchElement. */
    fun validateFixed(declaredDimensions: Set<String>, fixed: Map<String, String>, context: RunContext) {
        fixed.forEach { (dimension, key) ->
            context.checkpoint()
            require(dimension in declaredDimensions) { "Unknown dimension $dimension" }
            require(key in axis(dimension, context).keySet) { "Unknown member $key in $dimension" }
        }
    }

    private fun compatible(
        dimension: String,
        key: String,
        fixed: Map<String, String>,
        context: RunContext,
        relations: MutableMap<String, ParentRelation?>,
    ): Boolean {
        var currentDimension = dimension
        var currentKey = key
        val seen = HashSet<String>()
        while (true) {
            context.checkpoint()
            check(seen.add(currentDimension)) { "Cyclic parent dimension $currentDimension" }
            val relation = if (relations.containsKey(currentDimension)) {
                relations[currentDimension]
            } else {
                // A relation provider constructing metadata must charge its own actual scans.
                parents(currentDimension, context).also { relations[currentDimension] = it }
            } ?: return true
            context.charge(RunCounter.HOST_SCANS)
            val parentKey = relation.parentKeys[currentKey] ?: return false
            val required = fixed[relation.parentDimension]
            if (required != null && required != parentKey) return false
            currentDimension = relation.parentDimension
            currentKey = parentKey
        }
    }

    /** Schema replacement creates a new instance; explicit release is useful during close. */
    fun clear() = axes.clear()
}
