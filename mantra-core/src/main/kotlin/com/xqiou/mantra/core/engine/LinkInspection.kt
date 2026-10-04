package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunStage
import com.xqiou.mantra.core.model.CaseLink
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.PeriodSpec
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.NodeKind

/** Checks declarations only. Runtime presence, activity, nil and numeric integrality remain deferred. */
internal object LinkInspection {
    fun validate(
        target: CalculationView,
        source: CalculationView,
        link: CaseLink,
        claimed: MutableSet<InputAddress>,
        context: RunContext,
    ): List<Diagnostic> {
        val findings = mutableListOf<Diagnostic>()
        link.mappings.forEach { mapping ->
            context.at(RunStage.BINDING, context.nodeAddress(mapping.to.nodeId, mapping.to.coord)) {
                context.charge(RunCounter.HOST_SCANS)
                fun error(code: String, message: String) {
                    findings += Diagnostic(
                        Severity.ERROR,
                        code,
                        message,
                        mapping.location,
                        mapping.to.nodeId,
                        mapping.to.coord,
                        DiagnosticCategory.STRUCTURAL,
                    )
                }
                val destination = target.nodes[mapping.to.nodeId]
                val origin = source.nodes[mapping.from.nodeId]
                when {
                    destination == null || destination.kind != NodeKind.INPUT || destination.type == ValueType.TABLE ||
                        mapping.to.coord.size != destination.dims.size -> {
                        error("MANTRA-LINK-ADDRESS", "Link target must be a complete scalar input address")
                    }
                    origin == null || origin.type == ValueType.TABLE || mapping.from.coord.size != origin.dims.size -> {
                        error("MANTRA-LINK-ADDRESS", "Link source must name a scalar node with a complete coordinate")
                    }
                    !declaredCoordinate(target, destination.dims, mapping.to.coord, context) ||
                        !declaredCoordinate(source, origin.dims, mapping.from.coord, context) -> {
                        error("MANTRA-LINK-ADDRESS", "Link coordinate has an unknown declared member")
                    }
                    !compatible(destination.type, origin.type) -> {
                        error(
                            "MANTRA-LINK-TYPE",
                            "Declared source type cannot supply target ${destination.type.keyword}",
                        )
                    }
                    containsFact(target.case.inputs[mapping.to.nodeId], mapping.to.coord, context) ||
                        !claimed.add(mapping.to) -> {
                        error("MANTRA-LINK-CONFLICT", "Target already has a local, imported, or linked fact")
                    }
                }
            }
        }
        return findings
    }

    private fun compatible(target: ValueType, source: ValueType): Boolean =
        target == ValueType.ANY || source == ValueType.ANY || target == source || target.isNumeric && source.isNumeric

    private fun declaredCoordinate(
        view: CalculationView,
        dimensions: List<String>,
        coord: List<String>,
        context: RunContext,
    ): Boolean = dimensions.withIndex().all { (index, id) ->
        context.charge(RunCounter.HOST_SCANS)
        val dimension = view.dimensions.getValue(id)
        val key = coord[index]
        when (val periods = dimension.periods) {
            is PeriodSpec.Generated -> {
                val period = key.removePrefix("P").toIntOrNull()
                period != null && key == "P$period" && period in 1..periods.count
            }
            is PeriodSpec.Listed -> periods.entries.any {
                context.charge(RunCounter.HOST_SCANS)
                it.key == key
            }
            null -> dimension.fromTable != null || dimension.members.any {
                context.charge(RunCounter.HOST_SCANS)
                it.key == key
            }
        }
    }

    /** Presence, including explicit nil and competing keyword/text branches, is a conflict. */
    private fun containsFact(value: Value?, path: List<String>, context: RunContext): Boolean {
        context.charge(RunCounter.HOST_SCANS)
        if (value == null) return false
        if (path.isEmpty()) return true
        val entries = (value as? Value.MapV)?.entries ?: return true
        val keyword = Value.Kw(path.first())
        val text = Value.Text(path.first())
        if (keyword in entries && text in entries) return true
        val nested = when {
            keyword in entries -> entries.getValue(keyword)
            text in entries -> entries.getValue(text)
            else -> return false
        }
        return containsFact(nested, path.drop(1), context)
    }
}
