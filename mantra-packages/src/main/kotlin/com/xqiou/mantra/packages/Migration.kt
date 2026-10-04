package com.xqiou.mantra.packages

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticCategory
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.read.SourceText

sealed interface MigrationOperation {
    data class PinSchema(val schema: PackageSchemaBinding) : MigrationOperation
    data class BindParameters(val ids: List<String>) : MigrationOperation
    data class BindLayout(val id: String?) : MigrationOperation

    /** Explicit, source-preserving patch; offsets apply to the source after preceding operations. */
    data class ReplaceText(val offset: Int, val expected: String, val replacement: String) : MigrationOperation
}

data class MigrationTarget(
    val schema: PackageSchemaBinding,
    val packageIdentity: PackageIdentity,
    val packageRevision: String,
    val parameterSelectionRevision: String?,
)

/** Concrete immutable intent, distinct from its evaluated preview and explicit application. */
class MigrationPlan(
    val casePath: String,
    val baseRevision: String,
    val source: PackageSchemaBinding,
    val target: MigrationTarget,
    operations: List<MigrationOperation>,
) {
    val operations: List<MigrationOperation> = java.util.List.copyOf(
        operations.map {
            if (it is MigrationOperation.BindParameters) it.copy(ids = java.util.List.copyOf(it.ids)) else it
        },
    )
    init {
        logicalPath(casePath)
        require(baseRevision.isNotBlank())
        require(target.packageRevision.matches(Regex("[0-9a-f]{64}")))
        require(
            target.parameterSelectionRevision == null ||
                target.parameterSelectionRevision.matches(Regex("[0-9a-f]{64}")),
        )
    }
}

class MigrationState(
    val schema: PackageSchemaBinding,
    val graphRevision: String,
    val sourceSha256: String,
    val result: CalculationResult?,
    diagnostics: List<Diagnostic>,
) {
    val diagnostics: List<Diagnostic> = java.util.List.copyOf(
        diagnostics.map { it.copy(coord = java.util.List.copyOf(it.coord)) },
    )
}

class MigrationEvaluation(
    val target: MigrationTarget,
    val graphRevision: String,
    val result: CalculationResult?,
    diagnostics: List<Diagnostic>,
) {
    val diagnostics: List<Diagnostic> = java.util.List.copyOf(
        diagnostics.map { it.copy(coord = java.util.List.copyOf(it.coord)) },
    )
}

/** A host adapter uses the public CaseGraphRunner and its exact loader bindings. */
interface MigrationRuntime {
    fun current(casePath: String): MigrationState
    fun evaluate(casePath: String, candidate: SourceText, target: MigrationTarget): MigrationEvaluation

    /**
     * Check the live graph and source revisions while holding the host's mutation/epoch lock, then
     * invoke commit once. Releasing the lock before commit violates this port's contract. External
     * non-cooperating file writers are additionally guarded by the store's source-byte CAS.
     */
    fun commitIfCurrent(casePath: String, graphRevision: String, sourceSha256: String, commit: () -> Unit)
}

fun interface MigrationEditor {
    fun apply(source: SourceText, operations: List<MigrationOperation>): SourceText
}

/** Only an explicitly writable host store can commit; PackageSnapshot exposes no write API. */
interface MigrationStore {
    fun read(casePath: String): SourceText

    /** Locks source bytes, calls authorize with the sole write action, then atomically replaces. */
    fun commit(casePath: String, sourceSha256: String, candidate: SourceText, authorize: (write: () -> Unit) -> Unit)
}

class MigrationPreview internal constructor(
    val casePath: String,
    val source: PackageSchemaBinding,
    val target: MigrationTarget,
    val baseRevision: String,
    val original: SourceText,
    val candidate: SourceText,
    operations: List<MigrationOperation>,
    val before: MigrationState,
    val after: MigrationEvaluation,
    /** Human-reviewable exact source and result pairs; no consumer-side numeric approximation. */
    val reviewToken: String,
) {
    val operations: List<MigrationOperation> = java.util.List.copyOf(
        operations.map {
            if (it is MigrationOperation.BindParameters) it.copy(ids = java.util.List.copyOf(it.ids)) else it
        },
    )
    val originalSha256: String = digest(original.text.toByteArray(Charsets.UTF_8))
    val candidateSha256: String = digest(candidate.text.toByteArray(Charsets.UTF_8))
}

data class MigrationReceipt(
    val casePath: String,
    val oldSourceSha256: String,
    val newSourceSha256: String,
    val reviewToken: String,
    val evaluation: MigrationEvaluation,
)

/** Preview is read-only. Only apply(preview, the reviewed token) invokes the explicit writer. */
class MigrationCoordinator(
    private val store: MigrationStore,
    private val runtime: MigrationRuntime,
    private val editor: MigrationEditor = CaseMigrationEditor,
) {
    fun preview(plan: MigrationPlan): MigrationPreview = preview(
        plan.casePath,
        plan.baseRevision,
        plan.source,
        plan.target,
        plan.operations,
    )

    fun preview(
        casePath: String,
        baseRevision: String,
        source: PackageSchemaBinding,
        target: MigrationTarget,
        operations: List<MigrationOperation>,
    ): MigrationPreview {
        val plan = MigrationPlan(casePath, baseRevision, source, target, operations)
        val original = store.read(casePath)
        val before = runtime.current(casePath)
        val originalHash = digest(original.text.toByteArray(Charsets.UTF_8))
        if (before.graphRevision != baseRevision || before.sourceSha256 != originalHash) {
            fail(
                "MANTRA-MIGRATION-STALE",
                "Source or case graph changed before preview",
            )
        }
        if (before.schema != source) {
            fail(
                "MANTRA-MIGRATION-BINDING",
                "Original exact schema binding does not match the plan",
            )
        }
        val capturedOperations = plan.operations
        val candidate = editor.apply(original, java.util.List.copyOf(capturedOperations))
        val after = runtime.evaluate(casePath, candidate, target)
        validate(after, target)
        val token = seal(
            casePath,
            baseRevision,
            originalHash,
            digest(candidate.text.toByteArray(Charsets.UTF_8)),
            source,
            target,
            after.graphRevision,
        )
        return MigrationPreview(
            casePath, source, target, baseRevision, original, candidate, capturedOperations, before, after, token,
        )
    }

    fun apply(preview: MigrationPreview, reviewedToken: String): MigrationReceipt {
        if (reviewedToken != preview.reviewToken) {
            fail(
                "MANTRA-MIGRATION-REVIEW",
                "The reviewed preview token does not match",
            )
        }
        var committedEvaluation: MigrationEvaluation? = null
        store.commit(preview.casePath, preview.originalSha256, preview.candidate) { write ->
            runtime.commitIfCurrent(preview.casePath, preview.baseRevision, preview.originalSha256) {
                val evaluation = runtime.evaluate(preview.casePath, preview.candidate, preview.target)
                validate(evaluation, preview.target)
                if (evaluation.graphRevision != preview.after.graphRevision) {
                    fail(
                        "MANTRA-MIGRATION-STALE",
                        "Target resources or participating source cases changed after preview",
                    )
                }
                write()
                committedEvaluation = evaluation
            }
        }
        return MigrationReceipt(
            preview.casePath,
            preview.originalSha256,
            preview.candidateSha256,
            reviewedToken,
            committedEvaluation ?: fail("MANTRA-MIGRATION-COMMIT", "Host did not commit the reviewed migration"),
        )
    }

    private fun validate(evaluation: MigrationEvaluation, target: MigrationTarget) {
        if (evaluation.target != target) {
            fail(
                "MANTRA-MIGRATION-BINDING",
                "Target resources do not match the reviewed exact identity and revision",
            )
        }
        if (evaluation.result != null && evaluation.result.schema.identity != target.schema.identity) {
            fail(
                "MANTRA-MIGRATION-BINDING",
                "Calculation used a different exact target schema",
            )
        }
        if (evaluation.result?.succeeded != true || evaluation.diagnostics.any {
                it.severity == Severity.ERROR && it.category != DiagnosticCategory.BUSINESS
            }
        ) {
            throw PackageException(
                Diagnostic(
                    Severity.ERROR,
                    "MANTRA-MIGRATION-TECHNICAL",
                    "Target calculation failed technically; migration cannot commit",
                ),
                related = evaluation.diagnostics + evaluation.result?.diagnostics.orEmpty(),
            )
        }
    }

    private fun seal(
        path: String,
        base: String,
        old: String,
        next: String,
        source: PackageSchemaBinding,
        target: MigrationTarget,
        candidateRevision: String,
    ): String = fingerprint(
        listOf(
            path, base, old, next, source.identity.id, source.identity.version, source.mode.name,
            target.schema.identity.id, target.schema.identity.version, target.schema.mode.name,
            target.packageIdentity.id,
            target.packageIdentity.version.text, target.packageRevision,
            target.parameterSelectionRevision, candidateRevision,
        ),
    )
}
