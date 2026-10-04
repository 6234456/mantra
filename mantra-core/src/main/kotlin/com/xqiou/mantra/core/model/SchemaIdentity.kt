package com.xqiou.mantra.core.model

/** Actual loaded schema identity. Legacy unversioned schemas keep null, never a sentinel version. */
data class SchemaIdentity(val id: String, val version: String?) {
    init {
        require(id.isNotBlank()) { "Schema id cannot be blank" }
        require(version == null || version.isNotBlank()) { "A supplied schema version cannot be blank" }
    }
}
