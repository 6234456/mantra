package com.xqiou.mantra.cli

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.api.AuditOptions
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.api.CaseExplainAddress
import com.xqiou.mantra.core.api.CaseGraphInspector
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunDiagnostic
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.CaseRunResult
import com.xqiou.mantra.core.api.FunctionCatalog
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.NodeKind
import com.xqiou.mantra.core.view.ViewNote
import com.xqiou.mantra.core.view.ViewSection
import com.xqiou.mantra.core.view.ViewTreeNode
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.ColumnContent
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.render.layout.Presets
import com.xqiou.mantra.server.WorkbenchServer
import com.xqiou.mantra.workbench.CasePackageLoader
import com.xqiou.mantra.workbench.CasePackageOverrides
import com.xqiou.mantra.workbench.Fixtures
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val USAGE = """
mantra – calculation-schema engine (Normein DSL)

Usage:
  mantra run <schema.mantra> [--case <case.mantra>] [--workspace <dir>] [--layout <layout.mantra>]
             [--parameters <file[,file...]>] [--format text|html|xlsx|pdf] [--out <file>] [--audit]
  mantra check <schema.mantra> [--case <case.mantra>] [--workspace <dir>] [--parameters <file[,file...]>]
  mantra catalog
  mantra lsp [--stdio]
  mantra fixtures <case.mantra> [more cases...] --out <dir> [--workspace <dir>]
  mantra serve <workspace> [--port <number>] [--ui <workbench-ui/dist>]
             [--packages <host-config.json>] [--directory-policy strict-handles|trusted-local]
  mantra diff <schema.mantra> --case <case.mantra> --variant-parameters <file[,file...]>
              [--base-parameters <file[,file...]>] [--variant-case <case.mantra>]
              [--workspace <dir>] [--layout <layout.mantra>] [--format json|text] [--out <file>]
  mantra explain <schema.mantra> --case <case.mantra> --address <node>
                 [--coord <member[,member...]>] [--parameters <file[,file...]>]
                 [--workspace <dir>] [--layout <layout.mantra>] [--format json|text] [--out <file>]

Explicit package commands (separate read-only manifest mounts and writable host cases):
  mantra package-list --packages <host-config.json>
  mantra package-run --packages <host-config.json> --case <mount/case-path> [--format json|text|html|xlsx|pdf] [--out <file>]
  mantra package-explain --packages <host-config.json> --case <mount/case-path> --node <id> [--coord <members>]
  mantra package-migration-preview --packages <host-config.json> --case <id> --base-revision <sha256> --target-case <id>
  mantra package-migration-apply --packages <host-config.json> --case <id> --base-revision <sha256> --target-case <id> --review-token <token>
  mantra package-serve --packages <host-config.json> --workspace <legacy-host-root> [--port <number>] [--ui <dir>]

Commands:
  run      Evaluate the schema for a case and render a working paper (default: text to stdout).
  check    Compile the schema and linked case graph; report static diagnostics and structure.
  catalog  List the built-in schema forms, kernel functions, column contents and layout presets.
  fixtures Write versioned Structure, Run, Paper and Diagnostics JSON for cases with a sibling schema.mantra.
           A declared :layout resolves to sibling layout.mantra. --workspace sets the case-id root.
  serve    Start the loopback-only workbench service with structured case editing, Explain and live updates.
  diff     Compare two evaluations of one schema (JSON by default).
  explain  Explain one calculated value with bounded source steps (JSON by default).
"""

fun main(args: Array<String>) {
    com.xqiou.mantra.server.WorkbenchTransport.installDefaults()
    // POI logs through log4j-api; use its built-in simple logger instead of a missing backend.
    System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    System.setProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
    val status = executeCli(args)
    if (status != 0) exitProcess(status)
}

/** The real command dispatcher, with explicit streams and exit status for in-process integration tests. */
internal fun executeCli(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int = try {
    if (args.firstOrNull() == "lsp") {
        require(args.drop(1).isEmpty() || args.drop(1) == listOf("--stdio")) { "lsp accepts only --stdio" }
        com.xqiou.mantra.lsp.main()
    } else {
        val options = parseOptions(args.drop(1), out, err)
        when (val command = args.firstOrNull()) {
            "run" -> run(options)
            "check" -> check(options)
            "catalog" -> catalog(options)
            "package-list",
            "package-run",
            "package-explain",
            "package-migration-preview",
            "package-migration-apply",
            "package-serve",
            -> {
                val status = executePackageCli(args, out, err)
                if (status != 0) throw CliExit(status)
            }
            "fixtures" -> fixtures(options)
            "serve" -> serve(options)
            "diff" -> diff(options)
            "explain" -> explain(options)
            null, "help", "--help", "-h" -> out.println(USAGE.trimIndent())
            else -> fail("Unknown command `$command`.\n${USAGE.trimIndent()}")
        }
    }
    0
} catch (error: CliExit) {
    error.message?.let(err::println)
    error.status
} catch (error: com.xqiou.mantra.packages.PackageException) {
    error.diagnostics.forEach(err::println)
    2
} catch (error: MantraException) {
    err.println(error.diagnostics.joinToString("\n"))
    2
} catch (error: WorkspaceException) {
    err.println("mantra: ${error.message}")
    error.diagnostics.forEach(err::println)
    2
} catch (error: IOException) {
    err.println("mantra: ${error.message}")
    2
}

private class CliExit(val status: Int, message: String? = null) : RuntimeException(message)

private class Options(
    val positional: List<String>,
    val named: Map<String, String?>,
    val out: PrintStream,
    val err: PrintStream,
) {
    fun path(name: String): Path? = named[name]?.let(Path::of)
        ?: if (flag(name)) fail("--$name requires a path") else null
    fun flag(name: String): Boolean = named.containsKey(name)
    fun paths(name: String): List<Path>? = if (flag(name)) {
        val value = named[name] ?: fail("--$name requires a comma-separated file list")
        value.split(',').filter(String::isNotBlank).map { Path.of(it.trim()).toAbsolutePath().normalize() }
    } else {
        null
    }
    fun schemaPath(): Path = positional.firstOrNull()?.let(Path::of)?.toAbsolutePath()?.normalize()
        ?: fail("A schema file is required.\n${USAGE.trimIndent()}")
}

private fun parseOptions(args: List<String>, out: PrintStream, err: PrintStream): Options {
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
    return Options(positional, named, out, err)
}

private fun fail(message: String): Nothing = throw CliExit(1, message)

private data class CliEvaluation(
    val result: CalculationResult,
    val layout: LayoutSpec,
    val revision: String?,
    val parameterIds: List<String>,
    val graph: CaseRunResult? = null,
) {
    val succeeded: Boolean get() = graph?.succeeded ?: result.succeeded
    fun diagnostics(options: Options) {
        if (graph == null) {
            result.diagnostics.forEach(options.err::println)
        } else {
            printGraphDiagnostics(graph, options)
        }
    }
}

private fun printGraphDiagnostics(graph: CaseRunResult, options: Options) =
    printGraphDiagnostics(graph.diagnostics, options)

private fun printGraphDiagnostics(diagnostics: List<CaseRunDiagnostic>, options: Options) {
    diagnostics.forEach { owned ->
        val identity = owned.case?.value?.let { case ->
            "$case${owned.revision?.let { "@$it" }.orEmpty()}: "
        }.orEmpty()
        options.err.println("$identity${owned.finding}")
    }
}

private data class CliCaseLoader(val loader: CasePackageLoader, val reference: CaseReference)

private fun caseLoader(
    options: Options,
    casePath: Path,
    parameterOption: String,
    includeLayout: Boolean = true,
): CliCaseLoader {
    val actualCase = casePath.toRealPath()
    val workspace = (options.path("workspace") ?: actualCase.parent).toRealPath()
    if (!Files.isDirectory(workspace)) fail("--workspace requires a directory")
    if (!actualCase.startsWith(workspace)) fail("Case is outside --workspace: $casePath")
    val key = workspace.relativize(actualCase).toString().replace('\\', '/')
    return CliCaseLoader(
        CasePackageLoader(
            workspace,
            CasePackageOverrides(
                rootCase = key,
                schemaPath = options.schemaPath(),
                parameterPaths = options.paths(parameterOption),
                layoutPath = options.path("layout")?.toAbsolutePath()?.normalize(),
                includeLayout = includeLayout,
            ),
        ),
        CaseReference(key),
    )
}

/** Explicit root files are capabilities; they never change the workspace permitted to link or data reads. */
private fun evaluate(
    options: Options,
    casePath: Path? = options.path("case"),
    parameterOption: String = "parameters",
    audit: Boolean = true,
    explain: InputAddress? = null,
): CliEvaluation {
    val schemaPath = options.schemaPath()
    val parameterPaths = options.paths(parameterOption)
    if (casePath == null) {
        // A schema-only invocation has no case/link declarations to resolve.
        val schema = Mantra.loadSchema(schemaPath)
        val parameters = parameterPaths.orEmpty().map(Mantra::loadParameters)
        val result = Mantra.calculateForAudit(schema, CaseData.empty(), parameters)
        return CliEvaluation(
            result,
            options.path("layout")?.let(Render::loadLayout) ?: Render.defaultLayout(result),
            null,
            parameters.map { it.id },
        )
    }
    val prepared = caseLoader(options, casePath, parameterOption)
    val loader = prepared.loader
    val graph = CaseGraphRunner(loader).use { runner ->
        runner.run(
            CaseRunRequest(
                reference = prepared.reference,
                audit = if (audit) AuditOptions() else null,
                explain = explain?.let { CaseExplainAddress(address = it) },
            ),
        )
    }
    val result = graph.result ?: run {
        printGraphDiagnostics(graph, options)
        throw CliExit(3)
    }
    val root = graph.root ?: throw CliExit(3, "Case graph did not resolve a root")
    val binding = loader.binding(root)
    return CliEvaluation(
        result,
        binding.layout ?: Render.defaultLayout(result),
        graph.cases.getValue(root).revision,
        binding.parameterIds,
        graph,
    )
}

private fun run(options: Options) {
    val execution = evaluate(options)
    val result = execution.result
    val layout = execution.layout
    if (options.named["format"] == "pdf") {
        val out = options.path("out") ?: fail("--format pdf requires --out <file.pdf>")
        out.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.write(out, Render.pdf(result, layout))
        execution.diagnostics(options)
        if (!execution.succeeded) throw CliExit(3)
        return
    }
    if (options.named["format"] == "xlsx") {
        val out = options.path("out") ?: fail("--format xlsx requires --out <file.xlsx>")
        val workbook = com.xqiou.mantra.excel.ExcelExport.workbook(result, layout)
        workbook.use { it.write(out) }
        val report = workbook.report
        options.err.println(
            "mantra: wrote ${out.toAbsolutePath()} – ${report.formulaCells} formula cells, ${report.inputCells} input cells, ${report.names} names, ${report.fallbacks.size} values without formula",
        )
        report.fallbacks.forEach {
            options.err.println("  value only: ${it.sheet}!${it.cell} ${it.nodeId}: ${it.reason}")
        }
        report.evaluationErrors.forEach { options.err.println("  evaluation: $it") }
        execution.diagnostics(options)
        if (!execution.succeeded) throw CliExit(3)
        return
    }
    val output = when (options.named["format"] ?: "text") {
        "html" -> Render.html(result, layout)
        "text" -> Render.text(result, layout, includeAudit = options.flag("audit"))
        else -> fail("Unknown --format; use text, html or xlsx")
    }
    val out = options.path("out")
    if (out == null) {
        options.out.println(output)
    } else {
        out.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(out, output)
        options.err.println("mantra: wrote ${out.toAbsolutePath()}")
    }
    execution.diagnostics(options)
    if (!execution.succeeded) throw CliExit(3)
}

private fun fixtures(options: Options) {
    val cases = options.positional.map(Path::of)
    if (cases.isEmpty()) fail("fixtures requires a case file.\n${USAGE.trimIndent()}")
    val out = options.path("out") ?: fail("fixtures requires --out <dir>")
    val entries = Fixtures.writeMany(cases, out, workspaceRoot = options.path("workspace"))
    options.out.println("mantra: wrote ${entries.joinToString { it.id }} fixtures to ${out.toAbsolutePath()}")
}

private fun serve(options: Options) {
    val workspace = options.positional.singleOrNull()?.let(Path::of)
        ?: fail("serve requires one workspace directory.\n${USAGE.trimIndent()}")
    val port =
        options.named["port"]?.toIntOrNull() ?: if (options.flag("port")) fail("--port requires a number") else 8080
    if (port !in 0..65535) fail("--port must be between 0 and 65535")
    val ui = options.path("ui")
    if (options.flag("ui") && ui == null) fail("--ui requires a directory")
    if (ui != null && !Files.isDirectory(ui)) fail("--ui directory does not exist: $ui")
    val policy = when (options.named["directory-policy"]) {
        null, "strict-handles" -> com.xqiou.mantra.packages.DirectoryPolicy.STRICT_HANDLES
        "trusted-local" -> com.xqiou.mantra.packages.DirectoryPolicy.TRUSTED_LOCAL
        else -> fail("--directory-policy must be strict-handles or trusted-local")
    }
    val packages = options.path("packages")?.let {
        com.xqiou.mantra.workbench.packages.PackageWorkspaceConfig.open(
            it,
            com.xqiou.mantra.packages.SemanticVersion.parse(com.xqiou.mantra.core.api.RuntimeVersions.mantra),
        )
    } ?: com.xqiou.mantra.workbench.packages.PackageDirectoryWorkspace.open(workspace, policy)
    val server = WorkbenchServer(
        workspace,
        port,
        ui ?: Path.of("workbench-ui/dist").takeIf(Files::isDirectory),
        packageWorkspace = packages,
    ).start()
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    options.out.println("mantra: serving ${workspace.toAbsolutePath()} at http://127.0.0.1:${server.localPort}/")
    CountDownLatch(1).await()
}

private fun diff(options: Options) {
    val baseCase = options.path("case") ?: fail("diff requires --case <case.mantra>")
    if (options.paths("variant-parameters").isNullOrEmpty() && options.path("variant-case") == null) {
        fail("diff requires --variant-parameters or --variant-case")
    }
    val baseExecution = evaluate(options, baseCase, "base-parameters", audit = false)
    val variantExecution =
        evaluate(options, options.path("variant-case") ?: baseCase, "variant-parameters", audit = false)
    val base = baseExecution.result
    val variant = variantExecution.result
    val schema = base.schema
    val document = WorkbenchDocuments.compare(
        base.view,
        variant.view,
        baseExecution.layout,
        variantExecution.parameterIds,
    )
    val revision = DiffRevision.calculate(
        requireNotNull(baseExecution.revision),
        requireNotNull(variantExecution.revision),
    )
    val envelope = WorkbenchJson.envelope(
        revision,
        com.xqiou.mantra.core.api.RuntimeVersions.mantra,
        com.xqiou.mantra.core.api.RuntimeVersions.normein,
        document,
    )
    val output = when (options.named["format"] ?: "json") {
        "json" -> WorkbenchJson.write(envelope)
        "text" -> buildString {
            appendLine("Compare ${schema.id}")
            @Suppress("UNCHECKED_CAST")
            val mainline = document["mainline"] as List<Map<String, Any?>>
            mainline.forEach { row ->
                val display = row["display"] as Map<*, *>
                appendLine(
                    "${row["step"]}. ${row["panel"]}: ${display["base"]} → ${display["variant"]} (${display["delta"]})",
                )
            }
            @Suppress("UNCHECKED_CAST")
            val changes = document["changes"] as List<Map<String, Any?>>
            changes.forEach { group ->
                appendLine("${group["step"] ?: "·"} ${group["panel"] ?: "general"}")
                @Suppress("UNCHECKED_CAST")
                (group["items"] as List<Map<String, Any?>>).forEach { item ->
                    val display = item["display"] as Map<*, *>
                    val coord = (item["coord"] as List<*>).joinToString("/")
                    appendLine(
                        "  ${item["node"]}${if (coord.isEmpty()) "" else "[$coord]"}: ${display["base"]} → ${display["variant"]} (${display["delta"] ?: "–"})",
                    )
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
        options.err.println("mantra: wrote ${out.toAbsolutePath()}")
    } ?: options.out.println(output)
    baseExecution.diagnostics(options)
    variantExecution.diagnostics(options)
    if (!baseExecution.succeeded || !variantExecution.succeeded) throw CliExit(3)
}

private fun explain(options: Options) {
    if (options.path("case") == null) fail("explain requires --case <case.mantra>")
    val address = options.named["address"]?.takeIf(String::isNotBlank)
        ?: fail("explain requires --address <node>")
    val aggregate = address.startsWith("aggregate.")
    val nodeId = address.removePrefix("aggregate.")
    val coord = options.named["coord"]?.split(',')?.map(String::trim) ?: emptyList()
    if (coord.any(String::isBlank)) fail("--coord must contain nonempty member keys")
    val execution = evaluate(options, explain = if (aggregate) null else InputAddress(nodeId, coord))
    val result = execution.result
    val view = CalculationView.of(result)
    val node = view.nodes[nodeId] ?: fail("Explain node was not found: $nodeId")
    if (!aggregate && (
            node.dims.size != coord.size ||
                coord !in node.values
            )
    ) {
        fail("Explain coordinate was not found: $nodeId${coord.joinToString(prefix = "[", postfix = "]")}")
    }
    val layout = execution.layout
    val document = if (aggregate) {
        val fixed = coord.associate { binding ->
            val parts = binding.split('=', limit = 2)
            if (parts.size != 2 || parts.any(String::isBlank)) fail("Aggregate --coord requires dimension=member")
            parts[0] to parts[1]
        }
        if (fixed.size != coord.size || view.dimensionOrder(fixed.keys).map { "$it=${fixed.getValue(it)}" } != coord) {
            fail("Aggregate --coord must contain unique dimensions in schema declaration order")
        }
        val reduced = try {
            view.reduce(nodeId, fixed)
        } catch (failure: IllegalArgumentException) {
            fail(failure.message ?: "Aggregate coordinate was not found")
        }
        if (reduced.trace == null) fail("Explain aggregate was not found: $nodeId")
        WorkbenchDocuments.aggregate(view, layout, nodeId, fixed)
    } else {
        WorkbenchDocuments.explain(view, layout, nodeId, coord, result.explainTrace)
    }
    val revision = requireNotNull(execution.revision)
    val output = when (options.named["format"] ?: "json") {
        "json" -> WorkbenchJson.write(
            WorkbenchJson.envelope(
                revision,
                com.xqiou.mantra.core.api.RuntimeVersions.mantra,
                com.xqiou.mantra.core.api.RuntimeVersions.normein,
                document + ("revision" to revision),
            ),
        )
        "text" -> buildString {
            appendLine("${document["label"]}: ${(document["result"] as Map<*, *>) ["display"]}")
            @Suppress("UNCHECKED_CAST")
            (document["steps"] as List<Map<String, Any?>>).forEach { step ->
                appendLine("  ${step["text"]} = ${step["display"]}")
            }
            (document["aggregate"] as? Map<*, *>)?.let { trace ->
                val display = trace["display"] as Map<*, *>
                if (trace["kind"] == "ratio") {
                    appendLine("  Σ ${trace["numeratorId"]}: ${display["numeratorTotal"]}")
                    appendLine("  Σ ${trace["denominatorId"]}: ${display["denominatorTotal"]}")
                } else if (trace["kind"] == "boundary") {
                    appendLine("  ${trace["boundary"]} period of ${trace["dimension"]}")
                }
                appendLine("  active members: ${trace["activeMemberCount"]}/${trace["memberCount"]}")
                trace["undefinedReason"]?.let { appendLine("  undefined: $it") }
            }
        }.trimEnd()
        else -> fail("Unknown --format; use json or text")
    }
    options.path("out")?.let { out ->
        out.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(out, output + "\n")
        options.err.println("mantra: wrote ${out.toAbsolutePath()}")
    } ?: options.out.println(output)
    execution.diagnostics(options)
    if (!execution.succeeded) throw CliExit(3)
}

private fun check(options: Options) {
    val prepared = options.path("case")?.let { caseLoader(options, it, "parameters", includeLayout = false) }
    val inspection = prepared?.let { CaseGraphInspector(it.loader).inspect(it.reference) }
    if (inspection != null) {
        printGraphDiagnostics(inspection.diagnostics, options)
        if (!inspection.succeeded) throw CliExit(3)
    }
    val schema = inspection?.root?.let { prepared!!.loader.binding(it).packageData.schema }
        ?: Mantra.loadSchema(options.schemaPath())
    val view =
        inspection?.view
            ?: Mantra.inspect(
                schema,
                CaseData.empty(),
                options.paths("parameters").orEmpty().map(Mantra::loadParameters),
            )
    val values = view.nodes.values
    options.out.println("Schema ${schema.id} – ${schema.meta.title}")
    options.out.println("  sources:     ${schema.sources.joinToString()}")
    options.out.println("  dimensions:  ${view.dimensions.keys.joinToString().ifEmpty { "–" }}")
    options.out.println("  parameters:  ${values.count { it.kind == NodeKind.PARAM }}")
    options.out.println("  inputs:      ${values.count { it.kind == NodeKind.INPUT }}")
    options.out.println("  lines:       ${values.count { it.line != null }}")
    options.out.println("  totals:      ${values.count { it.kind == NodeKind.TOTAL }}")
    options.out.println("  choices:     ${values.count { it.kind == NodeKind.CHOICE }}")
    options.out.println("  checks:      ${values.count { it.kind == NodeKind.CHECK }}")
    options.out.println("  reconciles:  ${values.count { it.kind == NodeKind.RECONCILE }}")
    options.out.println("  functions:   ${schema.functions.joinToString { it.name }.ifEmpty { "–" }}")
    options.out.println("  dependency edges: ${view.dependencyCount}")
    options.out.println()
    fun tree(section: ViewSection, depth: Int) {
        section.children.forEach { child ->
            val indent = "  ".repeat(depth)
            when (child) {
                is ViewSection -> {
                    val dims = if (child.dims.isEmpty()) "" else " per ${child.dims.joinToString()}"
                    val kind = if (child.resultId != null) " → ${child.resultId}" else ""
                    options.out.println("$indent§ ${child.id} \"${child.label}\"$dims$kind")
                    tree(child, depth + 1)
                }
                is ViewTreeNode -> {
                    val sign = when {
                        child.item is com.xqiou.mantra.core.model.TotalItem -> "="
                        child.op > 0 -> "+"
                        child.op < 0 -> "-"
                        else -> "·"
                    }
                    options.out.println("$indent$sign ${child.id}")
                }
                is ViewNote -> Unit
            }
        }
    }
    tree(view.tree, 0)
}

private fun catalog(options: Options) {
    options.out.println("Schema forms (calculation layer)")
    com.xqiou.mantra.core.api.language.LanguageCatalog.forms.forEach {
        options.out.println("  %-64s %s".format(it.syntax, it.summary))
    }
    options.out.println(
        "  item options: :op :plus|:minus|:info  :per dim|[dims]  :when expr  :round n|[n :mode]  :spread true",
    )
    options.out.println("                :reference :note :source :format :precision :hidden :type :class")
    options.out.println(
        "                :aggregate :sum|:none|{:ratio [numerator denominator]}|{:first period}|{:last period}",
    )
    options.out.println("                application attributes (for example :kz or :zeile) pass through unchanged")
    options.out.println()
    options.out.println(
        "Kernel functions (${FunctionCatalog.libraryId}@${FunctionCatalog.semanticsVersion}, plus the Normein standard library)",
    )
    FunctionCatalog.functions.forEach { options.out.println("  %-16s %s".format(it.name, it.summary)) }
    options.out.println("  (${FunctionCatalog.callableCount} callables in total)")
    options.out.println()
    options.out.println("Layout forms (presentation layer)")
    com.xqiou.mantra.render.layout.LayoutLanguageCatalog.forms.forEach {
        options.out.println("  %-64s %s".format(it.syntax, it.summary))
    }
    options.out.println("  presets: ${Presets.ALL.keys.joinToString()}")
}
