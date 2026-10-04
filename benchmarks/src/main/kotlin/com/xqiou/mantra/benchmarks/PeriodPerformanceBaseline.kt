package com.xqiou.mantra.benchmarks

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RecalculationStats
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.excel.ExcelOptions
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.html.HtmlRenderer
import com.xqiou.mantra.render.text.TextRenderer
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import kotlin.math.ceil

/** Separate measured workload; the original six-operation M0/M1 harness remains unchanged. */
object PeriodPerformanceBaseline {
    private data class Settings(val output: Path, val warmup: Int, val repetitions: Int)
    private data class Outcome(val value: Any, val stats: RecalculationStats? = null)
    private data class Sample(
        val milliseconds: Double,
        val before: Long,
        val peak: Long,
        val after: Long,
        val stats: RecalculationStats?,
    )
    private var retained: Any? = null

    @JvmStatic
    fun main(arguments: Array<String>) {
        val settings = settings(arguments)
        Files.createDirectories(settings.output)
        val fixture = PeriodFixture()
        writePeriodEnvironment(settings.output, settings.warmup, settings.repetitions, fixture)
        Files.writeString(settings.output.resolve("schema.mantra"), fixture.schemaText)
        Files.writeString(settings.output.resolve("case.mantra"), fixture.caseText)
        Files.writeString(settings.output.resolve("layout-matrix.mantra"), fixture.matrixText)
        Files.writeString(settings.output.resolve("layout-transpose.mantra"), fixture.transposeText)
        val result = Mantra.calculate(fixture.schema, fixture.case)
        val audited = Mantra.calculateForAudit(fixture.schema, fixture.case)
        val explained = Mantra.calculateForExplain(
            fixture.schema,
            fixture.case,
            emptyList(),
            fixture.explainNode,
            fixture.explainCoord,
        )
        listOf(result, audited, explained).forEach { fixture.verify(it) }
        checkNotNull(explained.explainTrace) { "Requested period Explain trace was not captured" }
        val verification = mutableListOf(
            "Series: ${fixture.seriesCount}; generated periods: ${fixture.periodCount}",
            "6200 scalar coordinates and 633 global/fixed reductions checked by independent closed-form arithmetic.",
            "Global first opening: ${fixture.expectedReduction("opening")}",
            "Global last closing: ${fixture.expectedReduction("closing")}",
            "Global cumulative movement: ${fixture.expectedReduction("movement")}",
            "Audit diagnostic codes: ${audited.diagnostics.map { it.code }.distinct()}",
            "Audit uses standard bounded options; truncation warnings do not change numeric verification.",
        )
        fixture.layouts.forEach { (name, layout) ->
            val paper = Render.paper(audited, layout)
            val paperCount = fixture.verify(paper)
            Files.writeString(
                settings.output.resolve("paper-$name.txt"),
                TextRenderer.render(paper, includeAudit = true),
            )
            Files.writeString(settings.output.resolve("paper-$name.html"), HtmlRenderer.render(paper))
            ExcelExport.workbook(audited, layout, exportOptions()).use { workbook ->
                val xlsxCount = fixture.verify(workbook, Render.completePaper(audited, layout))
                workbook.write(settings.output.resolve("paper-$name.xlsx"))
                verification += "$name: $paperCount addressed paper values, $xlsxCount XLSX values, " +
                    "${workbook.report.formulaCells} formula cells, ${workbook.report.fallbacks.size} fallbacks, " +
                    "${workbook.report.evaluationErrors.size} evaluation errors."
            }
        }
        Files.write(settings.output.resolve("verification.txt"), verification)
        val samples = mutableListOf(
            "operation,repetition,wall_ms,heap_before_bytes,heap_peak_bytes,heap_after_bytes," +
                "evaluated_tasks,reused_tasks,invalidated_tasks,full_rebuild,formula_evaluations,execution_sessions",
        )
        val summaries =
            mutableListOf(
                "operation,samples,median_ms,p95_ms,min_ms,max_ms,max_heap_increase_bytes,max_heap_peak_bytes",
            )
        Mantra.openSession(fixture.schema, fixture.case).use { session ->
            fixture.verify(session.result)
            var currentCase = fixture.changedCase(10)
            fixture.verify(session.recalculate(currentCase), 10)
            check(!session.lastRun.fullRebuild && session.lastRun.reusedTasks > 0) {
                "An input edit did not reuse member tasks: ${session.lastRun}"
            }
            verification += "Verified edit stats: ${session.lastRun}"
            fixture.verify(session.recalculate(currentCase), 10)
            check(session.lastRun.evaluatedTasks == 0 && session.lastRun.formulaEvaluations == 0) {
                "An unchanged case evaluated tasks: ${session.lastRun}"
            }
            verification += "Verified repeat stats: ${session.lastRun}"
            Files.write(settings.output.resolve("verification.txt"), verification)
            var firstSeedDelta = 10L
            val matrix = fixture.layouts.getValue("matrix")
            val transpose = fixture.layouts.getValue("transpose")
            val operations = linkedMapOf<String, () -> Outcome>(
                "calculate" to { Outcome(Mantra.calculate(fixture.schema, fixture.case)) },
                "calculate-audit" to { Outcome(Mantra.calculateForAudit(fixture.schema, fixture.case)) },
                "explain" to
                    {
                        Outcome(
                            Mantra.calculateForExplain(
                                fixture.schema,
                                fixture.case,
                                emptyList(),
                                fixture.explainNode,
                                fixture.explainCoord,
                            ),
                        )
                    },
                "paper-matrix" to { Outcome(Render.paper(audited, matrix)) },
                "paper-transpose" to { Outcome(Render.paper(audited, transpose)) },
                "xlsx-matrix" to
                    {
                        Outcome(
                            ExcelExport.workbook(audited, matrix, exportOptions()).use {
                                it.bytes(64 * 1024 * 1024)
                            },
                        )
                    },
                "xlsx-transpose" to
                    {
                        Outcome(
                            ExcelExport.workbook(audited, transpose, exportOptions()).use {
                                it.bytes(64 * 1024 * 1024)
                            },
                        )
                    },
                "session-recalc" to {
                    firstSeedDelta = if (firstSeedDelta == 10L) 11L else 10L
                    currentCase = fixture.changedCase(firstSeedDelta)
                    Outcome(session.recalculate(currentCase), session.lastRun)
                },
                "session-repeat" to { Outcome(session.recalculate(currentCase), session.lastRun) },
            )
            operations.forEach { (name, operation) ->
                repeat(settings.warmup) { retained = operation().value }
                val measured = (1..settings.repetitions).map { repetition ->
                    val sample = measure(operation)
                    val stats = sample.stats
                    samples +=
                        listOf(
                            name, repetition, number(sample.milliseconds), sample.before, sample.peak, sample.after,
                            stats?.evaluatedTasks ?: "", stats?.reusedTasks ?: "", stats?.invalidatedTasks ?: "",
                            stats?.fullRebuild ?: "", stats?.formulaEvaluations ?: "", stats?.executionSessions ?: "",
                        ).joinToString(",")
                    sample
                }
                if (name.startsWith("session-")) fixture.verify(session.result, firstSeedDelta)
                val times = measured.map { it.milliseconds }.sorted()
                val median = if (times.size % 2 ==
                    1
                ) {
                    times[times.size / 2]
                } else {
                    (times[times.size / 2 - 1] + times[times.size / 2]) / 2
                }
                val p95 = times[(ceil(times.size * .95).toInt() - 1).coerceIn(times.indices)]
                summaries +=
                    listOf(
                        name,
                        times.size,
                        number(median),
                        number(p95),
                        number(times.first()),
                        number(times.last()),
                        measured.maxOf {
                            (it.peak - it.before).coerceAtLeast(0)
                        },
                        measured.maxOf { it.peak },
                    ).joinToString(",")
                Files.write(settings.output.resolve("samples.csv"), samples)
                Files.write(settings.output.resolve("summary.csv"), summaries)
                println("${name.padEnd(17)} median=${number(median)} ms p95=${number(p95)} ms")
            }
        }
        Files.write(settings.output.resolve("verification.txt"), verification)
        retained = null
        println("Verified continuous-period measurements: ${settings.output.toAbsolutePath()}")
    }

    private fun measure(operation: () -> Outcome): Sample {
        retained = null
        System.gc()
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        pools.forEach { it.resetPeakUsage() }
        val memory = ManagementFactory.getMemoryMXBean()
        val before = memory.heapMemoryUsage.used
        val start = System.nanoTime()
        val result = operation()
        val milliseconds = (System.nanoTime() - start) / 1_000_000.0
        retained = result.value
        return Sample(
            milliseconds,
            before,
            pools.sumOf {
                it.peakUsage.used
            },
            memory.heapMemoryUsage.used,
            result.stats,
        )
    }

    private fun exportOptions() = ExcelOptions(
        maxSheets = 16,
        maxCells = 250_000,
        reading = CalculationOptions(limits = RunLimits(maxDuration = Duration.ofMinutes(5))),
    )
    private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value)
    private fun settings(arguments: Array<String>): Settings {
        val options = arguments.toList().chunked(2).associate { pair ->
            require(pair.size == 2 && pair[0] in setOf("--out", "--warmup", "--repetitions")) {
                "Options: --out PATH --warmup N --repetitions N"
            }
            pair[0] to pair[1]
        }
        require(options.size * 2 == arguments.size) { "Duplicate option" }
        val warmup = options["--warmup"]?.toInt() ?: 5
        val repetitions = options["--repetitions"]?.toInt() ?: 10
        require(warmup in 1..100 && repetitions in 2..100)
        return Settings(Path.of(options["--out"] ?: "benchmarks/build/period-performance"), warmup, repetitions)
    }
}
