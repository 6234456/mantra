package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.core.model.SchemaReference
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import java.time.Instant
import java.util.Collections

/** Canonical resolver identity; equality, not an authored path spelling, identifies a graph case. */
data class CanonicalCaseKey(val value: String) {
    init {
        require(value.isNotBlank()) { "A canonical case key cannot be blank" }
    }
}

/**
 * An authored root or linked-case reference. Relative source paths resolve against [fromCase].
 * [expectedSchema] is an assertion checked by the runner, never an override of source bindings.
 */
data class CaseReference(
    val path: String,
    val fromCase: CanonicalCaseKey? = null,
    val expectedSchema: SchemaReference? = null,
) {
    init {
        require(path.isNotBlank()) { "Case source cannot be blank" }
    }
}

enum class SourceRole { SCHEMA, CASE, PARAMETERS, DATA, LAYOUT, INCLUDED }

/** Digest and size are obtained from the same captured bytes used to parse/import this resource. */
data class ParticipatingSource(val identity: String, val role: SourceRole, val sha256: String, val byteLength: Long) {
    init {
        require(identity.isNotBlank()) { "Participating source identity cannot be blank" }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Participating source sha256 must be 64 lowercase hexadecimal characters"
        }
        require(byteLength >= 0) { "Participating byte length cannot be negative" }
    }
}

/**
 * Source-owned inputs and parameters, prepared without evaluating any linked case.
 *
 * The resolver loads/imports its own declared data sources and parameter sets. It must not apply
 * the consumer's parameter overrides. Declared links remain available in the future CaseData
 * link field for the core graph runner to bind. Layout bytes contribute to participation/revision;
 * this type neither depends on render nor parses layout objects.
 *
 * The runner performs existing deep snapshotting before caching core model values. A resolver
 * transfers ownership of supplied models and must not mutate them after returning.
 */
class PreparedCasePackage(
    val key: CanonicalCaseKey,
    val caseId: String,
    val schemaIdentity: SchemaIdentity,
    val schema: Schema,
    val caseData: CaseData,
    parameters: List<ParameterSet>,
    sources: List<ParticipatingSource>,
    val revision: String,
) {
    val parameters: List<ParameterSet> = Collections.unmodifiableList(ArrayList(parameters))
    val sources: List<ParticipatingSource> = Collections.unmodifiableList(ArrayList(sources))

    init {
        require(caseId.isNotBlank()) { "Case id cannot be blank" }
        require(revision.isNotBlank()) { "Case revision cannot be blank" }
        val version = when (val raw = schema.meta.attributes["version"]) {
            null -> null
            is Value.Text -> raw.value.also {
                require(it.isNotBlank()) { "A supplied schema version cannot be blank" }
            }
            else -> throw IllegalArgumentException("A supplied schema version must be literal text")
        }
        require(schemaIdentity == SchemaIdentity(schema.id, version)) {
            "Prepared schema differs from its actual loaded identity"
        }
        require(caseData.id == caseId) { "Prepared case id differs from its declared identity" }
        require(this.sources.map { it.identity }.distinct().size == this.sources.size) {
            "One prepared package cannot contain duplicate canonical source identities"
        }
        require(this.sources.any { it.role == SourceRole.SCHEMA }) {
            "Prepared case requires a schema participation digest, including for virtual schemas"
        }
    }
}

/**
 * A scoped capability. Every method/property is valid only on the callback's owner thread and
 * while that callback is active. Retaining it cannot extend/reset a run epoch.
 *
 * Charge before buffer growth or retaining a row. The runner and loader nominate exactly one
 * charge owner for a shared canonical byte buffer; duplicate metadata must not double-charge it.
 */
interface CaseLoadControl {
    val deadline: Instant
    val cancellation: RunCancellation
    fun checkpoint()
    fun chargeParticipatingBytes(amount: Long)
    fun chargeInputRows(amount: Long = 1)
}

/**
 * File-system/package policy is supplied by the caller; core never imports workbench or render.
 *
 * identify canonicalizes without reading/importing the case body. The runner checks active-path
 * cycles, canonical memo hits and the unique-case cap before load. load returns the source's own
 * schema, parameters, imports and revision; matching an expected exact version is runner policy.
 * Callback controls belong to the same graph epoch and expire on every callback return/throw.
 */
interface CasePackageResolver {
    fun identify(reference: CaseReference, control: CaseLoadControl): CanonicalCaseKey
    fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage
}
