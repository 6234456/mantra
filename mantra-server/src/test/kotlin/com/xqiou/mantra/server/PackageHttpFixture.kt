package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.core.api.RuntimeVersions
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.packages.MigrationStore
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageParameterChoice
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.packages.ParameterSelectionMode
import com.xqiou.mantra.packages.SemanticVersion
import com.xqiou.mantra.workbench.packages.EditablePackageCase
import com.xqiou.mantra.workbench.packages.PackageMount
import com.xqiou.mantra.workbench.packages.PackageWorkspaceCatalog
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** A real captured JAR and public graph adapter. Expected numbers are hand-calculated. */
internal class PackageHttpFixture(private val directory: Path) {
    val rootKey = "old/cases/demo.mantra"
    val targetKey = "new/cases/demo.mantra"
    val sourceKey = "old/cases/source.mantra"
    val original = """
        (case demo {:schema "host/http" :schema-version "1.0.0"}
          ;; Keep authored false and the reviewed source comment.
          (inputs {:base-value 5 :divisor 1 :enabled false}))
    """.trimIndent()
    val source = """
        (case source {:schema "host/http-source" :schema-version "1.0.0"}
          (inputs {:source-value 2}))
    """.trimIndent()
    val jars = mutableListOf<Path>()
    val store = HttpCaseStore(original, source)
    private val choice = PackageParameterChoice(
        LocalDate.parse("2026-06-30"),
        ParameterSelectionMode.EFFECTIVE_DATE,
        listOf("current"),
        setOf("rate"),
    )

    fun catalog(
        editable: Boolean = true,
        linked: Boolean = false,
        imported: Boolean = false,
        zeroRows: Boolean = false,
    ): PackageWorkspaceCatalog {
        if (linked) store.values["root"] = linkedCase(original)
        if (imported) store.values["root"] = importedCase("1.0.0")
        val old = snapshot("http.old", "1.0.0", 1, linked, imported, zeroRows)
        val new = snapshot("http.new", "2.0.0", 3, linked, imported, zeroRows)
        val writers = if (editable) {
            buildList {
                add(EditablePackageCase(rootKey, store, "root"))
                if (linked) add(EditablePackageCase(sourceKey, store, "source"))
            }
        } else {
            emptyList()
        }
        return PackageWorkspaceCatalog(
            listOf(PackageMount("old", old), PackageMount("new", new)),
            mapOf(rootKey to choice, targetKey to choice),
            writers,
        )
    }

    private fun linkedCase(text: String): String = text.dropLast(1) + """
        (links {:path "source.mantra" :schema "host/http-source" :schema-version "1.0.0"
          :mappings [{:from {:node exported :coord []} :to {:input received :coord []}}]}))
    """.trimIndent()

    private fun importedCase(version: String) = """
        (case demo {:schema "host/http" :schema-version "$version"}
          (inputs {:divisor 1 :enabled false})
          (sources (csv {:path "../data/facts.csv" :delimiter ";" :decimal "." :grouping ""})))
    """.trimIndent()

    private fun snapshot(
        id: String,
        version: String,
        factor: Int,
        linked: Boolean,
        imported: Boolean,
        zeroRows: Boolean,
    ): PackageSnapshot {
        val binding = binding("host/http", version)
        val sourceBinding = binding("host/http-source", "1.0.0")
        val expression = if (linked) "(* base-value rate $factor received)" else "(* base-value rate $factor)"
        val files = linkedMapOf(
            "schema.mantra" to """
                (schema host/http {:version "$version"}
                  (param rate 1) (input base-value :decimal) (input divisor :decimal) (input enabled :boolean)
                  ${if (linked) "(input received :decimal)" else ""}
                  (section result "Result" ${if (zeroRows) "{:display :schedule}" else ""}
                    (line answer "Answer" (/ $expression divisor))
                    ${if (zeroRows) "(line blank \"Blank\" 0) (line inactive \"Inactive\" 9 {:when false})" else ""}
                    ${if (zeroRows) "(line concealed \"Concealed\" 13 {:hidden true})" else ""}))
            """.trimIndent(),
            "cases/demo.mantra" to when {
                imported -> importedCase(version)
                linked -> linkedCase(original.replace("1.0.0", version))
                else -> original.replace("1.0.0", version)
            },
            "parameters/current.mantra" to """
                (parameters current {:for "host/http" :valid-from "2026-01-01" :valid-until "2027-01-01"}
                  (value rate 2 {:reference "Fictional independent rate"}))
            """.trimIndent(),
        )
        if (imported) files["data/facts.csv"] = "key;value\nbase-value;7\n"
        if (linked) {
            files["source-schema.mantra"] = """
                (schema host/http-source {:version "1.0.0"}
                  (input source-value :decimal)
                  (section result "Source" (line exported "Exported" (* source-value 1))))
            """.trimIndent()
            files["cases/source.mantra"] = source
        }
        val schemas = buildList {
            add(mapOf("schema" to binding, "path" to "schema.mantra"))
            if (linked) add(mapOf("schema" to sourceBinding, "path" to "source-schema.mantra"))
        }
        val cases = buildList {
            add(
                mapOf(
                    "id" to "demo",
                    "schema" to binding,
                    "path" to "cases/demo.mantra",
                    "parameters" to listOf("current"),
                    "layout" to null,
                ),
            )
            if (linked) {
                add(
                    mapOf(
                        "id" to "source",
                        "schema" to sourceBinding,
                        "path" to "cases/source.mantra",
                        "parameters" to emptyList<String>(),
                        "layout" to null,
                    ),
                )
            }
        }
        val manifest = mapOf(
            "format" to "mantra.package/1", "id" to id, "version" to version,
            "engine" to ">=0.4.0-0 <2.0.0",
            "resources" to files.map { (path, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                mapOf(
                    "path" to path,
                    "byteLength" to bytes.size,
                    "sha256" to httpHash(bytes),
                    "role" to when {
                        path.startsWith("cases/") -> "case"
                        path.startsWith("parameters/") -> "parameters"
                        path.startsWith("data/") -> "data"
                        else -> "schema"
                    },
                )
            },
            "schemas" to schemas,
            "parameters" to listOf(
                mapOf(
                    "id" to "current",
                    "schema" to binding,
                    "path" to "parameters/current.mantra",
                ),
            ),
            "layouts" to emptyList<Any>(), "cases" to cases, "dependencies" to emptyList<Any>(),
        )
        Files.createDirectories(directory)
        val archive = directory.resolve("$id-$version.jar")
        jars.add(archive)
        JarOutputStream(Files.newOutputStream(archive)).use { output ->
            (files + ("manifest.json" to ObjectMapper().writeValueAsString(manifest))).forEach { (path, text) ->
                output.putNextEntry(JarEntry("bundle/$path"))
                output.write(text.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        return URLClassLoader(arrayOf(archive.toUri().toURL()), null).use {
            PackageLoader.classpath(
                "bundle",
                it,
                SemanticVersion.parse(RuntimeVersions.mantra),
                PackageLimits(65_536, 65_536, 524_288, 64, 16, 128),
            )
        }
    }
    private fun binding(id: String, version: String) =
        mapOf("id" to id, "version" to version, "versionMode" to "semver")
}

/** Cooperative test writer exercises byte CAS immediately before the host's graph authorization. */
internal class HttpCaseStore(root: String, source: String) : MigrationStore {
    val values = linkedMapOf("root" to root, "source" to source)
    var writes = 0
    var beforeCommit: (() -> Unit)? = null
    override fun read(casePath: String) = SourceText(casePath, values.getValue(casePath), casePath)
    override fun commit(
        casePath: String,
        sourceSha256: String,
        candidate: SourceText,
        authorize: (write: () -> Unit) -> Unit,
    ) {
        beforeCommit?.also { beforeCommit = null }?.invoke()
        fun checkBytes() {
            require(httpHash(values.getValue(casePath).toByteArray(Charsets.UTF_8)) == sourceSha256) {
                "Source byte CAS conflict"
            }
        }
        checkBytes()
        var invoked = false
        authorize {
            check(!invoked)
            checkBytes()
            values[casePath] = candidate.text
            writes++
            invoked = true
        }
        check(invoked)
    }
}

internal fun httpHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
