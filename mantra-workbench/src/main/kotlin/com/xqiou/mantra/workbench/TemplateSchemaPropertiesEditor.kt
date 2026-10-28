package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Severity
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.model.Item
import com.xqiou.mantra.core.model.NodeItem
import com.xqiou.mantra.core.model.NoteItem
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SchemaReader
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.isSequence
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.options
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.workbench.json.WorkbenchJson
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.security.MessageDigest

/** Source ownership comes from the public schema model; parsing a formula does not validate its bindings. */
internal object TemplateSchemaPropertiesEditor {
    data class Patch(val startOffset: Int, val endOffset: Int, val text: String, val removedText: String)
    data class Result(
        val schemaId: String,
        val text: String,
        val owners: List<Map<String, Any?>>,
        val patches: List<Patch>,
    )

    private data class Declaration(
        val document: Document,
        val form: DslForm.Sequence,
        val path: List<Int>,
        val node: String?,
        val text: String,
        val presentation: Presentation,
        val metadataIndex: Int,
        val metadata: DslForm.Sequence?,
        val classPair: Pair<DslForm, DslForm>?,
    ) {
        val textProperty: String get() = if (form.listHead == "note") "text" else "label"
        val textForm: DslForm get() = form.values[if (form.listHead == "note") 1 else 2]
    }

    private data class Owner(val declaration: Declaration, val property: String, val handle: String)
    private data class Parsed(val schemaId: String, val declarations: List<Declaration>)

    private val className = Regex("[a-z][a-z0-9-]*")
    private val metadataIndices = mapOf(
        "section" to 3, "slot" to 3, "field" to 3, "total" to 3,
        "choice" to 3, "choose-min" to 3, "choose-max" to 3,
        "line" to 4, "subtract" to 4, "info" to 4, "formula-slot" to 4, "check" to 4,
        "reconcile" to 5, "note" to 2,
    )

    fun apply(
        original: SourceText,
        source: String,
        documentHandle: String,
        operations: List<TemplateSchemaPropertyOperation>,
        resolver: SourceResolver,
    ): Result {
        budget(source)
        unicodeSource(SourceText(original.name, source, original.base))
        validateOperations(operations)
        val initial = parse(original, source, resolver)
        val owners = owners(initial, documentHandle, source).associateBy(Owner::handle)
        // Every incoming handle belongs to the initial text. Later patches change offsets and all handles.
        val bound = operations.map { operation ->
            val owner = owners[operation.owner] ?: bad("Unknown or expired schema property owner")
            if (owner.declaration.document.source.name != original.name) bad("Included schema properties are read-only")
            when (operation.op) {
                "setText" -> if (owner.property == "classes") bad("A classes owner does not accept text")
                "setClasses", "unset" -> if (owner.property != "classes") bad("Only classes can be set or unset here")
            }
            operation to owner
        }
        // Check all submitted labels before the first patch, even if an earlier edit would move their ranges.
        bound.forEach { (operation, owner) ->
            operation.text?.let { value ->
                unicode(value, owner.declaration.document.location(owner.declaration.textForm))
            }
        }
        var current = source
        val patches = mutableListOf<Patch>()
        bound.forEach { (operation, before) ->
            val parsed = parse(original, current, resolver)
            val declaration = parsed.declarations.singleOrNull { it.path == before.declaration.path }
                ?: invalid(
                    "Schema declaration can no longer be located uniquely",
                    before.declaration.document,
                    before.declaration.form,
                )
            if (declaration.document.source.name != original.name ||
                declaration.form.listHead != before.declaration.form.listHead ||
                declaration.node != before.declaration.node
            ) {
                invalid("Schema declaration identity changed during the edit", declaration.document, declaration.form)
            }
            when (operation.op) {
                "setText" -> {
                    val value = checkNotNull(operation.text)
                    if (declaration.text != value) {
                        current = change(current, declaration.textForm, textLiteral(value, declaration), patches)
                    }
                }
                "setClasses" -> {
                    val classes = checkNotNull(operation.classes)
                    if (declaration.classPair == null || declaration.presentation.classes != classes) {
                        val literal = classes.joinToString(" ", "[", "]") { ":$it" }
                        val pair = declaration.classPair
                        current = when {
                            pair != null -> change(current, pair.second, literal, patches)
                            declaration.metadata != null -> {
                                val map = declaration.metadata
                                val separator = if (map.values.isEmpty()) "" else " "
                                change(
                                    current,
                                    map.span.endOffset - 1,
                                    map.span.endOffset - 1,
                                    "$separator:class $literal",
                                    patches,
                                )
                            }
                            else -> {
                                val at = declaration.form.values[declaration.metadataIndex - 1].span.endOffset
                                change(current, at, at, " {:class $literal}", patches)
                            }
                        }
                    }
                }
                "unset" -> declaration.classPair?.let { pair ->
                    // Remove value before key, keeping comments between them and unrelated metadata intact.
                    current = change(current, pair.second, "", patches)
                    current = change(current, pair.first, "", patches)
                }
            }
            budget(current)
        }
        val final = parse(original, current, resolver)
        return Result(
            final.schemaId,
            current,
            owners(final, documentHandle, current).map { ownerData(it, original.name) },
            patches,
        )
    }

    private fun validateOperations(operations: List<TemplateSchemaPropertyOperation>) {
        if (operations.size > 32 || operations.map { it.owner }.distinct().size != operations.size) {
            bad("Schema properties accept at most 32 distinct owner operations")
        }
        operations.forEach { operation ->
            val correct = when (operation.op) {
                "setText" -> operation.text != null && operation.classes == null
                "setClasses" ->
                    operation.text == null && operation.classes != null &&
                        operation.classes.size <= 64 && operation.classes.distinct().size == operation.classes.size &&
                        operation.classes.all(className::matches)
                "unset" -> operation.text == null && operation.classes == null
                else -> false
            }
            if (!correct) bad("Invalid schema property operation")
            if ((operation.text?.length ?: 0) > 65_536) tooLarge()
        }
    }

    private fun parse(original: SourceText, text: String, resolver: SourceResolver): Parsed {
        val source = SourceText(original.name, text, original.base)
        val sources = linkedMapOf(source.name to source)
        val captured = SourceResolver { name, relative ->
            resolver.resolve(name, relative)?.also { included ->
                unicodeSource(included)
                val previous = sources.putIfAbsent(included.name, included)
                if (previous != null && previous.text != included.text) {
                    invalid(
                        "Captured source identity resolves to different text",
                        Document(
                            source,
                            readDocument(source).root,
                        ),
                    )
                }
            }
        }
        val sink = DiagnosticSink()
        val schema = try {
            SchemaReader(captured).read(source, sink)
        } catch (_: ArithmeticException) {
            invalid("Schema option cannot be represented exactly", readDocument(source))
        } catch (_: IllegalArgumentException) {
            invalid("Schema declaration cannot be read", readDocument(source))
        }
        if (schema == null || sink.hasErrors) invalidRead("Schema presentation properties cannot be read", source, sink)
        val documents = sources.mapValues { (_, content) -> readDocument(content) }
        val declarations = mutableListOf<Declaration>()
        fun walk(items: List<Item>, parent: List<Int>) {
            items.forEachIndexed { index, item ->
                val path = parent + index
                val document = documents[item.location.source]
                    ?: invalid("Schema declaration has no captured source", documents.getValue(original.name))
                val form = locate(document, item.location)
                val metadataIndex = metadataIndices[form.listHead]
                    ?: invalid("Schema declaration is outside the presentation editor", document, form)
                val node: String?
                val value: String
                val presentation: Presentation
                when (item) {
                    is SectionItem -> {
                        node = item.id
                        value = item.label
                        presentation = item.presentation
                    }
                    is NodeItem -> {
                        node = item.id
                        value = item.label
                        presentation = item.presentation
                    }
                    is NoteItem -> {
                        node = null
                        value = item.text
                        presentation = item.presentation
                    }
                }
                val token = form.values.getOrNull(if (item is NoteItem) 1 else 2)
                if (token?.string != value) {
                    invalid("Schema text property does not match its source token", document, form)
                }
                unicode(value, document.location(checkNotNull(token)))
                val metadata = form.values.getOrNull(metadataIndex)
                    ?.takeIf { it.isSequence(DslFormSequenceKind.MAP) } as? DslForm.Sequence
                val optionsSink = DiagnosticSink()
                document.options(metadata, optionsSink, "schema presentation owner")
                if (optionsSink.hasErrors) {
                    invalidRead(
                        "Schema metadata cannot be located uniquely",
                        document.source,
                        optionsSink,
                    )
                }
                val classPair = metadata?.values?.chunked(2)?.singleOrNull { it[0].keyword == "class" }
                    ?.let { it[0] to it[1] }
                declarations += Declaration(
                    document, form, path, node, value, presentation, metadataIndex, metadata,
                    classPair,
                )
                if (item is SectionItem) walk(item.children, path)
            }
        }
        walk(schema.root.children, emptyList())
        return Parsed(schema.id, declarations)
    }

    private fun readDocument(source: SourceText): Document {
        val sink = DiagnosticSink()
        return Document.read(source, sink) ?: invalidRead("Schema source cannot be read", source, sink)
    }

    /** Follow only ancestors of the model's exact range, never interpret formula/data forms as declarations. */
    private fun locate(document: Document, location: SourceLocation): DslForm.Sequence {
        val start = location.startOffset
        val end = location.endOffset
        if (start == null || end == null) invalid("Schema declaration has no exact source range", document)
        fun find(form: DslForm): List<DslForm.Sequence> {
            if (form.span.startOffset == start && form.span.endOffset == end) {
                return listOfNotNull((form as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.LIST })
            }
            return (form as? DslForm.Sequence)?.values.orEmpty().filter {
                it.span.startOffset <= start && it.span.endOffset >= end
            }.flatMap(::find)
        }
        return find(document.root).singleOrNull()
            ?: invalid("Schema declaration cannot be matched to one source form", document)
    }

    private fun owners(parsed: Parsed, documentHandle: String, text: String): List<Owner> {
        val sourceHash = digest(text)
        return parsed.declarations.flatMap { declaration ->
            listOf(declaration.textProperty, "classes").map { property ->
                val identity = listOf(
                    "mantra.schema-owner/1",
                    documentHandle,
                    sourceHash,
                    declaration.document.source.name,
                    declaration.path.joinToString("/"),
                    property,
                ).joinToString("\u0000")
                Owner(declaration, property, digest(identity))
            }
        }
    }

    private fun ownerData(owner: Owner, rootName: String): Map<String, Any?> {
        val declaration = owner.declaration
        val classes = owner.property == "classes"
        val token = if (classes) declaration.classPair?.second else declaration.textForm
        val editable = declaration.document.source.name == rootName
        return linkedMapOf(
            "handle" to owner.handle,
            "document" to declaration.document.source.name,
            "node" to declaration.node,
            "form" to checkNotNull(declaration.form.listHead),
            "path" to declaration.path,
            "property" to owner.property,
            "type" to if (classes) "classes" else "text",
            "declared" to (token != null),
            "value" to if (classes) {
                if (token == null) null else declaration.presentation.classes.map { mapOf("kw" to it) }
            } else {
                declaration.text
            },
            "editable" to editable,
            "reason" to if (editable) {
                null
            } else {
                "Captured include declarations are read-only; edit their source separately."
            },
            "canUnset" to (classes && token != null && editable),
            "range" to token?.let { range(declaration.document.location(it)) },
            "declarationRange" to range(declaration.document.location(declaration.form)),
        )
    }

    private fun range(location: SourceLocation): Map<String, Int> = linkedMapOf(
        "line" to location.line,
        "column" to location.column,
        "startOffset" to checkNotNull(location.startOffset),
        "endOffset" to checkNotNull(location.endOffset),
    )

    private fun textLiteral(text: String, declaration: Declaration): String {
        val encoded = WorkbenchJson.write(text)
        val probeSource = SourceText(declaration.document.source.name, "(note $encoded)")
        val sink = DiagnosticSink()
        val probe = Document.read(probeSource, sink)
        val actual = (probe?.root as? DslForm.Sequence)?.values?.getOrNull(1)?.string
        if (sink.hasErrors || actual != text) {
            invalid(
                "This text cannot be represented exactly by the current schema reader",
                declaration.document,
                declaration.textForm,
            )
        }
        return encoded
    }

    private fun change(text: String, form: DslForm, replacement: String, patches: MutableList<Patch>): String =
        change(text, form.span.startOffset, form.span.endOffset, replacement, patches)

    private fun change(text: String, start: Int, end: Int, replacement: String, patches: MutableList<Patch>): String {
        val removed = text.substring(start, end)
        if (removed == replacement) return text
        val result = text.substring(0, start) + replacement + text.substring(end)
        budget(result)
        patches += Patch(start, end, replacement, removed)
        return result
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun unicodeSource(source: SourceText) {
        if (Charsets.UTF_8.newEncoder().canEncode(source.text)) return
        var index = 0
        while (index < source.text.length) {
            val char = source.text[index]
            if (char.isHighSurrogate() && source.text.getOrNull(index + 1)?.isLowSurrogate() == true) {
                index += 2
            } else if (char.isSurrogate()) {
                break
            } else {
                index++
            }
        }
        val before = source.text.substring(0, index)
        val line = before.count { it == '\n' } + 1
        val column = index - before.lastIndexOf('\n')
        unicode(source.text, SourceLocation(source.name, line, column, index, index + 1))
    }

    /** UTF-8 hashing must be injective over accepted source text, including text inside comments. */
    private fun unicode(text: String, location: SourceLocation) {
        if (!Charsets.UTF_8.newEncoder().canEncode(text)) {
            val message = "Schema property text must contain valid Unicode scalar values"
            throw WorkspaceException(
                WorkspaceProblem.INVALID,
                message,
                listOf(Diagnostic(Severity.ERROR, "MANTRA-TEMPLATE-SCHEMA-PROPERTY", message, location)),
            )
        }
    }

    private fun budget(text: String) {
        if (text.length > 65_536 || text.toByteArray(Charsets.UTF_8).size > 1_048_576) tooLarge()
    }

    private fun tooLarge(): Nothing = throw WorkspaceException(
        WorkspaceProblem.TOO_LARGE,
        "Schema properties exceed the source budget",
    )

    private fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)

    private fun invalidRead(message: String, source: SourceText, sink: DiagnosticSink): Nothing {
        val location = sink.all.firstOrNull { it.severity == Severity.ERROR }?.location
            ?: SourceLocation(source.name, 1, 1, 0, source.text.length)
        throw WorkspaceException(
            WorkspaceProblem.INVALID,
            message,
            listOf(Diagnostic(Severity.ERROR, "MANTRA-TEMPLATE-SCHEMA-PROPERTY", message, location)) + sink.all,
        )
    }

    private fun invalid(message: String, document: Document, form: DslForm = document.root): Nothing =
        throw WorkspaceException(
            WorkspaceProblem.INVALID,
            message,
            listOf(Diagnostic(Severity.ERROR, "MANTRA-TEMPLATE-SCHEMA-PROPERTY", message, document.location(form))),
        )
}
