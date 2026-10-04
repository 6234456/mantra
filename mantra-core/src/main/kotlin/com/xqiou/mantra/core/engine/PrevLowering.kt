package com.xqiou.mantra.core.engine

import com.xqiou.normein.dsl.SourceSpan
import com.xqiou.normein.dsl.ast.DslAndNode
import com.xqiou.normein.dsl.ast.DslCallNode
import com.xqiou.normein.dsl.ast.DslCollectionLiteralNode
import com.xqiou.normein.dsl.ast.DslDraftCompileRequest
import com.xqiou.normein.dsl.ast.DslDraftResult
import com.xqiou.normein.dsl.ast.DslFieldAccessNode
import com.xqiou.normein.dsl.ast.DslIfNode
import com.xqiou.normein.dsl.ast.DslLambdaNode
import com.xqiou.normein.dsl.ast.DslLetNode
import com.xqiou.normein.dsl.ast.DslLiteralNode
import com.xqiou.normein.dsl.ast.DslMapLiteralNode
import com.xqiou.normein.dsl.ast.DslNode
import com.xqiou.normein.dsl.ast.DslNodeBuilder
import com.xqiou.normein.dsl.ast.DslNodeOrigin
import com.xqiou.normein.dsl.ast.DslNodeRewrite
import com.xqiou.normein.dsl.ast.DslNodeRewriteDecision
import com.xqiou.normein.dsl.ast.DslOrNode
import com.xqiou.normein.dsl.ast.DslScopeAwareTransformer
import com.xqiou.normein.dsl.ast.DslSymbolBinding
import com.xqiou.normein.dsl.ast.DslSymbolNode
import com.xqiou.normein.dsl.catalog.DslFunctionDocumentation
import com.xqiou.normein.dsl.catalog.DslLanguageSurfaceClassification
import com.xqiou.normein.dsl.compiler.DslCompileRequest
import com.xqiou.normein.dsl.compiler.DslCompileResult
import com.xqiou.normein.dsl.compiler.DslCompiledExpression
import com.xqiou.normein.dsl.compiler.DslCompiler
import com.xqiou.normein.dsl.compiler.DslSemanticCompiler
import com.xqiou.normein.dsl.compiler.DslSourceIndexEntry
import com.xqiou.normein.dsl.compiler.DslSourceIndexOrigin
import com.xqiou.normein.dsl.diagnostic.DslDiagnostic
import com.xqiou.normein.dsl.environment.DslAnalysisScope
import com.xqiou.normein.dsl.environment.DslEnvironment
import com.xqiou.normein.dsl.environment.DslRootDeclaration
import com.xqiou.normein.dsl.identity.DslCanonicalNodeId
import com.xqiou.normein.dsl.language.DslNameCategory
import com.xqiou.normein.dsl.language.DslNameResult
import com.xqiou.normein.dsl.language.DslNames
import com.xqiou.normein.dsl.language.DslSourceOrigin
import com.xqiou.normein.dsl.library.DslEvaluationStrategy
import com.xqiou.normein.dsl.library.DslFunctionResult
import com.xqiou.normein.dsl.library.DslFunctionSignature
import com.xqiou.normein.dsl.library.DslFunctionSpec
import com.xqiou.normein.dsl.library.DslParameterType
import com.xqiou.normein.dsl.library.DslTypeParameter
import com.xqiou.normein.dsl.library.dslFunctionHandler
import com.xqiou.normein.dsl.runtime.DslInputCandidate
import com.xqiou.normein.dsl.runtime.DslInputRootCandidate
import com.xqiou.normein.dsl.type.DslFieldPresence
import com.xqiou.normein.dsl.type.DslFunctionTypeSignature
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValues
import java.util.Collections

/**
 * Host-owned continuous-period compilation through public Normein 0.3.0 APIs.
 * The scheduler and business model are deliberately supplied by the host.
 */
internal interface PreviousBinding {
    val id: Int
    val syntheticRoot: String
    val firstPeriodRoot: String
    val targetNodeId: String
    val periodDimension: String
    val valueType: DslType
    val callSpan: SourceSpan
    val targetSpan: SourceSpan
    val fallbackAst: DslNode

    /** Enclosing prev fallbacks; all their period dimensions must be at the first member. */
    val firstFallbackGuards: Set<Int>

    /** Ownership resolved against the final compiled paths, not the original syntax paths. */
    val sourceOrigin: DslSourceIndexOrigin
}

internal data class PreviousTarget(val nodeId: String, val periodDimension: String, val valueType: DslType)

internal fun interface PreviousTargetResolver {
    /** Reject unknown/non-node roots and absent or ambiguous continuous period axes. */
    fun resolve(target: DslSymbolNode): PreviousTarget
}

internal data class PrevProblem(val message: String, val span: SourceSpan?)

internal sealed interface PrevCompileResult {
    data class Success(
        val expression: DslCompiledExpression,
        val bindings: List<PreviousBinding>,
        val authorSourceIndex: Map<DslCanonicalNodeId, DslSourceIndexEntry>,
    ) : PrevCompileResult

    data class Failure(
        val kernelDiagnostics: List<DslDiagnostic> = emptyList(),
        val hostProblems: List<PrevProblem> = emptyList(),
    ) : PrevCompileResult
}

private data class BindingDraft(
    val id: Int,
    val target: PreviousTarget,
    val callSpan: SourceSpan,
    val targetSpan: SourceSpan,
    val fallbackAst: DslNode,
) {
    val ownerId: String get() = "mantra.prev.$id"
    val syntheticRoot: String get() = "${PrevLowering.ROOT_PREFIX}${id}_value"
    val firstPeriodRoot: String get() = "${PrevLowering.ROOT_PREFIX}${id}_first"
}

private data class BindingSnapshot(
    override val id: Int,
    override val syntheticRoot: String,
    override val firstPeriodRoot: String,
    override val targetNodeId: String,
    override val periodDimension: String,
    override val valueType: DslType,
    override val callSpan: SourceSpan,
    override val targetSpan: SourceSpan,
    override val fallbackAst: DslNode,
    override val firstFallbackGuards: Set<Int>,
    override val sourceOrigin: DslSourceIndexOrigin,
) : PreviousBinding

/**
 * Global `(prev target initial-expression)` becomes a pure registered helper with typed roots
 * and a zero-argument closure. Local and named-function bindings called `prev` remain ordinary
 * callable values. Every node is constructed through DslNodeBuilder or the checked transformer.
 */
internal class PrevLowering(
    private val syntaxCompiler: DslCompiler = DslCompiler(),
    private val semanticCompiler: DslSemanticCompiler = DslSemanticCompiler(),
) {
    fun compile(
        request: DslCompileRequest,
        environment: DslEnvironment,
        resolveTarget: PreviousTargetResolver,
        /** Rebuild/cache the existing scope with OPTIONAL declarations and unchanged host types. */
        scopeWithRoots: (List<DslRootDeclaration>) -> DslAnalysisScope,
    ): PrevCompileResult {
        val syntax = when (val result = syntaxCompiler.compile(request)) {
            is DslCompileResult.Failure -> return PrevCompileResult.Failure(result.diagnostics)
            is DslCompileResult.Success -> result.expression
        }
        val problems = mutableListOf<PrevProblem>()
        visit(syntax.normalizedAst) { node ->
            val symbol = node as? DslSymbolNode ?: return@visit
            if (symbol.binding !is DslSymbolBinding.Local &&
                (symbol.name.value.startsWith(ROOT_PREFIX) || symbol.name.value == SELECT_HELPER)
            ) {
                problems += PrevProblem("This name belongs to the host period implementation", symbol.span)
            }
        }
        if (problems.isNotEmpty()) return PrevCompileResult.Failure(hostProblems = frozen(problems))

        val drafts = mutableListOf<BindingDraft>()
        val transformed = DslScopeAwareTransformer().transform(
            syntax,
            DslNodeRewrite { _, node ->
                val call = node as? DslCallNode ?: return@DslNodeRewrite DslNodeRewriteDecision.Keep
                val callable = call.callable as? DslSymbolNode
                    ?: return@DslNodeRewrite DslNodeRewriteDecision.Keep
                if (callable.name.value != "prev" || callable.binding != DslSymbolBinding.Unresolved) {
                    return@DslNodeRewrite DslNodeRewriteDecision.Keep
                }
                if (call.arguments.size != 2) {
                    problems += PrevProblem("prev requires a node reference and a first-period fallback", call.span)
                    return@DslNodeRewrite DslNodeRewriteDecision.Keep
                }
                val target = call.arguments[0] as? DslSymbolNode
                if (target == null || target.binding is DslSymbolBinding.Local) {
                    problems += PrevProblem("The first argument of prev must be a schema node reference", call.span)
                    return@DslNodeRewrite DslNodeRewriteDecision.Keep
                }
                val resolved = try {
                    resolveTarget.resolve(target)
                } catch (problem: IllegalArgumentException) {
                    problems += PrevProblem(problem.message ?: "Invalid previous-period target", target.span)
                    return@DslNodeRewrite DslNodeRewriteDecision.Keep
                }
                val binding = BindingDraft(drafts.size, resolved, call.span, target.span, call.arguments[1])
                drafts += binding
                val nodes = DslNodeBuilder(binding.ownerId)
                val fallback = nodes.lambda(emptyList()) { binding.fallbackAst }
                DslNodeRewriteDecision.Replace(
                    nodes.call(
                        SELECT_HELPER,
                        listOf(
                            nodes.root(binding.syntheticRoot, target.span),
                            nodes.root(binding.firstPeriodRoot),
                            fallback,
                        ),
                        call.span,
                    ),
                )
            },
        )
        if (problems.isNotEmpty()) return PrevCompileResult.Failure(hostProblems = frozen(problems))
        val draft = when (transformed) {
            is DslDraftResult.Failure -> return PrevCompileResult.Failure(transformed.diagnostics)
            is DslDraftResult.Success -> transformed.draft
        }

        // No lowering: retain the ordinary path including named-definition type metadata.
        if (drafts.isEmpty()) {
            return when (val result = semanticCompiler.compile(request, environment, scopeWithRoots(emptyList()))) {
                is DslCompileResult.Failure -> PrevCompileResult.Failure(result.diagnostics)
                is DslCompileResult.Success -> PrevCompileResult.Success(
                    result.expression,
                    emptyList(),
                    result.expression.sourceIndex,
                )
            }
        }

        // compileDraft has no namedDefinitions parameter. Mantra currently supplies Any for each.
        // Fail explicitly if a future caller requires richer metadata this prototype cannot retain.
        if (syntax.namedDefinitions.any { it.expectedType != DslType.Any }) {
            return PrevCompileResult.Failure(
                hostProblems = listOf(PrevProblem("Typed named-definition contracts need a host type guard", null)),
            )
        }
        val declarations = drafts.flatMap { binding ->
            listOf(
                DslRootDeclaration(
                    binding.syntheticRoot,
                    DslTypes.nullable(binding.target.valueType),
                    DslFieldPresence.OPTIONAL,
                ),
                DslRootDeclaration(binding.firstPeriodRoot, DslType.Boolean, DslFieldPresence.OPTIONAL),
            )
        }
        val compiled = when (
            val result = semanticCompiler.compileDraft(
                DslDraftCompileRequest(
                    draft = draft,
                    sourceFormatVersion = request.sourceFormatVersion,
                    sourceOrigin = DslSourceOrigin.GENERATED,
                    expressionSlotId = request.expressionSlotId,
                    expectedType = request.expectedType,
                    logicalLocation = request.logicalLocation,
                ),
                environment,
                scopeWithRoots(frozen(declarations)),
            )
        ) {
            is DslCompileResult.Failure -> return PrevCompileResult.Failure(result.diagnostics)
            is DslCompileResult.Success -> result.expression
        }
        val sourceIndex = authorOverlay(compiled, syntax)
        val fallbackGuards = syntacticFallbackGuards(draft.root, drafts)
        val bindings = drafts.map { binding ->
            val source = sourceIndex.values.firstOrNull {
                (it.nodeOrigin as? DslNodeOrigin.Programmatic)?.ownerId == binding.ownerId &&
                    it.span == binding.callSpan
            }?.origin ?: error("Lowered prev call lost its source index")
            BindingSnapshot(
                binding.id,
                binding.syntheticRoot,
                binding.firstPeriodRoot,
                binding.target.nodeId,
                binding.target.periodDimension,
                binding.target.valueType,
                binding.callSpan,
                binding.targetSpan,
                binding.fallbackAst,
                Collections.unmodifiableSet(fallbackGuards.getValue(binding.id).toSet()),
                source,
            )
        }
        return PrevCompileResult.Success(compiled, frozen(bindings), sourceIndex)
    }

    /** Copies the final path index. It does not mutate the kernel artifact or invent trace values. */
    private fun authorOverlay(
        compiled: DslCompiledExpression,
        original: DslCompiledExpression,
    ): Map<DslCanonicalNodeId, DslSourceIndexEntry> {
        check(original.namedDefinitions.isEmpty() || original.normalizedAst is DslLetNode)
        val namedPrefixes = original.namedDefinitions.mapIndexed { index, definition ->
            "0.$index" to DslSourceIndexOrigin.NamedDefinition(
                definition.name,
                definition.logicalLocation ?: original.logicalLocation,
            )
        }
        val restored = compiled.sourceIndex.mapValues { (path, entry) ->
            val namedOrigin = namedPrefixes.firstOrNull { (prefix, _) ->
                path.value == prefix || path.value.startsWith("$prefix.")
            }?.second
            entry.copy(origin = namedOrigin ?: DslSourceIndexOrigin.Expression(original.logicalLocation))
        }
        return Collections.unmodifiableMap(LinkedHashMap(restored))
    }

    /** Direct nesting only; Planner must additionally propagate guards through named call edges. */
    private fun syntacticFallbackGuards(root: DslNode, bindings: List<BindingDraft>): Map<Int, List<Int>> {
        val byOwner = bindings.associateBy(BindingDraft::ownerId)
        val guards = linkedMapOf<Int, List<Int>>()
        fun walk(node: DslNode, enclosing: List<Int>) {
            val binding = (node.origin as? DslNodeOrigin.Programmatic)?.ownerId?.let(byOwner::get)
                ?.takeIf { node is DslCallNode }
            if (binding != null && node is DslCallNode) {
                guards[binding.id] = enclosing
                walk((node.arguments[2] as DslLambdaNode).body, enclosing + binding.id)
            } else {
                children(node).forEach { walk(it, enclosing) }
            }
        }
        walk(root, emptyList())
        return guards
    }

    companion object {
        const val SELECT_HELPER = "mantra-internal/previous-select"
        const val ROOT_PREFIX = "__mantra_prev_"

        /**
         * Register in the host descriptor AND language surface before semantic compilation.
         * The three actual arguments are eager: previous value, Boolean and closure creation.
         * Only closure invocation is deferred, through the budgeted public runtime API.
         */
        fun selectHelperSpec(): DslFunctionSpec {
            val name = (DslNames.normalize("T", DslNameCategory.TYPE_COMPONENT) as DslNameResult.Valid).name
            val type = DslTypes.nullable(DslTypes.variable(name))
            val thunk = DslTypes.function(listOf(DslFunctionTypeSignature(parameters = emptyList(), returnType = type)))
            return DslFunctionSpec(
                name = SELECT_HELPER,
                semanticsVersion = "1",
                signatures = listOf(
                    DslFunctionSignature(
                        typeParameters = listOf(DslTypeParameter("T")),
                        parameters = listOf(
                            DslParameterType("previous", type),
                            DslParameterType("first-period", DslType.Boolean),
                            DslParameterType("initial", thunk),
                        ),
                        returnType = type,
                    ),
                ),
                evaluationStrategy = DslEvaluationStrategy.SHORT_CIRCUIT,
                providerId = MantraLibrary.PROVIDER_ID,
                handler = dslFunctionHandler { arguments, _, runtime ->
                    val first = (arguments[1] as DslValue.BooleanValue).value
                    DslFunctionResult.Value(
                        if (first) runtime.invokeCallable(arguments[2], emptyList()) else arguments[0],
                    )
                },
                documentation = DslFunctionDocumentation(
                    category = "mantra-internal",
                    summary = "Select a prepared prior-period value or invoke the initial-period closure",
                    classification = DslLanguageSurfaceClassification.DOMAIN_LIBRARY,
                ),
            )
        }

        /**
         * These input facts belong in the kernel input manifest. Previous nil is a supplied value,
         * never a trigger for fallback. The scheduler resolves only bindings active for this task.
         */
        fun runtimeRoots(
            bindings: List<PreviousBinding>,
            isFirstPeriod: (String) -> Boolean,
            previousValue: (PreviousBinding) -> DslValue,
        ): List<DslInputRootCandidate> {
            val byId = bindings.associateBy(PreviousBinding::id)
            return frozen(
                bindings.filter { binding ->
                    binding.firstFallbackGuards.all { id ->
                        isFirstPeriod(byId.getValue(id).periodDimension)
                    }
                }.flatMap { binding ->
                    val first = isFirstPeriod(binding.periodDimension)
                    listOf(
                        DslInputRootCandidate(
                            binding.syntheticRoot,
                            DslInputCandidate.ControlledValue(if (first) DslValue.Nil else previousValue(binding)),
                        ),
                        DslInputRootCandidate(
                            binding.firstPeriodRoot,
                            DslInputCandidate.ControlledValue(DslValues.boolean(first)),
                        ),
                    )
                },
            )
        }
    }
}

private fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())

private fun visit(node: DslNode, action: (DslNode) -> Unit) {
    action(node)
    children(node).forEach { visit(it, action) }
}

/** Public AST traversal order; no copies, reflection, internal constructors or private API. */
private fun children(node: DslNode): List<DslNode> = when (node) {
    is DslLiteralNode, is DslSymbolNode -> emptyList()
    is DslFieldAccessNode -> listOf(node.target)
    is DslCallNode -> listOf(node.callable) + node.arguments
    is DslLambdaNode -> listOf(node.body)
    is DslLetNode -> node.bindings.map { it.value } + node.body
    is DslIfNode -> listOf(node.test, node.thenBranch, node.elseBranch)
    is DslAndNode -> node.expressions
    is DslOrNode -> node.expressions
    is DslCollectionLiteralNode -> node.values
    is DslMapLiteralNode -> node.entries.flatMap { listOf(it.key, it.value) }
}
