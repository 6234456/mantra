package com.xqiou.mantra.cli

import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.packages.PackageException
import com.xqiou.mantra.packages.SemanticVersion
import com.xqiou.mantra.server.WorkbenchServer
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.json.WorkbenchJson
import com.xqiou.mantra.workbench.packages.PackageWorkspaceConfig
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** Explicit package commands. Legacy schema/case commands retain their current resolver and syntax. */
internal fun executePackageCli(args: Array<String>, out: PrintStream, err: PrintStream): Int {
    return try {
        val command = args.firstOrNull() ?: error("A package command is required")
        val allowed = when (command) {
            "package-list" -> setOf("packages")
            "package-run" -> setOf("packages", "case", "format", "out")
            "package-explain" -> setOf("packages", "case", "node", "coord", "source-case", "expected-revision", "out")
            "package-migration-preview" -> setOf("packages", "case", "base-revision", "target-case", "out")
            "package-migration-apply" -> setOf(
                "packages",
                "case",
                "review-token",
                "base-revision",
                "target-case",
                "out",
            )
            "package-serve" -> setOf("packages", "workspace", "port", "ui")
            else -> error("Unknown package command")
        }
        val values = linkedMapOf<String, String>()
        var index = 1
        while (index < args.size) {
            val name = args[index].removePrefix("--")
            require(args[index].startsWith("--") && name in allowed && name !in values) {
                "Unexpected or duplicate package option"
            }
            val value = args.getOrNull(index + 1) ?: error("--$name requires a value")
            require(!value.startsWith("--") && value.isNotBlank()) { "--$name requires a value" }
            values[name] = value
            index += 2
        }
        fun required(name: String): String = values[name] ?: error("--$name is required")
        val config = Path.of(required("packages"))
        val catalog = PackageWorkspaceConfig.open(
            config,
            SemanticVersion.parse(com.xqiou.mantra.core.api.RuntimeVersions.mantra),
        )
        fun write(payload: String) {
            val file = values["out"]?.let(Path::of)
            if (file == null) {
                out.println(payload)
            } else {
                file.toAbsolutePath().parent?.let(Files::createDirectories)
                Files.writeString(file, payload + "\n")
            }
        }
        when (command) {
            "package-list" -> write(WorkbenchJson.write(catalog.workspace()))
            "package-run" -> {
                val case = required("case")
                val format = values["format"] ?: "text"
                if (format == "json") {
                    val document = catalog.document(case, "run")
                    write(WorkbenchJson.write(document))
                    val data = document["data"] as Map<*, *>
                    if (data["succeeded"] != true) return 3
                } else {
                    val bytes = catalog.export(case, format)
                    val file = values["out"]?.let(Path::of)
                    require(format !in setOf("xlsx", "pdf") || file != null) { "Binary export requires --out" }
                    if (file == null) {
                        out.print(bytes.toString(Charsets.UTF_8))
                    } else {
                        file.toAbsolutePath().parent?.let(Files::createDirectories)
                        Files.write(file, bytes)
                    }
                }
            }
            "package-explain" -> {
                val coord = values["coord"]?.split(',') ?: emptyList()
                require(coord.all(String::isNotBlank))
                val document = catalog.explain(
                    required("case"),
                    required("node"),
                    coord,
                    values["source-case"],
                    values["expected-revision"],
                )
                write(WorkbenchJson.write(document))
                if ((document["data"] as Map<*, *>)["succeeded"] != true) return 3
            }
            "package-migration-preview" -> write(
                WorkbenchJson.write(
                    catalog.previewMigration(required("case"), required("base-revision"), required("target-case")),
                ),
            )
            "package-migration-apply" -> {
                // A new process recreates the exact preview and verifies the human's token; it never
                // deserializes an untrusted MigrationPreview or bypasses the coordinator's CAS.
                val case = required("case")
                val preview = catalog.previewMigration(case, required("base-revision"), required("target-case"))
                val token = (preview["data"] as Map<*, *>)["reviewToken"]
                require(token == required("review-token")) { "Reviewed token differs from the current exact preview" }
                write(WorkbenchJson.write(catalog.applyMigration(case, required("review-token"))))
            }
            "package-serve" -> {
                val port = values["port"]?.toInt() ?: 8080
                require(port in 0..65535)
                val server = WorkbenchServer(
                    Path.of(required("workspace")),
                    port,
                    values["ui"]?.let(Path::of) ?: Path.of("workbench-ui/dist").takeIf(Files::isDirectory),
                    packageWorkspace = catalog,
                ).start()
                Runtime.getRuntime().addShutdownHook(Thread { server.close() })
                out.println("mantra: packages at http://127.0.0.1:${server.localPort}/packages")
                CountDownLatch(1).await()
            }
        }
        0
    } catch (error: PackageException) {
        error.diagnostics.forEach(err::println)
        2
    } catch (error: MantraException) {
        error.diagnostics.forEach(err::println)
        2
    } catch (error: WorkspaceException) {
        err.println(error.message)
        error.diagnostics.forEach(err::println)
        if (error.problem == com.xqiou.mantra.workbench.WorkspaceProblem.INVALID) 3 else 2
    } catch (error: IOException) {
        err.println(error.message)
        2
    } catch (error: IllegalArgumentException) {
        err.println(error.message)
        1
    } catch (error: IllegalStateException) {
        err.println(error.message)
        1
    }
}
