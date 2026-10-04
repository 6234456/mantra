package com.xqiou.mantra.packages

import com.xqiou.mantra.core.model.SchemaIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

internal class PackageFixture(version: String? = "1.0.0", mode: SchemaVersionMode = SchemaVersionMode.SEMVER) {
    val binding = PackageSchemaBinding(SchemaIdentity("package/demo", version), mode)
    val files = linkedMapOf(
        "schema.mantra" to """
            (schema package/demo ${version?.let { "{:version \"$it\"}" } ?: ""}
              (param rate 1) (param ceiling 100)
              (input base-value :decimal)
              (input enabled :boolean)
              (line answer "Answer" (* base-value rate))
              (check within-ceiling "Ceiling" (<= answer ceiling)))
        """.trimIndent(),
        "cases/zero.mantra" to """
            (case zero {:schema "package/demo" ${version?.let { ":schema-version \"$it\"" } ?: ""}}
              ;; Original fictional facts; preserve this comment and false/zero literals.
              (inputs {:base-value 0 :enabled false}))
        """.trimIndent(),
    )
    val parameterIds = mutableListOf<String>()
    var dependencies = "[]"
    var engine = ">=0.4.0-0 <0.6.0"
    val limits = PackageLimits(64_000, 64_000, 256_000, 100, 20, 200)

    fun parameter(
        id: String,
        rate: String,
        from: String? = null,
        until: String? = null,
        ceiling: String? = null,
    ): PackageFixture {
        parameterIds += id
        files["parameters/$id.mantra"] = """
            (parameters $id {:for "package/demo" ${from?.let {
            ":valid-from \"$it\""
        } ?: ""} ${until?.let { ":valid-until \"$it\"" } ?: ""}}
              (value rate $rate {:reference "Fictional source $id"}) ${ceiling?.let { "(value ceiling $it)" } ?: ""})
        """.trimIndent()
        return this
    }

    fun manifest(): String {
        val resources = files.map { (path, text) ->
            val role = when {
                path == "schema.mantra" -> "schema"
                path.startsWith("cases/") -> "case"
                path.startsWith("parameters/") -> "parameters"
                path.endsWith(".csv") -> "data"
                else -> "fragment"
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            """{"path":"$path","role":"$role","byteLength":${bytes.size},"sha256":"${digest(bytes)}"}"""
        }.joinToString(",")
        val version = binding.identity.version?.let { "\"$it\"" } ?: "null"
        val mode = if (binding.mode == SchemaVersionMode.SEMVER) "semver" else "legacy-exact"
        val identity = """{"id":"package/demo","version":$version,"versionMode":"$mode"}"""
        val params = parameterIds.joinToString(",") {
            """{"id":"$it","schema":$identity,"path":"parameters/$it.mantra"}"""
        }
        return """
            {"format":"mantra.package/1","id":"fictional.demo","version":"1.0.0","engine":"$engine",
             "resources":[$resources],"schemas":[{"schema":$identity,"path":"schema.mantra"}],
             "parameters":[$params],"layouts":[],
             "cases":[{"id":"zero","schema":$identity,"path":"cases/zero.mantra","parameters":[],"layout":null}],
             "dependencies":$dependencies}
        """.trimIndent()
    }

    fun directory(root: Path, manifest: String = manifest()): Path {
        Files.createDirectories(root)
        files.forEach { (path, text) ->
            val target = root.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, text)
        }
        Files.writeString(root.resolve("manifest.json"), manifest)
        return root
    }

    fun jar(path: Path, prefix: String = "bundle", manifest: String = manifest()) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            (files + ("manifest.json" to manifest)).forEach { (name, text) ->
                jar.putNextEntry(JarEntry("$prefix/$name"))
                jar.write(text.toByteArray(Charsets.UTF_8))
                jar.closeEntry()
            }
        }
    }
}
