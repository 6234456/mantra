package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.literal
import com.xqiou.mantra.core.view.ExplainBranch
import com.xqiou.mantra.core.view.ExplainStep
import com.xqiou.mantra.core.view.ExplainTrace
import com.xqiou.normein.dsl.ast.DslNodeOrigin
import com.xqiou.normein.dsl.compiler.DslSourceIndexOrigin
import com.xqiou.normein.dsl.identity.DslCanonicalNodeId
import com.xqiou.normein.dsl.trace.DslTraceNode
import com.xqiou.normein.dsl.trace.DslTraceNodeKind
import java.time.LocalDate

/** Detaches bounded, child-first author-source evidence from the kernel trace. Never evaluates. */
internal object TraceProjection {
    data class Budget(val steps: Int, val branches: Int, val characters: Int, val items: Int = steps + branches)
    data class Projection(val trace: ExplainTrace, val events: Int, val characters: Int)

    fun project(formula: CompiledFormula, root: DslTraceNode, kernelTruncated: Boolean, budget: Budget): Projection {
        val index = formula.expression.sourceIndex
        val steps = mutableListOf<ExplainStep>()
        val branches = mutableListOf<ExplainBranch>()
        var truncated = kernelTruncated
        var characters = 0
        val visited = mutableListOf<DslTraceNode>()
        fun walk(node: DslTraceNode) {
            if (node.summaryTruncated || node.resultSummary?.truncated == true) truncated = true
            node.children.forEach(::walk)
            visited += node
        }
        walk(root)
        fun executedChild(node: DslTraceNode, id: DslCanonicalNodeId): DslTraceNode? {
            fun find(candidate: DslTraceNode): DslTraceNode? = if (candidate.kind == DslTraceNodeKind.AST_NODE &&
                candidate.nodeId == id
            ) {
                candidate
            } else {
                candidate.children.firstNotNullOfOrNull(::find)
            }
            return node.children.firstNotNullOfOrNull(::find)
        }
        fun snippet(node: DslTraceNode): Pair<String, SourceLocation>? {
            val entry = index[node.nodeId] ?: return null
            val (source, owner) = when (val origin = entry.origin) {
                is DslSourceIndexOrigin.Expression -> formula.formula.source to formula.formula.location
                is DslSourceIndexOrigin.NamedDefinition -> (formula.namedSources[origin.name] ?: return null).let {
                    it.source to it.location
                }
            }
            val sourceStart = owner.startOffset ?: return null
            val start = entry.span.startOffset - sourceStart
            val end = entry.span.endOffset - sourceStart
            if (start < 0 || end > source.length || start >= end) return null
            return source.substring(start, end) to SourceLocation(
                owner.source,
                entry.span.line,
                entry.span.column,
                entry.span.startOffset,
                entry.span.endOffset,
            )
        }
        fun reserve(length: Int): Boolean {
            if (length > budget.characters - characters || steps.size + branches.size >= budget.items) {
                truncated = true
                return false
            }
            characters += length
            return true
        }
        for (node in visited.filter { it.kind == DslTraceNodeKind.AST_NODE }) {
            val (text, location) = snippet(node) ?: continue
            val nodeOrigin = index[node.nodeId]?.nodeOrigin
            val conditional = Regex("^\\s*\\((if|cond)(?=\\s|\\))").containsMatchIn(text) ||
                (nodeOrigin is DslNodeOrigin.Lowered && nodeOrigin.surface == "cond")
            if (conditional) {
                val branchNode = executedChild(node, node.nodeId.child(1))
                    ?: executedChild(node, node.nodeId.child(2))
                val branch = branchNode?.let(::snippet)
                if (branch != null && branch.first != text &&
                    !Regex("^\\s*\\(cond(?=\\s|\\))").containsMatchIn(branch.first)
                ) {
                    if (branches.size < budget.branches && reserve(branch.first.length)) {
                        branches += ExplainBranch(branch.first, true, branch.second)
                    } else {
                        truncated = true
                    }
                }
            }
            if (!text.trimStart().startsWith('(') && node !== root) continue
            val summary = node.resultSummary
            val rendered = summary?.rendered
            if (steps.size < budget.steps && reserve(text.length + rendered.orEmpty().length)) {
                steps += ExplainStep(
                    text,
                    rendered?.takeUnless { summary?.truncated == true }?.let {
                        literalValue(it, summary?.valueKind.orEmpty())
                    },
                    location,
                    rendered,
                )
            } else {
                truncated = true
            }
        }
        return Projection(ExplainTrace(steps, branches, truncated), visited.size, characters)
    }

    private fun literalValue(rendered: String, kind: String): Value? {
        if (kind == "date") return runCatching { Value.Date(LocalDate.parse(rendered)) }.getOrNull()
        val sink = DiagnosticSink()
        val document = Document.read(SourceText("<trace-summary>", rendered), sink) ?: return null
        return document.literal(document.root, sink, "trace summary")?.takeUnless { sink.hasErrors }
    }
}
