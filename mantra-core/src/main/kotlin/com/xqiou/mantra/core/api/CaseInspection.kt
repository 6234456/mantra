package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.frozenList
import com.xqiou.mantra.core.view.frozenMap

/** Captured source identity and static structure. No formulas or input values have been evaluated. */
class CaseInspectionCase(
    val key: CanonicalCaseKey,
    val caseId: String,
    val schema: SchemaIdentity,
    /** Revision of this captured package; this is not an evaluated graph revision. */
    val revision: String,
    val view: CalculationView,
    sources: List<ParticipatingSource>,
) {
    val sources: List<ParticipatingSource> = frozenList(sources)
}

/** Static graph inspection never materializes links, dynamic member domains, or business findings. */
class CaseInspectionResult internal constructor(
    val root: CanonicalCaseKey?,
    val view: CalculationView?,
    cases: Map<CanonicalCaseKey, CaseInspectionCase>,
    edges: List<CaseRunEdge>,
    diagnostics: List<CaseRunDiagnostic>,
    val usage: RunUsage,
    val limits: RunLimits,
    val failure: RunFailure? = null,
) {
    val cases: Map<CanonicalCaseKey, CaseInspectionCase> = frozenMap(cases)
    val edges: List<CaseRunEdge> = frozenList(
        edges.map { edge ->
            edge.copy(
                from = edge.from.copy(coord = frozenList(edge.from.coord)),
                to = edge.to.copy(coord = frozenList(edge.to.coord)),
            )
        },
    )
    val diagnostics: List<CaseRunDiagnostic> = frozenList(
        diagnostics.map {
            it.copy(finding = it.finding.copy(coord = frozenList(it.finding.coord)))
        },
    )
    val succeeded: Boolean get() = failure == null && view != null && diagnostics.none {
        it.finding.severity == Severity.ERROR
    }
}
