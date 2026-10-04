package com.xqiou.mantra.core.api.language

import com.xqiou.mantra.core.engine.CompiledFormula
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
import com.xqiou.normein.dsl.compiler.DslSourceIndexOrigin
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import com.xqiou.normein.dsl.identity.DslCanonicalNodeId
import com.xqiou.normein.dsl.reference.DslReferenceKind
import com.xqiou.normein.dsl.reference.DslReferenceOrigin
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes

/** Matches real normalized lexical identities to original binder forms, without executing the AST. */
internal class StaticFormulaIndex(private val host: HostLanguageIndex) {
    private fun source(formula: CompiledFormula, origin: DslSourceIndexOrigin): String? = when (origin) {
        is DslSourceIndexOrigin.Expression -> formula.formula.location.source
        is DslSourceIndexOrigin.NamedDefinition -> formula.namedSources[origin.name]?.location?.source
    }

    fun add(formula: CompiledFormula) {
        val nodes = linkedMapOf<DslCanonicalNodeId, DslNode>()
        fun owner(path: DslCanonicalNodeId): String? =
            formula.authorSourceIndex[path]?.origin?.let { source(formula, it) }
        fun authoredSpan(path: DslCanonicalNodeId): LanguageSpan? {
            val entry = formula.authorSourceIndex[path] ?: return null
            val source = source(formula, entry.origin) ?: return null
            if (entry.nodeOrigin is DslNodeOrigin.Programmatic) return null
            val text = host.documents[source]?.first?.source?.text ?: return null
            if (entry.span.startOffset < 0 || entry.span.endOffset > text.length) return null
            return LanguageSpan(source, entry.span.startOffset, entry.span.endOffset)
        }
        fun forms(span: LanguageSpan): List<DslForm> = host.documents[span.source]?.second?.root?.allForms()
            ?.filter { it.span.startOffset >= span.start && it.span.endOffset <= span.end }.orEmpty()
        fun binder(
            node: DslNode,
            path: DslCanonicalNodeId,
            name: String,
            slot: Int,
            lambda: Boolean,
        ): LanguageSymbolId? {
            val span = authoredSpan(path) ?: return null
            val seq = forms(span).filterIsInstance<DslForm.Sequence>().firstOrNull {
                it.span.startOffset == span.start && it.span.endOffset == span.end &&
                    it.kind == DslFormSequenceKind.LIST
            } ?: return null
            val vector = when (seq.listHeadLocal()) {
                "fn", "let" -> seq.values.getOrNull(1)
                "defn" -> seq.values.getOrNull(2)
                else -> null
            } as? DslForm.Sequence ?: return null
            // Type annotations are separate reader atoms. Destructuring/variadic binders need an
            // independently verified matching rule; refuse completeness if there is no exact token.
            val names = if (lambda) {
                vector.values.filterIsInstance<DslForm.Atom>()
                    .filterNot { it.sourceText.startsWith("^") || it.sourceText == "&" }
            } else {
                buildList {
                    var index = 0
                    while (index < vector.values.size) {
                        val annotation = vector.values[index] as? DslForm.Atom
                        if (annotation?.sourceText?.startsWith("^") == true) index++
                        val token = vector.values.getOrNull(index) as? DslForm.Atom ?: return null
                        add(token)
                        index += 2 // Binder and its eager value, as in the locked normalizer.
                    }
                }
            }
            val token = names.getOrNull(slot)?.takeIf { tokenName(it) == name } ?: return null
            val definitionSpan = nameSpan(span.source, token, name) ?: return null
            val symbol =
                LanguageSymbolId(
                    (host.symbols.values.firstOrNull() ?: host.functions.values.first()).schema,
                    LanguageSymbolKind.LOCAL,
                    name,
                    definitionSpan,
                )
            host.definitions += LanguageDefinition(symbol, name, definitionSpan, "Lexical binding")
            return symbol
        }
        fun walk(node: DslNode, path: DslCanonicalNodeId, locals: Map<DslLocalId, LanguageSymbolId>) {
            nodes[path] = node
            fun child(value: DslNode, index: Int, env: Map<DslLocalId, LanguageSymbolId> = locals) =
                walk(value, path.child(index), env)
            when (node) {
                is DslLiteralNode -> Unit
                is DslSymbolNode -> {
                    val id = (node.binding as? DslSymbolBinding.Local)?.id
                    val symbol = id?.let(locals::get)
                    val span = authoredSpan(path)
                    if (symbol != null && span != null) {
                        val atom = forms(span).filterIsInstance<DslForm.Atom>().singleOrNull {
                            it.span.startOffset == span.start && it.span.endOffset == span.end
                        }
                        val precise = atom?.let { nameSpan(span.source, it, symbol.name) }
                        if (precise != null) {
                            val inferred = formula.expression.lexicalReferences.filter {
                                it.localId == id &&
                                    it.sourceName.value == node.name.value && it.sourceSpan == node.span
                            }
                                .map { it.inferredType.toString() }.distinct().singleOrNull()
                            host.occurrences += LanguageOccurrence(
                                symbol,
                                precise,
                                if (symbol.kind == LanguageSymbolKind.FUNCTION) {
                                    LanguageUseKind.NAMED_CALL
                                } else {
                                    LanguageUseKind.LEXICAL
                                },
                                inferred,
                            )
                            host.localCompletions += LocalCompletionScope(
                                precise,
                                listOf(
                                    LocalCompletionCandidate(
                                        symbol.name,
                                        if (symbol.kind == LanguageSymbolKind.FUNCTION) "FUNCTION" else "ROOT",
                                        inferred ?: "Lexical binding",
                                    ),
                                ),
                            )
                        }
                    } else if (id != null && span != null && node.name.value !in generatedNames(formula)) {
                        host.issues += LanguageIssue(
                            "Exact lexical definition unavailable for ${node.name.value}",
                            owner(path),
                        )
                    }
                }
                is DslFieldAccessNode -> child(node.target, 0)
                is DslCallNode -> {
                    child(node.callable, 0)
                    node.arguments.forEachIndexed { i, v -> child(v, i + 1) }
                }
                is DslLambdaNode -> {
                    val env = locals.toMutableMap()
                    (node.parameters + listOfNotNull(node.variadicParameter)).forEachIndexed { i, parameter ->
                        val symbol = binder(node, path, parameter.sourceName.value, i, true)
                        if (symbol ==
                            null
                        ) {
                            host.issues += LanguageIssue("Unsupported lexical parameter span", owner(path))
                        } else {
                            env[parameter.id] = symbol
                        }
                    }
                    child(node.body, 0, env)
                }
                is DslLetNode -> {
                    val env = locals.toMutableMap()
                    node.bindings.forEachIndexed { i, binding ->
                        child(binding.value, i, env)
                        val named = host.functions[binding.sourceName.value]?.takeIf {
                            path == DslCanonicalNodeId.ROOT && it.kind == LanguageSymbolKind.FUNCTION
                        }
                        val symbol = named ?: binder(node, path, binding.sourceName.value, i, false)
                        if (symbol != null) {
                            env[binding.id] = symbol
                        } else if (binding.sourceName.value !in generatedNames(formula)) {
                            host.issues += LanguageIssue("Unsupported lexical let binder span", owner(path))
                        }
                    }
                    child(node.body, node.bindings.size, env)
                }
                is DslIfNode -> {
                    child(node.test, 0)
                    child(node.thenBranch, 1)
                    child(node.elseBranch, 2)
                }
                is DslAndNode -> node.expressions.forEachIndexed { i, v -> child(v, i) }
                is DslOrNode -> node.expressions.forEachIndexed { i, v -> child(v, i) }
                is DslCollectionLiteralNode -> node.values.forEachIndexed { i, v -> child(v, i) }
                is DslMapLiteralNode -> node.entries.forEachIndexed { i, v ->
                    child(v.key, i * 2)
                    child(
                        v.value,
                        i * 2 + 1,
                    )
                }
            }
        }
        walk(formula.expression.normalizedAst, DslCanonicalNodeId.ROOT, emptyMap())
        formula.expression.references.forEach { reference ->
            if (reference.origin != DslReferenceOrigin.EXPLICIT_SOURCE) {
                if (reference.dynamic) {
                    val span = authoredSpan(reference.nodeId)
                    val field = nodes[reference.nodeId] as? DslFieldAccessNode
                    if (field != null && provedLocalField(field, formula)) {
                        if (span != null) {
                            host.types += LanguageTypeEvidence(span, reference.inferredType.toString())
                            val target = field.target as DslSymbolNode
                            val owners = localOwners(field, formula)
                            val names = owners.first().fields.map { it.name.value }.filter { name ->
                                owners.all { owner -> owner.fields.any { it.name.value == name } }
                            }
                            val candidates = names.map { name ->
                                val types = owners.map { owner ->
                                    owner.fields.single { it.name.value == name }.valueType
                                }
                                LocalCompletionCandidate(
                                    target.name.value + "." + name,
                                    "FIELD",
                                    DslTypes.union(types).toString(),
                                )
                            }
                            host.localCompletions += LocalCompletionScope(span, candidates)
                        }
                    } else {
                        host.issues += LanguageIssue("Dynamic formula reference cannot be renamed")
                    }
                }
                return@forEach
            }
            val span = authoredSpan(reference.nodeId) ?: return@forEach
            val root = reference.rootName ?: return@forEach
            if (root in generatedNames(formula)) return@forEach
            val name = if (root == "all") {
                reference.staticPath?.let { path ->
                    if (path.firstOrNull() == "all") path.getOrNull(1) else path.firstOrNull()
                }
            } else {
                formula.rootNames[root] ?: root.removePrefix("mantra_")
            }
            val symbol = name?.let(host.symbols::get) ?: return@forEach
            val possible = forms(span)
            val token = if (root == "all" && reference.kind == DslReferenceKind.FIELD_PATH) {
                possible.filterIsInstance<DslForm.Postfix>().minByOrNull { it.span.endOffset - it.span.startOffset }
            } else {
                possible.filterIsInstance<DslForm.Atom>().firstOrNull {
                    it.sourceText == root || it.sourceText == "mantra/$name"
                }
            }
            val precise = token?.let { nameSpan(span.source, it, name!!) }
            if (precise == null) {
                host.issues += LanguageIssue("Reference token unavailable for $name", span.source)
            } else {
                host.occurrences += LanguageOccurrence(
                    symbol,
                    precise,
                    LanguageUseKind.FORMULA,
                    reference.inferredType.toString(),
                    !reference.dynamic,
                )
            }
        }
        formula.previousBindings.forEach { previous ->
            val source = source(formula, previous.sourceOrigin) ?: return@forEach
            val symbol = host.symbols[previous.targetNodeId] ?: return@forEach
            val span = LanguageSpan(source, previous.targetSpan.startOffset, previous.targetSpan.endOffset)
            val token = forms(span).filterIsInstance<DslForm.Atom>().singleOrNull()
            val precise = token?.let { nameSpan(source, it, previous.targetNodeId) }
            if (precise == null) {
                host.issues += LanguageIssue("Previous target token unavailable", source)
            } else {
                host.occurrences += LanguageOccurrence(
                    symbol,
                    precise,
                    LanguageUseKind.PREVIOUS,
                    previous.valueType.toString(),
                )
            }
        }
    }

    private fun provedLocalField(node: DslFieldAccessNode, formula: CompiledFormula): Boolean {
        val owners = localOwners(node, formula)
        return owners.isNotEmpty() && owners.all { owner -> owner.fields.any { it.name.value == node.field.value } }
    }

    private fun localOwners(node: DslFieldAccessNode, formula: CompiledFormula): List<DslType.ObjectType> {
        val target = node.target as? DslSymbolNode ?: return emptyList()
        val local = target.binding as? DslSymbolBinding.Local ?: return emptyList()
        val type = formula.expression.lexicalReferences.filter {
            it.localId == local.id &&
                it.sourceSpan == target.span && it.sourceName == target.name
        }.map { it.inferredType }.distinct().singleOrNull()
            ?: return emptyList()
        fun concrete(type: DslType, seen: Set<com.xqiou.normein.dsl.type.DslTypeId> = emptySet()): List<DslType> =
            when (type) {
                is DslType.TypeRef -> if (type.typeId in seen) {
                    emptyList()
                } else {
                    host.typeSchema.resolve(type)
                        ?.let { concrete(it, seen + type.typeId) }.orEmpty()
                }
                is DslType.Union -> type.memberTypes.flatMap { concrete(it, seen) }.filter { it != DslType.Null }
                else -> listOf(type)
            }
        val owners = concrete(type)
        if (owners.any { it !is DslType.ObjectType }) return emptyList()
        return owners.filterIsInstance<DslType.ObjectType>()
    }

    private fun generatedNames(formula: CompiledFormula): Set<String> = formula.previousBindings
        .flatMap { listOf(it.syntheticRoot, it.firstPeriodRoot) }.toSet()
}

private fun DslForm.Sequence.listHeadLocal(): String? = values.firstOrNull()?.let(::tokenName)
