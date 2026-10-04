package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunCase
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.workbench.CasePackageLoader
import com.xqiou.mantra.workbench.CasePackageOverrides

/** Injection adapter only: loading, graph traversal, version checks and values stay in public SDKs. */
internal class GenericAcceptanceGraphRunner(private val paths: AcceptancePaths) : AcceptanceCalculationRunner {
    override fun calculate(request: AcceptanceCalculationRequest): AcceptanceExecution {
        val binding = request.binding
        val root = CanonicalCaseKey(paths.relative(binding.declaration.path))
        val loader = CasePackageLoader(
            paths.root,
            CasePackageOverrides(
                root.value,
                parameters = binding.parameterDocuments.map { it.parameters.id },
                layout = binding.layoutDocument?.id,
            ),
        )
        return CaseGraphRunner(loader).use { runner ->
            val graph = runner.run(
                CaseRunRequest(
                    CaseReference(root.value),
                    audit = if (request.evidence ==
                        AcceptanceEvidence.FULL
                    ) {
                        AuditOptions()
                    } else {
                        null
                    },
                ),
            )
            // A structural/source/control failure has no current root calculation to expose.
            // Never substitute a cached or independently re-evaluated successful result.
            val result = requireNotNull(graph.result) { "No current root calculation: ${graph.diagnostics}" }
            val rootCase = graph.cases.getValue(root)
            fun identity(case: CaseRunCase) = AcceptanceRunIdentity(
                paths.confined(paths.root.resolve(case.key.value)),
                AcceptanceSchemaId(case.schema.id, case.schema.version),
                case.revision,
            )
            fun local(key: CanonicalCaseKey) = graph.diagnostics.filter { it.case == key }.map { it.finding }
            val sources = graph.cases.values.filter { it.key != root }.map { case ->
                AcceptanceSourceRun(identity(case), case.result, local(case.key))
            }
            val inherited = graph.diagnostics.filter { finding ->
                finding.case != null && finding.case != root && finding.finding.category == DiagnosticCategory.BUSINESS
            }.map { finding ->
                val source = graph.cases.getValue(requireNotNull(finding.case))
                require(finding.revision == source.revision) { "Source finding lost its exact graph revision" }
                AcceptanceInheritedDiagnostic(identity(source), finding.finding)
            }
            val links = graph.edges.filter { it.target == root }.map { edge ->
                val source = graph.cases.getValue(edge.source)
                require(edge.sourceRevision == source.revision)
                val value = requireNotNull(source.result.nodes[edge.from.nodeId]?.values?.get(edge.from.coord))
                AcceptanceLinkedInput(
                    identity(source),
                    AcceptanceScalarAddress(edge.from.nodeId, edge.from.coord),
                    AcceptanceScalarAddress(edge.to.nodeId, edge.to.coord),
                    value,
                )
            }
            AcceptanceExecution(
                identity(rootCase),
                result,
                graph.succeeded,
                graph.validationPassed,
                local(root),
                sources,
                inherited,
                links,
            )
        }
    }
}
