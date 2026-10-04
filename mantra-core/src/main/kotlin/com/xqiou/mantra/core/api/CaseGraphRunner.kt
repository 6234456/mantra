package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.engine.RunAbortedException
import com.xqiou.mantra.core.engine.RunContext
import com.xqiou.mantra.core.engine.withLoadControl
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.snapshot
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Owner-thread graph runtime; the caller supplies loading/confinement policy through its resolver. */
class CaseGraphRunner(private val resolver: CasePackageResolver, private val sessionLimit: Int = 16) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var closed = false
    private class Entry(val fingerprint: String, val profile: Map<*, Long>, val session: CalculationSession)
    private val sessions = LinkedHashMap<CanonicalCaseKey, Entry>(16, .75f, true)
    private class Completed(
        val prepared: PreparedCasePackage,
        val revision: String,
        val result: CalculationResult,
        val stats: RecalculationStats?,
    )
    private class Stop(val findings: List<CaseRunDiagnostic>) : RuntimeException()

    init {
        require(sessionLimit >= 0)
    }

    fun run(request: CaseRunRequest): CaseRunResult {
        checkOwner()
        check(!closed) { "Case graph runner is closed" }
        var context: RunContext? = null
        var root: CanonicalCaseKey? = null
        val active = mutableListOf<CanonicalCaseKey>()
        val loadedOrder = linkedSetOf<CanonicalCaseKey>()
        val completed = linkedMapOf<CanonicalCaseKey, Completed>()
        val edges = mutableListOf<CaseRunEdge>()
        val failures = mutableListOf<CaseRunDiagnostic>()

        fun issue(
            key: CanonicalCaseKey?,
            code: String,
            message: String,
            category: DiagnosticCategory = DiagnosticCategory.STRUCTURAL,
            revision: String? = completed[key]?.revision,
        ): Nothing = throw Stop(
            listOf(
                CaseRunDiagnostic(key, revision, Diagnostic(Severity.ERROR, code, message, category = category)),
            ),
        )

        fun assertVersion(reference: CaseReference, prepared: PreparedCasePackage) {
            val actual = prepared.schemaIdentity
            val expected = reference.expectedSchema
            if (expected != null && (expected.id != actual.id || expected.version != actual.version)) {
                issue(
                    prepared.key,
                    "MANTRA-LINK-VERSION",
                    "Linked case does not have the expected exact schema identity/version",
                )
            }
            if (prepared.caseData.schemaId != null && prepared.caseData.schemaId != actual.id) {
                issue(prepared.key, "MANTRA-LINK-VERSION", "Case schema id differs from its loaded schema")
            }
            prepared.caseData.meta["schema-version"]?.let { declared ->
                if (declared !is Value.Text || declared.value.isBlank() || declared.value != actual.version) {
                    issue(prepared.key, "MANTRA-LINK-VERSION", "Case schema version differs from its loaded schema")
                }
            }
        }

        fun visit(reference: CaseReference, depth: Long): Completed {
            val ctx = checkNotNull(context)
            val key = ctx.at(RunStage.LOADING, RunAddress(reference.fromCase?.value)) {
                ctx.withLoadControl { resolver.identify(reference, it) }
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
                assertVersion(reference, it.prepared)
                return it
            }
            ctx.at(RunStage.LOADING, RunAddress(key.value)) { ctx.charge(RunCounter.CASES) }
            loadedOrder += key
            active += key
            try {
                val prepared = ctx.at(RunStage.LOADING, RunAddress(key.value)) {
                    ctx.withLoadControl { resolver.load(key, it) }
                }
                if (prepared.key !=
                    key
                ) {
                    issue(key, "MANTRA-LINK-ADDRESS", "Resolver returned a different canonical case key")
                }
                assertVersion(reference, prepared)
                val sources = prepared.caseData.links.map { link ->
                    ctx.at(RunStage.BINDING, RunAddress(key.value)) {
                        ctx.charge(RunCounter.LINK_MAPPINGS, link.mappings.size.toLong())
                    }
                    val source = visit(CaseReference(link.path, key, link.schema), depth + 1)
                    if (!source.result.succeeded) {
                        throw Stop(
                            source.result.diagnostics.map {
                                CaseRunDiagnostic(source.prepared.key, source.revision, it)
                            },
                        )
                    }
                    link.mappings.forEach { mapping ->
                        edges += CaseRunEdge(source.prepared.key, key, mapping.from, mapping.to, source.revision)
                    }
                    ResolvedLinkSource(link, source.prepared.key.value, source.revision, source.result.view)
                }
                val binding = ctx.at(RunStage.BINDING, RunAddress(key.value)) {
                    CaseLinkBinder.bind(prepared.schema, prepared.caseData, sources)
                }
                if (binding is CaseLinkBinding.Failure) {
                    throw Stop(
                        binding.diagnostics.map {
                            CaseRunDiagnostic(key, null, it)
                        },
                    )
                }
                binding as CaseLinkBinding.Success
                val case = binding.case.copy(linkInputs = binding.provenance).snapshot()
                val sourceCases = sources.map { completed.getValue(CanonicalCaseKey(it.caseKey)) }
                val revision = graphRevision(prepared, sourceCases.map { it.prepared.key.value to it.revision })
                val explain = request.explain?.takeIf { (it.case ?: root) == key }
                if (explain?.expectedRevision != null && explain.expectedRevision != revision) {
                    issue(key, "MANTRA-LINK-REVISION", "Explain source revision has changed", revision = revision)
                }
                val result: CalculationResult
                val stats: RecalculationStats?
                if (request.audit != null || explain != null) {
                    result = ctx.at(RunStage.PLANNING, RunAddress(key.value)) {
                        Mantra.calculateBound(
                            prepared.schema,
                            case,
                            prepared.parameters,
                            ctx,
                            request.audit,
                            explain?.address,
                        )
                    }
                    stats = null
                } else {
                    val fingerprint = schemaFingerprint(prepared)
                    var entry = sessions[key]
                    if (entry != null &&
                        (entry.fingerprint != fingerprint || entry.profile != request.options.formulaLimits)
                    ) {
                        sessions.remove(key)?.session?.close()
                        entry = null
                    }
                    if (entry == null) {
                        val session = ctx.at(RunStage.PLANNING, RunAddress(key.value)) {
                            Mantra.openSessionBound(prepared.schema, case, prepared.parameters, ctx)
                        }
                        entry = Entry(fingerprint, request.options.formulaLimits, session)
                        if (sessionLimit > 0) sessions[key] = entry
                        result = session.result
                        stats = session.lastRun
                        if (sessionLimit == 0) session.close()
                    } else {
                        result = ctx.at(RunStage.PLANNING, RunAddress(key.value)) {
                            entry.session.recalculateBound(case, prepared.parameters, ctx)
                        }
                        stats = entry.session.lastRun
                    }
                    while (sessions.size > sessionLimit) {
                        val oldest = sessions.entries.first()
                        sessions.remove(oldest.key)?.session?.close()
                    }
                }
                if (result.succeeded) {
                    binding.targets.forEach { target ->
                        ctx.at(RunStage.BINDING, RunAddress(key.value, target.nodeId, target.coord)) {
                            ctx.charge(RunCounter.HOST_SCANS)
                            val node = result.nodes[target.nodeId]
                            if (node == null || target.coord !in node.values || !node.isActive(target.coord)) {
                                issue(
                                    key,
                                    "MANTRA-LINK-UNDEFINED",
                                    "Bound target address is missing or inactive in the actual linked domain",
                                    DiagnosticCategory.EVALUATION,
                                )
                            }
                        }
                    }
                }
                val inherited = sourceCases.flatMap { source ->
                    source.result.diagnostics.filter { it.category == DiagnosticCategory.BUSINESS }.map { finding ->
                        if (finding.caseKey ==
                            null
                        ) {
                            finding.copy(caseKey = source.prepared.key.value, caseRevision = source.revision)
                        } else {
                            finding
                        }
                    }
                }.distinct()
                val published = result.withGraphMetadata(inherited, ctx.snapshot())
                return Completed(prepared, revision, published, stats).also { completed[key] = it }
            } catch (error: MantraException) {
                throw Stop(error.diagnostics.map { CaseRunDiagnostic(key, null, it) })
            } finally {
                check(active.removeLast() == key)
            }
        }

        var rootResult: CalculationResult? = null
        var failure: RunFailure? = null
        var usage: RunUsage? = null
        try {
            context = RunContext.begin(request.options)
            val complete = visit(request.reference, 0)
            if (request.explain != null && (request.explain.case ?: root) !in completed) {
                issue(root, "MANTRA-LINK-ADDRESS", "Explain case does not participate in this root graph")
            }
            usage = context.finish()
            rootResult =
                complete.result.withGraphMetadata(
                    complete.result.diagnostics.filter {
                        it.caseKey != null
                    },
                    checkNotNull(usage),
                )
        } catch (error: Stop) {
            failures += error.findings
            usage = checkNotNull(context).finish()
        } catch (error: MantraException) {
            failures += error.diagnostics.map { CaseRunDiagnostic(root, null, it) }
            usage = checkNotNull(context).finish()
        } catch (error: RunAbortedException) {
            failure = error.failure
            usage = context?.finish() ?: error.usage
            failures += CaseRunDiagnostic(
                error.failure.address?.caseKey?.let(::CanonicalCaseKey),
                null,
                Diagnostic(
                    Severity.ERROR,
                    error.failure.code,
                    "Run control stopped ${error.failure.stage}",
                    nodeId = error.failure.address?.nodeId,
                    coord = error.failure.address?.coord.orEmpty(),
                    category = DiagnosticCategory.EVALUATION,
                ),
            )
        }
        val cases = loadedOrder.mapNotNull { key ->
            completed[key]?.let { complete ->
                key to CaseRunCase(
                    key,
                    complete.prepared.caseId,
                    complete.prepared.schemaIdentity,
                    complete.revision,
                    complete.result,
                    complete.prepared.sources,
                    complete.stats,
                )
            }
        }.toMap()
        val findings = completed.values.flatMap { complete ->
            complete.result.diagnostics.filter {
                it.caseKey == null
            }.map { CaseRunDiagnostic(complete.prepared.key, complete.revision, it) }
        } + failures
        return CaseRunResult(
            root, rootResult, cases, edges, findings.distinct(), checkNotNull(usage), request.options.limits, failure,
            request.explain?.let { address -> completed[address.case ?: root]?.result?.explainTrace },
        )
    }

    override fun close() {
        checkOwner()
        if (!closed) {
            closed = true
            var first: Throwable? = null
            try {
                sessions.values.forEach { entry ->
                    try {
                        entry.session.close()
                    } catch (failure: Throwable) {
                        if (first == null) first = failure else first!!.addSuppressed(failure)
                    }
                }
            } finally {
                sessions.clear()
            }
            first?.let { throw it }
        }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Case graph runners must stay on their opening thread" }
    }
}

private fun schemaFingerprint(prepared: PreparedCasePackage): String = digest(
    prepared.sources.filter { it.role == SourceRole.SCHEMA || it.role == SourceRole.INCLUDED }
        .sortedBy { it.identity }.flatMap { listOf(it.identity, it.role.name, it.sha256) },
)

private fun graphRevision(prepared: PreparedCasePackage, sources: List<Pair<String, String>>): String = digest(
    listOf(
        prepared.key.value,
        prepared.caseId,
        prepared.schemaIdentity.id,
        prepared.schemaIdentity.version.orEmpty(),
        prepared.revision,
    ) +
        sources.distinct().sortedBy { it.first }.flatMap { listOf(it.first, it.second) },
)

private fun digest(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
