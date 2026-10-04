package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.api.CalculationSession
import com.xqiou.mantra.core.api.CompiledCalculation
import com.xqiou.mantra.core.api.RunStage
import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.CompiledTemplate
import com.xqiou.mantra.core.engine.Evaluator
import com.xqiou.mantra.core.engine.LiteralInputRows
import com.xqiou.mantra.core.engine.Planner
import com.xqiou.mantra.core.engine.RunBoundary
import com.xqiou.mantra.core.engine.RunContext
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.read.CaseReader
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.read.ParameterSetReader
import com.xqiou.mantra.core.read.SchemaReader
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.snapshot
import java.nio.file.Files
import java.nio.file.Path

/**
 * Public entry point of the calculation kernel.
 *
 * ```kotlin
 * val schema = Mantra.loadSchema(Path.of("schema.mantra"))
 * val case = Mantra.loadCase(Path.of("case.mantra"))
 * val result = Mantra.calculate(schema, case)
 * ```
 */
object Mantra {
    fun loadSchema(path: Path): Schema = loadSchema(FileSources.read(path), FileSources)

    fun loadSchema(source: SourceText, resolver: SourceResolver): Schema {
        val sink = DiagnosticSink()
        val schema = SchemaReader(resolver).read(source, sink)
        sink.throwIfErrors()
        return checkNotNull(schema)
    }

    fun loadCase(path: Path): CaseData = loadCase(FileSources.read(path))

    fun loadCase(source: SourceText): CaseData {
        val sink = DiagnosticSink()
        val case = CaseReader.read(source, sink)
        sink.throwIfErrors()
        return checkNotNull(case)
    }

    fun loadParameters(path: Path): ParameterSet = loadParameters(FileSources.read(path))

    fun loadParameters(source: SourceText): ParameterSet {
        val sink = DiagnosticSink()
        val set = ParameterSetReader.read(source, sink)
        sink.throwIfErrors()
        return checkNotNull(set)
    }

    /**
     * Checks immutable compilation shape once. Input facts and domains are validated per actual
     * case; functions, extensions, formula bindings and parameter types must remain compatible.
     * The returned template may be shared, while each worker owns its own execution sessions.
     */
    fun compile(
        schema: Schema,
        bindings: CaseData = CaseData.empty(),
        parameters: List<ParameterSet> = emptyList(),
        options: CalculationOptions = CalculationOptions(),
    ): CompiledCalculation {
        // The public template receives only detached usage, never this construction context.
        var usage: com.xqiou.mantra.core.api.RunUsage? = null
        val template = RunBoundary.independent(options, { checked, observed ->
            usage = observed
            checked
        }) {
            CompiledTemplate.create(schema, bindings, parameters, it)
        }
        return CompiledCalculation(template, checkNotNull(usage))
    }

    /** Compiles and orders the calculation; throws [MantraException] with all findings on error. */
    internal fun plan(
        schema: Schema,
        case: CaseData = CaseData.empty(),
        parameters: List<ParameterSet> = emptyList(),
    ): CalculationPlan {
        val sink = DiagnosticSink()
        val plan = Planner(sink).plan(schema, case, parameters)
        sink.throwIfErrors()
        return checkNotNull(plan)
    }

    /** Compiles a schema without evaluating it and returns its read-only structure and metadata. */
    fun inspect(
        schema: Schema,
        case: CaseData = CaseData.empty(),
        parameters: List<ParameterSet> = emptyList(),
        options: CalculationOptions = CalculationOptions(),
    ): CalculationView = RunBoundary.independent(options, { view, _ -> view }) {
        inspectBound(schema, case, parameters, it)
    }

    /** Static compilation only; unresolved link values and dynamic domains are execution concerns. */
    internal fun inspectBound(
        schema: Schema,
        case: CaseData,
        parameters: List<ParameterSet>,
        context: RunContext,
    ): CalculationView = context.at(com.xqiou.mantra.core.api.RunStage.PLANNING) {
        val sink = DiagnosticSink()
        val scan = { context.charge(com.xqiou.mantra.core.api.RunCounter.HOST_SCANS) }
        val declared = schema.snapshot(scan)
        val facts = case.snapshot(scan)
        LiteralInputRows.account(declared, facts, context)
        val planned = Planner(sink, context, requireMaterializedLinks = false).plan(declared, facts, parameters)
        sink.throwIfErrors()
        val plan = checkNotNull(planned)
        context.checkpoint()
        CalculationResult(
            plan,
            emptyMap(),
            plan.valueVertices.mapValues { (_, vertex) ->
                context.charge(com.xqiou.mantra.core.api.RunCounter.HOST_SCANS)
                com.xqiou.mantra.core.engine.NodeResult(vertex, emptyMap(), emptyMap(), emptyMap())
            },
            sink.all,
            projectionScan = scan,
        ).view.also { context.checkpoint() }
    }

    /**
     * Evaluates the schema for a case. Parameter values come from the schema, then the given
     * [parameters] sets (later sets win), then the case's own `(params …)`. Evaluation errors do not
     * throw: they are reported in [CalculationResult.diagnostics] so a partial paper can be rendered.
     */
    fun calculate(
        schema: Schema,
        case: CaseData = CaseData.empty(),
        parameters: List<ParameterSet> = emptyList(),
        options: CalculationOptions = CalculationOptions(),
    ): CalculationResult = RunBoundary.independent(options, { result, usage ->
        result.withGraphMetadata(emptyList(), usage)
    }) {
        calculateBound(schema, case, parameters, it)
    }

    /** Opens a reusable VALUE_ONLY runtime; recalculate and close it on this thread after the last edit. */
    fun openSession(
        schema: Schema,
        case: CaseData = CaseData.empty(),
        parameters: List<ParameterSet> = emptyList(),
        options: CalculationOptions = CalculationOptions(),
    ): CalculationSession = RunBoundary.independent(options, { session, usage ->
        session.attachUsage(usage)
    }, { it.close() }) {
        openSessionBound(schema, case, parameters, it)
    }

    internal fun calculate(plan: CalculationPlan): CalculationResult = RunBoundary.independent(
        CalculationOptions(),
        { result, usage -> result.withGraphMetadata(emptyList(), usage) },
    ) { context ->
        Evaluator(plan, DiagnosticSink(), calculationOptions = context.options).use { it.run(context) }
    }

    /**
     * Evaluates once while retaining bounded source-level evidence for paper and workbook audits.
     * The same Normein trace projection backs [calculateForExplain]. Reaching [options] adds a
     * warning and marks incomplete entries; calculated values are unaffected.
     */
    fun calculateForAudit(
        schema: Schema,
        case: CaseData = CaseData.empty(),
        parameters: List<ParameterSet> = emptyList(),
        options: AuditOptions = AuditOptions(),
        calculationOptions: CalculationOptions = CalculationOptions(),
    ): CalculationResult = RunBoundary.independent(
        calculationOptions,
        { result, usage -> result.withGraphMetadata(emptyList(), usage) },
    ) {
        calculateBound(schema, case, parameters, it, options)
    }

    /** Recalculates one case while collecting a bounded FULL trace only for the requested value. */
    fun calculateForExplain(
        schema: Schema,
        case: CaseData,
        parameters: List<ParameterSet>,
        node: String,
        coord: Coord = emptyList(),
        options: CalculationOptions = CalculationOptions(),
    ): CalculationResult = RunBoundary.independent(options, { result, usage ->
        result.withGraphMetadata(emptyList(), usage)
    }) {
        calculateBound(schema, case, parameters, it, explain = InputAddress(node, coord))
    }

    internal fun calculateBound(
        schema: Schema,
        case: CaseData,
        parameters: List<ParameterSet>,
        context: RunContext,
        audit: AuditOptions? = null,
        explain: InputAddress? = null,
    ): CalculationResult = context.at(RunStage.PLANNING) {
        LiteralInputRows.account(schema, case, context)
        val sink = DiagnosticSink()
        val plan = Planner(sink, context).plan(schema, case, parameters)
        sink.throwIfErrors()
        context.checkpoint()
        Evaluator(
            checkNotNull(plan),
            sink,
            explain?.let { it.nodeId to it.coord },
            audit,
            context.options,
        ).use { it.run(context) }
    }

    internal fun openSessionBound(
        schema: Schema,
        case: CaseData,
        parameters: List<ParameterSet>,
        context: RunContext,
    ): CalculationSession = CalculationSession(schema, case, parameters, context)
}

/** Resolves includes relative to the including file on the local file system. */
object FileSources : SourceResolver {
    fun read(path: Path): SourceText {
        val absolute = path.toAbsolutePath().normalize()
        return SourceText(absolute.fileName.toString(), Files.readString(absolute), absolute.parent?.toString())
    }

    override fun resolve(path: String, relativeTo: SourceText?): SourceText? {
        val base = relativeTo?.base?.let(Path::of) ?: Path.of("")
        val target = base.resolve(path).normalize()
        return if (Files.isRegularFile(target)) read(target) else null
    }
}

/** Resolves includes relative to a classpath resource directory. */
class ClasspathSources(private val loader: ClassLoader = ClasspathSources::class.java.classLoader) : SourceResolver {
    fun read(resource: String): SourceText? {
        val normalized = resource.trimStart('/')
        val text =
            loader.getResourceAsStream(normalized)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        return SourceText(normalized.substringAfterLast('/'), text, normalized.substringBeforeLast('/', ""))
    }

    override fun resolve(path: String, relativeTo: SourceText?): SourceText? {
        val base = relativeTo?.base.orEmpty()
        val joined = if (base.isEmpty()) path else "$base/$path"
        val segments = ArrayDeque<String>()
        joined.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return read(segments.joinToString("/"))
    }
}
