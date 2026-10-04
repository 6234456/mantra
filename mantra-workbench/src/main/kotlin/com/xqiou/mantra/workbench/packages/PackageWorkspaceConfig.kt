package com.xqiou.mantra.workbench.packages

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.packages.DirectoryPolicy
import com.xqiou.mantra.packages.FileMigrationStore
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageParameterChoice
import com.xqiou.mantra.packages.SemanticVersion
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** Explicit capability config, not a workspace scan or a package format with inferred write rights. */
object PackageWorkspaceConfig {
    fun open(
        file: Path,
        engine: SemanticVersion,
        loader: ClassLoader = PackageWorkspaceConfig::class.java.classLoader,
    ): PackageWorkspaceCatalog {
        require(Files.size(file) <= 65_536) { "Package workspace config exceeds 64 KiB" }
        val bytes = Files.newInputStream(file).use { it.readNBytes(65_537) }
        require(bytes.size <= 65_536)
        val base = file.toAbsolutePath().normalize().parent
        return open(bytes, base, engine, loader)
    }

    fun open(bytes: ByteArray, base: Path, engine: SemanticVersion, loader: ClassLoader): PackageWorkspaceCatalog {
        require(bytes.size <= 65_536)
        val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        json.factory.setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(65_536).build(),
        )
        val root = requireNotNull(json.readTree(bytes))
        root.fieldsExactly("contract", "limits", "mounts", "policies", "editableCases")
        require(root.text("contract") == "mantra.package-workspace/1")
        val limits = root["limits"]
        limits.fieldsExactly(
            "manifestBytes",
            "resourceBytes",
            "totalBytes",
            "resources",
            "jsonDepth",
            "containerEntries",
        )
        fun integer(node: JsonNode, key: String): Int {
            require(node[key]?.isInt == true && node[key].intValue() > 0) { "$key must be a positive integer" }
            return node[key].intValue()
        }
        fun long(key: String): Long {
            require(
                limits[key]?.isIntegralNumber == true && limits[key].canConvertToLong() && limits[key].longValue() > 0,
            )
            return limits[key].longValue()
        }
        val bound = PackageLimits(
            long("manifestBytes"),
            long("resourceBytes"),
            long("totalBytes"),
            integer(limits, "resources"),
            integer(limits, "jsonDepth"),
            integer(limits, "containerEntries"),
        )
        fun path(raw: String): Path = base.resolve(raw).toAbsolutePath().normalize()
        val directories = mutableListOf<Path>()
        val containers = mutableListOf<Path>()
        fun policy(node: JsonNode): DirectoryPolicy {
            val declared = node.get("directoryPolicy") ?: return DirectoryPolicy.STRICT_HANDLES
            require(declared.isTextual) { "directoryPolicy must be text when present" }
            return when (declared.textValue()) {
                "strict-handles" -> DirectoryPolicy.STRICT_HANDLES
                "trusted-local" -> DirectoryPolicy.TRUSTED_LOCAL
                else -> throw IllegalArgumentException("directoryPolicy must be strict-handles or trusted-local")
            }
        }
        val mounts = root.array("mounts", 64).map { node ->
            val fields = node.fieldNames().asSequence().toSet()
            val sourceFields = fields - "directoryPolicy"
            require(sourceFields == setOf("mount", "manifest") || sourceFields == setOf("mount", "classpath"))
            val snapshot = if (node.has("manifest")) {
                val manifest = path(node.text("manifest"))
                require(manifest.fileName.toString() == "manifest.json") {
                    "Manifest is an explicit manifest.json entry"
                }
                directories.add(manifest.parent.toRealPath())
                PackageLoader.directory(manifest.parent, engine, bound, policy(node))
            } else {
                val name = node.text("classpath")
                val capture = PackageLoader.classpath(name, loader, engine, bound, policy(node))
                val url = loader.getResource("$name/manifest.json")
                if (url?.protocol == "file") directories.add(Path.of(url.toURI()).parent.toRealPath())
                if (url?.protocol == "jar") {
                    val connection = url.openConnection() as java.net.JarURLConnection
                    connection.useCaches = false
                    containers.add(Path.of(connection.jarFileURL.toURI()).toRealPath())
                }
                capture
            }
            PackageMount(node.text("mount"), snapshot)
        }
        val pairs = root.array("policies", 256).map { node ->
            node.fieldsExactly("case", "effectiveDate", "mode", "candidates", "keys")
            fun strings(name: String): List<String> = node.array(name, 128).map {
                require(it.isTextual && it.textValue().isNotBlank())
                it.textValue()
            }.also { require(it.isNotEmpty() && it.distinct().size == it.size) }
            node.text("case") to PackageParameterChoice(
                LocalDate.parse(node.text("effectiveDate")),
                PackageHostPins.mode(node.text("mode")),
                strings("candidates"),
                strings("keys").toSet(),
            )
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "Duplicate per-case parameter policy" }
        val editable = root.array("editableCases", 256).map { node ->
            require(
                node.isObject &&
                    node.fieldNames().asSequence().toSet() - "directoryPolicy" ==
                    setOf("case", "root", "path", "maxBytes"),
            )
            val writableRoot = path(node.text("root")).toRealPath()
            val storePath = node.text("path")
            val storeFile = writableRoot.resolve(storePath).normalize()
            require(
                storeFile.startsWith(writableRoot) && directories.none { storeFile.startsWith(it) } &&
                    storeFile !in containers,
            ) {
                "Writable host cases cannot be package resources"
            }
            val maxBytes = integer(node, "maxBytes")
            EditablePackageCase(
                node.text("case"),
                FileMigrationStore(writableRoot, maxBytes.toLong(), policy(node)),
                storePath,
                maxBytes,
            )
        }
        return PackageWorkspaceCatalog(mounts, pairs.toMap(), editable, mantraVersion = engine.text)
    }

    private fun JsonNode.fieldsExactly(vararg expected: String) {
        require(isObject && fieldNames().asSequence().toSet() == expected.toSet()) { "Unexpected config fields" }
    }
    private fun JsonNode.text(key: String): String {
        val value = get(key)
        require(
            value?.isTextual == true && value.textValue().isNotBlank() && value.textValue().none {
                it.code < 32
            },
        ) { "$key must be nonblank text" }
        return value.textValue()
    }
    private fun JsonNode.array(key: String, limit: Int): List<JsonNode> {
        val value = get(key)
        require(value?.isArray == true && value.size() <= limit) { "$key must be a bounded array" }
        return value.toList()
    }
}
