package com.xqiou.mantra.workbench.packages

import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseExplainAddress
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.CaseRunResult
import com.xqiou.mantra.core.api.RunFailureKind
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.excel.ExcelExportLimitException
import com.xqiou.mantra.excel.ExcelOptions
import com.xqiou.mantra.packages.CaseMigrationEditor
import com.xqiou.mantra.packages.MigrationCoordinator
import com.xqiou.mantra.packages.MigrationEvaluation
import com.xqiou.mantra.packages.MigrationOperation
import com.xqiou.mantra.packages.MigrationPlan
import com.xqiou.mantra.packages.MigrationPreview
import com.xqiou.mantra.packages.MigrationRuntime
import com.xqiou.mantra.packages.MigrationState
import com.xqiou.mantra.packages.MigrationStore
import com.xqiou.mantra.packages.MigrationTarget
import com.xqiou.mantra.packages.PackageCatalog
import com.xqiou.mantra.packages.PackageException
import com.xqiou.mantra.packages.PackageParameterChoice
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.html.HtmlRenderer
import com.xqiou.mantra.render.pdf.PdfOptions
import com.xqiou.mantra.render.pdf.PdfRenderException
import com.xqiou.mantra.render.pdf.PdfRenderer
import com.xqiou.mantra.render.text.TextRenderer
import com.xqiou.mantra.workbench.CaseTextEditor
import com.xqiou.mantra.workbench.DiagnosticSources
import com.xqiou.mantra.workbench.EditorValueParser
import com.xqiou.mantra.workbench.ExportBudget
import com.xqiou.mantra.workbench.ExportDocuments
import com.xqiou.mantra.workbench.PaperExportLimits
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson

/** Explicit mounts are authority. A manifest dependency or a filename never mounts another package. */
data class PackageMount(val name: String, val snapshot: PackageSnapshot)

/** An independently writable host capability. It never comes from the package loader. */
data class EditablePackageCase(
    val case: String,
    val store: MigrationStore,
    val storePath: String,
    val maxBytes: Int = 65_536,
) {
    init {
        require(maxBytes in 1..1_048_576)
    }
}

class PackageWorkspaceCatalog(
    mounts: List<PackageMount>,
    policies: Map<String, PackageParameterChoice> = emptyMap(),
    editable: List<EditablePackageCase> = emptyList(),
    private val options: CalculationOptions = CalculationOptions(),
    private val mantraVersion: String = com.xqiou.mantra.core.api.RuntimeVersions.mantra,
    private val normeinVersion: String = com.xqiou.mantra.core.api.RuntimeVersions.normein,
) {
    private val mounts = java.util.List.copyOf(mounts)
    private val catalog = PackageCatalog()
    private val policies = policies.mapValues { (_, it) ->
        it.copy(
            candidateIds = java.util.List.copyOf(it.candidateIds),
            requiredKeys = java.util.Set.copyOf(it.requiredKeys),
        )
    }
    private val editable = editable.associateBy { it.case }
    private val previews = PackageMigrationWorkflow()
    private data class History(val undo: ArrayDeque<String> = ArrayDeque(), val redo: ArrayDeque<String> = ArrayDeque())
    private val history = mutableMapOf<String, History>()

    init {
        require(mounts.isNotEmpty())
        require(editable.map { it.case }.distinct().size == editable.size)
        mounts.forEach { catalog.register(it.name, it.snapshot) }
        (this.policies.keys + this.editable.keys).forEach { require(catalog.resolveCase(it).canonicalPath == it) }
    }

    class Execution internal constructor(
        val graph: CaseRunResult,
        internal val bindings: Map<CanonicalCaseKey, PackageHostResolver.Binding>,
    ) {
        val revision: String? get() = graph.cases[graph.root]?.revision
        val layout get() = graph.root?.let { bindings[it]?.layout }
            ?: graph.result?.let { Render.defaultLayout(it.view) }
    }

    /** A fresh owner-confined runner per request. Captured resources never change during this host lifetime. */
    @Synchronized
    fun evaluate(case: String, audit: Boolean = true, explain: CaseExplainAddress? = null): Execution =
        run(case, emptyMap(), audit, explain)

    private fun run(
        case: String,
        candidates: Map<String, SourceText>,
        audit: Boolean = true,
        explain: CaseExplainAddress? = null,
        comparisonChoices: Map<String, PackageParameterChoice> = emptyMap(),
    ): Execution {
        require(catalog.resolveCase(case).canonicalPath == case) { "A canonical mounted case key is required" }
        val resolver = PackageHostResolver(catalog, policies, editable, candidates, comparisonChoices)
        val graph = CaseGraphRunner(resolver).use {
            it.run(
                CaseRunRequest(
                    CaseReference(case),
                    options,
                    if (audit) AuditOptions() else null,
                    explain,
                ),
            )
        }
        return Execution(graph, resolver.bindings.toMap())
    }

    @Synchronized
    fun workspace(): Map<String, Any?> = envelope(
        hostFingerprint(mounts.map { it.snapshot.revision }),
        linkedMapOf(
            "packages" to mounts.map { mount ->
                val snapshot = mount.snapshot
                linkedMapOf(
                    "mount" to mount.name, "id" to snapshot.manifest.identity.id,
                    "version" to snapshot.manifest.identity.version.text, "revision" to snapshot.revision,
                    "readOnly" to true, "resourceCount" to snapshot.manifest.resources.size,
                    "caseCount" to snapshot.manifest.cases.size, "schemaCount" to snapshot.manifest.schemas.size,
                    "parameterSetCount" to snapshot.manifest.parameters.size,
                    "layoutCount" to snapshot.manifest.layouts.size,
                    "capturedBytes" to snapshot.totalByteLength.toString(),
                    "parameters" to snapshot.manifest.parameters.map { parameter ->
                        mapOf(
                            "id" to "${mount.name}/${parameter.path}",
                            "title" to parameter.id,
                            "schema" to parameter.schema.identity.id,
                            "schemaVersion" to parameter.schema.identity.version,
                        )
                    },
                    "cases" to snapshot.manifest.cases.map { case ->
                        val key = "${mount.name}/${case.path}"
                        linkedMapOf(
                            "id" to key,
                            "caseId" to case.id,
                            "schema" to case.schema.identity.id,
                            "schemaVersion" to case.schema.identity.version,
                            "editable" to (key in editable),
                        )
                    },
                )
            },
        ),
    )

    @Synchronized
    fun document(case: String, name: String, panel: String? = null): Map<String, Any?> =
        document(case, name, panel, false)

    @Synchronized
    fun document(case: String, name: String, panel: String? = null, includeZero: Boolean): Map<String, Any?> {
        val execution = evaluate(case, audit = name == "paper")
        val graph = execution.graph
        val view = graph.result?.view
        val layout = execution.layout
        val document = if (view == null || layout == null) {
            null
        } else {
            when (name) {
                "run" -> WorkbenchDocuments.run(view, layout, graph)
                "structure" -> WorkbenchDocuments.structure(view)
                "paper" -> WorkbenchDocuments.paper(view, layout, panel, includeZero)
                "parameters" -> WorkbenchDocuments.parameters(view)
                "diagnostics" -> WorkbenchDocuments.diagnostics(ownedDiagnostics(graph))
                "sources" -> mapOf(
                    "sources" to view.case.sources.mapIndexed { index, source ->
                        mapOf(
                            "index" to index,
                            "kind" to source.kind,
                            "path" to (source.options["path"] as? com.xqiou.mantra.core.model.Value.Text)?.value,
                            "options" to source.options.mapValues { WorkbenchJson.value(it.value) },
                            "overridden" to emptyList<String>(),
                        )
                    },
                )
                else -> throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Package document was not found")
            }
        }
        val key = graph.root ?: CanonicalCaseKey(case)
        val binding = execution.bindings[key]
        return envelope(
            execution.revision,
            linkedMapOf(
                "case" to key.value,
                "succeeded" to graph.succeeded,
                "validationPassed" to graph.validationPassed,
                "binding" to binding?.let(PackageHostDocuments::binding),
                "parameterSources" to PackageHostDocuments.parameters(execution),
                "document" to
                    document?.let {
                        WorkbenchJson.envelope(requireNotNull(execution.revision), mantraVersion, normeinVersion, it)
                    },
                "diagnostics" to ownedDiagnostics(graph).map(WorkbenchDocuments::diagnostic),
            ),
        )
    }

    @Synchronized
    fun compare(
        case: String,
        variantParameters: List<String>,
        effectiveDate: java.time.LocalDate,
        expectedRevision: String,
    ): Map<String, Any?> {
        require(Regex("[0-9a-f]{64}").matches(expectedRevision)) { "An exact baseline revision is required" }
        require(variantParameters.size in 1..8 && variantParameters.distinct().size == variantParameters.size) {
            "Choose between one and eight distinct captured parameter resources"
        }
        val base = evaluate(case, audit = false)
        requireSuccess(base)
        checkRevision(expectedRevision, checkNotNull(base.revision))
        val key = checkNotNull(base.graph.root)
        val binding = checkNotNull(base.bindings[key])
        val resourceCase = binding.resourceCase
        val mount = resourceCase.canonicalPath.removeSuffix("/${resourceCase.entry.path}")
        val entries = resourceCase.snapshot.manifest.parameters.filter { it.schema == resourceCase.entry.schema }
            .associateBy { "$mount/${it.path}" }
        val selected = variantParameters.map { id ->
            requireNotNull(entries[id]) { "Parameter resource does not belong to the selected mount and exact schema" }
        }
        val requiredKeys = selected.flatMap { resourceCase.snapshot.parameters(it.id).values.keys }.toSet()
        val choice = PackageParameterChoice(
            effectiveDate,
            com.xqiou.mantra.packages.ParameterSelectionMode.WHAT_IF,
            selected.map { it.id },
            requiredKeys,
        )
        // Freeze every participating host source so both sides use the same facts, even during external edits.
        val candidates = base.bindings.filterValues {
            it.editable
        }.mapKeys { it.key.value }.mapValues { it.value.source }
        val variant = run(case, candidates, audit = false, comparisonChoices = mapOf(case to choice))
        requireSuccess(variant)
        val revision = hostFingerprint(listOf(base.revision, variant.revision))
        val document = WorkbenchDocuments.compare(
            checkNotNull(base.graph.result).view,
            checkNotNull(variant.graph.result).view,
            checkNotNull(base.layout),
            variantParameters,
        )
        return envelope(
            revision,
            mapOf(
                "case" to case,
                "succeeded" to true,
                "validationPassed" to (base.graph.validationPassed && variant.graph.validationPassed),
                "binding" to PackageHostDocuments.binding(checkNotNull(variant.bindings[key])),
                "parameterSources" to PackageHostDocuments.parameters(variant),
                "document" to WorkbenchJson.envelope(revision, mantraVersion, normeinVersion, document),
                "diagnostics" to ownedDiagnostics(variant.graph).map(WorkbenchDocuments::diagnostic),
            ),
        )
    }

    @Synchronized
    fun sourceContext(case: String, diagnostic: Int, expectedRevision: String): Map<String, Any?> {
        DiagnosticSources.checkRequest(diagnostic, expectedRevision)
        val execution = evaluate(case, audit = false)
        val revision = execution.revision ?: throw WorkspaceException(
            WorkspaceProblem.INVALID,
            "Diagnostic case could not be resolved",
            ownedDiagnostics(execution.graph),
        )
        DiagnosticSources.checkRevision(expectedRevision, revision)
        val finding = ownedDiagnostics(execution.graph).getOrNull(diagnostic)
            ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic was not found")
        val location = finding.location
            ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic has no source location")
        val key = finding.caseKey?.let(::CanonicalCaseKey) ?: checkNotNull(execution.graph.root)
        val sourceCase = execution.graph.cases[key]
            ?: throw WorkspaceException(
                WorkspaceProblem.NOT_FOUND,
                "Diagnostic case does not participate in this graph",
            )
        finding.caseRevision?.let { DiagnosticSources.checkRevision(it, sourceCase.revision) }
        val binding = execution.bindings[key]
            ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Diagnostic source is unavailable")
        val data = DiagnosticSources.excerpt(
            binding.diagnosticSource(key.value, location),
            location,
            key.value,
            sourceCase.revision,
        )
        return envelope(
            revision,
            mapOf(
                "case" to case,
                "succeeded" to execution.graph.succeeded,
                "document" to WorkbenchJson.envelope(revision, mantraVersion, normeinVersion, data),
            ),
        )
    }

    @Synchronized
    fun explain(
        case: String,
        node: String,
        coord: List<String>,
        sourceCase: String? = null,
        expectedRevision: String? = null,
    ): Map<String, Any?> {
        val target = sourceCase?.let(::CanonicalCaseKey)
        val execution =
            evaluate(case, explain = CaseExplainAddress(target, InputAddress(node, coord), expectedRevision))
        val graph = execution.graph
        val requested = target ?: graph.root
        val result = requested?.let { graph.cases[it] }
        val layout = requested?.let { execution.bindings[it]?.layout } ?: result?.let { Render.defaultLayout(it.view) }
        val data = if (graph.succeeded && result != null && layout != null) {
            WorkbenchDocuments.explain(result.view, layout, node, coord, graph.explain)
        } else {
            null
        }
        return envelope(
            execution.revision,
            mapOf(
                "case" to case,
                "succeeded" to graph.succeeded,
                "document" to data?.let {
                    WorkbenchJson.envelope(result!!.revision, mantraVersion, normeinVersion, it)
                },
                "diagnostics" to ownedDiagnostics(graph).map(WorkbenchDocuments::diagnostic),
            ),
        )
    }

    @Synchronized
    fun export(case: String, format: String): ByteArray = export(case, format, ExportBudget())

    /** Per-request host ceilings; existing constructor and export ABI remain intact. */
    @Synchronized
    fun export(case: String, format: String, budget: ExportBudget): ByteArray = exportLimits {
        if (format !in setOf("xlsx", "html", "text", "pdf")) {
            throw WorkspaceException(WorkspaceProblem.REQUEST, "Expected xlsx, html, text or pdf")
        }
        val execution = evaluate(case)
        requireSuccess(execution)
        val view = requireNotNull(execution.graph.result).view
        val layout = requireNotNull(execution.layout)
        if (format == "xlsx") {
            ExcelExport.workbook(view, layout, excelOptions(budget)).use { it.bytes(budget.maxBytes) }
        } else {
            view.openReader(options).use { reader ->
                val paper = Render.paper(view, layout, reader)
                PaperExportLimits.checkPaper(paper, budget, reader, html = format == "html")
                val bytes = when (format) {
                    "html" -> PaperExportLimits.boundedUtf8(HtmlRenderer.render(paper), budget.maxBytes)
                    "text" -> {
                        PaperExportLimits.checkTextExpansion(paper, budget, reader)
                        PaperExportLimits.boundedUtf8(TextRenderer.render(paper, includeAudit = false), budget.maxBytes)
                    }
                    else -> PdfRenderer.render(
                        paper,
                        PdfOptions(
                            maxPages = budget.maxSheets,
                            maxRows = budget.maxCells,
                            maxOutputBytes = budget.maxBytes,
                        ),
                    )
                }
                reader.checkpoint()
                bytes
            }
        }
    }

    /** Structured editing cannot alter resources, source/link identity or parameter policy. */
    @Synchronized
    fun edit(
        case: String,
        baseRevision: String,
        operations: List<CaseTextEditor.Operation>,
        previewOnly: Boolean = false,
    ): Map<String, Any?> {
        require(operations.size in 1..100)
        require(
            operations.none {
                it is CaseTextEditor.Operation.SetBindings || it is CaseTextEditor.Operation.AddSource ||
                    it is CaseTextEditor.Operation.RemoveSource
            },
        ) {
            "Resource binding changes require explicit migration"
        }
        val store = writable(case)
        val base = currentState(case)
        checkRevision(baseRevision, base.graphRevision)
        val original = store.read(case)
        val authored = com.xqiou.mantra.core.Mantra.loadCase(original)
        val linked = authored.links.flatMap { it.mappings }.map { it.to }
        operations.forEach { operation ->
            val address = when (operation) {
                is CaseTextEditor.Operation.SetInput -> InputAddress(operation.id, operation.coord)
                is CaseTextEditor.Operation.ClearInput -> InputAddress(operation.id, operation.coord)
                else -> null
            }
            require(address == null || address !in linked) { "A linked input is source-owned" }
        }
        val candidate = CaseTextEditor.apply(original.text, operations)
        var capturedAfter: Execution? = null
        val preview = exactPreview(case, base, original, candidate) { capturedAfter = it }
        if (!previewOnly) {
            coordinator(case) { capturedAfter = it }.apply(preview, preview.reviewToken)
            remember(case, original.text)
        }
        val afterRun = requireNotNull(capturedAfter)
        val after = requireNotNull(afterRun.graph.result)
        val before = requireNotNull(preview.before.result)
        val layout = requireNotNull(afterRun.layout)
        val data = mapOf(
            "document" to case,
            "preview" to previewOnly,
            "proposedRevision" to preview.after.graphRevision,
            "diagnostics" to preview.after.diagnostics.map(WorkbenchDocuments::diagnostic),
            "run" to WorkbenchDocuments.run(after.view, layout, afterRun.graph),
            "difference" to WorkbenchDocuments.compare(before.view, after.view, layout),
        )
        return envelope(
            if (previewOnly) baseRevision else preview.after.graphRevision,
            mapOf(
                "case" to case,
                "succeeded" to true,
                "document" to WorkbenchJson.envelope(
                    if (previewOnly) baseRevision else preview.after.graphRevision,
                    mantraVersion,
                    normeinVersion,
                    data,
                ),
            ),
        )
    }

    /** Returns before/after public results and exact source text; this entry never writes. */
    @Synchronized
    fun previewMigration(case: String, baseRevision: String, targetCase: String): Map<String, Any?> {
        writable(case)
        val before = currentState(case)
        checkRevision(baseRevision, before.graphRevision)
        val target = catalog.resolveCase(targetCase)
        val choice = policies[target.canonicalPath]
        val targetSelection = selection(target, choice)
        val destination =
            MigrationTarget(
                target.entry.schema,
                target.snapshot.manifest.identity,
                target.snapshot.revision,
                targetSelection?.revision,
            )
        val store = writable(case)
        val original = store.read(case)
        val operations = listOf(
            MigrationOperation.PinSchema(destination.schema),
            MigrationOperation.BindParameters(choice?.candidateIds ?: target.entry.parameters),
            MigrationOperation.BindLayout(target.entry.layout),
        )
        val bound = CaseMigrationEditor.apply(original, operations)
        val candidate = PackageHostPins.write(
            bound,
            PackageHostPins.Pin(target.canonicalPath, target.snapshot.revision, choice),
        )
        val patch = MigrationOperation.ReplaceText(0, bound.text, candidate.text)
        var beforeCapture: Execution? = null
        var afterCapture: Execution? = null
        val plan = MigrationPlan(case, baseRevision, before.schema, destination, operations + patch)
        val preview = coordinator(case, { afterCapture = it }, { beforeCapture = it }).preview(plan)
        previews.retain(plan, preview.reviewToken)
        return envelope(baseRevision, PackageHostDocuments.preview(preview, beforeCapture?.graph, afterCapture?.graph))
    }

    /** Review token, source CAS and full graph revision all have to remain current. */
    @Synchronized
    fun applyMigration(case: String, token: String): Map<String, Any?> {
        val plan = previews.plan(case, token)
        // Rebuild evidence under the original intent/revisions. Never adopt a new graph silently.
        val workflow = coordinator(case)
        val preview = try {
            workflow.preview(plan)
        } catch (error: PackageException) {
            if (error.diagnostic.code != "MANTRA-MIGRATION-STALE") throw error
            throw WorkspaceException(WorkspaceProblem.CONFLICT, error.message.orEmpty(), error.diagnostics)
        }
        if (preview.reviewToken != token) {
            throw WorkspaceException(
                WorkspaceProblem.CONFLICT,
                "Source graph or target resources changed after the reviewed migration preview",
            )
        }
        workflow.apply(preview, token)
        previews.remove(token)
        remember(case, preview.original.text)
        return document(case, "run")
    }

    @Synchronized
    fun restore(case: String, baseRevision: String, undo: Boolean): Map<String, Any?> {
        val versions = history.getOrPut(case, ::History)
        val from = if (undo) versions.undo else versions.redo
        val to = if (undo) versions.redo else versions.undo
        require(from.isNotEmpty()) { "No ${if (undo) "undo" else "redo"} version" }
        val before = currentState(case)
        checkRevision(baseRevision, before.graphRevision)
        val original = writable(case).read(case)
        val preview = exactPreview(case, before, original, from.last())
        coordinator(case).apply(preview, preview.reviewToken)
        from.removeLast()
        to.addLast(original.text)
        while (to.size > 50) to.removeFirst()
        return document(case, "run")
    }

    @Synchronized
    fun parseEditText(
        case: String,
        id: String,
        parameter: Boolean,
        text: String,
        column: String? = null,
    ): com.xqiou.mantra.core.model.Value {
        val execution = evaluate(case, audit = false)
        val view =
            execution.graph.result?.view
                ?: throw WorkspaceException(
                    WorkspaceProblem.INVALID,
                    "Case cannot be parsed for editing",
                    ownedDiagnostics(execution.graph),
                )
        return EditorValueParser.parse(view, requireNotNull(execution.layout), id, parameter, text, column)
    }

    @Synchronized
    fun tableKeyColumn(case: String, table: String): String? {
        val view = requireNotNull(evaluate(case, audit = false).graph.result).view
        require(view.nodes[table]?.input?.type == com.xqiou.mantra.core.model.ValueType.TABLE)
        return view.dimensions.values.firstOrNull { it.fromTable == table }?.keyColumn
    }

    @Synchronized
    fun parseEditRowText(
        case: String,
        table: String,
        columns: Map<String, String>,
    ): com.xqiou.mantra.core.model.Value.MapV = com.xqiou.mantra.core.model.Value.MapV(
        columns.map { (column, text) ->
            com.xqiou.mantra.core.model.Value.Kw(column) to parseEditText(case, table, false, text, column)
        }.toMap(),
    )

    @Synchronized
    fun exportPreview(case: String, sheet: String? = null): Map<String, Any?> =
        exportPreview(case, sheet, ExportBudget())

    @Synchronized
    fun exportPreview(case: String, sheet: String?, budget: ExportBudget): Map<String, Any?> = exportLimits {
        val execution = evaluate(case)
        requireSuccess(execution)
        val preview = ExcelExport.workbook(
            requireNotNull(execution.graph.result).view,
            requireNotNull(execution.layout),
            excelOptions(budget),
        ).use {
            // Preview generation must honor the same serialized byte ceiling as downloads.
            it.bytes(budget.maxBytes)
            it.describe(sheet)?.let(ExportDocuments::preview)
                ?: throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Worksheet was not found")
        }
        envelope(
            execution.revision,
            mapOf(
                "case" to case,
                "succeeded" to true,
                "document" to
                    WorkbenchJson.envelope(requireNotNull(execution.revision), mantraVersion, normeinVersion, preview),
            ),
        )
    }

    private fun excelOptions(budget: ExportBudget) = ExcelOptions(
        maxSheets = budget.maxSheets,
        maxCells = budget.maxCells,
        reading = options,
    )

    private fun <T> exportLimits(action: () -> T): T = try {
        action()
    } catch (error: ExcelExportLimitException) {
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, error.message.orEmpty())
    } catch (error: PdfRenderException) {
        if (error.code != "MANTRA-PDF-LIMIT") throw error
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, error.message.orEmpty())
    } catch (error: MantraException) {
        if (error.runFailure?.kind != RunFailureKind.LIMIT) throw error
        throw WorkspaceException(WorkspaceProblem.TOO_LARGE, error.message.orEmpty(), error.diagnostics)
    }

    private fun exactPreview(
        case: String,
        base: MigrationState,
        original: SourceText,
        text: String,
        capture: (Execution) -> Unit = {
        },
    ): MigrationPreview {
        val candidate = SourceText(case, text, case)
        val execution = run(case, mapOf(case to candidate))
        requireSuccess(execution)
        val target = target(execution, case)
        return coordinator(case, capture).preview(
            MigrationPlan(
                case,
                base.graphRevision,
                base.schema,
                target,
                listOf(MigrationOperation.ReplaceText(0, original.text, text)),
            ),
        )
    }

    private fun remember(case: String, source: String) {
        val versions = history.getOrPut(case, ::History)
        versions.undo.addLast(source)
        while (versions.undo.size > 50) versions.undo.removeFirst()
        versions.redo.clear()
    }

    private fun target(execution: Execution, case: String): MigrationTarget {
        val binding = execution.bindings[CanonicalCaseKey(case)] ?: error("Target binding could not be loaded")
        return MigrationTarget(
            binding.resourceCase.entry.schema,
            binding.resourceCase.snapshot.manifest.identity,
            binding.resourceCase.snapshot.revision,
            binding.selection?.revision,
        )
    }

    private fun currentState(case: String, capture: (Execution) -> Unit = {}): MigrationState {
        val execution = run(case, emptyMap())
        capture(execution)
        val binding = execution.bindings[CanonicalCaseKey(case)] ?: throw WorkspaceException(
            WorkspaceProblem.INVALID,
            "Current package case failed to resolve",
            ownedDiagnostics(execution.graph),
        )
        return MigrationState(
            binding.resourceCase.entry.schema,
            requireNotNull(execution.revision),
            hostHash(binding.source.text.toByteArray(Charsets.UTF_8)),
            execution.graph.result,
            ownedDiagnostics(execution.graph),
        )
    }

    private fun coordinator(
        case: String,
        capture: (Execution) -> Unit = {
        },
        captureCurrent: (Execution) -> Unit = {},
    ): MigrationCoordinator = MigrationCoordinator(
        writable(case),
        object : MigrationRuntime {
            override fun current(casePath: String) = currentState(casePath, captureCurrent)
            override fun evaluate(
                casePath: String,
                candidate: SourceText,
                target: MigrationTarget,
            ): MigrationEvaluation {
                val execution = run(casePath, mapOf(casePath to candidate))
                capture(execution)
                val actual =
                    execution.bindings[CanonicalCaseKey(casePath)]?.let {
                        this@PackageWorkspaceCatalog.target(execution, casePath)
                    }
                        ?: target
                return MigrationEvaluation(
                    actual,
                    execution.revision ?: "technical-failure",
                    execution.graph.result,
                    ownedDiagnostics(execution.graph),
                )
            }
            override fun commitIfCurrent(
                casePath: String,
                graphRevision: String,
                sourceSha256: String,
                commit: () -> Unit,
            ) {
                // Called under this catalog monitor; source-byte CAS additionally covers other writers.
                val live = currentState(casePath)
                checkRevision(graphRevision, live.graphRevision)
                require(live.sourceSha256 == sourceSha256) { "Source bytes changed after preview" }
                commit()
            }
        },
    )

    private fun writable(case: String): MigrationStore {
        val access =
            editable[case]
                ?: throw WorkspaceException(
                    WorkspaceProblem.REQUEST,
                    "Mounted package resources are read-only; register a separate writable host case",
                )
        return object : MigrationStore {
            override fun read(casePath: String): SourceText {
                require(casePath == case)
                return access.store.read(access.storePath).let { SourceText(case, it.text, case) }
            }
            override fun commit(
                casePath: String,
                sourceSha256: String,
                candidate: SourceText,
                authorize: (write: () -> Unit) -> Unit,
            ) {
                require(casePath == case)
                require(candidate.text.toByteArray(Charsets.UTF_8).size <= access.maxBytes)
                access.store.commit(access.storePath, sourceSha256, candidate, authorize)
            }
        }
    }

    private fun requireSuccess(execution: Execution) {
        if (execution.graph.failure?.kind == RunFailureKind.LIMIT) {
            throw WorkspaceException(
                WorkspaceProblem.TOO_LARGE,
                "Package calculation exceeded its run limits",
                ownedDiagnostics(execution.graph),
                execution.revision,
            )
        }
        if (!execution.graph.succeeded) {
            throw WorkspaceException(
                WorkspaceProblem.INVALID,
                "Current package calculation failed technically",
                ownedDiagnostics(execution.graph),
                execution.revision,
            )
        }
    }
    private fun checkRevision(expected: String, actual: String) {
        if (expected !=
            actual
        ) {
            throw WorkspaceException(
                WorkspaceProblem.CONFLICT,
                "Base graph revision is stale",
                currentRevision = actual,
            )
        }
    }
    private fun ownedDiagnostics(graph: CaseRunResult) = graph.diagnostics.map {
        it.finding.copy(caseKey = it.case?.value, caseRevision = it.revision)
    }
    private fun envelope(revision: String?, data: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "contract" to "mantra.packages/1",
        "revision" to revision,
        "data" to data,
    )
}
