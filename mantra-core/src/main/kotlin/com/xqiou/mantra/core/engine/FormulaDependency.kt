package com.xqiou.mantra.core.engine

import com.xqiou.normein.dsl.ast.DslAndNode
import com.xqiou.normein.dsl.ast.DslCallNode
import com.xqiou.normein.dsl.ast.DslCollectionLiteralNode
import com.xqiou.normein.dsl.ast.DslFieldAccessNode
import com.xqiou.normein.dsl.ast.DslIfNode
import com.xqiou.normein.dsl.ast.DslLambdaNode
import com.xqiou.normein.dsl.ast.DslLetNode
import com.xqiou.normein.dsl.ast.DslLiteralNode
import com.xqiou.normein.dsl.ast.DslLocalId
import com.xqiou.normein.dsl.ast.DslMapLiteralNode
import com.xqiou.normein.dsl.ast.DslNode
import com.xqiou.normein.dsl.ast.DslNodeOrigin
import com.xqiou.normein.dsl.ast.DslOrNode
import com.xqiou.normein.dsl.ast.DslSymbolBinding
import com.xqiou.normein.dsl.ast.DslSymbolNode
import java.util.Collections

internal enum class FormulaDependencyKind { CURRENT, ALL, PREVIOUS, DIMENSION, RELATION }

/** One dependency use. Multiple activation sets for the same root are alternatives, not conjuncts. */
internal data class FormulaDependency(
    val rootName: String,
    val kind: FormulaDependencyKind,
    val targetNodeId: String,
    val firstFallbackGuards: Set<Int> = emptySet(),
    val previousBindingId: Int? = null,
)

/**
 * Derives first-only edges from checked AST occurrences, including lexical callable aliases.
 * A named closure's body is visited at its call site, so defining a fallback closure does not
 * make its roots unconditional dependencies. Higher-order callable arguments are conservative.
 */
internal object FormulaDependencies {
    fun collect(
        root: DslNode,
        roots: Map<String, String>,
        dimensions: Set<String>,
        relations: Map<String, String>,
        previous: List<PreviousBinding>,
    ): List<FormulaDependency> {
        val bindings = previous.associateBy { "mantra.prev.${it.id}" }
        data class LocalValue(val node: DslNode, val scope: Map<DslLocalId, LocalValue>)
        data class Closure(val lambda: DslLambdaNode, val scope: Map<DslLocalId, LocalValue>)
        fun callable(node: DslNode, scope: Map<DslLocalId, LocalValue>, seen: Set<DslLocalId> = emptySet()): Closure? =
            when (node) {
                is DslLambdaNode -> Closure(node, scope)
                is DslSymbolNode -> (node.binding as? DslSymbolBinding.Local)?.id?.let { id ->
                    if (id in seen) null else scope[id]?.let { callable(it.node, it.scope, seen + id) }
                }
                else -> null
            }
        val found = linkedSetOf<FormulaDependency>()
        val activeCalls = linkedSetOf<Pair<DslLambdaNode, Set<Int>>>()
        fun add(rootName: String, kind: FormulaDependencyKind, target: String, guards: Set<Int>, id: Int? = null) {
            found += FormulaDependency(rootName, kind, target, Collections.unmodifiableSet(guards.toSet()), id)
        }
        fun fieldPath(node: DslNode): List<String>? = when (node) {
            is DslSymbolNode -> if (node.binding is DslSymbolBinding.Root) listOf(node.name.value) else null
            is DslFieldAccessNode -> fieldPath(node.target)?.plus(node.field.value)
            else -> null
        }
        fun walk(node: DslNode, guards: Set<Int>, scope: Map<DslLocalId, LocalValue>, invokeClosure: Boolean = false) {
            if (invokeClosure) {
                callable(node, scope)?.let { closure ->
                    val lambda = closure.lambda
                    val key = lambda to guards
                    if (activeCalls.add(key)) {
                        walk(lambda.body, guards, closure.scope)
                        activeCalls.remove(key)
                    }
                    return
                }
            }
            when (node) {
                is DslSymbolNode -> if (node.binding is DslSymbolBinding.Root) {
                    val name = node.name.value
                    roots[name]?.let { add(name, FormulaDependencyKind.CURRENT, it, guards) }
                    if (name in dimensions) add(name, FormulaDependencyKind.DIMENSION, name, guards)
                    relations[name]?.let { add(name, FormulaDependencyKind.RELATION, it, guards) }
                }
                is DslFieldAccessNode -> {
                    val path = fieldPath(node)
                    if (path?.firstOrNull() == "all" && path.size >= 2) {
                        add("all", FormulaDependencyKind.ALL, path[1], guards)
                    } else {
                        walk(node.target, guards, scope)
                    }
                }
                is DslCallNode -> {
                    val binding = (node.origin as? DslNodeOrigin.Programmatic)?.ownerId?.let(bindings::get)
                    if (binding != null) {
                        add(
                            binding.syntheticRoot,
                            FormulaDependencyKind.PREVIOUS,
                            binding.targetNodeId,
                            guards,
                            binding.id,
                        )
                        walk((node.arguments[2] as DslLambdaNode).body, guards + binding.id, scope)
                    } else {
                        val closure = callable(node.callable, scope)
                        if (closure == null) {
                            walk(node.callable, guards, scope, invokeClosure = true)
                            node.arguments.forEach { walk(it, guards, scope, invokeClosure = true) }
                        } else {
                            node.arguments.forEach { walk(it, guards, scope) }
                            val arguments = closure.lambda.parameters.zip(
                                node.arguments,
                            ).associate { (parameter, value) ->
                                parameter.id to LocalValue(value, scope)
                            }
                            val key = closure.lambda to guards
                            if (activeCalls.add(key)) {
                                walk(closure.lambda.body, guards, closure.scope + arguments)
                                activeCalls.remove(key)
                            }
                        }
                    }
                }
                is DslLetNode -> {
                    var nested = scope
                    node.bindings.forEach { binding ->
                        // Store the lexical environment at declaration time, including aliases.
                        if (callable(binding.value, nested) == null) walk(binding.value, guards, nested)
                        nested = nested + (binding.id to LocalValue(binding.value, nested))
                    }
                    walk(node.body, guards, nested, invokeClosure)
                }
                is DslLambdaNode -> Unit
                else -> dependencyChildren(node).forEach { walk(it, guards, scope, invokeClosure) }
            }
        }
        walk(root, emptySet(), emptyMap())
        return Collections.unmodifiableList(found.toList())
    }
}

private fun dependencyChildren(node: DslNode): List<DslNode> = when (node) {
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
