package com.xqiou.mantra.packages

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import java.time.LocalDate

enum class ParameterSelectionMode { EFFECTIVE_DATE, WHAT_IF }

data class ParameterProvenance(
    val key: String,
    val setId: String,
    val schema: PackageSchemaBinding,
    val packageIdentity: PackageIdentity,
    val packageRevision: String,
    val resourcePath: String,
    val resourceSha256: String,
    val effectiveDate: LocalDate,
    val validFrom: LocalDate?,
    val validUntil: LocalDate?,
    val mode: ParameterSelectionMode,
    val validForDate: Boolean,
    val reference: String?,
)

class ParameterSelection internal constructor(
    parameters: List<ParameterSet>,
    provenance: List<ParameterProvenance>,
    /** Includes the explicit date and choice policy even when the selected values are identical. */
    val revision: String,
) {
    val parameters: List<ParameterSet> = java.util.List.copyOf(parameters)
    val provenance: List<ParameterProvenance> = java.util.List.copyOf(provenance)
}

/** Selects declared keys at the host boundary. No arithmetic, today(), inferred interval or latest. */
object ParameterSelector {
    fun effectiveDate(
        snapshot: PackageSnapshot,
        schema: PackageSchemaBinding,
        effectiveDate: LocalDate,
        candidateIds: List<String>,
        requiredKeys: Set<String>,
    ): ParameterSelection =
        select(snapshot, schema, effectiveDate, candidateIds, requiredKeys, ParameterSelectionMode.EFFECTIVE_DATE)

    /** Ordered explicit layers; date eligibility is evidence, not a claim that the choice is valid. */
    fun whatIf(
        snapshot: PackageSnapshot,
        schema: PackageSchemaBinding,
        effectiveDate: LocalDate,
        orderedIds: List<String>,
        requiredKeys: Set<String>,
    ): ParameterSelection =
        select(snapshot, schema, effectiveDate, orderedIds, requiredKeys, ParameterSelectionMode.WHAT_IF)

    private data class Candidate(
        val entry: PackageParameters,
        val set: ParameterSet,
        val from: LocalDate?,
        val until: LocalDate?,
    ) {
        fun eligible(date: LocalDate): Boolean =
            (from == null || !date.isBefore(from)) && (until == null || date.isBefore(until))
    }

    private fun select(
        snapshot: PackageSnapshot,
        schema: PackageSchemaBinding,
        date: LocalDate,
        ids: List<String>,
        keys: Set<String>,
        mode: ParameterSelectionMode,
    ): ParameterSelection {
        if (ids.isEmpty() || ids.toSet().size != ids.size ||
            keys.isEmpty()
        ) {
            fail("MANTRA-PACKAGE-PARAMETERS", "Explicit unique candidates and requested keys are required")
        }
        val declarations = snapshot.schema(schema).params.map { it.id }.toSet()
        if (!declarations.containsAll(
                keys,
            )
        ) {
            fail("MANTRA-PACKAGE-PARAMETERS", "Requested keys are not declared by the exact schema")
        }
        val candidates = ids.map { id ->
            val entry = snapshot.manifest.parameters.singleOrNull { it.id == id }
                ?: fail("MANTRA-PACKAGE-PARAMETERS", "Unknown parameter candidate: $id")
            if (entry.schema != schema) fail("MANTRA-PACKAGE-BINDING", "Parameter $id targets a different exact schema")
            val set = snapshot.parameters(id)
            fun endpoint(name: String): LocalDate? {
                val value = set.meta[name] ?: return null
                return try {
                    when (value) {
                        is Value.Date -> value.value
                        is Value.Text -> LocalDate.parse(value.value)
                        else -> fail("MANTRA-PACKAGE-DATE", "$id :$name must be an ISO date")
                    }
                } catch (error: java.time.format.DateTimeParseException) {
                    fail("MANTRA-PACKAGE-DATE", "$id :$name must be an ISO date", error)
                }
            }
            val from = endpoint("valid-from")
            val until = endpoint("valid-until")
            if (from != null && until != null &&
                !from.isBefore(until)
            ) {
                fail("MANTRA-PACKAGE-DATE", "Empty or reversed half-open interval: $id")
            }
            Candidate(entry, set, from, until)
        }
        val selected = keys.sorted().associateWith { key ->
            val matching = candidates.filter {
                key in it.set.values &&
                    (mode == ParameterSelectionMode.WHAT_IF || it.eligible(date))
            }
            if (matching.isEmpty()) fail("MANTRA-PACKAGE-DATE-GAP", "No eligible value for $key at $date")
            if (mode == ParameterSelectionMode.EFFECTIVE_DATE && matching.size > 1) {
                fail(
                    "MANTRA-PACKAGE-DATE-OVERLAP",
                    "Overlapping values for $key at $date: ${matching.joinToString {
                        it.set.id
                    }}",
                )
            }
            matching.last()
        }
        // Preserve every explicitly chosen what-if layer for actual precedence and its evidence.
        val filtered = candidates.mapNotNull { candidate ->
            val candidateKeys = keys.filter { key ->
                key in candidate.set.values && (mode == ParameterSelectionMode.WHAT_IF || selected[key] === candidate)
            }.toSet()
            if (candidateKeys.isEmpty()) {
                null
            } else {
                candidate.set.copy(
                    values = java.util.Map.copyOf(
                        candidate.set.values.filterKeys {
                            it in candidateKeys
                        }.mapValues { immutableValue(it.value) },
                    ),
                    references = java.util.Map.copyOf(candidate.set.references.filterKeys { it in candidateKeys }),
                    meta = java.util.Map.copyOf(candidate.set.meta.mapValues { immutableValue(it.value) }),
                )
            }
        }
        val provenance = selected.map { (key, candidate) ->
            ParameterProvenance(
                key, candidate.set.id, schema, snapshot.manifest.identity, snapshot.revision,
                candidate.entry.path,
                snapshot.descriptor(
                    candidate.entry.path,
                ).sha256,
                date, candidate.from, candidate.until,
                mode, candidate.eligible(date), candidate.set.references[key],
            )
        }
        val context = listOf(
            snapshot.revision,
            schema.identity.id,
            schema.identity.version,
            schema.mode.name,
            date.toString(),
            mode.name,
            ids.size.toString(),
        ) +
            (if (mode == ParameterSelectionMode.WHAT_IF) ids else ids.sorted()) +
            listOf(keys.size.toString()) +
            keys.sorted()
        val revision = fingerprint(context)
        return ParameterSelection(filtered, provenance, revision)
    }

    private fun immutableValue(value: Value): Value = when (value) {
        is Value.Vec -> Value.Vec(java.util.List.copyOf(value.items.map(::immutableValue)))
        is Value.MapV -> Value.MapV(
            java.util.Collections.unmodifiableMap(
                value.entries.entries.associate {
                    immutableValue(it.key) to
                        immutableValue(it.value)
                },
            ),
        )
        else -> value
    }
}
