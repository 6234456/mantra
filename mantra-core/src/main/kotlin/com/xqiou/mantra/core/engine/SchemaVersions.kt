package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.model.Value

/** Exact opaque metadata, checked on both initial planning and incremental rebinding. */
internal object SchemaVersions {
    fun validate(schema: Schema, case: CaseData, sink: DiagnosticSink, requireMaterializedLinks: Boolean = true) {
        if (requireMaterializedLinks) {
            case.links.forEach { declaration ->
                declaration.mappings.forEach { mapping ->
                    val provenance = case.linkInputs[mapping.to]
                    if (provenance == null ||
                        provenance.schema != declaration.schema || provenance.from != mapping.from
                    ) {
                        sink.error(
                            "MANTRA-LINK-UNDEFINED",
                            "Authored link to ${mapping.to.nodeId} has not been materialized",
                            mapping.location,
                            mapping.to.nodeId,
                            mapping.to.coord,
                            category = DiagnosticCategory.EVALUATION,
                        )
                    }
                }
            }
        }
        val version = schema.meta.attributes["version"]
        if (version != null && (version !is Value.Text || version.value.isBlank())) {
            sink.error("MANTRA-SCHEMA-VERSION", "Schema :version must be nonblank literal text")
        }
        val declared = case.meta["schema-version"]
        if (declared != null && (declared !is Value.Text || declared.value.isBlank())) {
            sink.error("MANTRA-CASE-SCHEMA-VERSION", "Case :schema-version must be nonblank literal text")
        } else if (declared != null && declared != version) {
            sink.error("MANTRA-CASE-SCHEMA-VERSION", "Case ${case.id} schema version differs from its loaded schema")
        }
    }
}
