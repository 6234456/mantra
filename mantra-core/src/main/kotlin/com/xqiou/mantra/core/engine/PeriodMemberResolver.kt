package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunStage
import com.xqiou.mantra.core.model.PeriodEntry
import com.xqiou.mantra.core.model.PeriodSpec
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.Member
import java.time.DateTimeException
import java.time.LocalDate

/** Resolved timeline metadata, projected through immutable member records. */
internal data class PeriodMember(
    val key: String,
    val label: String,
    val index: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val previousKey: String?,
    val parentKey: String?,
) {
    fun asMember(): Member = Member(
        key,
        label,
        index,
        linkedMapOf(
            "key" to Value.Kw(key),
            "label" to Value.Text(label),
            "index" to Value.num(index.toLong()),
            "start" to Value.Date(start),
            "end-exclusive" to Value.Date(endExclusive),
            "previous-key" to (previousKey?.let(Value::Kw) ?: Value.Nil),
            "parent-key" to (parentKey?.let(Value::Kw) ?: Value.Nil),
        ),
    )
}

internal data class ResolvedPeriodDimension(
    val id: String,
    val periods: List<PeriodMember>,
    val parentDimension: String?,
) {
    val members: List<Member> get() = periods.map(PeriodMember::asMember)

    /** The same relation_<dimension> map used by table-backed parent relations. */
    val parents: Map<String, String> get() = periods.mapNotNull { member ->
        member.parentKey?.let { member.key to it }
    }.toMap()

    fun previous(key: String, context: RunContext? = null): PeriodMember? {
        val index = periods.indexOfFirst {
            context?.charge(RunCounter.HOST_SCANS)
            it.key == key
        }
        require(index >= 0) { "Unknown member $key of $id" }
        return periods.getOrNull(index - 1)
    }
}

/**
 * Resolves only period-domain facts. No value formulas, activity flags or business validation run
 * here. The member scheduler must resolve the optional parent domain before invoking this method.
 */
internal class PeriodMemberResolver(
    private val sink: DiagnosticSink,
    /** Matches the pinned kernel's member-map collection ceiling; not an execution budget. */
    private val maxMembers: Int = 5_000,
) {
    fun resolve(
        id: String,
        spec: PeriodSpec,
        location: SourceLocation,
        parent: ResolvedPeriodDimension? = null,
        context: RunContext? = null,
    ): ResolvedPeriodDimension? = if (context == null) {
        resolveCurrent(id, spec, location, parent, null)
    } else {
        context.at(RunStage.DOMAIN, context.nodeAddress(id)) { resolveCurrent(id, spec, location, parent, context) }
    }

    private fun resolveCurrent(
        id: String,
        spec: PeriodSpec,
        location: SourceLocation,
        parent: ResolvedPeriodDimension?,
        context: RunContext?,
    ): ResolvedPeriodDimension? {
        val entries = when (spec) {
            is PeriodSpec.Generated -> generated(id, spec, location, context) ?: return null
            is PeriodSpec.Listed -> spec.entries
        }
        if (entries.isEmpty()) {
            error("MANTRA-PERIOD-COUNT", "Period dimension $id must contain at least one period", id, location)
            return null
        }
        if (entries.size > maxMembers) {
            error(
                "MANTRA-PERIOD-LIMIT",
                "Period dimension $id contains ${entries.size} members; limit is $maxMembers",
                id,
                location,
            )
            return null
        }

        context?.coordinateProduct(listOf(entries.size.toLong()))
        var valid = true
        val seen = mutableSetOf<String>()
        entries.forEachIndexed { index, entry ->
            context?.charge(RunCounter.HOST_SCANS)
            val at = entry.location ?: location
            if (entry.key.isBlank() || !seen.add(entry.key)) {
                error("MANTRA-PERIOD-KEY", "Period $id has a blank or duplicate key ${entry.key}", id, at)
                valid = false
            }
            if (entry.start >= entry.endExclusive) {
                error(
                    "MANTRA-PERIOD-RANGE",
                    "Period ${entry.key} of $id must have start before its exclusive end",
                    id,
                    at,
                )
                valid = false
            }
            val previous = entries.getOrNull(index - 1)
            if (previous != null && previous.endExclusive != entry.start) {
                error(
                    "MANTRA-PERIOD-CONTINUITY",
                    "Period ${entry.key} of $id starts ${entry.start}; ${previous.key} ends " +
                        "${previous.endExclusive}. Periods must be ordered and adjacent",
                    id,
                    at,
                )
                valid = false
            }
        }
        if (!valid) return null

        val parentKeys = if (parent == null) {
            List<String?>(entries.size) { null }
        } else {
            entries.map { entry ->
                val owners = parent.periods.filter {
                    context?.charge(RunCounter.HOST_SCANS)
                    it.start <= entry.start && entry.endExclusive <= it.endExclusive
                }
                if (owners.size == 1) {
                    owners.single().key
                } else {
                    val overlapping = parent.periods.count {
                        context?.charge(RunCounter.HOST_SCANS)
                        entry.start < it.endExclusive && it.start < entry.endExclusive
                    }
                    val reason = when {
                        owners.size > 1 -> "is contained by multiple parent periods"
                        overlapping > 1 -> "crosses a parent-period boundary"
                        else -> "is outside the parent-period range"
                    }
                    error(
                        "MANTRA-PERIOD-PARENT",
                        "Period ${entry.key} of $id $reason in ${parent.id}",
                        id,
                        entry.location ?: location,
                    )
                    valid = false
                    null
                }
            }
        }
        if (!valid) return null

        return ResolvedPeriodDimension(
            id,
            entries.mapIndexed { index, entry ->
                context?.charge(RunCounter.HOST_SCANS)
                PeriodMember(
                    entry.key,
                    entry.label ?: "${entry.start} – ${entry.endExclusive.minusDays(1)}",
                    index,
                    entry.start,
                    entry.endExclusive,
                    entries.getOrNull(index - 1)?.key,
                    parentKeys[index],
                )
            },
            parent?.id,
        )
    }

    private fun generated(
        id: String,
        spec: PeriodSpec.Generated,
        location: SourceLocation,
        context: RunContext?,
    ): List<PeriodEntry>? {
        if (spec.count !in 1..maxMembers) {
            error(
                "MANTRA-PERIOD-COUNT",
                "Period dimension $id requires :count in 1..$maxMembers; got ${spec.count}",
                id,
                location,
            )
            return null
        }
        context?.coordinateProduct(listOf(spec.count.toLong()))
        return try {
            // Always advance from the original anchor: Jan 31 → Feb 28 → Mar 31.
            val boundaries = (0..spec.count).map { index ->
                context?.charge(RunCounter.HOST_SCANS)
                spec.start.plusMonths(Math.multiplyExact(index.toLong(), spec.unit.months))
            }
            List(spec.count) { index ->
                context?.charge(RunCounter.HOST_SCANS)
                PeriodEntry("P${index + 1}", boundaries[index], boundaries[index + 1], location = location)
            }
        } catch (_: DateTimeException) {
            error("MANTRA-PERIOD-RANGE", "Generated periods of $id exceed supported date range", id, location)
            null
        } catch (_: ArithmeticException) {
            error("MANTRA-PERIOD-RANGE", "Generated periods of $id exceed supported date range", id, location)
            null
        }
    }

    private fun error(code: String, message: String, id: String, location: SourceLocation) {
        sink.error(code, message, location, id, category = DiagnosticCategory.STRUCTURAL)
    }
}
