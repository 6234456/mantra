package com.xqiou.mantra.apps.leases

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.BatchLimits
import com.xqiou.mantra.core.api.BatchOptions
import com.xqiou.mantra.core.api.CalculationReader
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.packages.DirectoryPolicy
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.packages.SemanticVersion
import java.io.BufferedReader
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate

/** Typed, independently checked streaming over one compiled template; no generated DSL cases. */
object LeaseBatchDemo {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size % 2 == 0) {
            "Use --package-root --reference --reference-manifest --out --engine-version --directory-policy --max-seconds [--limit], each with a value"
        }
        val options = arguments.toList().chunked(2).associate { it[0] to it[1] }
        fun option(name: String): String = options[name] ?: error("Required option $name")
        val reference = Path.of(option("--reference"))
        val manifest = Json.parse(Files.readString(Path.of(option("--reference-manifest")))).record()
        val descriptor = manifest.field("records").record()
        val expectedHash = descriptor.text("sha256")
        require(Files.size(reference) == descriptor.number("byteLength").longValueExact())
        require(hash(reference) == expectedHash) { "Independent reference bytes changed" }
        val available = manifest.number("cases").longValueExact()
        val count = options["--limit"]?.toLong() ?: available
        require(count in 1..available)
        val seconds = option("--max-seconds").toLong()
        require(seconds > 0)
        val output = Path.of(option("--out"))
        Files.createDirectories(output)
        // Explicit host capture limits; these are neither timing promises nor universal defaults.
        val directoryPolicy = when (option("--directory-policy")) {
            "strict-handles" -> DirectoryPolicy.STRICT_HANDLES
            "trusted-local" -> DirectoryPolicy.TRUSTED_LOCAL
            else -> error("Explicit directory policy must be strict-handles or trusted-local")
        }
        val snapshot = PackageLoader.directory(
            Path.of(option("--package-root")),
            SemanticVersion.parse(option("--engine-version")),
            PackageLimits(1_048_576, 2_097_152, 16_777_216, 4096, 32, 8192),
            directoryPolicy,
        )
        val schemaEntry = snapshot.manifest.schemas.single()
        val binding = manifest.field("binding").record()
        require(
            schemaEntry.binding.identity.id == binding.text("schemaId") &&
                schemaEntry.binding.identity.version == binding.text("schemaVersion"),
        )
        val parameterIds = binding.field("parameterIds").vector().map { it.textValue() }
        val parameters = parameterIds.map { snapshot.parameters(it) }
        val schema = snapshot.schema(schemaEntry.binding)
        val started = System.nanoTime()
        ManagementFactory.getMemoryPoolMXBeans().forEach { it.resetPeakUsage() }
        val compiled = Mantra.compile(schema, parameters = parameters)
        var checked = 0L
        var comparisons = 0L
        val actualFile = output.resolve("actual.jsonl")
        val summary = Files.newBufferedReader(reference).use { reader ->
            val records = TypedLeaseRecords(reader, snapshot, count)
            Files.newBufferedWriter(actualFile).use { writer ->
                compiled.forEach(
                    records,
                    parameters = parameters,
                    options = BatchOptions(limits = BatchLimits(count, Duration.ofSeconds(seconds))),
                ) { item ->
                    val current = checkNotNull(records.current)
                    check(item.index == current.index && item.caseId == current.case.id) {
                        "Batch reused the wrong typed case ID"
                    }
                    val result = checkNotNull(item.result) { "Technical failure: ${item.diagnostics}" }
                    check(item.succeeded && item.validationPassed) { "Actual case findings: ${item.diagnostics}" }
                    val actual = result.view.openReader().use { calculationReader ->
                        current.expected.mapValues { (key, expected) ->
                            val value = numeric(result, calculationReader, key)
                            check(value.compareTo(expected) == 0) {
                                "${item.caseId}/$key: expected $expected, actual $value"
                            }
                            comparisons++
                            value.toPlainString()
                        }
                    }
                    writer.appendLine(
                        "{\"index\":${item.index},\"caseId\":${quote(item.caseId)},\"values\":${stringMap(actual)}}",
                    )
                    checked++
                }
            }
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        require(hash(reference) == expectedHash) { "Independent source changed during execution" }
        val stats = summary.statistics
        val environment = mapOf(
            "java" to System.getProperty("java.version"),
            "vendor" to System.getProperty("java.vendor"),
            "os" to System.getProperty("os.name"),
            "arch" to System.getProperty("os.arch"),
            "kernelArtifact" to compiled.kernel.artifactId,
            "kernelSha256" to compiled.kernel.executionContentSha256,
            "directoryPolicy" to directoryPolicy.name,
        )
        val report = """
            {"requestedCases":$count,"completedCases":${summary.completedCases},"checkedCases":$checked,"exactNumericComparisons":$comparisons,
             "succeededCases":${summary.succeededCases},"validationFailedCases":${summary.validationFailedCases},"technicalFailedCases":${summary.technicalFailedCases},
             "batchFailure":${summary.failure?.code?.let(::quote) ?: "null"},"elapsedMillis":$elapsedMillis,
             "peakHeapPoolSumBytes":${ManagementFactory.getMemoryPoolMXBeans().filter {
            it.type == java.lang.management.MemoryType.HEAP
        }.sumOf { it.peakUsage.used }},
             "packageRevision":${quote(
            snapshot.revision,
        )},"referenceSha256":${quote(expectedHash)},"actualSha256":${quote(hash(actualFile))},
             "compilation":{"syntaxCompilerCalls":${compiled.compilation.syntaxCompilerCalls},"semanticCompilerCalls":${compiled.compilation.semanticCompilerCalls},
                            "executionPlanCompilations":${compiled.compilation.executionPlanCompilations},"formulaCount":${compiled.compilation.formulaCount}},
             "statistics":{"sessionOpens":${stats.sessionOpens},"sessionCloseAttempts":${stats.sessionCloseAttempts},"successfulSessionCloses":${stats.successfulSessionCloses},
                           "executionPlanCompilations":${stats.executionPlanCompilations},"rowCycles":${stats.rowCycles},"physicalRowPreparations":${stats.physicalRowPreparations},
                           "logicalExpressionEvaluations":${stats.logicalExpressionEvaluations},"frameAllocations":${stats.frameAllocations},
                           "valueOnlyEvidenceMaterializations":${stats.valueOnlyEvidenceMaterializations}},"environment":${stringMap(
            environment,
        )}}
        """.trimIndent()
        Files.writeString(output.resolve("summary.json"), report + "\n")
        check(
            summary.failure == null && checked == count && summary.completedCases == count &&
                summary.succeededCases == count &&
                summary.technicalFailedCases == 0L,
        ) {
            "Streaming did not complete: $report"
        }
        println(report)
    }

    /** All arithmetic belongs to the engine or independent source; this only reads public values. */
    private fun numeric(result: CalculationResult, reader: CalculationReader, key: String): BigDecimal {
        val id = key.substringBefore('@')
        val suffix = key.substringAfter('@', "")
        val node = result.node(id)
        val value = when {
            suffix.isEmpty() -> node.value(emptyList())
            suffix == "*" -> reader.reduce(id).value
            '*' in suffix -> {
                val parts = suffix.split('/')
                require(parts.size == node.dims.size)
                reader.reduce(id, node.dims.zip(parts).filter { it.second != "*" }.toMap()).value
            }
            else -> node.value(suffix.split('/'))
        }
        return (value as? Value.Num)?.value
            ?: error("$key is ${value?.javaClass?.simpleName ?: "undefined"}; never substitute zero")
    }

    private data class Reference(val index: Long, val case: CaseData, val expected: Map<String, BigDecimal>)

    /** The iterator retains only the current reference/case, not the input sequence or results. */
    private class TypedLeaseRecords(
        private val reader: BufferedReader,
        private val snapshot: PackageSnapshot,
        private val count: Long,
    ) : Iterable<CaseData>,
        Iterator<CaseData> {
        private var index = 0L
        var current: Reference? = null
            private set
        override fun iterator(): Iterator<CaseData> = this
        override fun hasNext(): Boolean = index < count
        override fun next(): CaseData {
            check(hasNext())
            val line = reader.readLine() ?: error("Independent source ended early at $index")
            require(line.length <= 65_536)
            val reference = Json.parse(line).record()
            require(reference.number("index").longValueExact() == index)
            require(
                reference.field("validationPassed") == Value.Bool(true) &&
                    reference.field("expectedBusiness").vector().isEmpty(),
            )
            val id = reference.text("caseId")
            require(id == "lease-batch-%05d".format(index + 1))
            val facts = reference.field("facts").record()
            val lease = facts.field("leases").vector().single().record()
            val leaseId = lease.text("id")
            require(leaseId == "L%05d".format(index + 1))
            val payments = lease.field("payments").vector().map { it.textValue().toBigDecimal() }
            val reported = lease.field("reported_liability_closing").record()
            val periods = facts.field("periods").vector().map { it.record() }
            val term = lease.number("term_years").intValueExact()
            require(periods.size == 3 && term in 1..3 && payments.size == term)
            fun row(vararg fields: Pair<String, Value>): Value = Value.MapV(
                fields.associate {
                    Value.Kw(it.first) to
                        it.second
                },
            )
            val inputs = mapOf(
                "leases" to Value.Vec(
                    listOf(
                        row(
                            "id" to Value.Kw(leaseId),
                            "title" to Value.Text(lease.text("title")),
                            "annual-rate" to Value.Num(lease.text("annual_rate").toBigDecimal()),
                            "term-years" to lease.field("term_years"),
                            "commencement-payment" to Value.Num(lease.text("commencement_payment").toBigDecimal()),
                            "initial-direct-costs" to Value.Num(lease.text("initial_direct_costs").toBigDecimal()),
                            "commencement-incentive" to Value.Num(lease.text("commencement_incentive").toBigDecimal()),
                        ),
                    ),
                ),
                "lease-payments" to
                    Value.Vec(
                        periods.mapIndexed { periodIndex, period ->
                            row(
                                "lease-id" to Value.Kw(leaseId),
                                "period-id" to Value.Kw(period.text("id")),
                                // Periods after the declared term have an explicit scheduled input payment of zero.
                                "payment" to Value.Num(payments.getOrNull(periodIndex) ?: BigDecimal.ZERO),
                            )
                        },
                    ),
                "reported-closings" to
                    Value.Vec(
                        periods.map { period ->
                            row(
                                "lease-id" to Value.Kw(leaseId),
                                "period-id" to Value.Kw(period.text("id")),
                                "liability-amount" to Value.Num(reported.text(period.text("id")).toBigDecimal()),
                            )
                        },
                    ),
                "calendar-periods" to
                    Value.Vec(
                        periods.map { period ->
                            row(
                                "id" to Value.Kw(period.text("id")),
                                "start" to Value.Date(LocalDate.parse(period.text("start"))),
                                "end" to Value.Date(LocalDate.parse(period.text("end"))),
                            )
                        },
                    ),
            )
            val schema = snapshot.manifest.schemas.single().binding.identity
            val case = CaseData.empty(id).copy(
                schemaId = schema.id,
                meta = schema.version?.let {
                    mapOf("schema-version" to Value.Text(it))
                } ?: emptyMap(),
                inputs = inputs,
                source = "typed:$id",
            )
            val expected = reference.field("values").record().entries.entries.associate { (key, value) ->
                check(key is Value.Kw)
                key.name to value.textValue().toBigDecimal()
            }
            current = Reference(index, case, expected)
            index++
            return case
        }
    }

    private fun Value.record(): Value.MapV = this as? Value.MapV ?: error("Expected an object")
    private fun Value.vector(): List<Value> = (this as? Value.Vec)?.items ?: error("Expected an array")
    private fun Value.textValue(): String = (this as? Value.Text)?.value ?: error("Expected literal text")
    private fun Value.MapV.field(name: String): Value =
        entries[Value.Kw(name)] ?: error("Missing independent source field $name")
    private fun Value.MapV.text(name: String): String = field(name).textValue()
    private fun Value.MapV.number(name: String): BigDecimal =
        (field(name) as? Value.Num)?.value ?: error("Expected literal number $name")
    private fun hash(path: Path): String {
        val sha = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                sha.update(buffer, 0, size)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    private fun stringMap(values: Map<String, String>): String =
        values.entries.joinToString(",", "{", "}") { "${quote(it.key)}:${quote(it.value)}" }
}
