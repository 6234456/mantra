package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.data.DataSources
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.CaseLink
import com.xqiou.mantra.core.model.CaseLinkMapping
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.LinkProvenance
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.frozenList
import com.xqiou.mantra.core.view.frozenMap
import com.xqiou.mantra.core.view.snapshot

/** One resolved link source. The runner supplies canonical identity and captured-byte revision. */
data class ResolvedLinkSource(
    val declaration: CaseLink,
    val caseKey: String,
    val revision: String,
    val view: CalculationView,
)

sealed interface CaseLinkBinding {
    /** Atomic materialization only: the graph runner still validates the actual target domain. */
    data class Success(
        val case: CaseData,
        val provenance: Map<InputAddress, LinkProvenance>,
        val targets: List<InputAddress>,
    ) : CaseLinkBinding
    data class Failure(val diagnostics: List<Diagnostic>) : CaseLinkBinding
}

/** Pure binding: no I/O, evaluation, hidden defaults, domain guessing, or mutable cache access. */
object CaseLinkBinder {
    fun bind(schema: Schema, case: CaseData, sources: List<ResolvedLinkSource>): CaseLinkBinding {
        // Future CaseData.links integration: atomic success covers the entire authored graph edge set.
        // Compare a multiset so duplicate/omitted resolver entries cannot masquerade as full binding.
        if (case.links.groupingBy { it }.eachCount() != sources.groupingBy { it.declaration }.eachCount()) {
            return CaseLinkBinding.Failure(
                frozenList(
                    listOf(
                        Diagnostic(
                            Severity.ERROR,
                            "MANTRA-LINK-ADDRESS",
                            "Resolved sources must cover every authored link exactly once",
                            case.links.firstOrNull()?.location ?: sources.firstOrNull()?.declaration?.location,
                            category = DiagnosticCategory.STRUCTURAL,
                        ),
                    ),
                ),
            )
        }
        val declarations = DataSources.inputs(schema)
        val dimensions = DataSources.inputDimensions(schema)
        val diagnostics = mutableListOf<Diagnostic>()
        val staged = linkedMapOf<InputAddress, Pair<Value, LinkProvenance>>()
        fun error(
            code: String,
            message: String,
            mapping: CaseLinkMapping,
            category: DiagnosticCategory = DiagnosticCategory.STRUCTURAL,
        ) {
            diagnostics += Diagnostic(
                Severity.ERROR,
                code,
                message,
                mapping.location,
                mapping.to.nodeId,
                frozenList(mapping.to.coord),
                category,
            )
        }
        for (source in sources) {
            val link = source.declaration
            val view = source.view
            val validVersion = view.schema.id == link.schema.id &&
                view.schema.text("version") == link.schema.version
            for (mapping in link.mappings) {
                val address = mapping.to.copy(coord = frozenList(mapping.to.coord))
                val input = declarations[address.nodeId]
                if (input == null || input.type == ValueType.TABLE ||
                    address.coord.size != dimensions[address.nodeId].orEmpty().size
                ) {
                    error("MANTRA-LINK-ADDRESS", "Link target must be a complete scalar input address", mapping)
                    continue
                }
                if (!validVersion) {
                    error("MANTRA-LINK-VERSION", "Source has a different exact schema identity/version", mapping)
                    continue
                }
                if (!view.succeeded) {
                    error("MANTRA-LINK-UNDEFINED", "Source failed technically", mapping, DiagnosticCategory.EVALUATION)
                    continue
                }
                val node = view.nodes[mapping.from.nodeId]
                if (node == null || mapping.from.coord.size != node.dims.size) {
                    error("MANTRA-LINK-ADDRESS", "Source address has an unknown node or incomplete coordinate", mapping)
                    continue
                }
                if (!node.values.containsKey(mapping.from.coord) || !node.isActive(mapping.from.coord)) {
                    error(
                        "MANTRA-LINK-UNDEFINED",
                        "Source address is missing or inactive",
                        mapping,
                        DiagnosticCategory.EVALUATION,
                    )
                    continue
                }
                val value = node.value(mapping.from.coord)
                if (value == Value.Nil) {
                    error("MANTRA-LINK-UNDEFINED", "Source value is nil", mapping, DiagnosticCategory.EVALUATION)
                    continue
                }
                if (!accepts(input.type, value)) {
                    error("MANTRA-LINK-TYPE", "Source value does not match target ${input.type.keyword}", mapping)
                    continue
                }
                if (containsFact(case.inputs[address.nodeId], address.coord) || address in staged) {
                    error("MANTRA-LINK-CONFLICT", "Target already has a local, imported, or linked fact", mapping)
                    continue
                }
                val provenance = LinkProvenance(
                    source.caseKey,
                    link.path,
                    view.case.id,
                    link.schema,
                    source.revision,
                    mapping.from.copy(coord = frozenList(mapping.from.coord)),
                )
                staged[address] = value.snapshot() to provenance
            }
        }
        if (diagnostics.isNotEmpty()) return CaseLinkBinding.Failure(frozenList(diagnostics))
        val inputs = LinkedHashMap(case.inputs)
        staged.forEach { (address, fact) ->
            inputs[address.nodeId] = insert(inputs[address.nodeId], address.coord, fact.first)
        }
        val provenance = frozenMap(staged.mapValues { it.value.second })
        val materialized = case.copy(inputs = frozenMap(inputs), linkInputs = provenance).snapshot()
        return CaseLinkBinding.Success(materialized, provenance, frozenList(staged.keys))
    }

    private fun accepts(type: ValueType, value: Value): Boolean = when (type) {
        ValueType.DECIMAL -> value is Value.Num
        ValueType.INTEGER -> value is Value.Num && value.value.stripTrailingZeros().scale() <= 0
        ValueType.BOOLEAN -> value is Value.Bool
        ValueType.KEYWORD -> value is Value.Kw
        ValueType.TEXT -> value is Value.Text
        ValueType.DATE -> value is Value.Date
        ValueType.ANY -> value !is Value.MapV && value !is Value.Vec && value != Value.Nil
        ValueType.TABLE -> false
    }

    /** An explicitly present nil conflicts too; malformed enclosing scalar facts cannot be overwritten. */
    private fun containsFact(value: Value?, path: List<String>): Boolean {
        if (value == null) return false
        if (path.isEmpty()) return true
        val entries = (value as? Value.MapV)?.entries ?: return true
        val key = path.first()
        // A canonical member must not acquire two competing branches through an import/local merge.
        // Reject along the selected path even when neither branch currently contains the leaf.
        if (entries.containsKey(Value.Kw(key)) && entries.containsKey(Value.Text(key))) return true
        val nested = when {
            entries.containsKey(Value.Kw(key)) -> entries.getValue(Value.Kw(key))
            entries.containsKey(Value.Text(key)) -> entries.getValue(Value.Text(key))
            else -> return false
        }
        return containsFact(nested, path.drop(1))
    }

    private fun insert(value: Value?, path: List<String>, fact: Value): Value {
        if (path.isEmpty()) return fact
        val entries = LinkedHashMap((value as? Value.MapV)?.entries.orEmpty())
        val keyword = Value.Kw(path.first())
        val text = Value.Text(path.first())
        val key = when {
            entries.containsKey(keyword) -> keyword
            entries.containsKey(text) -> text
            else -> keyword
        }
        entries[key] = insert(entries[key], path.drop(1), fact)
        return Value.MapV(frozenMap(entries))
    }
}
