package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.RuntimeVersions
import com.xqiou.mantra.packages.DirectoryPolicy
import com.xqiou.mantra.packages.PackageCatalog
import com.xqiou.mantra.packages.PackageGraphResolver
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.SemanticVersion
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.PackageImports
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Package results are compared to the independently verified application runs, then every XLSX scalar. */
class PackageApplicationAcceptanceTest {
    @TempDir lateinit var temporary: Path
    private val application = Path.of(requireNotNull(System.getProperty("mantra.appDir"))).toRealPath()

    @Test fun directoryAndJarPackagesRetainAllCaseAndParameterValues() {
        val environment = loadGenericAcceptanceEnvironment(application)
        val paths = AcceptancePaths(environment.workspaceRoot)
        val limits = PackageLimits(1_048_576, 2_097_152, 16_777_216, 4096, 32, 8192)
        val engine = SemanticVersion.parse(RuntimeVersions.mantra)
        val directories = Files.list(environment.workspaceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it.resolve("manifest.json")) }.sorted().toList()
        }
        assertTrue(directories.isNotEmpty())
        for (mode in listOf("directory", "jar")) {
            val catalog = PackageCatalog()
            directories.forEach { directory ->
                val captured = PackageLoader.directory(directory, engine, limits, DirectoryPolicy.TRUSTED_LOCAL)
                val snapshot = if (mode == "directory") {
                    captured
                } else {
                    val jar = temporary.resolve("${directory.fileName}.jar")
                    JarOutputStream(Files.newOutputStream(jar)).use { archive ->
                        (listOf("manifest.json") + captured.manifest.resources.map { it.path }).forEach { name ->
                            archive.putNextEntry(JarEntry("bundle/$name"))
                            archive.write(Files.readAllBytes(directory.resolve(name)))
                            archive.closeEntry()
                        }
                    }
                    URLClassLoader(arrayOf(jar.toUri().toURL()), null).use {
                        PackageLoader.classpath("bundle", it, engine, limits)
                    }
                }
                catalog.register(directory.fileName.toString(), snapshot)
            }
            val runner = GenericAcceptanceGraphRunner(paths) { request, root ->
                val delegate = PackageGraphResolver(catalog, PackageImports)
                object : CasePackageResolver {
                    override fun identify(reference: CaseReference, control: CaseLoadControl) =
                        delegate.identify(reference, control)
                    override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage {
                        val prepared = delegate.load(key, control)
                        if (key != root) return prepared
                        val owned = catalog.resolveCase(key.value).snapshot
                        val sets = request.binding.parameterDocuments.map { owned.parameters(it.parameters.id) }
                        val identity = (listOf(prepared.revision) + sets.map { it.id }).joinToString("\n")
                        val fingerprint = MessageDigest.getInstance(
                            "SHA-256",
                        ).digest(identity.toByteArray()).joinToString("") {
                            "%02x".format(it)
                        }
                        return PreparedCasePackage(
                            prepared.key,
                            prepared.caseId,
                            prepared.schemaIdentity,
                            prepared.schema,
                            prepared.caseData,
                            sets,
                            prepared.sources,
                            fingerprint,
                        )
                    }
                }
            }
            var compared = 0
            environment.ownedCases.forEach { entry ->
                val variants = if ("parameters" in
                    entry.case.meta
                ) {
                    emptyList()
                } else {
                    environment.legacyParameterVariants[entry.path].orEmpty()
                }
                val runs = listOf("" to null) + variants.map { it.suffix to it.parameterIds }
                runs.forEach { (suffix, parameters) ->
                    val binding = AcceptanceCaseBinder.bind(
                        environment.documents,
                        application,
                        environment.workspaceRoot,
                        entry,
                        parameters,
                    )
                    val request = AcceptanceCalculationRequest(binding, AcceptanceEvidence.FULL)
                    val expected = environment.calculations.calculate(request)
                    val actual = runner.calculate(request)
                    assertEquals(expected.succeeded, actual.succeeded, "$mode ${entry.path}")
                    assertEquals(expected.validationPassed, actual.validationPassed)
                    assertEquals(
                        expected.result.nodes.mapValues {
                            it.value.values
                        },
                        actual.result.nodes.mapValues { it.value.values },
                    )
                    assertEquals(expected.localDiagnostics.map { it.code }, actual.localDiagnostics.map { it.code })
                    assertEquals(expected.links.map { it.value }, actual.links.map { it.value })
                    compared++
                    if (actual.succeeded && entry.role != AcceptanceCaseRole.TECHNICAL_FAILURE) {
                        val layout = binding.layoutDocument?.layout ?: Render.defaultLayout(actual.result)
                        val name = AcceptanceArtifactNames.base(binding, suffix + "-package-$mode")
                        val output = application.resolve("build/out/$name")
                        Files.createDirectories(output.parent)
                        Files.writeString(Path.of("$output.html"), Render.html(actual.result, layout))
                        Files.writeString(Path.of("$output.txt"), Render.text(actual.result, layout))
                        SharedApplicationAcceptance(environment).compareWorkbook(actual.result, layout, name)
                    }
                }
            }
            assertTrue(compared >= environment.ownedCases.size)
        }
    }
}
