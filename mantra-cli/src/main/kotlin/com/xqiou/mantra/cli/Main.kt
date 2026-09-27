package com.xqiou.mantra.cli

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.engine.MantraKernel
import com.xqiou.mantra.core.engine.MantraLibrary
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.mantra.workbench.Fixtures
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.system.exitProcess

private const val USAGE = """
mantra – calculation-schema engine (Normein DSL)

Usage:
  mantra run <schema.mantra> [--case <case.mantra>] [--layout <layout.mantra>]
             [--format text|html|xlsx] [--out <file>] [--audit]
  mantra check <schema.mantra> [--case <case.mantra>]
  mantra catalog
  mantra fixtures <case.mantra> [more cases...] --out <dir> [--workspace <dir>]
  mantra diff <schema.mantra> --case <case.mantra> --variant-parameters <file[,file...]>
              [--base-parameters <file[,file...]>] [--variant-case <case.mantra>]
              [--layout <layout.mantra>] [--format json|text] [--out <file>]

Commands:
  run      Evaluate the schema for a case and render a working paper (default: text to stdout).
  check    Read, compile and order the schema; print its structure and dependency statistics.
  catalog  List the built-in schema forms, kernel functions, column contents and layout presets.
  fixtures Write versioned Structure, Run, Paper and Diagnostics JSON for cases with a sibling schema.mantra
           and optional sibling layout.mantra. --workspace sets the case-id root.
  diff     Compare two evaluations of one schema (JSON by default).
"""

fun main(args: Array<String>) {
    // POI logs through log4j-api; use its built-in simple logger instead of a missing backend.
    System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    System.setProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
    val command = args.firstOrNull()
    val options = parseOptions(args.drop(1))
    try {
        when (command) {
            "run" -> run(options)
            "check" -> check(options)
            "catalog" -> catalog()
            "fixtures" -> fixtures(options)
            "diff" -> diff(options)
            null, "help", "--help", "-h" -> println(USAGE.trimIndent())
            else -> fail("Unknown command `$command`.\n${USAGE.trimIndent()}")
        }
    } catch (error: MantraException) {
        System.err.println(error.diagnostics.joinToString("\n"))
        exitProcess(2)
    }
}

private class Options(val positional: List<String>, val named: Map<String, String?>) {
    fun path(name: String): Path? = named[name]?.let(Path::of)
    fun flag(name: String): Boolean = named.containsKey(name)
}

private fun parseOptions(args: List<String>): Options {
    val positional = mutableListOf<String>()
    val named = linkedMapOf<String, String?>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        if (arg.startsWith("--")) {
            val name = arg.removePrefix("--")
            val value = args.getOrNull(index + 1)?.takeIf { !it.startsWith("--") && name != "audit" }
            named[name] = value
            index += if (value != null) 2 else 1
        } else {
            positional += arg
            index++
        }
    }
    return Options(positional, named)
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

private fun loadInputs(options: Options): Pair<com.xqiou.mantra.core.model.Schema, CaseData> {
    val schemaPath = options.positional.firstOrNull()?.let(Path::of) ?: fail("A schema file is required.\n${USAGE.trimIndent()}")
    val schema = Mantra.loadSchema(schemaPath)
    val case = options.path("case")?.let(Mantra::loadCase) ?: CaseData.empty()
    return schema to case
}

private fun run(options: Options) {
    val (schema, case) = loadInputs(options)
    val result = Mantra.calculate(schema, case)
    val layout = options.path("layout")?.let(Render::loadLayout) ?: Render.defaultLayout(result)
    if (options.named["format"] == "xlsx") {
        val out = options.path("out") ?: fail("--format xlsx requires --out <file.xlsx>")
        val workbook = com.xqiou.mantra.excel.ExcelExport.workbook(result, layout)
        workbook.write(out)
        val report = workbook.report
        System.err.println("mantra: wrote ${out.toAbsolutePath()} – ${report.formulaCells} formula cells, ${report.inputCells} input cells, ${report.names} names, ${report.fallbacks.size} values without formula")
        report.fallbacks.forEach { System.err.println("  value only: ${it.sheet}!${it.cell} ${it.nodeId}: ${it.reason}") }
        report.evaluationErrors.forEach { System.err.println("  evaluation: $it") }
        result.diagnostics.forEach { System.err.println(it) }
        if (!result.succeeded) exitProcess(3)
        return
    }
    val output = when (options.named["format"] ?: "text") {
        "html" -> Render.html(result, layout)
        "text" -> Render.text(result, layout, includeAudit = options.flag("audit"))
        else -> fail("Unknown --format; use text, html or xlsx")
    }
    val out = options.path("out")
    if (out == null) {
        println(output)
    } else {
        out.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(out, output)
        System.err.println("mantra: wrote ${out.toAbsolutePath()}")
    }
    result.diagnostics.forEach { System.err.println(it) }
    if (!result.succeeded) exitProcess(3)
}

private fun fixtures(options: Options) {
    val cases = options.positional.map(Path::of)
    if (cases.isEmpty()) fail("fixtures requires a case file.\n${USAGE.trimIndent()}")
    val out = options.path("out") ?: fail("fixtures requires --out <dir>")
    val entries = Fixtures.writeMany(cases, out, workspaceRoot = options.path("workspace"))
    println("mantra: wrote ${entries.joinToString { it.id }} fixtures to ${out.toAbsolutePath()}")
}

private fun diff(options: Options) {
    val (schema, baseCase) = loadInputs(options)
    val variantCase = options.path("variant-case")?.let(Mantra::loadCase) ?: baseCase
    fun sets(name: String) = options.named[name].orEmpty().split(',').filter { it.isNotBlank() }
        .map { Mantra.loadParameters(Path.of(it.trim())) }
    val baseSets = sets("base-parameters")
    val variantSets = sets("variant-parameters")
    if (variantSets.isEmpty() && options.path("variant-case") == null) {
        fail("diff requires --variant-parameters or --variant-case")
    }
    val base = Mantra.calculate(schema, baseCase, baseSets)
    val variant = Mantra.calculate(schema, variantCase, variantSets)
    val layout = options.path("layout")?.let(Render::loadLayout) ?: Render.defaultLayout(base)
    val document = WorkbenchDocuments.compare(CalculationView.of(base), CalculationView.of(variant), layout,
        variantSets.map { it.id })
    val schemaPath = options.positional.first().let(Path::of).toAbsolutePath().normalize()
    val files = buildList {
        add(schemaPath)
        schema.sources.forEach { add(schemaPath.parent.resolve(it).normalize()) }
        options.path("case")?.let { add(it.toAbsolutePath().normalize()) }
        options.path("variant-case")?.let { add(it.toAbsolutePath().normalize()) }
        options.path("layout")?.let { add(it.toAbsolutePath().normalize()) }
        listOf("base-parameters", "variant-parameters").forEach { name ->
            options.named[name].orEmpty().split(',').filter { it.isNotBlank() }
                .forEach { add(Path.of(it.trim()).toAbsolutePath().normalize()) }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    files.distinct().sortedBy(Path::toString).forEach { file ->
        val name = if (file.startsWith(schemaPath.parent)) schemaPath.parent.relativize(file).toString() else file.toString()
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(Files.readAllBytes(file))
        digest.update(0.toByte())
    }
    val revision = digest.digest().take(8).joinToString("") { "%02x".format(it) }
    var directory: Path? = schemaPath.parent
    var normein = "unknown"
    while (directory != null) {
        val lock = directory.resolve("normein-build.lock")
        if (Files.isRegularFile(lock)) {
            normein = Files.readAllLines(lock).firstOrNull { it.startsWith("normeinCommit=") }
                ?.substringAfter('=')?.take(8) ?: "unknown"
            break
        }
        directory = directory.parent
    }
    val envelope = WorkbenchJson.envelope(revision, "0.1.0-SNAPSHOT", normein, document)
    val output = when (options.named["format"] ?: "json") {
        "json" -> WorkbenchJson.write(envelope)
        "text" -> buildString {
            appendLine("Compare ${schema.id}")
            @Suppress("UNCHECKED_CAST")
            val mainline = document["mainline"] as List<Map<String, Any?>>
            mainline.forEach { row ->
                val display = row["display"] as Map<*, *>
                appendLine("${row["step"]}. ${row["panel"]}: ${display["base"]} → ${display["variant"]} (${display["delta"]})")
            }
            @Suppress("UNCHECKED_CAST")
            val changes = document["changes"] as List<Map<String, Any?>>
            changes.forEach { group ->
                appendLine("${group["step"] ?: "·"} ${group["panel"] ?: "general"}")
                @Suppress("UNCHECKED_CAST")
                (group["items"] as List<Map<String, Any?>>).forEach { item ->
                    val display = item["display"] as Map<*, *>
                    val coord = (item["coord"] as List<*>).joinToString("/")
                    appendLine("  ${item["node"]}${if (coord.isEmpty()) "" else "[$coord]"}: ${display["base"]} → ${display["variant"]} (${display["delta"] ?: "–"})")
                }
            }
            @Suppress("UNCHECKED_CAST")
            val parameters = document["parameterChanges"] as List<Map<String, Any?>>
            if (parameters.isNotEmpty()) appendLine("Parameters")
            parameters.forEach { item ->
                val display = item["display"] as Map<*, *>
                appendLine("  ${item["node"]}: ${display["base"]} → ${display["variant"]} (${display["delta"] ?: "–"})")
            }
        }.trimEnd()
        else -> fail("Unknown --format; use json or text")
    }
    options.path("out")?.let { out ->
        out.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(out, output + "\n")
        System.err.println("mantra: wrote ${out.toAbsolutePath()}")
    } ?: println(output)
    (base.diagnostics + variant.diagnostics).forEach { System.err.println(it) }
    if (!base.succeeded || !variant.succeeded) exitProcess(3)
}

private fun check(options: Options) {
    val (schema, case) = loadInputs(options)
    val plan = Mantra.plan(schema, case)
    val values = plan.valueVertices.values
    println("Schema ${schema.id} – ${schema.meta.title}")
    println("  sources:     ${schema.sources.joinToString()}")
    println("  dimensions:  ${plan.dimensions.keys.joinToString().ifEmpty { "–" }}")
    println("  parameters:  ${values.count { it is com.xqiou.mantra.core.engine.ParamVertex }}")
    println("  inputs:      ${values.count { it is com.xqiou.mantra.core.engine.InputVertex }}")
    println("  lines:       ${values.count { it is com.xqiou.mantra.core.engine.LineVertex }}")
    println("  totals:      ${values.count { it is com.xqiou.mantra.core.engine.TotalVertex }}")
    println("  choices:     ${values.count { it is com.xqiou.mantra.core.engine.ChoiceVertex }}")
    println("  functions:   ${schema.functions.joinToString { it.name }.ifEmpty { "–" }}")
    println("  dependency edges: ${plan.vertices.values.sumOf { it.dependencies.size }}")
    println()
    fun tree(section: com.xqiou.mantra.core.engine.ResolvedSection, depth: Int) {
        section.children.forEach { child ->
            val indent = "  ".repeat(depth)
            when (child) {
                is com.xqiou.mantra.core.engine.ResolvedSection -> {
                    val dims = if (child.dims.isEmpty()) "" else " per ${child.dims.joinToString()}"
                    val kind = if (child.resultId != null) " → ${child.resultId}" else ""
                    println("$indent§ ${child.id} \"${child.label}\"$dims$kind")
                    tree(child, depth + 1)
                }
                is com.xqiou.mantra.core.engine.ResolvedNode -> {
                    val sign = when {
                        child.item is com.xqiou.mantra.core.model.TotalItem -> "="
                        child.op > 0 -> "+"
                        child.op < 0 -> "-"
                        else -> "·"
                    }
                    println("$indent$sign ${child.id}")
                }
                is com.xqiou.mantra.core.engine.ResolvedNote -> Unit
            }
        }
    }
    tree(plan.tree, 0)
}

private fun catalog() {
    println("Schema forms (calculation layer)")
    listOf(
        "(schema id {meta} decl*)" to "calculation schema document",
        "(fragment decl*)" to "included document",
        "(include \"path\")" to "include a fragment",
        "(param id literal {opts})" to "template parameter, overridable per case",
        "(input id :type {opts})" to "user fact (:decimal :integer :boolean :keyword :text :date :table)",
        "(dimension id {:members [...] | :from table})" to "members; table dimensions may declare :parent and :parent-key",
        "(defn name [^Type arg] body)" to "helper function in Normein DSL",
        "(section id \"Label\" {opts} item*)" to "grouping; with a total it has a running sum",
        "(field id \"Label\" {opts})" to "input shown in place",
        "(line id \"Label\" formula {opts})" to "computed line (Normein expression)",
        "(formula-slot id \"Label\" default {opts})" to "typed formula hook; cases may use (bind id formula)",
        "(total id \"Label\" {opts})" to "running subtotal / total of the section",
        "(choice id \"Label\" {:rule :min|:max} (option :key \"Label\" formula)+)" to "alternatives (Günstigerprüfung, higher-of)",
        "(slot id \"Label\" {opts})" to "extension point filled by (extend id ...) in a case",
        "(note \"Text\")" to "text row",
    ).forEach { (form, text) -> println("  %-64s %s".format(form, text)) }
    println("  item options: :op :plus|:minus|:info  :per dim|[dims]  :when expr  :round n|[n :mode]  :spread true")
    println("                :reference :note :source :format :precision :hidden :type :class")
    println("                application attributes (for example :kz or :zeile) pass through unchanged")
    println()
    println("Kernel functions (${MantraLibrary.LIBRARY_ID}@${MantraLibrary.SEMANTICS_VERSION}, plus the Normein standard library)")
    MantraLibrary.functions.forEach { println("  %-16s %s".format(it.name, it.documentation.summary)) }
    println("  (${MantraKernel.environment.registry.functions.size} callables in total)")
    println()
    println("Layout forms (presentation layer)")
    println("  (layout id {:preset … :locale … :precision … :negative … :zero … :hide-zero … :explain … :signed …} form*)")
    println("  (operators {...})  (columns :tiered|:matrix col*)  (table section-id {opts} col*)")
    println("  (attribute :name {opts}?)  application metadata column")
    println("  (schedule id*)  (inline id*)  (hide id*)")
    println("  (col id {:content <content> :header \"…\" :width n :align …})  (members dim)  (member dim :key)")
    println("  column contents: ${ColumnContent.catalog.joinToString()}")
    println("  presets: ${Presets.ALL.keys.joinToString()}")
}
