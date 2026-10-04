package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.model.Value
import java.nio.file.Path

/** Adapt the future public graph identity directly; do not derive identity from a diagnostic message. */
data class AcceptanceRunIdentity(val casePath: Path, val schema: AcceptanceSchemaId, val revision: String) {
    init {
        require(casePath.isAbsolute)
        require(revision.isNotBlank())
    }
}

data class AcceptanceSourceRun(
    val identity: AcceptanceRunIdentity,
    val result: CalculationResult,
    val localDiagnostics: List<Diagnostic>,
)
data class AcceptanceInheritedDiagnostic(val source: AcceptanceRunIdentity, val diagnostic: Diagnostic)
data class AcceptanceScalarAddress(val node: String, val coord: List<String> = emptyList())
data class AcceptanceLinkedInput(
    val source: AcceptanceRunIdentity,
    val from: AcceptanceScalarAddress,
    val to: AcceptanceScalarAddress,
    val value: Value,
)

enum class AcceptanceEvidence { VALUE_ONLY, FULL }
data class AcceptanceCalculationRequest(val binding: AcceptanceCaseBinding, val evidence: AcceptanceEvidence)

/**
 * This is only an injection port for shared tests, not another graph evaluator or source loader.
 * The root adapter must preserve statuses, local diagnostic ownership, typed source identities,
 * revisions and real public results from the generic SDK/host graph runner.
 */
data class AcceptanceExecution(
    val identity: AcceptanceRunIdentity,
    val result: CalculationResult,
    val succeeded: Boolean,
    val validationPassed: Boolean,
    val localDiagnostics: List<Diagnostic>,
    val sources: List<AcceptanceSourceRun> = emptyList(),
    val inheritedDiagnostics: List<AcceptanceInheritedDiagnostic> = emptyList(),
    val links: List<AcceptanceLinkedInput> = emptyList(),
)

fun interface AcceptanceCalculationRunner {
    fun calculate(request: AcceptanceCalculationRequest): AcceptanceExecution
}

/** The host owns canonical workspace-confined relative-path resolution. */
fun interface AcceptanceSourcePathResolver {
    fun resolve(authoringCase: Path, relativePath: String): Path
}

/** Implement with public WorkspaceCatalog documents; linked graph/provenance stays generic. */
interface AcceptanceWorkbenchProbe {
    fun document(casePath: Path, document: String, layoutId: String?): Map<String, Any?>

    /** Deserialize these from actual public Workbench wire documents, not the calculation callback. */
    fun inheritedDiagnostics(casePath: Path): List<AcceptanceInheritedDiagnostic>
    fun links(casePath: Path): List<AcceptanceLinkedInput>
}
