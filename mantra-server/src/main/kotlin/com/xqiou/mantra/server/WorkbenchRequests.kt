package com.xqiou.mantra.server

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.workbench.AuthoringTarget
import com.xqiou.mantra.workbench.CaseTextEditor
import com.xqiou.mantra.workbench.ImportFiles
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import java.math.BigDecimal
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.LocalDate
import java.util.Base64

/** Validates JSON request shapes before invoking workspace operations. */
internal class WorkbenchRequests(private val catalog: WorkspaceCatalog) {
    private data class EditAddress(val node: String, val coord: List<String>, val cell: Pair<String, String>?)
    val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun parseCompare(body: ByteArray): Pair<String?, List<String>?> {
        fun invalid(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root: JsonNode = try {
            json.readTree(body) ?: invalid("Comparison body is required")
        } catch (
            error: WorkspaceException,
        ) {
            throw error
        } catch (_: Exception) {
            invalid("Malformed comparison JSON")
        }
        if (!root.isObject || root.size() != 1 || !root.has("variant")) invalid("Expected a variant object")
        val variant = root["variant"]
        if (!variant.isObject || variant.size() !in 1..2 ||
            variant.fieldNames().asSequence().any { it !in setOf("case", "parameters") }
        ) {
            invalid("Expected case or parameters in variant")
        }
        val case = variant.get("case")?.let { node ->
            if (!node.isTextual || node.textValue().isBlank()) invalid("Variant case must be a nonempty path")
            val value = node.textValue()
            val path = try {
                Path.of(value)
            } catch (
                _: InvalidPathException,
            ) {
                invalid("Variant case path is invalid")
            }
            if (path.isAbsolute || path.normalize().toString() != value || '\\' in value) {
                invalid("Variant case must be a canonical workspace-relative path")
            }
            value
        }
        val parameters = variant.get("parameters")?.let { node ->
            if (!node.isArray || node.size() > 128) invalid("Variant parameters must be an array of at most 128 ids")
            node.map { id ->
                if (!id.isTextual || id.textValue().isBlank()) invalid("Parameter id must be nonempty text")
                id.textValue()
            }
        }
        return case to parameters
    }

    data class ImportRequest(
        val name: String,
        val format: String,
        val content: ByteArray,
        val revision: String?,
        val options: Map<String, Value>,
    )

    private fun sourceOptions(encoded: JsonNode?): Map<String, Value> {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val options = encoded?.takeIf(JsonNode::isObject) ?: bad("options must be an object")
        if (options.size() > 64) bad("Too many source options")
        return options.fields().asSequence().associate { (key, value) ->
            if (!Regex("[A-Za-z][A-Za-z0-9_-]*").matches(key)) bad("Invalid source option")
            key to when {
                value.isTextual -> Value.Text(value.textValue())
                value.isObject && value.size() <= 256 -> Value.MapV(
                    LinkedHashMap<Value, Value>().apply {
                        value.fields().forEach { (from, to) ->
                            if (!to.isTextual) bad("Source mapping values must be text")
                            put(Value.Text(from), Value.Text(to.textValue()))
                        }
                    },
                )
                else -> bad("Source options must be text or text mappings")
            }
        }
    }

    fun parseTemplate(body: ByteArray): Triple<String, String, Map<String, Value>> {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root = try {
            json.readTree(body) ?: bad("Template body is required")
        } catch (
            error: WorkspaceException,
        ) {
            throw error
        } catch (_: Exception) {
            bad("Malformed template JSON")
        }
        if (!root.isObject || root.fieldNames().asSequence().toSet() != setOf("name", "format", "options")) {
            bad("Unexpected template fields")
        }
        val name = root["name"]?.takeIf(JsonNode::isTextual)?.textValue() ?: bad("Template name must be text")
        val format = root["format"]?.takeIf(JsonNode::isTextual)?.textValue() ?: bad("Template format must be text")
        return Triple(name, format, sourceOptions(root["options"]))
    }

    fun parseImport(body: ByteArray, apply: Boolean): ImportRequest {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root = try {
            json.readTree(body) ?: bad("Import body is required")
        } catch (
            error: WorkspaceException,
        ) {
            throw error
        } catch (_: Exception) {
            bad("Malformed import JSON")
        }
        val fields = if (apply) {
            setOf("name", "format", "contentBase64", "baseRevision", "options")
        } else {
            setOf("name", "format", "contentBase64")
        }
        if (!root.isObject || root.fieldNames().asSequence().toSet() != fields) bad("Unexpected import request fields")
        fun string(key: String): String = root[key]?.takeIf(JsonNode::isTextual)?.textValue()
            ?: bad("$key must be text")
        val name = string("name")
        val format = string("format")
        if (format !in setOf("csv", "json", "xlsx")) bad("Unsupported import format")
        val content = try {
            Base64.getDecoder().decode(string("contentBase64"))
        } catch (
            _: IllegalArgumentException,
        ) {
            bad("Invalid base64 content")
        }
        if (content.size >
            ImportFiles.MAX_BYTES
        ) {
            throw WorkspaceException(WorkspaceProblem.TOO_LARGE, "Import file exceeds 10 MiB")
        }
        val revision = if (apply) {
            string("baseRevision").takeIf { Regex("[0-9a-f]{16}").matches(it) }
                ?: bad("baseRevision must be 16 hexadecimal characters")
        } else {
            null
        }
        val options = if (apply) sourceOptions(root["options"]) else emptyMap()
        return ImportRequest(name, format, content, revision, options)
    }

    fun parseEdits(caseId: String, body: ByteArray, history: Boolean): Pair<String, List<CaseTextEditor.Operation>> {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root = try {
            json.readTree(body) ?: bad("Edit body is required")
        } catch (
            error: WorkspaceException,
        ) {
            throw error
        } catch (_: Exception) {
            bad("Malformed edit JSON")
        }
        val expected = if (history) setOf("baseRevision") else setOf("baseRevision", "operations")
        if (!root.isObject || root.fieldNames().asSequence().toSet() != expected) bad("Unexpected edit request fields")
        val revision = root["baseRevision"]?.takeIf(JsonNode::isTextual)?.textValue()
            ?.takeIf { Regex("[0-9a-f]{16}").matches(it) } ?: bad("baseRevision must be 16 hexadecimal characters")
        if (history) return revision to emptyList()
        val values = root["operations"]?.takeIf(JsonNode::isArray) ?: bad("operations must be an array")
        if (values.size() !in 1..100) bad("Expected 1–100 operations")
        fun requireFields(node: JsonNode, vararg fields: String) {
            if (!node.isObject ||
                node.fieldNames().asSequence().toSet() != fields.toSet()
            ) {
                bad("Unexpected operation fields")
            }
        }
        fun string(node: JsonNode, name: String): String = node[name]?.takeIf(JsonNode::isTextual)?.textValue()
            ?: bad("$name must be text")
        fun integer(node: JsonNode, name: String): Int = node[name]?.takeIf(JsonNode::isInt)?.intValue()
            ?: bad("$name must be an integer")
        fun address(node: JsonNode): EditAddress {
            val address = node["address"] ?: bad("address is required")
            if (!address.isObject || address.fieldNames().asSequence().any { it !in setOf("node", "coord", "cell") } ||
                (address.has("coord") && address.has("cell"))
            ) {
                bad("Invalid address")
            }
            val id = string(address, "node")
            val coord = address["coord"]?.let { array ->
                if (!array.isArray || array.size() > 8 || array.any { !it.isTextual }) bad("Invalid coordinate")
                array.map(JsonNode::textValue)
            }.orEmpty()
            val cell = address["cell"]?.let { entry ->
                if (!entry.isObject ||
                    entry.fieldNames().asSequence().toSet() != setOf("row", "column")
                ) {
                    bad("Invalid cell address")
                }
                string(entry, "row") to string(entry, "column")
            }
            return EditAddress(id, coord, cell)
        }
        fun valueOrText(node: JsonNode, id: String, parameter: Boolean, column: String? = null): Value {
            if (node.has("value") == node.has("text")) bad("Provide exactly one of value and text")
            return if (node.has("text")) {
                catalog.parseEditText(caseId, id, parameter, string(node, "text"), column)
            } else {
                parseValue(node["value"])
            }
        }
        val operations = values.map { op ->
            val kind = string(op, "op")
            when (kind) {
                "setInput" -> {
                    val target = address(op)
                    if (op.fieldNames().asSequence().toSet() !=
                        setOf("op", "address", if (op.has("text")) "text" else "value")
                    ) {
                        bad("Unexpected setInput fields")
                    }
                    val value = valueOrText(op, target.node, false, target.cell?.second)
                    if (target.cell == null) {
                        CaseTextEditor.Operation.SetInput(target.node, value, target.coord)
                    } else {
                        CaseTextEditor.Operation.SetCell(
                            target.node,
                            target.cell.first,
                            target.cell.second,
                            value,
                            catalog.tableKeyColumn(caseId, target.node),
                        )
                    }
                }
                "clearInput" -> {
                    requireFields(op, "op", "address")
                    val target = address(op)
                    if (target.cell == null) {
                        CaseTextEditor.Operation.ClearInput(target.node, target.coord)
                    } else {
                        CaseTextEditor.Operation.ClearCell(
                            target.node,
                            target.cell.first,
                            target.cell.second,
                            catalog.tableKeyColumn(caseId, target.node),
                        )
                    }
                }
                "setParam" -> {
                    val id = string(op, "id")
                    if (op.fieldNames().asSequence().toSet() !=
                        setOf("op", "id", if (op.has("text")) "text" else "value")
                    ) {
                        bad("Unexpected setParam fields")
                    }
                    CaseTextEditor.Operation.SetParam(id, valueOrText(op, id, true))
                }
                "resetParam" -> {
                    requireFields(op, "op", "id")
                    CaseTextEditor.Operation.ResetParam(string(op, "id"))
                }
                "insertRow" -> {
                    if (op.fieldNames().asSequence().any { it !in setOf("op", "table", "row", "rowText", "index") } ||
                        op.has("row") == op.has("rowText")
                    ) {
                        bad("Provide exactly one of row and rowText")
                    }
                    val table = string(op, "table")
                    val row = if (op.has("row")) {
                        parseValue(op["row"]) as? Value.MapV ?: bad("row must be a map value")
                    } else {
                        val source = op["rowText"]?.takeIf(JsonNode::isObject) ?: bad("rowText must be an object")
                        if (source.size() !in 1..64) bad("rowText must contain 1–64 columns")
                        val texts = source.fields().asSequence().associate { (column, value) ->
                            if (!value.isTextual ||
                                value.textValue().length > 10_000
                            ) {
                                bad("rowText values must be text")
                            }
                            column to value.textValue()
                        }
                        catalog.parseEditRowText(caseId, table, texts)
                    }
                    CaseTextEditor.Operation.InsertRow(table, row, op["index"]?.let { integer(op, "index") })
                }
                "updateRow" -> {
                    requireFields(op, "op", "table", "index", "row")
                    CaseTextEditor.Operation.UpdateRow(
                        string(op, "table"),
                        integer(op, "index"),
                        parseValue(op["row"]) as? Value.MapV ?: bad("row must be a map value"),
                    )
                }
                "deleteRow" -> {
                    requireFields(op, "op", "table", "index")
                    CaseTextEditor.Operation.DeleteRow(string(op, "table"), integer(op, "index"))
                }
                "moveRow" -> {
                    requireFields(op, "op", "table", "from", "to")
                    CaseTextEditor.Operation.MoveRow(string(op, "table"), integer(op, "from"), integer(op, "to"))
                }
                "addExtension" -> {
                    requireFields(op, "op", "slot", "id", "title", "formula")
                    CaseTextEditor.Operation.AddExtension(
                        string(op, "slot"),
                        string(op, "id"),
                        string(op, "title"),
                        string(op, "formula"),
                    )
                }
                "updateExtension" -> {
                    requireFields(op, "op", "slot", "id", "title", "formula")
                    CaseTextEditor.Operation.UpdateExtension(
                        string(op, "slot"),
                        string(op, "id"),
                        string(op, "title"),
                        string(op, "formula"),
                    )
                }
                "removeExtension" -> {
                    requireFields(op, "op", "slot", "id")
                    CaseTextEditor.Operation.RemoveExtension(string(op, "slot"), string(op, "id"))
                }
                "bindFormula" -> {
                    requireFields(op, "op", "id", "formula")
                    CaseTextEditor.Operation.BindFormula(string(op, "id"), string(op, "formula"))
                }
                "unbindFormula" -> {
                    requireFields(op, "op", "id")
                    CaseTextEditor.Operation.UnbindFormula(string(op, "id"))
                }
                "setMeta" -> {
                    requireFields(op, "op", "key", "text")
                    CaseTextEditor.Operation.SetMeta(string(op, "key"), string(op, "text"))
                }
                "setBindings" -> {
                    if (op.fieldNames().asSequence().any { it !in setOf("op", "parameters", "layout") } ||
                        (!op.has("parameters") && !op.has("layout"))
                    ) {
                        bad("Invalid bindings")
                    }
                    val parameters = op["parameters"]?.let { array ->
                        if (!array.isArray || array.size() > 128 ||
                            array.any { !it.isTextual }
                        ) {
                            bad("Invalid parameters")
                        }
                        array.map(JsonNode::textValue)
                    }
                    val layoutNode = op["layout"]
                    val layout = layoutNode?.let {
                        if (!it.isTextual &&
                            !it.isNull
                        ) {
                            bad("Invalid layout")
                        }
                        if (it.isNull) null else it.textValue()
                    }
                    CaseTextEditor.Operation.SetBindings(parameters, layout, clearLayout = layoutNode?.isNull == true)
                }
                "addSource" -> {
                    requireFields(op, "op", "kind", "options")
                    val encoded = parseValue(op["options"]) as? Value.MapV ?: bad("options must be an encoded map")
                    val options = encoded.entries.map { (key, value) ->
                        (key as? Value.Kw)?.name?.let { it to value } ?: bad("option keys must be keywords")
                    }.toMap()
                    CaseTextEditor.Operation.AddSource(string(op, "kind"), options)
                }
                "removeSource" -> {
                    requireFields(op, "op", "index")
                    CaseTextEditor.Operation.RemoveSource(integer(op, "index"))
                }
                else -> bad("Unknown edit operation")
            }
        }
        return revision to operations
    }

    fun parseAuthoring(body: ByteArray, action: String): Triple<AuthoringTarget, String, Int?> {
        fun bad(message: String): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, message)
        val root = try {
            json.readTree(body) ?: bad("Authoring body is required")
        } catch (
            error: WorkspaceException,
        ) {
            throw error
        } catch (_: Exception) {
            bad("Malformed authoring JSON")
        }
        val expected = if (action == "check") setOf("target", "source") else setOf("target", "source", "cursorOffset")
        if (!root.isObject ||
            root.fieldNames().asSequence().toSet() != expected
        ) {
            bad("Unexpected authoring request fields")
        }
        val source = root["source"]?.takeIf(JsonNode::isTextual)?.textValue() ?: bad("source must be text")
        val cursor = if (action == "check") {
            null
        } else {
            root["cursorOffset"]?.takeIf(JsonNode::isInt)?.intValue()
                ?.takeIf { it in 0..source.length } ?: bad("cursorOffset is outside source")
        }
        val node = root["target"]?.takeIf(JsonNode::isObject) ?: bad("target must be an object")
        val kind = node["kind"]?.takeIf(JsonNode::isTextual)?.textValue() ?: bad("target kind is required")
        fun string(name: String): String = node[name]?.takeIf(JsonNode::isTextual)?.textValue()
            ?.takeIf(String::isNotBlank) ?: bad("$name must be text")
        val target = when (kind) {
            "extension" -> {
                if (node.fieldNames().asSequence().toSet() !=
                    setOf("kind", "slot", "id", "title")
                ) {
                    bad("Unexpected extension target fields")
                }
                AuthoringTarget.Extension(string("slot"), string("id"), string("title"))
            }
            "formulaSlot" -> {
                if (node.fieldNames().asSequence().toSet() !=
                    setOf("kind", "id")
                ) {
                    bad("Unexpected formula slot target fields")
                }
                AuthoringTarget.FormulaSlot(string("id"))
            }
            else -> bad("Unknown authoring target")
        }
        return Triple(target, source, cursor)
    }

    private fun parseValue(node: JsonNode, depth: Int = 0): Value {
        fun bad(): Nothing = throw WorkspaceException(WorkspaceProblem.REQUEST, "Invalid encoded value")
        if (depth > 16) bad()
        if (node.isNull) return Value.Nil
        if (node.isBoolean) return Value.Bool(node.booleanValue())
        if (node.isTextual) return Value.Text(node.textValue())
        if (node.isArray) return Value.Vec(node.map { parseValue(it, depth + 1) })
        if (!node.isObject || node.size() != 1) bad()
        val field = node.fieldNames().next()
        val item = node[field]
        return when (field) {
            "n" -> {
                if (!item.isTextual || !Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?").matches(item.textValue())) bad()
                Value.Num(BigDecimal(item.textValue()))
            }
            "kw" -> {
                if (!item.isTextual ||
                    !Regex("[A-Za-z][A-Za-z0-9_-]*[?!*]?").matches(item.textValue())
                ) {
                    bad()
                }
                Value.Kw(item.textValue())
            }
            "date" -> {
                if (!item.isTextual) bad()
                try {
                    Value.Date(LocalDate.parse(item.textValue()))
                } catch (
                    _: Exception,
                ) {
                    bad()
                }
            }
            "map" -> {
                if (!item.isArray) bad()
                val entries = linkedMapOf<Value, Value>()
                item.forEach { entry ->
                    if (!entry.isArray || entry.size() != 2) bad()
                    val key = parseValue(entry[0], depth + 1)
                    if (entries.put(key, parseValue(entry[1], depth + 1)) != null) bad()
                }
                Value.MapV(entries)
            }
            else -> bad()
        }
    }
}
