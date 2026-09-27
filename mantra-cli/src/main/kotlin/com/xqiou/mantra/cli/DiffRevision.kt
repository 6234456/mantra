package com.xqiou.mantra.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** File-content revision for a CLI comparison, independent of checkout or external file paths. */
internal object DiffRevision {
    fun calculate(
        schemaPath: Path,
        schemaSources: List<String>,
        baseCase: Path?,
        variantCase: Path?,
        layout: Path?,
        baseParameters: List<Path>,
        variantParameters: List<Path>,
    ): String {
        val schema = schemaPath.toAbsolutePath().normalize()
        val inputs = buildList<Pair<String, Path>> {
            add("schema" to schema)
            schemaSources.forEachIndexed { index, source ->
                val file = schema.parent.resolve(source).normalize()
                if (file != schema) add("schema-source[$index]:$source" to file)
            }
            baseCase?.let { add("base-case" to it) }
            variantCase?.let { add("variant-case" to it) }
            layout?.let { add("layout" to it) }
            baseParameters.forEachIndexed { index, path -> add("base-parameters[$index]" to path) }
            variantParameters.forEachIndexed { index, path -> add("variant-parameters[$index]" to path) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        inputs.forEach { (label, path) ->
            val bytes = Files.readAllBytes(path)
            digest.update(label.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
            digest.update(0.toByte())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
