package com.xqiou.mantra.core

import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.engine.CalculationPlan
import com.xqiou.mantra.core.engine.Evaluator
import com.xqiou.mantra.core.engine.Planner
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Schema
import com.xqiou.mantra.core.read.CaseReader
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.core.read.ParameterSetReader
import com.xqiou.mantra.core.read.SchemaReader
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.Coord
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

    fun loadParameters(path: Path): ParameterSet {
        val sink = DiagnosticSink()
        val set = ParameterSetReader.read(FileSources.read(path), sink)
        sink.throwIfErrors()
        return checkNotNull(set)
    }

    /** Compiles and orders the calculation; throws [MantraException] with all findings on error. */
    internal fun plan(schema: Schema, case: CaseData = CaseData.empty(), parameters: List<ParameterSet> = emptyList()): CalculationPlan {
        val sink = DiagnosticSink()
        val plan = Planner(sink).plan(schema, case, parameters)
        sink.throwIfErrors()
        return checkNotNull(plan)
    }

    /** Compiles a schema without evaluating it and returns its read-only structure and metadata. */
    fun inspect(schema: Schema, case: CaseData = CaseData.empty(), parameters: List<ParameterSet> = emptyList()): CalculationView =
        CalculationView.of(plan(schema, case, parameters))

    /**
     * Evaluates the schema for a case. Parameter values come from the schema, then the given
     * [parameters] sets (later sets win), then the case's own `(params …)`. Evaluation errors do not
     * throw: they are reported in [CalculationResult.diagnostics] so a partial paper can be rendered.
     */
    fun calculate(schema: Schema, case: CaseData = CaseData.empty(), parameters: List<ParameterSet> = emptyList()): CalculationResult {
        val sink = DiagnosticSink()
        val plan = Planner(sink).plan(schema, case, parameters)
        sink.throwIfErrors()
        return Evaluator(checkNotNull(plan), sink).run()
    }

    internal fun calculate(plan: CalculationPlan): CalculationResult = Evaluator(plan, DiagnosticSink()).run()

    /** Recalculates one case while collecting a bounded FULL trace only for the requested value. */
    fun calculateForExplain(schema: Schema, case: CaseData, parameters: List<ParameterSet>, node: String,
                            coord: Coord = emptyList()): CalculationResult {
        val sink = DiagnosticSink()
        val plan = Planner(sink).plan(schema, case, parameters)
        sink.throwIfErrors()
        return Evaluator(checkNotNull(plan), sink, node to coord).run()
    }
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
        val text = loader.getResourceAsStream(normalized)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
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
