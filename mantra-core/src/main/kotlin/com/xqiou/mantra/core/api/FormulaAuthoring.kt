package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.LineVertex
import com.xqiou.mantra.core.engine.MantraKernel
import com.xqiou.mantra.core.engine.Planner
import com.xqiou.mantra.core.engine.Qualified
import com.xqiou.mantra.core.engine.ResolvedSection
import com.xqiou.mantra.core.engine.Types
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
    private val scopedCompletion: DslAuthoringService,
    private val compiler: DslSemanticCompiler,
    private val plan: CalculationPlan,
    private val scope: com.xqiou.normein.dsl.environment.DslAnalysisScope,
    private val expectedType: DslType,
    private val allowedRefs: Set<String>?,
) {
    /** Offers functions and references valid at [cursorOffset] in this formula's scope. */
    fun complete(source: String, cursorOffset: Int): DslCompletionResult {
        val rewritten = Qualified.rewrite(source)
        val request = DslCompletionRequest(rewritten, cursorOffset, limit = 100)
        val result = scopedCompletion.complete(request)
        val items = if (allowedRefs == null) {
            result.items
        } else {
            // Filter the catalog before Normein's 100-item cap; :uses may name a root
            // that comes after hundreds of unrelated roots in catalog order.
            val references = scopedCompletion.complete(
                request.copy(kinds = setOf(DslCompletionItemKind.ROOT, DslCompletionItemKind.FIELD)),
            ).items
            val allFields = allowedRefs.flatMap { ref ->
                service.complete(
                    DslCompletionRequest("all.$ref", kinds = setOf(DslCompletionItemKind.FIELD), limit = 100),
                )
                    .items.filter(::allowed)
            }.filter { result.query.isEmpty() || it.label.contains(result.query, ignoreCase = true) }
            (references + allFields + result.items).filter(::allowed).distinctBy { it.kind to it.insertText }.take(100)
        }
        return result.copy(
            query = source.substring(result.replacementRange.startOffset, cursorOffset.coerceIn(0, source.length)),
            items = items.map(::display),
        )
    }

    /** Returns documentation for the symbol at [cursorOffset], respecting declared reference limits. */
    fun hover(source: String, cursorOffset: Int): DslHover? = service.hover(Qualified.rewrite(source), cursorOffset)
        ?.takeIf { allowed(it.symbol, it.kind == com.xqiou.normein.dsl.authoring.DslHoverKind.FIELD) }
        ?.let { it.copy(symbol = displayName(it.symbol)) }

    /** Compiles [source] against the declared result type and returns compiler diagnostics. */
    fun check(source: String): List<DslDiagnostic> = when (
        val result = compiler.compile(
            DslCompileRequest(
                Qualified.rewrite(source),
                namedDefinitions = plan.definitions,
                expectedType = expectedType,
            ),
            MantraKernel.environment,
            scope,
        )
    ) {
        is DslCompileResult.Failure -> result.diagnostics
        is DslCompileResult.Success -> emptyList()
    }

    private fun allowed(item: DslCompletionItem): Boolean = when (item.kind) {
        DslCompletionItemKind.ROOT, DslCompletionItemKind.FIELD -> allowed(
            item.label,
            item.kind == DslCompletionItemKind.FIELD,
        )
        else -> true
    }

    private fun allowed(symbol: String, field: Boolean): Boolean {
        val refs = allowedRefs ?: return true
        val name = symbol.removePrefix("mantra_").removePrefix("mantra/")
        if (name == "all") return false
        if (name.startsWith("all.")) return name.removePrefix("all.").substringBefore('.') in refs
        return name.substringBefore('.') in refs || (field && name in refs)
    }

    private fun display(item: DslCompletionItem): DslCompletionItem = if (item.kind in
        setOf(DslCompletionItemKind.ROOT, DslCompletionItemKind.FIELD)
    ) {
        item.copy(label = displayName(item.label), insertText = displayName(item.insertText))
    } else {
        item
    }

    private fun displayName(name: String): String = if (name.startsWith("mantra_")) {
        "mantra/" +
            name.removePrefix("mantra_")
    } else {
        name
    }

    companion object {
        /** Builds tooling for an application-declared formula slot and its allowed references. */
        fun forFormulaSlot(
            schema: Schema,
            case: CaseData,
            parameters: List<ParameterSet>,
            id: String,
        ): FormulaAuthoring {
            val sink = DiagnosticSink()
            val planner = Planner(sink)
            val plan = planner.plan(schema, case, parameters)
            sink.throwIfErrors()
            val line = (checkNotNull(plan).vertices[id] as? LineVertex)?.takeIf { it.item.formulaSlot }
                ?: throw IllegalArgumentException("Unknown formula slot $id")
            val dims = if (line.item.spread) line.dims.dropLast(1) else line.dims
            val expected = if (line.item.spread) {
                DslTypes.map(DslType.Keyword, Types.expected(line.item.type))
            } else {
                Types.expected(line.item.type)
            }
            return create(planner, plan, dims, expected, line.item.allowedRefs)
        }

        /** Builds tooling for a new extension line or the existing line identified by [id]. */
        fun forExtension(
            schema: Schema,
            case: CaseData,
            parameters: List<ParameterSet>,
            slot: String,
            id: String? = null,
        ): FormulaAuthoring {
            val sink = DiagnosticSink()
            val planner = Planner(sink)
            val plan = planner.plan(schema, case, parameters)
            sink.throwIfErrors()
            val dims = slotDimensions(checkNotNull(plan).tree, slot)
                ?: throw IllegalArgumentException("Unknown extension slot $slot")
            if (id != null &&
                case.extensions[slot].orEmpty().any { it is com.xqiou.mantra.core.model.LineItem && it.id == id }
            ) {
                val line =
                    plan.vertices[id] as? LineVertex ?: throw IllegalArgumentException("Unknown extension line $id")
                val context = if (line.item.spread) line.dims.dropLast(1) else line.dims
                val expected = if (line.item.spread) {
                    DslTypes.map(DslType.Keyword, Types.expected(line.item.type))
                } else {
                    Types.expected(line.item.type)
                }
                return create(planner, plan, context, expected, null)
            }
            return create(planner, plan, dims, DslType.Decimal, null)
        }

        private fun slotDimensions(section: ResolvedSection, id: String): List<String>? {
            if (section.item.slot && section.item.id == id) return section.dims
            return section.children.filterIsInstance<ResolvedSection>().firstNotNullOfOrNull { slotDimensions(it, id) }
        }

        private fun create(
            planner: Planner,
            plan: CalculationPlan,
            dims: List<String>,
            expected: DslType,
            refs: Set<String>?,
        ): FormulaAuthoring {
            val scope = planner.authoringScope(dims)
            val service = DslAuthoringService(MantraKernel.environment, scope)
            val scopedCompletion = if (refs == null) {
                service
            } else {
                DslAuthoringService(
                    MantraKernel.environment.catalog(scope).let { catalog ->
                        catalog.copy(
                            roots = catalog.roots.filter { root ->
                                root.name.removePrefix("mantra_").substringBefore('.') in refs
                            },
                        )
                    },
                )
            }
            return FormulaAuthoring(
                service,
                scopedCompletion,
                DslSemanticCompiler(),
                plan,
                scope,
                expected,
                refs,
            )
        }
    }
}
