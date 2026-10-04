package com.xqiou.mantra.lsp

import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections

/** Editor documents only. No fact source, network, executable archive or workbench runner is loaded. */
class ConfinedSources(
    roots: List<Path>,
    private val epoch: DocumentEpoch,
    private val checkpoint: () -> Unit,
    private val maxFiles: Int = 256,
    private val maxBytes: Long = 4L * 1024 * 1024,
) : SourceResolver {
    private val roots = roots.map { it.toRealPath() }.distinct()
    private val loaded = linkedMapOf<String, DocumentSnapshot>()
    private var bytes = 0L
    val snapshots: Map<String, DocumentSnapshot> get() = Collections.unmodifiableMap(LinkedHashMap(loaded))

    fun canonical(raw: String): String = safe(Path.of(fileUri(raw))).toUri().toString()

    private fun safe(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        val existing = if (Files.exists(normalized)) {
            normalized.toRealPath()
        } else {
            val parent = requireNotNull(normalized.parent).toRealPath()
            parent.resolve(normalized.fileName).normalize()
        }
        require(roots.any { existing.startsWith(it) }) { "Document escapes explicit workspace roots" }
        require(existing.none { it.toString() in FORBIDDEN }) { "Private workspace directory is excluded" }
        require(existing.fileName.toString().endsWith(".mantra")) { "Only .mantra source documents are readable" }
        return existing
    }

    fun read(raw: String): SourceText {
        checkpoint()
        val path = safe(Path.of(fileUri(raw)))
        val uri = path.toUri().toString()
        loaded[uri]?.let { return SourceText(uri, it.text, path.parent.toString()) }
        require(loaded.size < maxFiles) { "Workspace file limit reached" }
        val overlay = epoch.overlays[uri]
        val snapshot = if (overlay != null) {
            overlay
        } else {
            require(Files.isRegularFile(path)) { "Source document is missing" }
            val size = Files.size(path)
            require(size <= 262_144L && bytes + size <= maxBytes) { "Workspace source byte limit reached" }
            val captured = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                captureSource(input, minOf(262_144L, maxBytes - bytes), checkpoint)
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(captured)).toString()
            require(text.length <= 65_536) { "Document source limit reached" }
            DocumentSnapshot(uri, text, null)
        }
        val size = snapshot.text.toByteArray(Charsets.UTF_8).size.toLong()
        require(bytes + size <= maxBytes) { "Workspace source byte limit reached" }
        bytes += size
        loaded[uri] = snapshot
        checkpoint()
        return SourceText(uri, snapshot.text, path.parent.toString())
    }

    override fun resolve(path: String, relativeTo: SourceText?): SourceText? {
        val owner = relativeTo ?: return null
        val base = Path.of(fileUri(owner.name)).parent ?: return null
        val candidate = base.resolve(path).normalize()
        return read(candidate.toUri().toString())
    }

    fun discover(): List<SourceText> {
        var entries = 0
        roots.forEach { root ->
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkpoint()
                        require(++entries <= 50_000) { "Workspace discovery entry limit reached" }
                        if (dir.any { it.toString() in FORBIDDEN }) return FileVisitResult.SKIP_SUBTREE
                        require(root.relativize(dir).nameCount <= 64) { "Workspace directory depth limit reached" }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        checkpoint()
                        require(++entries <= 50_000) { "Workspace discovery entry limit reached" }
                        if (file.any { it.toString() in FORBIDDEN }) return FileVisitResult.CONTINUE
                        if (attrs.isSymbolicLink && Files.isDirectory(file)) {
                            throw IllegalArgumentException(
                                "Symlink directory requires an explicit canonical workspace root",
                            )
                        }
                        if (attrs.isRegularFile && file.fileName.toString().endsWith(".mantra")) {
                            read(file.toUri().toString())
                        }
                        // Symlink files are not silently indexed. Explicit reads perform canonical
                        // confinement checks; an undiscovered consumer is never claimed complete.
                        if (attrs.isSymbolicLink && file.fileName.toString().endsWith(".mantra")) {
                            read(file.toUri().toString())
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        epoch.overlays.keys.forEach { uri -> if (uri.startsWith("file:")) read(uri) }
        return snapshots.values.map { SourceText(it.uri, it.text, Path.of(fileUri(it.uri)).parent.toString()) }
    }

    companion object {
        private val FORBIDDEN = setOf(".git", ".deps", ".agents", ".codex", ".aws", "build", "out", "node_modules")
    }
}

/** Check each actual chunk before retaining it; an earlier Files.size observation is not a bound. */
internal fun captureSource(input: InputStream, maximumBytes: Long, checkpoint: () -> Unit): ByteArray {
    require(maximumBytes in 0..262_144L)
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        checkpoint()
        val remaining = maximumBytes - output.size()
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
        if (count < 0) return output.toByteArray()
        require(count.toLong() <= remaining) { "Workspace source byte limit reached" }
        output.write(buffer, 0, count)
    }
}
