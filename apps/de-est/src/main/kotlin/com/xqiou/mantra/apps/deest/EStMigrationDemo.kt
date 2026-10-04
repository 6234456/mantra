package com.xqiou.mantra.apps.deest

import com.xqiou.mantra.core.Diagnostic
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.CaseRunResult
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.packages.DirectoryPolicy
import com.xqiou.mantra.packages.FileMigrationStore
import com.xqiou.mantra.packages.MigrationCoordinator
import com.xqiou.mantra.packages.MigrationEvaluation
import com.xqiou.mantra.packages.MigrationOperation
import com.xqiou.mantra.packages.MigrationPlan
import com.xqiou.mantra.packages.MigrationRuntime
import com.xqiou.mantra.packages.MigrationState
import com.xqiou.mantra.packages.MigrationTarget
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageSchemaBinding
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.packages.ParameterSelector
import com.xqiou.mantra.packages.SemanticVersion
import com.xqiou.mantra.workbench.CasePackageLoader
import com.xqiou.mantra.workbench.CasePackageOverrides
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDate

/** A tested public package/CaseGraphRunner migration example, not a domain engine. */
object EStMigrationDemo {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size % 2 == 0)
        val options = arguments.toList().chunked(2).associate { it[0] to it[1] }
        fun option(name: String): String = options[name] ?: error("Required option $name")
        val policy = when (option("--directory-policy")) {
            "strict-handles" -> DirectoryPolicy.STRICT_HANDLES
            "trusted-local" -> DirectoryPolicy.TRUSTED_LOCAL
            else -> error("Explicit directory policy must be strict-handles or trusted-local")
        }
        val packageRoot = Path.of(option("--package-root")).toRealPath()
        val hostRoot = Path.of(option("--host-root")).toAbsolutePath().normalize()
        Files.createDirectories(hostRoot)
        val realHost = hostRoot.toRealPath()
        require(!realHost.startsWith(packageRoot) && !packageRoot.startsWith(realHost)) {
            "Use a separate explicit writable host root"
        }
        val casePath = option("--case-path")
        require(
            casePath.isNotBlank() && !casePath.startsWith('/') && ':' !in casePath && '\\' !in casePath &&
                casePath.split('/').none { it.isEmpty() || it == "." || it == ".." },
        )
        val engine = SemanticVersion.parse(option("--engine-version"))
        val capture = {
            PackageLoader.directory(
                packageRoot,
                engine,
                PackageLimits(1_048_576, 2_097_152, 16_777_216, 4096, 32, 8192),
                policy,
            )
        }
        val snapshot = capture()
        val originalEntry = snapshot.manifest.cases.single { it.id == "mustermann-2025" }
        val original = snapshot.source(originalEntry.path)
        val mode = option("--mode")
        if (mode == "prepare") {
            val destination = realHost.resolve(casePath)
            Files.createDirectories(destination.parent)
            Files.write(
                destination,
                snapshot.bytes(originalEntry.path),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            return
        }
        require(mode == "preview" || mode == "apply")
        val reference = Json.parse(snapshot.source("migration/reference.json").text).record()
        val targetBinding = snapshot.manifest.schemas.single { it.binding.identity.version == "2025.3" }.binding
        val targetParameters = listOf("de.est/params-2025.3")
        val targetLayout = "de.est/steuerberechnung-2025.3"
        val effectiveDate = LocalDate.parse(reference.text("effectiveDate"))
        val store = FileMigrationStore(realHost, 65_536, policy)
        val runtime = HostRuntime(realHost, packageRoot, store, capture, effectiveDate)
        val current = runtime.current(casePath)
        val target = runtime.target(snapshot, targetBinding, targetParameters)
        val coordinator = MigrationCoordinator(store, runtime)
        val plan = MigrationPlan(
            casePath,
            current.graphRevision,
            current.schema,
            target,
            listOf(
                MigrationOperation.PinSchema(targetBinding),
                MigrationOperation.BindParameters(targetParameters),
                MigrationOperation.BindLayout(targetLayout),
            ),
        )
        val preview = coordinator.preview(plan)
        check(preview.original.text == original.text) {
            "This demonstration requires the untouched original fictional facts"
        }
        val oldResult = checkNotNull(preview.before.result)
        val newResult = checkNotNull(preview.after.result)
        verify(oldResult, reference, targetVersion = false)
        verify(newResult, reference, targetVersion = true)
        val output = Path.of(option("--out"))
        Files.createDirectories(output)
        Files.writeString(output.resolve("original.mantra"), preview.original.text)
        Files.writeString(output.resolve("candidate.mantra"), preview.candidate.text)
        Files.writeString(output.resolve("review-token.txt"), preview.reviewToken + "\n")
        Files.writeString(
            output.resolve("preview.json"),
            """
            {"sourceVersion":"2025.2","targetVersion":"2025.3","versionMode":"legacy-exact",
             "packageRevision":${quote(snapshot.revision)},"baseGraphRevision":${quote(preview.baseRevision)},
             "candidateGraphRevision":${quote(
                preview.after.graphRevision,
            )},"originalSha256":${quote(preview.originalSha256)},
             "candidateSha256":${quote(preview.candidateSha256)},"reviewToken":${quote(preview.reviewToken)},
             "sourceTax":${quote(number(oldResult, "festzusetzende-est").toPlainString())},
             "targetTax":${quote(
                number(newResult, "festzusetzende-est").toPlainString(),
            )},"numericChecks":50,"booleanChecks":1}
            """.trimIndent() + "\n",
        )
        if (mode == "apply") {
            val receipt = coordinator.apply(preview, option("--review-token"))
            verify(checkNotNull(runtime.current(casePath).result), reference, targetVersion = true)
            check(store.read(casePath).text == preview.candidate.text)
            val unchangedPackage = capture()
            check(
                unchangedPackage.revision == snapshot.revision &&
                    unchangedPackage.source(originalEntry.path).text == original.text,
            )
            // Old exact schema and original case remain independently executable after host migration.
            val oldGraph = runtime.run(casePath, original.text).first
            check(oldGraph.succeeded)
            verify(checkNotNull(oldGraph.result), reference, targetVersion = false)
            Files.writeString(
                output.resolve("receipt.json"),
                """{"oldSourceSha256":${quote(
                    receipt.oldSourceSha256,
                )},"newSourceSha256":${quote(
                    receipt.newSourceSha256,
                )},"reviewToken":${quote(receipt.reviewToken)},"oldVersionStillExecutable":true}""" +
                    "\n",
            )
        } else {
            check(store.read(casePath).text == preview.original.text) { "Preview must not write the host case" }
        }
    }

    /** Fresh public loader/runner per read epoch; linked source bindings are never overridden. */
    private class HostRuntime(
        private val hostRoot: Path,
        private val packageRoot: Path,
        private val store: FileMigrationStore,
        private val capture: () -> PackageSnapshot,
        private val effectiveDate: LocalDate,
    ) : MigrationRuntime {
        private val epoch = Any()

        fun target(
            snapshot: PackageSnapshot,
            binding: PackageSchemaBinding,
            parameters: List<String>,
        ): MigrationTarget {
            val selection = parameters.takeIf { it.isNotEmpty() }?.let { ids ->
                ParameterSelector.effectiveDate(
                    snapshot,
                    binding,
                    effectiveDate,
                    ids,
                    ids.flatMap { snapshot.parameters(it).values.keys }.toSet(),
                )
            }
            return MigrationTarget(binding, snapshot.manifest.identity, snapshot.revision, selection?.revision)
        }

        fun run(casePath: String, text: String): Pair<CaseRunResult, MigrationTarget> {
            val snapshot = capture()
            val supplied = Mantra.loadCase(SourceText(casePath, text, casePath))
            val schema = snapshot.manifest.schemas.single {
                it.binding.identity.id == supplied.schemaId &&
                    it.binding.identity.version == supplied.schemaVersion
            }
            val parameters = (supplied.meta["parameters"] as? Value.Vec)?.items?.map {
                (it as Value.Text).value
            }.orEmpty()
            val parameterPaths = parameters.map { id ->
                packageRoot.resolve(
                    snapshot.manifest.parameters.single {
                        it.id ==
                            id &&
                            it.schema == schema.binding
                    }.path,
                )
            }
            val layoutId = (supplied.meta["layout"] as? Value.Text)?.value
            val layoutPath = layoutId?.let { id ->
                packageRoot.resolve(
                    snapshot.manifest.layouts.single {
                        it.id == id &&
                            it.schema == schema.binding
                    }.path,
                )
            }
            val loader = CasePackageLoader(
                hostRoot,
                CasePackageOverrides(
                    rootCase = casePath,
                    text = text,
                    schemaPath = packageRoot.resolve(schema.path),
                    parameterPaths = parameterPaths,
                    layoutPath = layoutPath,
                ),
            )
            val graph = CaseGraphRunner(loader).use { it.run(CaseRunRequest(CaseReference(casePath))) }
            check(capture().revision == snapshot.revision) { "Package resources changed during evaluation" }
            return graph to target(snapshot, schema.binding, parameters)
        }

        override fun current(casePath: String): MigrationState {
            val source = store.read(casePath)
            val (graph, target) = run(casePath, source.text)
            val root = graph.root?.let { graph.cases[it] } ?: error("No current root calculation: ${graph.diagnostics}")
            return MigrationState(
                target.schema,
                root.revision,
                hash(source.text.toByteArray()),
                graph.result,
                findings(graph),
            )
        }

        override fun evaluate(casePath: String, candidate: SourceText, target: MigrationTarget): MigrationEvaluation {
            val (graph, actualTarget) = run(casePath, candidate.text)
            val revision = graph.root?.let { graph.cases[it]?.revision }.orEmpty()
            return MigrationEvaluation(actualTarget, revision, graph.result, findings(graph))
        }

        override fun commitIfCurrent(
            casePath: String,
            graphRevision: String,
            sourceSha256: String,
            commit: () -> Unit,
        ) = synchronized(epoch) {
            val live = current(casePath)
            check(live.graphRevision == graphRevision && live.sourceSha256 == sourceSha256) {
                "Host graph/source changed since preview"
            }
            commit()
        }

        private fun findings(graph: CaseRunResult): List<Diagnostic> = graph.diagnostics.map {
            it.finding.copy(
                caseKey = it.case?.value ?: it.finding.caseKey,
                caseRevision =
                it.revision ?: it.finding.caseRevision,
            )
        }
    }

    private fun verify(result: CalculationResult, reference: Value.MapV, targetVersion: Boolean) {
        check(result.succeeded && result.validationPassed) { "Actual findings: ${result.diagnostics}" }
        check(result.schema.version == if (targetVersion) "2025.3" else "2025.2")
        val maps =
            listOf(reference.field("commonNumbers").record()) +
                if (targetVersion) listOf(reference.field("targetNumbers").record()) else emptyList()
        maps.forEach { values ->
            values.entries.forEach { (key, expected) ->
                val id = (key as Value.Kw).name
                check(number(result, id).compareTo((expected as Value.Text).value.toBigDecimal()) == 0) {
                    "Independent mismatch: $id"
                }
            }
        }
        reference.field("commonBooleans").record().entries.forEach { (key, expected) ->
            check(result.value((key as Value.Kw).name) == expected)
        }
    }

    private fun number(result: CalculationResult, key: String): BigDecimal {
        val id = key.substringBefore('@')
        val suffix = key.substringAfter('@', "")
        val value = if (suffix ==
            "*"
        ) {
            result.view.openReader().use { it.reduce(id).value }
        } else {
            result.node(id).value(if (suffix.isEmpty()) emptyList() else suffix.split('/'))
        }
        return (value as? Value.Num)?.value ?: error("$key is undefined or nonnumeric; zero is never substituted")
    }
    private fun Value.record(): Value.MapV = this as? Value.MapV ?: error("Expected reference object")
    private fun Value.MapV.field(name: String): Value =
        entries[Value.Kw(name)] ?: error("Missing reference field $name")
    private fun Value.MapV.text(name: String): String = (field(name) as Value.Text).value
    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
