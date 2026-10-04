package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.engine.CompiledTemplate
import com.xqiou.mantra.core.engine.MantraKernel
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.view.Coord

/**
 * Immutable checked template, safe to share across threads. Each worker owns separate kernel
 * sessions and fresh per-case domains, dependencies, diagnostics and provenance. Input values use
 * [CaseData] and Value directly; this API does not load links or generate DSL source.
 */
class CompiledCalculation internal constructor(
    internal val template: CompiledTemplate,
    val compilationUsage: RunUsage,
) {
    val schema: SchemaIdentity get() = template.plan.schema.identity
    val compilation: CompilationStatistics get() = template.compilation

    /** Authored extension/function/formula shape and parameter type examples; input facts are empty. */
    val bindings: CaseData get() = template.plan.case
    val parameterTypes: Map<String, ValueType> get() = template.parameterTypes
    val kernel: CompiledKernelIdentity = MantraKernel.environment.let { environment ->
        val artifact = MantraKernel.kernelArtifact
        CompiledKernelIdentity(
            environment.contractId,
            environment.descriptorVersion,
            environment.fingerprint,
            artifact.coordinatesOrApplicationCodeId,
            artifact.buildRevision,
            artifact.executionContentSha256,
        )
    }

    /** Open and close each worker on the thread that will execute its cases. */
    fun openSession(options: CalculationOptions = CalculationOptions()): CompiledCalculationSession =
        CompiledCalculationSession(template, options)

    fun calculate(
        case: CaseData,
        parameters: List<ParameterSet> = template.parameters,
        options: CalculationOptions = CalculationOptions(),
    ): CalculationResult = openSession(options).use { it.calculate(case, parameters, options) }

    /** FULL audit evidence is captured from the current case; the template retains no traces. */
    fun calculateForAudit(
        case: CaseData,
        parameters: List<ParameterSet> = template.parameters,
        options: AuditOptions = AuditOptions(),
        calculationOptions: CalculationOptions = CalculationOptions(),
    ): CalculationResult = openSession(calculationOptions).use {
        it.calculateEvidence(case, parameters, calculationOptions, options, null)
    }

    fun calculateForExplain(
        case: CaseData,
        parameters: List<ParameterSet> = template.parameters,
        node: String,
        coord: Coord = emptyList(),
        options: CalculationOptions = CalculationOptions(),
    ): CalculationResult = openSession(options).use {
        it.calculateEvidence(case, parameters, options, null, InputAddress(node, coord))
    }

    /** Sequential streaming consumption; no list of inputs or results is retained by the runner. */
    fun forEach(
        cases: Iterable<CaseData>,
        parameters: List<ParameterSet> = template.parameters,
        options: BatchOptions = BatchOptions(),
        consume: (BatchItem) -> Unit,
    ): BatchSummary = BatchRunner.run(this, cases, parameters, options, consume)
}
