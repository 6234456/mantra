package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.engine.LinkInspection
import com.xqiou.mantra.core.engine.RunAbortedException
import com.xqiou.mantra.core.engine.RunContext
import com.xqiou.mantra.core.engine.withLoadControl
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.Value

/**
 * Loads and compiles real source bindings without evaluating formulas or materializing link values.
 * No sessions, execution cache, or request controls are retained between independent inspections.
 * Dynamic domains, actual source nil/activity and integer-valued decimals are checked by execution.
 */
class CaseGraphInspector(private val resolver: CasePackageResolver) {
    private class Stop(val findings: List<CaseRunDiagnostic>) : RuntimeException()

    fun inspect(reference: CaseReference, options: CalculationOptions = CalculationOptions()): CaseInspectionResult {
        var context: RunContext? = null
        var root: CanonicalCaseKey? = null
        val active = mutableListOf<CanonicalCaseKey>()
        val completed = linkedMapOf<CanonicalCaseKey, CaseInspectionCase>()
        val order = linkedSetOf<CanonicalCaseKey>()
        val edges = mutableListOf<CaseRunEdge>()
        val findings = mutableListOf<CaseRunDiagnostic>()
        var rootView: com.xqiou.mantra.core.view.CalculationView? = null
        var failure: RunFailure? = null
        var usage: RunUsage? = null

        fun issue(key: CanonicalCaseKey?, code: String, message: String, revision: String? = null): Nothing =
            throw Stop(
                listOf(
                    CaseRunDiagnostic(
                        key,
                        revision,
                        Diagnostic(
                            Severity.ERROR,
                            code,
                            message,
                            category = DiagnosticCategory.STRUCTURAL,
                        ),
                    ),
                ),
            )

        fun assertVersion(ref: CaseReference, identity: CaseInspectionCase) {
            val expected = ref.expectedSchema
            if (expected != null &&
                (expected.id != identity.schema.id || expected.version != identity.schema.version)
            ) {
                issue(
                    identity.key,
                    "MANTRA-LINK-VERSION",
                    "Source has a different exact schema identity/version",
                    identity.revision,
                )
            }
        }

        fun visit(ref: CaseReference, depth: Long): CaseInspectionCase {
            val ctx = checkNotNull(context)
            val key = ctx.at(RunStage.LOADING, RunAddress(ref.fromCase?.value)) {
                ctx.withLoadControl { resolver.identify(ref, it) }
            }
            if (root == null) root = key
            ctx.at(RunStage.LOADING, RunAddress(key.value)) {
                ctx.highWater(RunCounter.LINK_DEPTH, depth)
                if (key in
                    active
                ) {
                    issue(
                        key,
                        "MANTRA-LINK-CYCLE",
                        "Case link cycle: ${(active + key).joinToString(" -> ") {
                            it.value
                        }}",
                    )
                }
            }
            completed[key]?.let {
                assertVersion(ref, it)
                return it
            }
            ctx.at(RunStage.LOADING, RunAddress(key.value)) { ctx.charge(RunCounter.CASES) }
            order += key
            active += key
            var revision: String? = null
            try {
                val prepared = ctx.at(RunStage.LOADING, RunAddress(key.value)) {
                    ctx.withLoadControl { resolver.load(key, it) }
                }
                revision = prepared.revision
                if (prepared.key !=
                    key
                ) {
                    issue(key, "MANTRA-LINK-ADDRESS", "Resolver returned a different canonical case key", revision)
                }
                val expected = ref.expectedSchema
                if (expected != null &&
                    (expected.id != prepared.schemaIdentity.id || expected.version != prepared.schemaIdentity.version)
                ) {
                    issue(key, "MANTRA-LINK-VERSION", "Source has a different exact schema identity/version", revision)
                }
                if (prepared.caseData.schemaId?.let { it != prepared.schemaIdentity.id } == true) {
                    issue(key, "MANTRA-LINK-VERSION", "Case schema id differs from its loaded schema", revision)
                }
                prepared.caseData.meta["schema-version"]?.let { version ->
                    if (version !is Value.Text || version.value != prepared.schemaIdentity.version) {
                        issue(
                            key,
                            "MANTRA-LINK-VERSION",
                            "Case schema version differs from its loaded schema",
                            revision,
                        )
                    }
                }
                val sources = prepared.caseData.links.map { link ->
                    ctx.at(RunStage.BINDING, RunAddress(key.value)) {
                        ctx.charge(RunCounter.LINK_MAPPINGS, link.mappings.size.toLong())
                    }
                    link to visit(CaseReference(link.path, key, link.schema), depth + 1)
                }
                val view = ctx.at(RunStage.PLANNING, RunAddress(key.value)) {
                    Mantra.inspectBound(prepared.schema, prepared.caseData, prepared.parameters, ctx)
                }
                val claimed = linkedSetOf<InputAddress>()
                val errors = sources.flatMap { (link, source) ->
                    ctx.at(RunStage.BINDING, RunAddress(key.value)) {
                        LinkInspection.validate(view, source.view, link, claimed, ctx)
                    }.map { CaseRunDiagnostic(key, revision, it) }
                }
                if (errors.isNotEmpty()) throw Stop(errors)
                sources.forEach { (link, source) ->
                    link.mappings.forEach { mapping ->
                        ctx.charge(RunCounter.HOST_SCANS)
                        edges += CaseRunEdge(source.key, key, mapping.from, mapping.to, source.revision)
                    }
                }
                val inspected =
                    CaseInspectionCase(
                        key,
                        prepared.caseId,
                        prepared.schemaIdentity,
                        prepared.revision,
                        view,
                        prepared.sources,
                    )
                view.diagnostics.forEach {
                    ctx.charge(RunCounter.HOST_SCANS)
                    findings += CaseRunDiagnostic(key, revision, it)
                }
                completed[key] = inspected
                return inspected
            } catch (error: MantraException) {
                throw Stop(error.diagnostics.map { CaseRunDiagnostic(key, revision, it) })
            } finally {
                check(active.removeLast() == key)
            }
        }

        try {
            val ctx = RunContext.begin(options).also { context = it }
            rootView = visit(reference, 0).view
            ctx.checkpoint()
        } catch (error: Stop) {
            findings += error.findings
            rootView = null
        } catch (error: MantraException) {
            findings += error.diagnostics.map { CaseRunDiagnostic(root, null, it) }
            rootView = null
        } catch (error: RunAbortedException) {
            failure = error.failure
            usage = error.usage
            rootView = null
            findings += CaseRunDiagnostic(
                error.failure.address?.caseKey?.let(::CanonicalCaseKey),
                null,
                Diagnostic(
                    Severity.ERROR,
                    error.failure.code,
                    "Run control stopped static inspection",
                    nodeId = error.failure.address?.nodeId,
                    coord = error.failure.address?.coord.orEmpty(),
                    category = DiagnosticCategory.EVALUATION,
                ),
            )
        } finally {
            context?.let { usage = it.finish() }
        }
        return CaseInspectionResult(
            root,
            rootView,
            order.mapNotNull { key -> completed[key]?.let { key to it } }.toMap(),
            edges,
            findings.distinct(),
            checkNotNull(usage),
            options.limits,
            failure,
        )
    }
}
