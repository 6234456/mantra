package com.xqiou.mantra.workbench.packages

import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.packages.MigrationStore
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.packages.SemanticVersion
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Real loader/JAR and byte-CAS host store; calculations are the public graph runner. */
internal class PackageHostFixture(val root: Path) {
    val engine = SemanticVersion.parse("0.4.0-SNAPSHOT")
    val limits = PackageLimits(65_536, 65_536, 524_288, 64, 16, 128)
    val original = """
        (case demo {:schema "host/demo" :schema-version "1.0.0"}
          ;; Keep original zero/false facts and this comment.
          (inputs {:base-value 0 :enabled false}))
    """.trimIndent()

    fun snapshot(id: String, version: String, factor: Int = 1, technical: Boolean = false): PackageSnapshot {
        val files = linkedMapOf(
            "schema.mantra" to """
                (schema host/demo {:version "$version"}
                  (param rate 1) (input base-value :decimal) (input enabled :boolean)
                  (section result "Result"
                    (line answer "Answer" ${if (technical) "(/ base-value 0)" else "(* base-value rate $factor)"})))
            """.trimIndent(),
            "cases/demo.mantra" to original.replace("1.0.0", version),
            "parameters/current.mantra" to """
                (parameters current {:for "host/demo" :valid-from "2026-01-01" :valid-until "2027-01-01"}
                  (value rate 2 {:reference "Fictional independent rate source"}))
            """.trimIndent(),
        )
        return jar(id, version, files)
    }

    private fun jar(id: String, version: String, files: Map<String, String>): PackageSnapshot {
        val json = ObjectMapper()
        val binding = mapOf("id" to "host/demo", "version" to version, "versionMode" to "semver")
        val manifest = linkedMapOf(
            "format" to "mantra.package/1", "id" to id, "version" to version, "engine" to ">=0.4.0-0 <0.6.0",
            "resources" to files.map { (path, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                mapOf(
                    "path" to path,
                    "role" to when {
                        path.startsWith("cases/") -> "case"
                        path.startsWith("parameters/") -> "parameters"
                        else -> "schema"
                    },
                    "byteLength" to bytes.size,
                    "sha256" to hostHash(bytes),
                )
            },
            "schemas" to listOf(mapOf("schema" to binding, "path" to "schema.mantra")),
            "parameters" to listOf(
                mapOf("id" to "current", "schema" to binding, "path" to "parameters/current.mantra"),
            ),
            "layouts" to emptyList<Any>(),
            "cases" to
                listOf(
                    mapOf(
                        "id" to "demo",
                        "schema" to binding,
                        "path" to "cases/demo.mantra",
                        "parameters" to listOf("current"),
                        "layout" to null,
                    ),
                ),
            "dependencies" to emptyList<Any>(),
        )
        Files.createDirectories(root)
        val file = root.resolve("$id-$version.jar")
        JarOutputStream(Files.newOutputStream(file)).use { output ->
            (files + ("manifest.json" to json.writeValueAsString(manifest))).forEach { (path, text) ->
                output.putNextEntry(JarEntry("bundle/$path"))
                output.write(text.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        return URLClassLoader(arrayOf(file.toUri().toURL()), null).use {
            PackageLoader.classpath("bundle", it, engine, limits)
        }
    }
}

internal class MemoryHostStore(initial: String) : MigrationStore {
    var text = initial
    var writes = 0
    override fun read(casePath: String) = SourceText(casePath, text, casePath)
    override fun commit(
        casePath: String,
        sourceSha256: String,
        candidate: SourceText,
        authorize: (write: () -> Unit) -> Unit,
    ) {
        require(hostHash(text.toByteArray(Charsets.UTF_8)) == sourceSha256) { "Source CAS conflict" }
        var written = false
        authorize {
            require(!written && hostHash(text.toByteArray(Charsets.UTF_8)) == sourceSha256)
            text = candidate.text
            writes++
            written = true
        }
        require(written)
    }
}
