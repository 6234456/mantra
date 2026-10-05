package com.xqiou.mantra.benchmarks

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

internal fun writePeriodEnvironment(output: Path, warmup: Int, repetitions: Int, fixture: PeriodFixture) {
    val lock = Properties().apply { Files.newInputStream(Path.of("normein-build.lock")).use(::load) }
    val metadata = linkedMapOf(
        "capturedAtUtc" to Instant.now().toString(),
        "mantraRevision" to periodGit("rev-parse", "HEAD"),
        "workingTreeModified" to periodGit("status", "--porcelain").isNotEmpty().toString(),
        "normeinVersion" to com.xqiou.mantra.core.api.RuntimeVersions.normein,
        "normeinSourceBaselineCommit" to lock.getProperty("normeinCommit"),
        "normeinRuntimeJarSha256" to kernelRuntimeJarSha256(),
        "javaVersion" to System.getProperty("java.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "vmName" to System.getProperty("java.vm.name"),
        "vmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "),
        "kotlinVersion" to KotlinVersion.CURRENT.toString(),
        "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        "availableProcessors" to Runtime.getRuntime().availableProcessors().toString(),
        "maxHeapBytes" to Runtime.getRuntime().maxMemory().toString(),
        "warmupPerOperation" to warmup.toString(),
        "repetitionsPerOperation" to repetitions.toString(),
        "xlsxReadMaxDurationSeconds" to "300",
        "series" to fixture.seriesCount.toString(),
        "generatedPeriods" to fixture.periodCount.toString(),
        "libraryAndHarnessSourceSha256" to periodSourceFingerprint(),
        "heapMethod" to
            "Sum of JVM heap-pool peaks after reset; GC requested before each sample outside measured time; not allocations or process RSS",
        "sessionMethod" to
            "One value-only closeable session; recalc toggles only S1 seed +10/+11; repeat submits unchanged facts; actual runtime counters recorded",
        "auditMethod" to
            "Standard bounded audit options; diagnostic codes and truncation warnings retained in verification.txt",
    )
    Files.writeString(
        output.resolve("environment.txt"),
        metadata.entries.joinToString("\n", postfix = "\n") {
            "${it.key}=${it.value}"
        },
    )
}

private fun periodSourceFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sources = listOf("mantra-core", "mantra-render", "mantra-excel", "benchmarks").flatMap { module ->
        Files.walk(Path.of(module)).use { paths ->
            paths.filter {
                Files.isRegularFile(it) && "build" !in it.map(Path::toString) &&
                    it.toString().endsWith(".kt")
            }.toList()
        }
    } + listOf(Path.of("normein-build.lock"))
    sources.sortedBy(Path::toString).forEach { path ->
        digest.update(path.toString().toByteArray())
        digest.update(0)
        digest.update(Files.readAllBytes(path))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun periodGit(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments.toList()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")}: $output" }
    return output
}
