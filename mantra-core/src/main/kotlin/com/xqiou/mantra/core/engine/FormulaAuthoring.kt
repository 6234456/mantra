package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.normein.dsl.authoring.DslAuthoringService
import com.xqiou.normein.dsl.authoring.DslCompletionItem
import com.xqiou.normein.dsl.authoring.DslCompletionItemKind
import com.xqiou.normein.dsl.authoring.DslCompletionRequest
import com.xqiou.normein.dsl.authoring.DslCompletionResult
import com.xqiou.normein.dsl.authoring.DslHover
import com.xqiou.normein.dsl.compiler.DslCompileRequest
import com.xqiou.normein.dsl.compiler.DslCompileResult
import com.xqiou.normein.dsl.compiler.DslSemanticCompiler
import com.xqiou.normein.dsl.diagnostic.DslDiagnostic
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes

/** Formula tooling built from the same plan and scope as the calculation compiler. */
class FormulaAuthoring private constructor(
    private val service: DslAuthoringService,
    private val compiler: DslSemanticCompiler,
    private val plan: CalculationPlan,
    private val scope: com.xqiou.normein.dsl.environment.DslAnalysisScope,
    private val expectedType: DslType,
    private val allowedRefs: Set<String>?,
) {
    fun complete(source: String, cursorOffset: Int): DslCompletionResult {
        val request = DslCompletionRequest(source, cursorOffset, limit = 100)
        val result = service.complete(request)
        if (allowedRefs == null) return result
        // Normein caps a completion response at 100 items. Query references separately
        // so a large function catalog cannot crowd all permitted roots out of an empty query.
        val references = service.complete(request.copy(kinds = setOf(DslCompletionItemKind.ROOT, DslCompletionItemKind.FIELD)))
            .items.filter(::allowed)
        return result.copy(items = (references + result.items.filter(::allowed))
            .distinctBy { it.kind to it.insertText }.take(100))
    }

    fun hover(source: String, cursorOffset: Int): DslHover? = service.hover(source, cursorOffset)
        ?.takeIf { allowed(it.symbol, it.kind == com.xqiou.normein.dsl.authoring.DslHoverKind.FIELD) }

    fun check(source: String): List<DslDiagnostic> = when (val result = compiler.compile(
        DslCompileRequest(source, namedDefinitions = plan.definitions, expectedType = expectedType),
        MantraKernel.environment, scope,
    )) {
        is DslCompileResult.Failure -> result.diagnostics
        is DslCompileResult.Success -> emptyList()
    }

    private fun allowed(item: DslCompletionItem): Boolean =
        when (item.kind) {
            DslCompletionItemKind.ROOT, DslCompletionItemKind.FIELD -> allowed(item.label, item.kind == DslCompletionItemKind.FIELD)
            else -> true
        }

    private fun allowed(symbol: String, field: Boolean): Boolean {
        val refs = allowedRefs ?: return true
        val name = symbol.removePrefix("mantra/")
        if (name == "all") return false
        if (name.startsWith("all.")) return name.removePrefix("all.").substringBefore('.') in refs
        return name.substringBefore('.') in refs || (field && name in refs)
    }

    companion object {
        fun forFormulaSlot(schema: Schema, case: CaseData, parameters: List<ParameterSet>, id: String): FormulaAuthoring {
            val sink = DiagnosticSink()
            val planner = Planner(sink)
            val plan = planner.plan(schema, case, parameters)
            sink.throwIfErrors()
            val line = (checkNotNull(plan).vertices[id] as? LineVertex)?.takeIf { it.item.formulaSlot }
                ?: throw IllegalArgumentException("Unknown formula slot $id")
            val dims = if (line.item.spread) line.dims.dropLast(1) else line.dims
            val expected = if (line.item.spread) DslTypes.map(DslType.Keyword, Types.expected(line.item.type))
                else Types.expected(line.item.type)
            return create(planner, plan, dims, expected, line.item.allowedRefs)
        }

        fun forExtension(schema: Schema, case: CaseData, parameters: List<ParameterSet>, slot: String): FormulaAuthoring {
            val sink = DiagnosticSink()
            val planner = Planner(sink)
            val plan = planner.plan(schema, case, parameters)
            sink.throwIfErrors()
            val dims = slotDimensions(checkNotNull(plan).tree, slot)
                ?: throw IllegalArgumentException("Unknown extension slot $slot")
            return create(planner, plan, dims, DslType.Decimal, null)
        }

        private fun slotDimensions(section: ResolvedSection, id: String): List<String>? {
            if (section.item.slot && section.item.id == id) return section.dims
            return section.children.filterIsInstance<ResolvedSection>().firstNotNullOfOrNull { slotDimensions(it, id) }
        }

        private fun create(planner: Planner, plan: CalculationPlan, dims: List<String>, expected: DslType,
                           refs: Set<String>?): FormulaAuthoring {
            val scope = planner.authoringScope(dims)
            return FormulaAuthoring(DslAuthoringService(MantraKernel.environment, scope), DslSemanticCompiler(),
                plan, scope, expected, refs)
        }
    }
}
