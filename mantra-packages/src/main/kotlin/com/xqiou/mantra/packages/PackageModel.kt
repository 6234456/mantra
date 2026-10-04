package com.xqiou.mantra.packages

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.Severity
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class PackageException(val diagnostic: Diagnostic, cause: Throwable? = null, related: List<Diagnostic> = emptyList()) :
    RuntimeException(diagnostic.message, cause) {
    val diagnostics: List<Diagnostic> = java.util.List.copyOf(
        (listOf(diagnostic) + related).distinct()
            .map { it.copy(coord = java.util.List.copyOf(it.coord)) },
    )
}

internal fun fail(code: String, message: String, cause: Throwable? = null): Nothing =
    throw PackageException(Diagnostic(Severity.ERROR, code, message), cause)

internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
    "%02x".format(it)
}

/** Length-prefixes distinguish null, literal sentinel spellings and embedded separators. */
internal fun fingerprint(parts: List<String?>): String {
    val sha = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part?.toByteArray(StandardCharsets.UTF_8)
        sha.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
        if (bytes != null) sha.update(bytes)
    }
    return sha.digest().joinToString("") { "%02x".format(it) }
}

internal fun utf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (error: java.nio.charset.CharacterCodingException) {
    fail("MANTRA-PACKAGE-UTF8", "Resource is not valid UTF-8", error)
}

data class PackageIdentity(val id: String, val version: SemanticVersion) {
    init {
        require(id.isNotBlank())
    }
}

enum class PackageResourceRole {
    SCHEMA,
    FRAGMENT,
    PARAMETERS,
    CASE,
    LAYOUT,
    DATA,
    IMPORT_TEMPLATE,
    DOCUMENTATION,
    MIGRATION,
}

data class PackageResource(val path: String, val role: PackageResourceRole, val byteLength: Long, val sha256: String)
data class PackageSchema(val binding: PackageSchemaBinding, val path: String)
data class PackageParameters(val id: String, val schema: PackageSchemaBinding, val path: String)
data class PackageLayout(val id: String, val schema: PackageSchemaBinding, val path: String)
data class PackageCase(
    val id: String,
    val schema: PackageSchemaBinding,
    val path: String,
    val parameters: List<String>,
    val layout: String?,
)

/** Explicit limits supplied by the host, not an undocumented M4 performance budget. */
data class PackageLimits(
    val manifestBytes: Long,
    val resourceBytes: Long,
    val totalBytes: Long,
    val resources: Int,
    val jsonDepth: Int,
    val containerEntries: Int,
) {
    init {
        require(
            manifestBytes > 0 && resourceBytes > 0 && totalBytes > 0 && resources > 0 && jsonDepth > 0 &&
                containerEntries > 0,
        )
    }
}

class PackageManifest internal constructor(
    val identity: PackageIdentity,
    val engine: VersionRange,
    resources: List<PackageResource>,
    schemas: List<PackageSchema>,
    parameters: List<PackageParameters>,
    layouts: List<PackageLayout>,
    cases: List<PackageCase>,
    dependencies: List<PackageIdentity>,
) {
    val resources: List<PackageResource> = java.util.List.copyOf(resources)
    val schemas: List<PackageSchema> = java.util.List.copyOf(schemas)
    val parameters: List<PackageParameters> = java.util.List.copyOf(parameters)
    val layouts: List<PackageLayout> = java.util.List.copyOf(layouts)
    val cases: List<PackageCase> = java.util.List.copyOf(
        cases.map {
            it.copy(parameters = java.util.List.copyOf(it.parameters))
        },
    )
    val dependencies: List<PackageIdentity> = java.util.List.copyOf(dependencies)
}

internal fun logicalPath(path: String): String {
    if (path.isEmpty() || path.startsWith('/') || '\\' in path || ':' in path ||
        path.any { it.code < 32 || it.code == 127 } ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." }
    ) {
        fail("MANTRA-PACKAGE-PATH", "A canonical relative POSIX resource path is required: $path")
    }
    return path
}

internal fun relativePath(reference: String, from: String?): String {
    if (reference.isBlank() || reference.startsWith('/') || '\\' in reference || ':' in reference ||
        reference.any { it.code < 32 || it.code == 127 }
    ) {
        fail("MANTRA-PACKAGE-PATH", "Invalid relative resource reference: $reference")
    }
    val segments =
        from?.substringBeforeLast('/', "")?.split('/')?.filter(String::isNotEmpty)?.toMutableList() ?: mutableListOf()
    reference.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) {
                fail(
                    "MANTRA-PACKAGE-PATH",
                    "Resource reference leaves its package: $reference",
                )
            } else {
                segments.removeAt(segments.lastIndex)
            }
            else -> segments += segment
        }
    }
    return logicalPath(segments.joinToString("/"))
}
