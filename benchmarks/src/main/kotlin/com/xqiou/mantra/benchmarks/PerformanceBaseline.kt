package com.xqiou.mantra.benchmarks

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.excel.ExcelOptions
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.html.HtmlRenderer
import com.xqiou.mantra.render.text.TextRenderer
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.Properties
import kotlin.math.ceil
import kotlin.math.max

@Volatile
private var retained: Any? = null

private data class Settings(val output: Path, val warmup: Int, val repetitions: Int, val scenarios: List<Scenario>)
private data class Sample(
    val operation: String,
    val repetition: Int,
    val milliseconds: Double,
    val beforeHeap: Long,
    val peakHeap: Long,
    val afterHeap: Long,
)

/** Separate JVM consumer of public library APIs; deliberately has no timing assertions. */
fun main(arguments: Array<String>) {
    val settings = settings(arguments)
    Files.createDirectories(settings.output)
    writeMetadata(settings)
    val samples = mutableListOf<String>()
    val summaries = mutableListOf<String>()
    samples += "scenario,lines,members,table_rows,operation,repetition,wall_ms," +
        "heap_before_bytes,heap_peak_bytes,heap_after_bytes"
    summaries += "scenario,lines,members,table_rows,operation,samples,median_ms,p95_ms,min_ms,max_ms," +
        "max_heap_increase_bytes,max_heap_peak_bytes"
    settings.scenarios.forEach { scenario ->
        val fixture = SyntheticFixture(scenario)
        val folder = settings.output.resolve(scenario.name)
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("schema.mantra"), fixture.schemaText)
        Files.writeString(folder.resolve("case.mantra"), fixture.caseText)
        val result = Mantra.calculate(fixture.schema, fixture.case)
        fixture.verify(result)
        val explained = Mantra.calculateForExplain(
            fixture.schema,
            fixture.case,
            emptyList(),
            fixture.explainNode,
            fixture.explainCoord,
        )
        fixture.verify(explained)
        checkNotNull(explained.explainTrace) { "Explain trace was not collected" }
        val paper = Render.paper(result, fixture.layout)
        Files.writeString(folder.resolve("paper.txt"), TextRenderer.render(paper, includeAudit = true))
        Files.writeString(folder.resolve("paper.html"), HtmlRenderer.render(paper))
        ExcelExport.workbook(result, fixture.layout, exportOptions()).use { workbook ->
            fixture.verify(workbook)
            workbook.write(folder.resolve("paper.xlsx"))
            Files.writeString(
                folder.resolve("verification.txt"),
                buildString {
                    appendLine(
                        "All ${scenario.lines * scenario.members + 3} calculated values matched " +
                            "independent arithmetic and cached XLSX values.",
                    )
                    appendLine("Expected answer: ${fixture.expectedAnswer}")
                    appendLine("Formula cells: ${workbook.report.formulaCells}")
                    appendLine("Fallbacks: ${workbook.report.fallbacks.size}")
                    workbook.report.fallbacks.forEach {
                        appendLine("${it.nodeId} ${it.sheet}!${it.cell}: ${it.reason}")
                    }
                    appendLine("Evaluation errors: ${workbook.report.evaluationErrors.size}")
                },
            )
        }
        val operations = linkedMapOf<String, () -> Any>(
            "plan" to { Mantra.inspect(fixture.schema, fixture.case) },
            "calculate" to { Mantra.calculate(fixture.schema, fixture.case) },
            "explain" to
                {
                    Mantra.calculateForExplain(
                        fixture.schema,
                        fixture.case,
                        emptyList(),
                        fixture.explainNode,
                        fixture.explainCoord,
                    )
                },
            "paper" to { Render.paper(result, fixture.layout) },
            "xlsx" to
                { ExcelExport.workbook(result, fixture.layout, exportOptions()).use { it.bytes(64 * 1_024 * 1_024) } },
        )
        operations.forEach { (name, operation) ->
            repeat(settings.warmup) { retained = operation() }
            val measured = (1..settings.repetitions).map { repetition -> measure(name, repetition, operation) }
            measured.forEach { sample ->
                samples += csv(
                    scenario.name,
                    scenario.lines,
                    scenario.members,
                    scenario.tableRows,
                    name,
                    sample.repetition,
                    number(sample.milliseconds),
                    sample.beforeHeap,
                    sample.peakHeap,
                    sample.afterHeap,
                )
            }
            val times = measured.map { it.milliseconds }.sorted()
            val median = if (times.size % 2 ==
                1
            ) {
                times[times.size / 2]
            } else {
                (times[times.size / 2 - 1] + times[times.size / 2]) / 2
            }
            val p95 = times[(ceil(times.size * .95).toInt() - 1).coerceIn(times.indices)]
            summaries += csv(
                scenario.name,
                scenario.lines,
                scenario.members,
                scenario.tableRows,
                name,
                times.size,
                number(median),
                number(p95),
                number(times.first()),
                number(times.last()),
                measured.maxOf { max(0, it.peakHeap - it.beforeHeap) },
                measured.maxOf { it.peakHeap },
            )
            println("${scenario.name.padEnd(10)} ${name.padEnd(9)} median=${number(median)} ms p95=${number(p95)} ms")
            Files.write(settings.output.resolve("samples.csv"), samples)
            Files.write(settings.output.resolve("summary.csv"), summaries)
        }
        retained = null
    }
    println("Baseline and verified public-API outputs: ${settings.output.toAbsolutePath()}")
}

private fun exportOptions() = ExcelOptions(maxSheets = 16, maxCells = 250_000)

private fun measure(name: String, repetition: Int, operation: () -> Any): Sample {
    retained = null
    System.gc()
    val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
    pools.forEach { it.resetPeakUsage() }
    val memory = ManagementFactory.getMemoryMXBean()
    val before = memory.heapMemoryUsage.used
    val start = System.nanoTime()
    retained = operation()
    val elapsed = (System.nanoTime() - start) / 1_000_000.0
    return Sample(name, repetition, elapsed, before, pools.sumOf { it.peakUsage.used }, memory.heapMemoryUsage.used)
}

private fun settings(arguments: Array<String>): Settings {
    val values = linkedMapOf<String, String>()
    var index = 0
    while (index < arguments.size) {
        val key = arguments[index++]
        require(key in setOf("--output", "--warmup", "--repetitions", "--scenarios")) { "Unknown option $key" }
        require(index < arguments.size) { "Missing value for $key" }
        require(values.put(key, arguments[index++]) == null) { "Duplicate option $key" }
    }
    val requested = values["--scenarios"]?.split(',')?.toSet()
    val selected = if (requested == null) baselineScenarios else baselineScenarios.filter { it.name in requested }
    require(
        requested == null || selected.map {
            it.name
        }.toSet() == requested,
    ) { "Scenarios: ${baselineScenarios.joinToString { it.name }}" }
    val warmup = values["--warmup"]?.toInt() ?: 5
    val repetitions = values["--repetitions"]?.toInt() ?: 10
    require(warmup in 1..100 && repetitions in 2..100) { "Warmup must be 1..100; repetitions must be 2..100" }
    return Settings(Path.of(values["--output"] ?: "benchmarks/build/performance"), warmup, repetitions, selected)
}

private fun writeMetadata(settings: Settings) {
    val lock = Properties().apply { Files.newInputStream(Path.of("normein-build.lock")).use(::load) }
    val metadata = linkedMapOf(
        "capturedAtUtc" to Instant.now().toString(),
        "mantraRevision" to git("rev-parse", "HEAD"),
        "workingTreeModified" to git("status", "--porcelain").isNotEmpty().toString(),
        "normeinVersion" to lock.getProperty("normeinVersion"),
        "normeinCommit" to lock.getProperty("normeinCommit"),
        "javaVersion" to System.getProperty("java.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "vmName" to System.getProperty("java.vm.name"),
        "vmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "),
        "kotlinVersion" to KotlinVersion.CURRENT.toString(),
        "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        "availableProcessors" to Runtime.getRuntime().availableProcessors().toString(),
        "maxHeapBytes" to Runtime.getRuntime().maxMemory().toString(),
        "warmupPerOperation" to settings.warmup.toString(),
        "repetitionsPerOperation" to settings.repetitions.toString(),
        "scenarios" to settings.scenarios.joinToString { "${it.name}:${it.lines}/${it.members}/${it.tableRows}" },
        "libraryAndHarnessSourceSha256" to sourceFingerprint(),
        "heapMethod" to (
            "Sum of JVM heap-pool peaks after reset; GC requested before each sample outside measured time; " +
                "not allocations or process RSS"
            ),
    )
    Files.writeString(
        settings.output.resolve("environment.txt"),
        metadata.entries.joinToString("\n", postfix = "\n") {
            "${it.key}=${it.value}"
        },
    )
}

private fun sourceFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val files = listOf("mantra-core", "mantra-render", "mantra-excel", "benchmarks").flatMap { directory ->
        Files.walk(Path.of(directory)).use { paths ->
            paths.filter {
                Files.isRegularFile(it) && "build" !in it.map(Path::toString) &&
                    it.toString().endsWith(".kt")
            }.toList()
        }
    } + listOf(Path.of("normein-build.lock"))
    files.sortedBy(Path::toString).forEach { path ->
        digest.update(path.toString().toByteArray())
        digest.update(0)
        digest.update(Files.readAllBytes(path))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun git(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments.toList()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")}: $output" }
    return output
}

private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value)

private fun csv(vararg values: Any) = values.joinToString(",")
