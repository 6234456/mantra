package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.DiagnosticSink
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.ParticipatingSource
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.Document
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.options
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.render.layout.LayoutReader
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.normein.dsl.form.DslForm
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Overrides apply only to the explicitly named root case; linked sources retain their own bindings. */
data class CasePackageOverrides(
    val rootCase: String,
    val text: String? = null,
    val parameters: List<String>? = null,
    val layout: String? = null,
    val schemaPath: Path? = null,
    val parameterPaths: List<Path>? = null,
    val layoutPath: Path? = null,
    val includeLayout: Boolean = true,
)

/** Disposable draft state stays separate from the published override constructor and copy ABI. */
internal data class TemplateCandidateOverrides(
    val sourceTexts: Map<String, String>,
    val inputTexts: List<TemplateInputText>,
    val allowedSources: Set<String>?,
)

/**
 * One-request, confined loader shared by CLI, workbench and application acceptance.
 * Captured buffers are reused for parsing, importing and hashing. Construct a fresh loader per run.
 * Index reads also consume the byte budget; only selected resources participate in case revisions.
 */
class CasePackageLoader(directory: Path, private val overrides: CasePackageOverrides? = null) : CasePackageResolver {
    val root: Path = directory.toRealPath().also { require(Files.isDirectory(it)) }
    private val buffers = linkedMapOf<Path, ByteArray>()
    private val canonicalPaths = linkedMapOf<Path, Path>()
    private var capturedBuffers: Map<Path, ByteArray> = emptyMap()
    private var index: List<Entry>? = null
    private val metadata = linkedMapOf<CanonicalCaseKey, Binding>()
    private val capturedSources = linkedMapOf<CanonicalCaseKey, Map<String, SourceText>>()
    private var templateCandidate: TemplateCandidateOverrides? = null
    private data class Entry(val path: Path, val kind: String, val id: String?, val version: String?)

    data class Binding(
        val packageData: PreparedCasePackage,
        val layout: LayoutSpec?,
        val parameterIds: List<String>,
        val sourceOverrides: List<List<String>>,
    )

    fun binding(key: CanonicalCaseKey): Binding = metadata.getValue(key)

    /** Only parsed document buffers, never index-only reads or imported data. */
    internal fun sourceTexts(key: CanonicalCaseKey): Map<String, SourceText> = capturedSources.getValue(key)

    internal fun capturedCasePath(path: Path): Path = confined(path)

    /** Reuse immutable captured documents, data and index; a fresh run still charges its own budget. */
    internal fun fork(overrides: CasePackageOverrides): CasePackageLoader = CasePackageLoader(root, overrides).also {
        it.capturedBuffers = capturedBuffers + buffers
        it.canonicalPaths.putAll(canonicalPaths)
        it.index = index
    }

    internal fun fork(overrides: CasePackageOverrides, candidate: TemplateCandidateOverrides?): CasePackageLoader =
        fork(overrides).also { it.templateCandidate = candidate }

    override fun identify(reference: CaseReference, control: CaseLoadControl): CanonicalCaseKey {
        control.checkpoint()
        val base = reference.fromCase?.let { confined(root.resolve(it.value)).parent } ?: root
        return CanonicalCaseKey(relative(confined(base.resolve(reference.path))))
    }

    override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage {
        val casePath = confined(root.resolve(key.value))
        val used = linkedMapOf<Path, SourceRole>()
        val texts = linkedMapOf<String, SourceText>()
        val selected = overrides?.takeIf { it.rootCase == key.value }
        val selectedTemplate = templateCandidate.takeIf { selected != null }
        val explicitFiles = listOfNotNull(selected?.schemaPath, selected?.layoutPath) +
            selected?.parameterPaths.orEmpty()
        val authorized = explicitFiles.mapTo(mutableSetOf(), ::canonical)
        val schemaIncludeRoot = selected?.schemaPath?.let(::canonical)?.parent
        var candidateCaseText: String? = selected?.text
        fun checkCaptured(path: Path) {
            if (templateCandidate?.allowedSources?.let { relative(path) !in it } == true) {
                invalid("MANTRA-TEMPLATE-DEPENDENCY", "Template preview cannot introduce a new participating source")
            }
        }
        fun source(path: Path, role: SourceRole): SourceText {
            val candidate = path.toAbsolutePath().normalize()
            val explicit = canonicalPaths[candidate] ?: candidate.takeIf { Files.isRegularFile(it) }?.let(::canonical)
            val allowedInclude = role == SourceRole.INCLUDED && schemaIncludeRoot != null &&
                explicit != null && explicit.startsWith(schemaIncludeRoot)
            val actual = if (explicit != null && (explicit in authorized || allowedInclude)) {
                explicit
            } else {
                confined(path)
            }
            checkCaptured(actual)
            used.putIfAbsent(actual, role)
            val bytes = capture(actual, control, 1_048_576)
            val replacement = if (actual ==
                casePath
            ) {
                candidateCaseText
            } else {
                selectedTemplate?.sourceTexts?.get(relative(actual))
            }
            val text = if (replacement != null) {
                replacement.also {
                    require(it.length <= 65_536) { "Case exceeds reader limit" }
                    control.chargeParticipatingBytes(it.toByteArray(Charsets.UTF_8).size.toLong())
                }
            } else {
                bytes.toString(Charsets.UTF_8)
            }
            if (text.length > 65_536) {
                throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Document exceeds reader limit")
            }
            return SourceText(relative(actual), text, actual.parent.toString()).also { texts[it.name] = it }
        }
        var supplied = Mantra.loadCase(source(casePath, SourceRole.CASE))
        val schemaId = supplied.schemaId ?: invalid("MANTRA-WORKBENCH-CASE-SCHEMA", "Case does not declare :schema")
        val documents = documents(control)
        val candidates = documents.filter { it.kind == "schema" && it.id == schemaId }
        val matches = supplied.schemaVersion?.let { version ->
            candidates.filter { it.version == version }
        } ?: candidates
        if (selected?.schemaPath == null && matches.size != 1) {
            invalid("MANTRA-LINK-VERSION", "Expected one exact schema binding for $schemaId, found ${matches.size}")
        }
        val schemaPath = selected?.schemaPath ?: matches.single().path
        val schema = Mantra.loadSchema(
            source(schemaPath, SourceRole.SCHEMA),
            SourceResolver { name, from ->
                val base = from?.base?.let(Path::of) ?: schemaPath.parent
                source(base.resolve(name), SourceRole.INCLUDED)
            },
        )
        val versionMismatch = supplied.schemaVersion != null && supplied.schemaVersion != schema.version
        if (supplied.schemaId != schema.id || versionMismatch) {
            invalid("MANTRA-LINK-VERSION", "Case version differs from its loaded schema")
        }
        val parameterBinding = supplied.meta["parameters"]
        if (selected?.parameters == null && selected?.parameterPaths == null &&
            parameterBinding != null && parameterBinding !is Value.Vec
        ) {
            invalid("MANTRA-WORKBENCH-DOCUMENT", ":parameters must be a vector")
        }
        val requestedParameterIds = selected?.parameters ?: (parameterBinding as? Value.Vec)?.items?.map {
            (it as? Value.Text)?.value ?: invalid("MANTRA-WORKBENCH-DOCUMENT", "Parameter id must be literal text")
        }.orEmpty()
        fun named(kind: String, id: String): Path {
            val found = documents.filter { it.kind == kind && it.id == id }
            if (found.size != 1) invalid("MANTRA-WORKBENCH-DOCUMENT", "$kind $id cannot be uniquely resolved")
            return found.single().path
        }
        val parameters = selected?.parameterPaths?.map { Mantra.loadParameters(source(it, SourceRole.PARAMETERS)) }
            ?: requestedParameterIds.map {
                Mantra.loadParameters(source(named("parameters", it), SourceRole.PARAMETERS))
            }
        val parameterIds = parameters.map { it.id }
        if (supplied.meta["layout"] != null && supplied.meta["layout"] !is Value.Text) {
            invalid("MANTRA-WORKBENCH-DOCUMENT", ":layout must be literal text")
        }
        val layoutId = selected?.layout ?: (supplied.meta["layout"] as? Value.Text)?.value
        val layout = if (selected?.includeLayout == false) {
            null
        } else {
            selected?.layoutPath?.let {
                LayoutReader.read(source(it, SourceRole.LAYOUT))
            }
                ?: layoutId?.let { LayoutReader.read(source(named("layout", it), SourceRole.LAYOUT)) }
                ?: documents.firstOrNull {
                    it.kind == "layout" && it.path == schemaPath.parent.resolve("layout.mantra")
                }
                    ?.let { LayoutReader.read(source(it.path, SourceRole.LAYOUT)) }
        }
        if (selectedTemplate?.inputTexts?.isNotEmpty() == true) {
            val operations = selectedTemplate.inputTexts.map { input ->
                // Field declarations may repeat an input; the planner uses its first declaration.
                val declaration = schema.inputs.firstOrNull { it.id == input.node }
                    ?: invalid("MANTRA-INPUT-UNKNOWN", "Unknown candidate input ${input.node}")
                if (declaration.type == com.xqiou.mantra.core.model.ValueType.TABLE ||
                    declaration.per?.isNotEmpty() == true
                ) {
                    invalid("MANTRA-TEMPLATE-INPUT", "Template preview accepts only scalar inputs")
                }
                CaseTextEditor.Operation.SetInput(
                    input.node,
                    EditorValueParser.parseScalar(
                        declaration.type,
                        layout ?: (schema.meta.attributes["preset"] as? Value.Kw)?.name
                            ?.let(com.xqiou.mantra.render.layout.Presets::of)
                            ?: com.xqiou.mantra.render.layout.Presets.DE_STAFFEL_4,
                        input.text,
                    ),
                )
            }
            candidateCaseText = try {
                CaseTextEditor.apply(texts.getValue(key.value).text, operations)
            } catch (error: IllegalArgumentException) {
                invalid("MANTRA-TEMPLATE-INPUT", error.message ?: "Candidate input cannot be encoded in a case")
            } catch (error: IllegalStateException) {
                invalid("MANTRA-TEMPLATE-INPUT", error.message ?: "Candidate input cannot be encoded in a case")
            }
            if (candidateCaseText.length > 65_536) {
                throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Candidate case exceeds reader limit")
            }
            control.chargeParticipatingBytes(candidateCaseText.toByteArray(Charsets.UTF_8).size.toLong())
            val candidateSource = SourceText(key.value, candidateCaseText, casePath.parent.toString())
            texts[key.value] = candidateSource
            supplied = Mantra.loadCase(candidateSource)
        }
        val bound = BoundSources.load(
            supplied,
            schema,
            casePath,
            root,
            capturedRead = { path ->
                val actual = confined(path)
                checkCaptured(actual)
                used.putIfAbsent(actual, SourceRole.DATA)
                capture(actual, control, ImportFiles.MAX_BYTES)
            },
            onRow = { control.chargeInputRows() },
            checkpoint = control::checkpoint,
        )
        val sources = used.map { (path, role) ->
            val replacement = if (path ==
                casePath
            ) {
                candidateCaseText
            } else {
                selectedTemplate?.sourceTexts?.get(relative(path))
            }
            val bytes = if (replacement != null) {
                replacement.toByteArray(Charsets.UTF_8)
            } else {
                buffers.getValue(path)
            }
            ParticipatingSource(relative(path), role, sha256(bytes), bytes.size.toLong())
        }
        val prepared = PreparedCasePackage(
            key,
            supplied.id,
            schema.identity,
            schema,
            bound.case,
            parameters,
            sources,
            revision(sources),
        )
        metadata[key] = Binding(prepared, layout, parameterIds.toList(), bound.overridden)
        capturedSources[key] = texts.toMap()
        return prepared
    }

    private fun documents(control: CaseLoadControl): List<Entry> = index ?: Files.walk(root).use { paths ->
        val files = paths.peek { control.checkpoint() }
            .filter { path ->
                path.toString().endsWith(".mantra") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            }
            .limit(4097).toList().sorted()
        if (files.size > 4096) {
            throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Workspace exceeds 4096 Mantra files")
        }
        files.mapNotNull { path ->
            control.checkpoint()
            val text = capture(path, control, 1_048_576).toString(Charsets.UTF_8)
            if (text.length > 65_536) return@mapNotNull null
            val sink = DiagnosticSink()
            val document = Document.read(SourceText(relative(path), text), sink) ?: return@mapNotNull null
            val form = document.root as? DslForm.Sequence ?: return@mapNotNull null
            val kind = form.listHead ?: return@mapNotNull null
            val id = form.values.getOrNull(1)?.let { it.string ?: it.symbol }
            val version = document.options(form.values.getOrNull(2), sink, "metadata")["version"]?.string
            Entry(path, kind, id, version)
        }.also { index = it }
    }

    private fun capture(file: Path, control: CaseLoadControl, maxBytes: Int): ByteArray {
        buffers[file]?.let { return it }
        control.checkpoint()
        capturedBuffers[file]?.let { bytes ->
            if (bytes.size > maxBytes) {
                throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Captured file exceeds permitted size")
            }
            control.chargeParticipatingBytes(bytes.size.toLong())
            buffers[file] = bytes
            return bytes
        }
        if (Files.size(file) > maxBytes) {
            throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "File exceeds permitted size")
        }
        val bytes = Files.newInputStream(file).use { input ->
            val out = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                control.checkpoint()
                val count = input.read(block)
                if (count < 0) break
                if (out.size().toLong() + count > maxBytes) {
                    throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "File grew beyond permitted size")
                }
                control.chargeParticipatingBytes(count.toLong())
                out.write(block, 0, count)
            }
            out.toByteArray()
        }
        buffers[file] = bytes
        return bytes
    }

    private fun confined(candidate: Path): Path {
        val normalized = candidate.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || (normalized !in canonicalPaths && !Files.isRegularFile(normalized))) {
            throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Linked source is missing or outside the workspace")
        }
        return canonical(normalized).also {
            if (!it.startsWith(root)) {
                throw WorkspaceException(WorkspaceProblem.NOT_FOUND, "Linked source is outside the workspace")
            }
        }
    }

    private fun canonical(candidate: Path): Path {
        val normalized = candidate.toAbsolutePath().normalize()
        return canonicalPaths.getOrPut(normalized) { normalized.toRealPath() }
    }

    private fun relative(path: Path): String {
        val relative = if (path.startsWith(root)) root.relativize(path) else path
        return relative.toString().replace('\\', '/')
    }
    private fun invalid(code: String, message: String): Nothing = throw MantraException(
        listOf(com.xqiou.mantra.core.Diagnostic(com.xqiou.mantra.core.Severity.ERROR, code, message)),
    )
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
    "%02x".format(it)
}

private fun revision(sources: List<ParticipatingSource>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    sources.sortedBy { it.identity }.forEach { source ->
        listOf(source.identity, source.role.name, source.sha256, source.byteLength.toString()).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
