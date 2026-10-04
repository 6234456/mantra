package com.xqiou.mantra.core.model

import com.xqiou.mantra.core.SourceLocation

/** Exact opaque schema version; no implicit latest or semantic-version normalization. */
data class SchemaReference(val id: String, val version: String) {
    init {
        require(id.isNotBlank() && version.isNotBlank())
    }
}

/** Complete scalar member address, in the node's declared dimension order. */
data class InputAddress(val nodeId: String, val coord: List<String> = emptyList())

data class CaseLinkMapping(val from: InputAddress, val to: InputAddress, val location: SourceLocation)

/** An authored source case reference, resolved relative to the declaring case. */
data class CaseLink(
    val path: String,
    val schema: SchemaReference,
    val mappings: List<CaseLinkMapping>,
    val location: SourceLocation,
)

/** Detached source identity and revision for one materialized LINK input. */
data class LinkProvenance(
    val caseKey: String,
    val path: String,
    val caseId: String,
    val schema: SchemaReference,
    val revision: String,
    val from: InputAddress,
)
