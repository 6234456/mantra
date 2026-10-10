package com.xqiou.mantra.core.api.language

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.DimensionVertex
import com.xqiou.mantra.core.engine.InputVertex
import com.xqiou.mantra.core.engine.ParamVertex
import com.xqiou.mantra.core.engine.ValueVertex
import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormPostfix
import com.xqiou.normein.dsl.form.DslFormSequenceKind

/** Supplied by a confined editor resolver. Link source ownership is exact and independent of file names. */
data class LanguageDocumentContext(
    val source: SourceText,
    val schema: SchemaIdentity,
    val linkSchemas: Map<String, SchemaIdentity> = emptyMap(),
    val linkOwners: Map<String, String> = emptyMap(),
)

internal class HostLanguageIndex(private val plan: CalculationPlan, contexts: List<LanguageDocumentContext>) {
    val documents = contexts.associate { context ->
        val document = Document.read(context.source, DiagnosticSink())
        context.source.name to (context to document)
    }
    val definitions = mutableListOf<LanguageDefinition>()
    val types = mutableListOf<LanguageTypeEvidence>()
    val localCompletions = mutableListOf<LocalCompletionScope>()
    val typeSchema get() = plan.typeSchema
    val occurrences = mutableListOf<LanguageOccurrence>()
    val issues = mutableListOf<LanguageIssue>()
    val symbols = linkedMapOf<String, LanguageSymbolId>()
    val functions = linkedMapOf<String, LanguageSymbolId>()

    init {
        plan.vertices.values.forEach { vertex ->
            val kind = when (vertex) {
                is InputVertex -> LanguageSymbolKind.INPUT
                is ParamVertex -> LanguageSymbolKind.PARAMETER
                is DimensionVertex -> LanguageSymbolKind.DIMENSION
                is ValueVertex -> LanguageSymbolKind.NODE
                else -> null
            }
            if (kind != null) {
                declare(
                    vertex.id,
                    kind,
                    vertex.location,
                    vertex.javaClass.simpleName,
                    (vertex as? ValueVertex)?.dims.orEmpty(),
                )
            }
        }
        fun sections(section: SectionItem) {
            declare(section.id, LanguageSymbolKind.SECTION, section.location, "Section")
            section.children.filterIsInstance<SectionItem>().forEach(::sections)
        }
        plan.schema.root.children.filterIsInstance<SectionItem>().forEach(::sections)
        (plan.schema.functions + plan.case.functions).forEach {
            declare(it.name, LanguageSymbolKind.FUNCTION, it.location, "Named function")
        }
    }

    private fun declare(
        name: String,
        kind: LanguageSymbolKind,
        location: SourceLocation,
        detail: String,
        dims: List<String> = emptyList(),
    ) {
        val id = LanguageSymbolId(
            plan.schema.identity,
            kind,
            name,
            ownerSource = location.source.takeIf { it == plan.case.source },
        )
        if (kind == LanguageSymbolKind.FUNCTION) functions[name] = id else symbols[name] = id
        val document = documents[location.source]?.second
        val form = document?.root?.allForms()?.filterIsInstance<DslForm.Sequence>()?.firstOrNull {
            it.span.startOffset == location.startOffset && it.span.endOffset == location.endOffset
        }
        val token = form?.values?.getOrNull(1)?.takeIf { tokenName(it) == name }
        val span = token?.let { nameSpan(location.source, it, name) }
        if (span == null) {
            issues += LanguageIssue("Declaration token unavailable for $name", location.source)
        } else {
            definitions += LanguageDefinition(id, name, span, detail, frozen(dims))
        }
    }

    /** Visit host positions only. Formula spans and literal data do not enter this index. */
    fun hostReferences(formulas: List<LanguageSpan>) {
        documents.values.forEach { (context, document) ->
            if (document == null) {
                issues += LanguageIssue("Current document has no accepting form tree", context.source.name)
                return@forEach
            }
            val ranges = formulas.filter { it.source == context.source.name }
            fun use(
                form: DslForm,
                expected: Set<LanguageSymbolKind>,
                owner: SchemaIdentity = context.schema,
                sourceOwner: String? = null,
            ) {
                val name = tokenName(form) ?: return
                val own = symbols[name]?.takeIf { it.kind in expected }
                val symbol = if (owner == plan.schema.identity && sourceOwner == null) {
                    own
                } else {
                    // A source mapping is projected under its exact foreign owner. Its declaration is
                    // joined by the workspace's separately compiled source schema analysis.
                    LanguageSymbolId(owner, LanguageSymbolKind.NODE, name, ownerSource = sourceOwner)
                }
                if (symbol == null) {
                    issues += LanguageIssue("Unresolved host reference $name", context.source.name)
                    return
                }
                nameSpan(context.source.name, form, name)?.let {
                    occurrences += LanguageOccurrence(symbol, it, LanguageUseKind.HOST)
                } ?: run { issues += LanguageIssue("Unsupported address representation", context.source.name) }
            }
            fun mapOptions(form: DslForm, keys: Set<String>, expected: Set<LanguageSymbolKind>) {
                val map = form as? DslForm.Sequence ?: return
                if (map.kind != DslFormSequenceKind.MAP) return
                map.values.chunked(2).filter { it.size == 2 }.forEach { (key, value) ->
                    if (key.keyword !in keys) return@forEach
                    if (value is DslForm.Sequence && value.kind == DslFormSequenceKind.VECTOR) {
                        value.values.forEach { use(it, expected) }
                    } else {
                        use(value, expected)
                    }
                }
            }
            fun link(record: DslForm) {
                val map = record as? DslForm.Sequence ?: return
                val options = map.values.chunked(2).filter { it.size == 2 }.associate { it[0].keyword to it[1] }
                val path = options["path"]?.string
                val owner = path?.let(context.linkSchemas::get)
                val mappings = options["mappings"] as? DslForm.Sequence ?: return
                mappings.values.forEach { mapping ->
                    val fields = (mapping as? DslForm.Sequence)?.values?.chunked(2)
                        ?.filter { it.size == 2 }?.associate { it[0].keyword to it[1] }.orEmpty()
                    listOf("from" to "node", "to" to "input").forEach { (side, key) ->
                        val address = fields[side] as? DslForm.Sequence ?: return@forEach
                        val target = address.values.chunked(2).firstOrNull { it.size == 2 && it[0].keyword == key }
                            ?.get(1) ?: return@forEach
                        if (side == "from" && (owner == null || path?.let(context.linkOwners::get) == null)) {
                            issues +=
                                LanguageIssue("Pinned link source schema/case owner is unresolved", context.source.name)
                        } else {
                            use(
                                target,
                                VALUES,
                                if (side == "from") owner!! else context.schema,
                                if (side == "from") path?.let(context.linkOwners::get) else null,
                            )
                        }
                    }
                }
            }
            fun walk(form: DslForm) {
                if (ranges.any { form.span.startOffset >= it.start && form.span.endOffset <= it.end }) return
                val list = form as? DslForm.Sequence ?: return
                if (list.kind != DslFormSequenceKind.LIST) return
                when (list.listHead) {
                    "inputs", "params", "values" -> {
                        val expected = if (list.listHead == "inputs") {
                            setOf(LanguageSymbolKind.INPUT)
                        } else {
                            setOf(LanguageSymbolKind.PARAMETER)
                        }
                        // Input values include (rows ...) table literals. Only their owning input
                        // keys are host references; headers and cells are data, never root uses.
                        list.values.drop(1).filterIsInstance<DslForm.Sequence>().forEach { map ->
                            map.values.chunked(2).filter { it.size == 2 }.forEach { use(it[0], expected) }
                        }
                        return
                    }
                    "bind" -> {
                        list.values.getOrNull(1)?.let { use(it, setOf(LanguageSymbolKind.NODE)) }
                        return
                    }
                    "extend", "table", "schedule", "inline", "hide" -> {
                        val targets = if (list.listHead in setOf("schedule", "inline", "hide")) {
                            list.values.drop(1)
                        } else {
                            list.values.drop(1).take(1)
                        }
                        targets.forEach { use(it, VALUES + LanguageSymbolKind.SECTION) }
                    }
                    "field", "node" -> list.values.getOrNull(1)?.let { use(it, VALUES) }
                    "value" -> list.values.getOrNull(1)?.let { use(it, setOf(LanguageSymbolKind.PARAMETER)) }
                    "members", "member" -> list.values.getOrNull(1)?.let {
                        use(it, setOf(LanguageSymbolKind.DIMENSION))
                    }
                    "links" -> {
                        list.values.drop(1).forEach(::link)
                        return
                    }
                    "sources" -> {
                        list.values.drop(1).filterIsInstance<DslForm.Sequence>().forEach { source ->
                            source.values.getOrNull(1)?.let { options ->
                                mapOptions(options, setOf("input"), setOf(LanguageSymbolKind.INPUT))
                            }
                        }
                        return
                    }
                    "defn" -> return
                }
                list.values.filterIsInstance<DslForm.Sequence>().filter { it.kind == DslFormSequenceKind.MAP }
                    .forEach { map ->
                        mapOptions(map, setOf("per", "parent", "row-dimension"), setOf(LanguageSymbolKind.DIMENSION))
                        mapOptions(map, setOf("headline", "numerator", "denominator", "from"), VALUES)
                        mapOptions(map, setOf("section", "mainline"), setOf(LanguageSymbolKind.SECTION))
                        map.values.chunked(2).filter { it.size == 2 }.forEach { (key, value) ->
                            when (key.keyword) {
                                "aggregate" -> {
                                    mapOptions(value, setOf("first", "last"), setOf(LanguageSymbolKind.DIMENSION))
                                    mapOptions(value, setOf("ratio"), VALUES)
                                }
                                "references" -> (value as? DslForm.Sequence)?.values?.chunked(2)
                                    ?.filter {
                                        it.size == 2
                                    }?.forEach { use(it[1], setOf(LanguageSymbolKind.DIMENSION)) }
                                "fixed" -> (value as? DslForm.Sequence)?.values?.chunked(2)
                                    ?.filter {
                                        it.size == 2
                                    }?.forEach { use(it[0], setOf(LanguageSymbolKind.DIMENSION)) }
                                "uses" -> (value as? DslForm.Sequence)?.values?.forEach { use(it, VALUES) }
                            }
                        }
                    }
                // Only recurse into host lists. Never interpret tables, maps or quoted values as code.
                list.values.filterIsInstance<DslForm.Sequence>().filter { it.kind == DslFormSequenceKind.LIST }
                    .forEach(::walk)
            }
            walk(document.root)
        }
    }

    companion object {
        private val VALUES = setOf(LanguageSymbolKind.NODE, LanguageSymbolKind.INPUT, LanguageSymbolKind.PARAMETER)
    }
}

internal fun tokenName(form: DslForm): String? = form.symbol ?: form.keyword ?: form.string

/** Exact token component, retaining keyword colons and quoted address delimiters in the source. */
internal fun nameSpan(source: String, form: DslForm, name: String): LanguageSpan? {
    if (form is DslForm.Atom) {
        val raw = form.sourceText
        if (form.string == name && raw.startsWith('"') && raw.endsWith('"')) {
            return LanguageSpan(source, form.span.startOffset + 1, form.span.endOffset - 1)
        }
        val prefix = when {
            raw == name -> 0
            raw == ":$name" -> 1
            raw == "\"$name\"" -> 1
            raw == "mantra/$name" || raw == "mantra_$name" -> 7
            else -> return null
        }
        return LanguageSpan(source, form.span.startOffset + prefix, form.span.startOffset + prefix + name.length)
    }
    if (form is DslForm.Postfix) {
        val member = form.suffixes.filterIsInstance<DslFormPostfix.Member>()
            .firstOrNull { it.value.removePrefix(".") == name }
        if (member != null) {
            return LanguageSpan(
                source,
                member.span.startOffset + if (member.sourceText.startsWith(".")) 1 else 0,
                member.span.endOffset,
            )
        }
        val bracket = form.suffixes.filterIsInstance<DslFormPostfix.Bracket>()
            .firstOrNull { tokenName(it.key) == name }
        return bracket?.let { nameSpan(source, it.key, name) }
    }
    return null
}

internal fun DslForm.allForms(): List<DslForm> = buildList {
    fun visit(form: DslForm) {
        add(form)
        when (form) {
            is DslForm.Atom -> Unit
            is DslForm.Sequence -> form.values.forEach(::visit)
            is DslForm.Postfix -> {
                visit(form.target)
                form.suffixes.filterIsInstance<DslFormPostfix.Bracket>().forEach { visit(it.key) }
            }
        }
    }
    visit(this@allForms)
}

/** Completion candidates derived from real compiler-inferred types at an authored lexical use. */
internal data class LocalCompletionScope(val span: LanguageSpan, val candidates: List<LocalCompletionCandidate>)
internal data class LocalCompletionCandidate(val label: String, val kind: String, val detail: String)
