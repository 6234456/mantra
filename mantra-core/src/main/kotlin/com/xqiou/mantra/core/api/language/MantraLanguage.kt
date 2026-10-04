package com.xqiou.mantra.core.api.language

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.engine.CheckVertex
import com.xqiou.mantra.core.engine.ChoiceVertex
import com.xqiou.mantra.core.engine.CompiledFormula
import com.xqiou.mantra.core.engine.ConditionVertex
import com.xqiou.mantra.core.engine.DimensionVertex
import com.xqiou.mantra.core.engine.FormulaCompiler
import com.xqiou.mantra.core.engine.InputValidationVertex
import com.xqiou.mantra.core.engine.LineVertex
import com.xqiou.mantra.core.engine.MantraKernel
import com.xqiou.mantra.core.engine.NamedSource
import com.xqiou.mantra.core.engine.Planner
import com.xqiou.mantra.core.engine.Qualified
import com.xqiou.mantra.core.engine.ReconcileVertex
import com.xqiou.mantra.core.engine.ValueVertex
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.normein.dsl.authoring.DslAuthoringService
import com.xqiou.normein.dsl.authoring.DslCompletionRequest
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.type.DslType
import java.util.Collections

/** Static editor bridge. Never creates an evaluator, calculation session or runtime trace. */
object MantraLanguage {
    fun analyze(
        schema: Schema,
        case: CaseData,
        parameters: List<ParameterSet> = emptyList(),
        documents: List<LanguageDocumentContext>,
        checkpoint: () -> Unit = {},
    ): LanguageAnalysis {
        checkpoint()
        val sink = DiagnosticSink()
        val planner = Planner(sink, requireMaterializedLinks = false)
        val plan = planner.plan(schema, case, parameters)
        checkpoint() // Locked compiler calls are synchronous, not interruptible by this callback.
        if (plan == null || sink.hasErrors) {
            return LanguageAnalysis(
                schema.identity,
                emptyList(),
                emptyList(),
                emptyList(),
                frozen(sink.all),
                emptyList(),
                listOf(LanguageIssue("Current static compilation is incomplete")),
                emptyMap(),
            )
        }
        val host = HostLanguageIndex(plan, documents)
        val named = (schema.functions + case.functions).associate { it.name to NamedSource(it.source, it.location) }
        val compiler = FormulaCompiler(
            sink,
            plan.dimensions,
            plan.vertices,
            plan.definitions,
            named,
            plan.types,
            planner::elementType,
        )
        val contexts = mutableListOf<LanguageFormulaContext>()
        val assistance = linkedMapOf<LanguageSpan, FormulaAssistance>()
        val formulas = mutableListOf<CompiledFormula>()
        fun add(owner: String, role: String, formula: CompiledFormula?) {
            if (formula == null) return
            formulas += formula
            val location = formula.formula.location
            val start = location.startOffset ?: return
            val end = location.endOffset ?: return
            val span = LanguageSpan(location.source, start, end)
            contexts += LanguageFormulaContext(
                owner,
                role,
                span,
                frozen(formula.dims),
                formula.expression.expectedType.toString(),
                formula.rowTable,
            )
            val previous = formula.previousBindings.flatMap { listOf(it.syntheticRoot, it.firstPeriodRoot) }.toSet()
            val scope = compiler.scopeFor(formula.dims, formula.rowTable)
            val service = DslAuthoringService(MantraKernel.environment, scope)
            val allowed = (plan.vertices[owner] as? LineVertex)?.item?.allowedRefs
            assistance[span] = ScopedAssistance(formula, span, service, previous, allowed?.toSet())
        }
        plan.vertices.values.forEach { vertex ->
            checkpoint()
            when (vertex) {
                is DimensionVertex -> vertex.memberConditions.forEach { (key, value) ->
                    add(vertex.id, "member.$key", value)
                }
                is ConditionVertex -> add(vertex.sectionId, "section.when", vertex.compiled)
                is LineVertex -> add(vertex.id, "value", vertex.compiled)
                is CheckVertex -> add(vertex.id, "check", vertex.compiled)
                is ReconcileVertex -> {
                    add(vertex.id, "left", vertex.left)
                    add(vertex.id, "right", vertex.right)
                }
                is ChoiceVertex -> vertex.options.forEach { option ->
                    add(vertex.id, "option.${option.option.key}", option.formula)
                    add(vertex.id, "option.${option.option.key}.when", option.condition)
                }
                is InputValidationVertex -> {
                    add(vertex.input.id, "required", vertex.required)
                    vertex.columns.forEach { (key, value) -> add(vertex.input.id, "column.$key.required", value) }
                }
                else -> Unit
            }
            if (vertex is ValueVertex) add(vertex.id, "when", vertex.ownCondition)
        }
        // A selected named function is not an inventory. Compile every authored definition as a
        // function value in actual available contexts, never invoke its body. Context-dependent
        // definitions with no successful scope explicitly make the graph incomplete.
        val possibleDimensions = (
            formulas.map { it.dims } + listOf(emptyList()) +
                plan.dimensions.keys.map { listOf(it) } + listOf(plan.dimensions.keys.toList())
            ).distinct()
        (schema.functions + case.functions).forEach { definition ->
            checkpoint()
            val document = host.documents[definition.location.source]?.second
            val token = document?.root?.allForms()?.filterIsInstance<DslForm.Sequence>()?.firstOrNull {
                it.span.startOffset == definition.location.startOffset &&
                    it.span.endOffset == definition.location.endOffset
            }?.values?.getOrNull(1)
            if (document == null || token == null) {
                host.issues += LanguageIssue("Named definition source is unavailable", definition.location.source)
            } else {
                val compiled = possibleDimensions.mapNotNull { dims ->
                    val isolatedSink = DiagnosticSink()
                    val isolated = FormulaCompiler(
                        isolatedSink,
                        plan.dimensions,
                        plan.vertices,
                        plan.definitions,
                        named,
                        plan.types,
                        planner::elementType,
                    )
                    isolated.compile(document.formula(token), dims, DslType.Any, "language.defn.${definition.name}")
                        ?.takeUnless { isolatedSink.hasErrors }
                }
                if (compiled.isEmpty()) {
                    host.issues += LanguageIssue(
                        "Named definition has no unambiguous valid static context",
                        definition.location.source,
                    )
                } else {
                    formulas += compiled
                    val selected = compiled.first()
                    val start = definition.location.startOffset
                    val end = definition.location.endOffset
                    if (start != null && end != null) {
                        val namedSpan = LanguageSpan(definition.location.source, start, end)
                        contexts += LanguageFormulaContext(
                            definition.name,
                            "named-definition",
                            namedSpan,
                            frozen(selected.dims),
                            "Function",
                        )
                        if (compiled.map { it.dims }.distinct().size == 1) {
                            assistance[namedSpan] = ScopedAssistance(
                                selected,
                                namedSpan,
                                DslAuthoringService(MantraKernel.environment, compiler.scopeFor(selected.dims)),
                                emptySet(),
                                null,
                                definition.source,
                            )
                        }
                    }
                }
            }
        }
        val index = StaticFormulaIndex(host)
        formulas.forEach {
            checkpoint()
            index.add(it)
        }
        host.hostReferences(contexts.map { it.span })
        val occurrences = host.occurrences.distinct()
        occurrences.groupBy { it.span }.values.filter { uses -> uses.map { it.symbol }.distinct().size > 1 }
            .forEach { uses ->
                host.issues += LanguageIssue(
                    "One token has multiple schema/lexical bindings",
                    uses.first().span.source,
                )
            }
        checkpoint()
        return LanguageAnalysis(
            schema.identity,
            frozen(host.definitions.distinct()),
            frozen(occurrences),
            frozen(contexts.distinct()),
            frozen(sink.all),
            frozen(host.types.distinct()),
            frozen(host.issues.distinct()),
            Collections.unmodifiableMap(
                assistance.mapValues { (_, value) ->
                    (value as ScopedAssistance).withCandidates(frozen(host.localCompletions))
                },
            ),
        )
    }

    /** Identifier/reserved-name check uses the same host rules as the planner. */
    fun validNodeName(name: String): Boolean = com.xqiou.mantra.core.read.isIdentifier(name) &&
        name !in MantraKernel.reservedNames && !name.startsWith("mantra_")
}

private class ScopedAssistance(
    private val formula: CompiledFormula,
    private val span: LanguageSpan,
    private val service: DslAuthoringService,
    private val generated: Set<String>,
    private val allowed: Set<String>?,
    private val authorText: String = formula.formula.source,
    private val locals: List<LocalCompletionScope> = emptyList(),
) : FormulaAssistance {
    fun withCandidates(candidates: List<LocalCompletionScope>): ScopedAssistance =
        ScopedAssistance(formula, span, service, generated, allowed, authorText, candidates)
    private fun display(name: String): String = if (name.startsWith("mantra_")) {
        "mantra/" + name.removePrefix("mantra_")
    } else {
        name
    }
    private fun permitted(name: String): Boolean {
        if (name.startsWith("mantra-internal/") || name in generated) return false
        if (allowed == null) return true
        val root = name.removePrefix("mantra_").removePrefix("mantra/").removePrefix("all.").substringBefore('.')
        return root in allowed || name.contains('/') && !name.startsWith("mantra")
    }
    override fun complete(offset: Int): List<LanguageCompletion> {
        val result = service.complete(
            DslCompletionRequest(
                Qualified.rewrite(authorText),
                (offset - span.start).coerceIn(0, span.end - span.start),
                limit = 100,
            ),
        )
        val local = locals.filter { scope ->
            scope.span.source == span.source &&
                offset in scope.span.start..scope.span.end
        }.flatMap { it.candidates }
            .filter { it.label.startsWith(result.query) }.map { item ->
                LanguageCompletion(
                    item.label,
                    LanguageSpan(
                        span.source,
                        span.start + result.replacementRange.startOffset,
                        span.start + result.replacementRange.endOffset,
                    ),
                    if (item.kind == "FUNCTION") "(${item.label} )" else item.label,
                    item.kind,
                    item.detail,
                )
            }
        val catalog = result.items.filter { item ->
            item.kind.name == "FUNCTION" && !item.label.startsWith("mantra-internal/") || permitted(item.label)
        }.map { item ->
            LanguageCompletion(
                display(item.label),
                LanguageSpan(
                    span.source,
                    span.start + result.replacementRange.startOffset,
                    span.start + result.replacementRange.endOffset,
                ),
                display(item.insertText),
                item.kind.name,
                item.detail,
                item.documentation,
            )
        }
        return (local + catalog).distinctBy { it.kind to it.insertText }.take(100)
    }
    override fun hover(offset: Int): LanguageHover? = service.hover(
        Qualified.rewrite(authorText),
        offset - span.start,
    )?.takeIf { permitted(it.symbol) }?.let {
        LanguageHover(
            LanguageSpan(span.source, span.start + it.range.startOffset, span.start + it.range.endOffset),
            listOf(display(it.symbol), it.detail, it.documentation).filterNotNull().joinToString("\n\n"),
        )
    }
}

internal fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
