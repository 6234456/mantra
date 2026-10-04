package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.mantra.core.view.frozenList
import com.xqiou.mantra.core.view.frozenMap

/** A null case selects the root; an explicit key must belong to the requested root graph. */
data class CaseExplainAddress(
    val case: CanonicalCaseKey? = null,
    val address: InputAddress,
    val expectedRevision: String? = null,
)

/** Independent top-level request; source and target calculations share its controls. */
data class CaseRunRequest(
    val reference: CaseReference,
    val options: CalculationOptions = CalculationOptions(),
    val audit: AuditOptions? = null,
    val explain: CaseExplainAddress? = null,
)

/** Detached snapshot of one participating case. Legacy unversioned roots keep a null version. */
class CaseRunCase(
    val key: CanonicalCaseKey,
    val caseId: String,
    val schema: SchemaIdentity,
    val revision: String,
    val result: CalculationResult,
    sources: List<ParticipatingSource>,
    val recalculation: RecalculationStats? = null,
) {
    val view: CalculationView get() = result.view
    val sources: List<ParticipatingSource> = frozenList(sources)
}

/** One explicit mapping, with canonical source identity and its actual participating revision. */
data class CaseRunEdge(
    val source: CanonicalCaseKey,
    val target: CanonicalCaseKey,
    val from: InputAddress,
    val to: InputAddress,
    val sourceRevision: String,
)

/** Findings retain original case identity; node names alone never determine ownership. */
data class CaseRunDiagnostic(val case: CanonicalCaseKey?, val revision: String?, val finding: Diagnostic)

/**
 * Immutable case-graph outcome. A failed run never supplies an old successful root result.
 * [usage] is actual cumulative host work in the shared epoch, not summed kernel cost metrics.
 */
class CaseRunResult internal constructor(
    val root: CanonicalCaseKey?,
    val result: CalculationResult?,
    cases: Map<CanonicalCaseKey, CaseRunCase>,
    edges: List<CaseRunEdge>,
    diagnostics: List<CaseRunDiagnostic>,
    val usage: RunUsage,
    val limits: RunLimits = RunLimits(),
    val failure: RunFailure? = null,
    val explain: ExplainTrace? = null,
) {
    val cases: Map<CanonicalCaseKey, CaseRunCase> = frozenMap(cases)
    val edges: List<CaseRunEdge> = frozenList(
        edges.map { edge ->
            edge.copy(
                from = edge.from.copy(coord = frozenList(edge.from.coord)),
                to = edge.to.copy(coord = frozenList(edge.to.coord)),
            )
        },
    )
    val diagnostics: List<CaseRunDiagnostic> = frozenList(
        diagnostics.map { it.copy(finding = it.finding.copy(coord = frozenList(it.finding.coord))) },
    )
    val succeeded: Boolean get() = failure == null && result?.succeeded == true && diagnostics.none {
        it.finding.severity == Severity.ERROR && it.finding.category != DiagnosticCategory.BUSINESS
    }
    val validationPassed: Boolean get() = result?.validationPassed == true && diagnostics.none {
        it.finding.severity == Severity.ERROR && it.finding.category == DiagnosticCategory.BUSINESS
    }
}
