package com.xqiou.mantra.cli

import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private data class AcceptanceOptions(val positional: List<String>, val workspace: Path, val output: Path?)

/** Independently versioned, read-only acceptance commands; legacy commands keep their existing syntax. */
internal fun executeTemplateAcceptanceCli(args: Array<String>, out: PrintStream, err: PrintStream): Int {
    val command = args.firstOrNull()
    val options = try {
        acceptanceOptions(args)
    } catch (error: IllegalArgumentException) {
        err.println("mantra: ${error.message}")
        return 1
    }
    return try {
        WorkspaceCatalog(options.workspace).use { catalog ->
            if (command == "template-capture") {
                val capture = captureTemplateAcceptanceSuite(catalog, options.positional)
                val payload = WorkbenchJson.write(capture.suite)
                // A capture must itself fit the public reader, including a large dependency graph.
                parseTemplateAcceptanceSuite(payload.toByteArray(Charsets.UTF_8))
                writeAcceptanceJson(payload, options.output, catalog, capture.sourceDocuments, emptyList(), true, out)
                err.println(
                    "Captured ${options.positional.size} case(s). Fill expect with independent expectations before verification.",
                )
                0
            } else {
                val suitePath = Path.of(options.positional.single())
                val suite = readTemplateAcceptanceSuite(suitePath)
                val run = runTemplateAcceptanceSuite(catalog, suite)
                writeAcceptanceJson(
                    WorkbenchJson.write(run.report),
                    options.output,
                    catalog,
                    run.sourceDocuments,
                    listOf(suitePath),
                    run.sourceCaptureComplete,
                    out,
                )
                err.println(
                    if (run.status ==
                        0
                    ) {
                        "Template acceptance passed."
                    } else {
                        "Template acceptance did not pass; inspect the report."
                    },
                )
                run.status
            }
        }
    } catch (error: WorkspaceException) {
        err.println("mantra: ${error.message}")
        error.diagnostics.forEach(err::println)
        2
    } catch (error: MantraException) {
        error.diagnostics.forEach(err::println)
        2
    } catch (error: IOException) {
        err.println("mantra: ${error.message}")
        2
    } catch (error: IllegalArgumentException) {
        err.println("mantra: ${error.message}")
        2
    }
}

private fun acceptanceOptions(args: Array<String>): AcceptanceOptions {
    val command = args.firstOrNull()
    require(command == "template-capture" || command == "template-verify") { "Unknown template acceptance command" }
    val positional = mutableListOf<String>()
    val named = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val arg = args[index++]
        if (arg.startsWith("--")) {
            val key = arg.removePrefix("--")
            require(key in setOf("workspace", "out") && key !in named) { "Unexpected or duplicate option $arg" }
            val value = args.getOrNull(index++)
            require(value != null && value.isNotBlank() && !value.startsWith("--")) { "$arg requires a value" }
            named[key] = value
        } else {
            require(arg.isNotBlank()) { "An empty file argument is not allowed" }
            positional += arg
        }
    }
    require(positional.size in 1..32 && (command == "template-capture" || positional.size == 1)) {
        if (command ==
            "template-capture"
        ) {
            "Provide 1–32 workspace-relative cases"
        } else {
            "Provide exactly one acceptance suite JSON file"
        }
    }
    val workspace = named["workspace"] ?: throw IllegalArgumentException("--workspace is required")
    return AcceptanceOptions(positional, Path.of(workspace), named["out"]?.let(Path::of))
}

/** Replace the output entry without following its final symlink or writing through a hard link. */
private fun writeAcceptanceJson(
    payload: String,
    output: Path?,
    catalog: WorkspaceCatalog,
    sourceDocuments: Set<String>,
    protectedFiles: List<Path>,
    sourceCaptureComplete: Boolean,
    out: PrintStream,
) {
    if (output == null) {
        out.println(payload)
        return
    }
    val requested = output.toAbsolutePath().normalize()
    val protected = protectedFiles.map { it.toAbsolutePath().normalize() } +
        sourceDocuments.map { catalog.root.resolve(it).toAbsolutePath().normalize() }
    fun check(target: Path) {
        require(!Files.isSymbolicLink(target)) { "Output must not be a symbolic link" }
        require(
            protected.none { source ->
                target == source ||
                    (Files.exists(target) && Files.exists(source) && Files.isSameFile(target, source))
            },
        ) { "Output must not overwrite the suite or any participating source" }
    }
    fun checkIncompleteCapture(target: Path) {
        require(
            sourceCaptureComplete ||
                (!target.startsWith(catalog.root) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)),
        ) {
            "Sources could not be fully captured; use stdout or a new output file outside the workspace"
        }
    }
    check(requested)
    Files.createDirectories(requested.parent)
    // Canonicalize the directory so parent aliases cannot hide a protected source.
    val destination = requested.parent.toRealPath().resolve(requested.fileName)
    check(destination)
    checkIncompleteCapture(destination)
    require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(destination)) {
        "Output must be a regular JSON file"
    }
    val temporary = Files.createTempFile(destination.parent, ".mantra-acceptance-", ".tmp")
    try {
        Files.writeString(temporary, payload + "\n")
        check(destination)
        checkIncompleteCapture(destination)
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
