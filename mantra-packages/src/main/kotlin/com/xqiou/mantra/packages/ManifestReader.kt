package com.xqiou.mantra.packages

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.core.model.SchemaIdentity

/** Strict, bounded JSON; a duplicate field is rejected before any interpretation or hashing. */
internal object ManifestReader {
    fun read(bytes: ByteArray, limits: PackageLimits): PackageManifest {
        if (bytes.size.toLong() > limits.manifestBytes) fail("MANTRA-PACKAGE-LIMIT", "Manifest exceeds its byte limit")
        val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        mapper.factory.setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(limits.jsonDepth)
                .maxStringLength(limits.manifestBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).build(),
        )
        val root = try {
            mapper.readTree(utf8(bytes))
        } catch (error: Exception) {
            if (error is PackageException) throw error
            fail("MANTRA-PACKAGE-MANIFEST", "Invalid manifest JSON: ${error.message}", error)
        } ?: fail("MANTRA-PACKAGE-MANIFEST", "Manifest is empty")
        root.fields(
            setOf(
                "format", "id", "version", "engine", "resources",
                "schemas", "parameters", "layouts", "cases", "dependencies",
            ),
        )
        if (root.text("format") != "mantra.package/1") fail("MANTRA-PACKAGE-MANIFEST", "Unsupported package format")
        val identity = PackageIdentity(root.text("id"), SemanticVersion.parse(root.text("version")))
        val engine = VersionRange.parse(root.text("engine"))
        val resources = root.array("resources").map { entry ->
            entry.fields(setOf("path", "role", "byteLength", "sha256"))
            val role =
                PackageResourceRole.entries.firstOrNull { it.name.lowercase().replace('_', '-') == entry.text("role") }
                    ?: fail("MANTRA-PACKAGE-MANIFEST", "Unknown resource role")
            val length = entry.get("byteLength")
            if (length == null || !length.isIntegralNumber || !length.canConvertToLong() || length.longValue() < 0) {
                fail("MANTRA-PACKAGE-MANIFEST", "byteLength must be a nonnegative 64-bit integer")
            }
            val hash = entry.text("sha256")
            if (!hash.matches(
                    Regex("[0-9a-f]{64}"),
                )
            ) {
                fail("MANTRA-PACKAGE-MANIFEST", "sha256 must be 64 lowercase hexadecimal digits")
            }
            val path = logicalPath(entry.text("path"))
            if (path == "manifest.json") fail("MANTRA-PACKAGE-MANIFEST", "Manifest cannot declare itself as a resource")
            PackageResource(path, role, length.longValue(), hash)
        }
        if (resources.size > limits.resources) fail("MANTRA-PACKAGE-LIMIT", "Too many package resources")
        unique(resources.map { it.path }, "resource path")
        val schemas = root.array("schemas").map { entry ->
            entry.fields(setOf("schema", "path"))
            PackageSchema(binding(entry.get("schema")), logicalPath(entry.text("path")))
        }
        unique(schemas.map { it.binding.identity }, "schema identity")
        val parameters = root.array("parameters").map { entry ->
            entry.fields(setOf("id", "schema", "path"))
            PackageParameters(entry.text("id"), binding(entry.get("schema")), logicalPath(entry.text("path")))
        }
        val layouts = root.array("layouts").map { entry ->
            entry.fields(setOf("id", "schema", "path"))
            PackageLayout(entry.text("id"), binding(entry.get("schema")), logicalPath(entry.text("path")))
        }
        val cases = root.array("cases").map { entry ->
            entry.fields(setOf("id", "schema", "path", "parameters", "layout"))
            PackageCase(
                entry.text("id"),
                binding(entry.get("schema")),
                logicalPath(entry.text("path")),
                entry.array("parameters").map { it.stringValue() },
                entry.get("layout")?.takeUnless(JsonNode::isNull)?.stringValue(),
            )
        }
        val dependencies = root.array("dependencies").map { entry ->
            entry.fields(setOf("id", "version"))
            PackageIdentity(entry.text("id"), SemanticVersion.parse(entry.text("version")))
        }
        unique(parameters.map { it.id }, "parameter id")
        unique(layouts.map { it.id }, "layout id")
        unique(cases.map { it.id }, "case id")
        unique(dependencies.map { it }, "dependency identity")
        val roles = resources.associateBy { it.path }
        val ownedPaths = mutableSetOf<String>()
        fun own(path: String, role: PackageResourceRole, schema: PackageSchemaBinding?) {
            if (roles[path]?.role != role) fail("MANTRA-PACKAGE-MANIFEST", "$path is not a declared $role resource")
            if (!ownedPaths.add(path)) fail("MANTRA-PACKAGE-MANIFEST", "Document has multiple identities: $path")
            if (schema != null &&
                schemas.none { it.binding == schema }
            ) {
                fail(
                    "MANTRA-PACKAGE-MANIFEST",
                    "Document references an undeclared exact schema: ${schema.identity}",
                )
            }
        }
        schemas.forEach { own(it.path, PackageResourceRole.SCHEMA, null) }
        parameters.forEach { own(it.path, PackageResourceRole.PARAMETERS, it.schema) }
        layouts.forEach { own(it.path, PackageResourceRole.LAYOUT, it.schema) }
        cases.forEach { case ->
            own(case.path, PackageResourceRole.CASE, case.schema)
            unique(case.parameters, "case parameter id")
            case.parameters.forEach { id ->
                if (parameters.none {
                        it.id == id && it.schema == case.schema
                    }
                ) {
                    fail("MANTRA-PACKAGE-MANIFEST", "Case parameter $id has no matching schema binding")
                }
            }
            if (case.layout != null &&
                layouts.none { it.id == case.layout && it.schema == case.schema }
            ) {
                fail("MANTRA-PACKAGE-MANIFEST", "Case layout has no matching schema binding")
            }
        }
        resources.filter {
            it.role in
                setOf(
                    PackageResourceRole.SCHEMA,
                    PackageResourceRole.PARAMETERS,
                    PackageResourceRole.LAYOUT,
                    PackageResourceRole.CASE,
                )
        }
            .forEach {
                if (it.path !in
                    ownedPaths
                ) {
                    fail("MANTRA-PACKAGE-MANIFEST", "Document resource has no identity: ${it.path}")
                }
            }
        return PackageManifest(identity, engine, resources, schemas, parameters, layouts, cases, dependencies)
    }

    private fun binding(node: JsonNode?): PackageSchemaBinding {
        node ?: fail("MANTRA-PACKAGE-MANIFEST", "Missing exact schema binding")
        node.fields(setOf("id", "version", "versionMode"))
        if (!node.has(
                "version",
            )
        ) {
            fail("MANTRA-PACKAGE-MANIFEST", "Schema version must be explicit, including legacy null")
        }
        val version = node.get("version").takeUnless(JsonNode::isNull)?.stringValue()
        val mode = when (node.text("versionMode")) {
            "semver" -> SchemaVersionMode.SEMVER
            "legacy-exact" -> SchemaVersionMode.LEGACY_EXACT
            else -> fail("MANTRA-PACKAGE-MANIFEST", "Unknown schema versionMode")
        }
        return PackageSchemaBinding(SchemaIdentity(node.text("id"), version), mode)
    }

    private fun JsonNode.fields(allowed: Set<String>) {
        if (!isObject) fail("MANTRA-PACKAGE-MANIFEST", "Expected a JSON object")
        fieldNames().forEach { if (it !in allowed) fail("MANTRA-PACKAGE-MANIFEST", "Unknown manifest field: $it") }
    }
    private fun JsonNode.text(name: String): String =
        get(name)?.stringValue() ?: fail("MANTRA-PACKAGE-MANIFEST", "Missing $name")
    private fun JsonNode.stringValue(): String {
        if (!isTextual || textValue().isBlank() ||
            textValue().any { it.code < 32 }
        ) {
            fail("MANTRA-PACKAGE-MANIFEST", "Expected nonblank JSON text")
        }
        return textValue()
    }
    private fun JsonNode.array(name: String): List<JsonNode> {
        val node = get(name) ?: fail("MANTRA-PACKAGE-MANIFEST", "Missing array $name")
        if (!node.isArray) fail("MANTRA-PACKAGE-MANIFEST", "Expected array $name")
        return node.toList()
    }
    private fun <T> unique(values: List<T>, what: String) {
        if (values.toSet().size != values.size) fail("MANTRA-PACKAGE-DUPLICATE", "Duplicate $what")
    }
}
