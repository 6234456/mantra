package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.options
import com.xqiou.mantra.packages.PackageParameterChoice
import com.xqiou.mantra.packages.ParameterSelectionMode
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.time.LocalDate

/** Host metadata lives in the writable case, never in a mounted package or an in-memory binding. */
internal object PackageHostPins {
    data class Pin(val case: String, val revision: String, val choice: PackageParameterChoice?)

    fun read(case: CaseData): Pin? {
        val raw = case.meta["host-package"] ?: return null
        val map = (raw as? Value.MapV)?.entries ?: error("host-package must be a literal map")
        fun text(name: String) = (map[Value.Kw(name)] as? Value.Text)?.value
            ?: error("host-package :$name must be text")
        require(map.keys.toSet() == setOf(Value.Kw("case"), Value.Kw("revision"), Value.Kw("policy")))
        val revision = text("revision")
        require(Regex("[0-9a-f]{64}").matches(revision))
        val policy = when (val value = map[Value.Kw("policy")]) {
            Value.Nil -> null
            is Value.MapV -> {
                val fields = value.entries
                require(fields.keys.toSet() == setOf("date", "mode", "candidates", "keys").map { Value.Kw(it) }.toSet())
                fun string(name: String) = (fields[Value.Kw(name)] as? Value.Text)?.value
                    ?: error("host-package policy :$name must be text")
                fun strings(name: String): List<String> = (fields[Value.Kw(name)] as? Value.Vec)?.items?.map {
                    (it as? Value.Text)?.value ?: error("host-package policy :$name must contain text")
                } ?: error("host-package policy :$name must be a vector")
                val ids = strings("candidates")
                val keys = strings("keys")
                require(
                    ids.isNotEmpty() && keys.isNotEmpty() && ids.distinct().size == ids.size &&
                        keys.distinct().size == keys.size,
                )
                PackageParameterChoice(LocalDate.parse(string("date")), mode(string("mode")), ids, keys.toSet())
            }
            else -> error("host-package :policy must be a map or nil")
        }
        return Pin(text("case"), revision, policy)
    }

    fun mode(text: String): ParameterSelectionMode = when (text) {
        "effective-date" -> ParameterSelectionMode.EFFECTIVE_DATE
        "what-if" -> ParameterSelectionMode.WHAT_IF
        else -> error("Explicit parameter mode must be effective-date or what-if")
    }

    fun write(source: SourceText, pin: Pin): SourceText {
        val sink = DiagnosticSink()
        val document = requireNotNull(Document.read(source, sink)) { "Expected a case document" }
        sink.throwIfErrors()
        val root = document.root as? DslForm.Sequence ?: error("Expected a case document")
        require(root.listHead == "case")
        val metadata = (root.values.getOrNull(2) as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.MAP }
        val old = metadata?.let { document.options(it, sink, "case metadata")["host-package"] }
        sink.throwIfErrors()
        val value = buildString {
            append(
                "{:case ",
            ).append(quote(pin.case)).append(" :revision ").append(quote(pin.revision)).append(" :policy ")
            val choice = pin.choice
            if (choice == null) {
                append("nil")
            } else {
                append("{:date ").append(quote(choice.effectiveDate.toString()))
                append(" :mode ").append(quote(choice.mode.name.lowercase().replace('_', '-')))
                append(" :candidates ").append(vector(choice.candidateIds))
                append(" :keys ").append(vector(choice.requiredKeys.sorted())).append('}')
            }
            append('}')
        }
        val text = when {
            old != null -> source.text.replaceRange(old.span.startOffset, old.span.endOffset, value)
            metadata != null -> source.text.replaceRange(
                metadata.span.endOffset - 1,
                metadata.span.endOffset - 1,
                " :host-package $value",
            )
            else -> {
                val offset = root.values[1].span.endOffset
                source.text.replaceRange(offset, offset, " {:host-package $value}")
            }
        }
        return SourceText(source.name, text, source.base)
    }

    private fun vector(values: List<String>) = values.joinToString(" ", "[", "]", transform = ::quote)
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (it.code < 32) append("\\u%04x".format(it.code)) else append(it)
            }
        }
        append('"')
    }
}
