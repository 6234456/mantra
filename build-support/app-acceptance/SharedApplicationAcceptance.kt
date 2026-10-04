package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.SourceBinding
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.render.layout.LayoutSpec
import com.xqiou.mantra.workbench.BoundSources
import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.util.CellReference
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One template sample is bound to an explicit case, so a multi-schema app cannot guess its type. */
data class AcceptanceImportExample(
    val name: String,
    val declaration: AcceptanceCaseDocument,
    val format: String,
    val options: Map<String, Value>,
)

data class AcceptanceEnvironment(
    val applicationDirectory: Path,
    val workspaceRoot: Path,
    val documents: AcceptanceDocumentCatalog,
    val ownedCases: List<AcceptanceCaseDocument>,
    val calculations: AcceptanceCalculationRunner,
    val sourcePaths: AcceptanceSourcePathResolver,
    val workbench: AcceptanceWorkbenchProbe,
    val legacyParameterVariants: Map<Path, List<AcceptanceParameterVariant>> = emptyMap(),
    val layoutVariants: Map<Path, List<AcceptanceLayoutVariant>> = emptyMap(),
    val imports: List<AcceptanceImportExample> = emptyList(),
)

/** Test harness only: executable schemas, graph execution and file loading stay in public hosts. */
class SharedApplicationAcceptance(private val environment: AcceptanceEnvironment) {
    private fun bind(entry: AcceptanceCaseDocument, parameters: List<String>? = null, layout: String? = null) =
        AcceptanceCaseBinder.bind(
            environment.documents,
            environment.applicationDirectory,
            environment.workspaceRoot,
            entry,
            parameters,
            layout,
        )

    fun ordinaryCases() {
        val cases = environment.ownedCases.filter { it.role == AcceptanceCaseRole.ORDINARY }
        assertTrue(cases.isNotEmpty(), "application must declare ordinary cases")
        cases.forEach { entry ->
            val variants = if ("parameters" in entry.case.meta) {
                emptyList()
            } else {
                environment.legacyParameterVariants[entry.path].orEmpty()
            }
            val runs = listOf("" to null) + variants.map { it.suffix to it.parameterIds }
            runs.forEach { (parameterSuffix, parameterIds) ->
                val binding = bind(entry, parameterIds)
                val execution = environment.calculations.calculate(
                    AcceptanceCalculationRequest(binding, AcceptanceEvidence.FULL),
                )
                AcceptanceAssertions.ordinary(binding, execution, environment.sourcePaths)
                val result = execution.result
                val base = AcceptanceArtifactNames.base(binding, parameterSuffix)
                writeNumericValues(result, output(base, "values.json"))
                val selected = binding.layoutDocument?.layout ?: Render.defaultLayout(result)
                val layouts = listOf("" to selected) + environment.layoutVariants[entry.path].orEmpty().map { variant ->
                    variant.suffix to requireNotNull(bind(entry, parameterIds, variant.layoutId).layoutDocument).layout
                }
                layouts.forEach { (layoutSuffix, layout) ->
                    val name = AcceptanceArtifactNames.base(binding, parameterSuffix + layoutSuffix)
                    stablePaper(result, layout, name)
                    compareWorkbook(result, layout, name)
                }
                if (parameterSuffix.isEmpty()) verifyWorkbench(binding, execution)
            }
        }
    }

    /** Evaluation failure fixtures are executed and asserted, never rescued by catch-all success. */
    fun technicalFailures() {
        val cases = environment.ownedCases.filter { it.role == AcceptanceCaseRole.TECHNICAL_FAILURE }
        cases.forEach { entry ->
            val binding = bind(entry)
            val execution = environment.calculations.calculate(
                AcceptanceCalculationRequest(binding, AcceptanceEvidence.FULL),
            )
            AcceptanceAssertions.technicalFailure(binding, execution)
            val run = environment.workbench.document(entry.path, "run", binding.layoutDocument?.id)
            AcceptanceAssertions.failureDocument(binding, run)
            for (document in listOf("structure", "paper", "parameters", "diagnostics")) {
                assertTrue(
                    environment.workbench.document(entry.path, document, binding.layoutDocument?.id).isNotEmpty(),
                )
            }
            // Failure evidence is separate from ordinary numeric/golden outputs.
            Files.writeString(output(AcceptanceArtifactNames.base(binding), "failure.json"), WorkbenchJson.write(run))
        }
    }

    /** Focused amount contracts need their independently declared key/value checks in caller tests. */
    fun focusedCases(): List<Pair<AcceptanceCaseBinding, AcceptanceExecution>> = environment.ownedCases.filter {
        it.role ==
            AcceptanceCaseRole.FOCUSED_AMOUNT
    }.map { entry ->
        val binding = bind(entry)
        val execution = environment.calculations.calculate(
            AcceptanceCalculationRequest(binding, AcceptanceEvidence.FULL),
        )
        AcceptanceAssertions.ordinary(binding, execution, environment.sourcePaths)
        compareIndependentFocusedAmounts(binding, execution.result)
        binding to execution
    }

    private fun compareIndependentFocusedAmounts(binding: AcceptanceCaseBinding, result: CalculationResult) {
        val reference = requireNotNull(binding.declaration.case.text("expected-values")) {
            "Focused case must name its independently sourced amount reference"
        }
        val file = environment.sourcePaths.resolve(binding.declaration.path, reference)
        val expected = Json.parse(Files.readString(file)) as? Value.MapV
            ?: error("Focused reference must be a numeric address map")
        assertTrue(expected.entries.isNotEmpty(), "Focused reference cannot be empty")
        expected.entries.forEach { (key, value) ->
            val address = (key as? Value.Kw)?.name ?: error("Expected address must be a JSON object key")
            val id = address.substringBefore('@')
            val coordinates = address.substringAfter('@', "").takeIf { it.isNotEmpty() }?.split('/').orEmpty()
            val node = requireNotNull(result.nodes[id]) { "Focused result lacks $id" }
            val actual = if ("*" in coordinates) {
                require(node.dims.size == coordinates.size || coordinates == listOf("*"))
                val fixed = if (coordinates == listOf("*")) {
                    emptyMap()
                } else {
                    node.dims.zip(coordinates).filter { (_, member) -> member != "*" }.toMap()
                }
                result.view.reduce(id, fixed).value
            } else {
                require(node.dims.size == coordinates.size) { "Expected full coordinate at $address" }
                node.value(coordinates)
            }
            val expectedNumber = when (value) {
                is Value.Num -> value.value
                is Value.Text -> BigDecimal(value.value)
                else -> error("Focused reference $address must contain a decimal")
            }
            assertTrue(actual is Value.Num, "Focused amount $address must really be numeric")
            assertEquals(0, expectedNumber.compareTo(actual.value), "Independent amount at $address")
        }
    }

    fun sampleImports() {
        val hasTable = environment.ownedCases.any { entry ->
            bind(entry).schema.inputs.any {
                it.type ==
                    com.xqiou.mantra.core.model.ValueType.TABLE
            }
        }
        if (hasTable) assertTrue(environment.imports.isNotEmpty(), "Table applications need typed import samples")
        environment.imports.forEach { example ->
            val binding = bind(example.declaration)
            val supplied = CaseData.empty("import-${example.name}").copy(
                schemaId = binding.schema.id,
                sources = listOf(SourceBinding(example.format, example.options, SourceLocation(example.name, 1, 1))),
            )
            val loaded = BoundSources.load(
                supplied,
                binding.schema,
                example.declaration.path,
                environment.workspaceRoot,
            )
            assertTrue(loaded.case.inputs.isNotEmpty(), "${example.name}: no typed values imported")
            assertEquals(1, loaded.files.size)
        }
    }

    private fun verifyWorkbench(binding: AcceptanceCaseBinding, execution: AcceptanceExecution) {
        val layoutId = binding.layoutDocument?.id
        for (document in listOf("structure", "run", "paper", "parameters", "diagnostics", "export-preview")) {
            assertTrue(
                environment.workbench.document(binding.declaration.path, document, layoutId).isNotEmpty(),
                document,
            )
        }
        val run = environment.workbench.document(binding.declaration.path, "run", layoutId)
        assertEquals(execution.succeeded, run["succeeded"])
        assertEquals(execution.validationPassed, run["validationPassed"])
        if (binding.declaration.hasLinks) {
            assertEquals(
                execution.inheritedDiagnostics,
                environment.workbench.inheritedDiagnostics(binding.declaration.path),
                "Workbench must retain typed source diagnostic ownership and revision",
            )
            val actualLinks = environment.workbench.links(binding.declaration.path)
            assertEquals(
                execution.links.toSet(),
                actualLinks.toSet(),
                "Workbench must retain full linked-value provenance",
            )
        }
    }

    private fun output(name: Path, extension: String): Path {
        val file = environment.applicationDirectory.resolve("build/out").resolve("$name-$extension")
        Files.createDirectories(file.parent)
        return file
    }

    private fun stablePaper(result: CalculationResult, layout: LayoutSpec, name: Path) {
        for ((format, content) in mapOf(
            "txt" to Render.text(result, layout, includeAudit = true),
            "html" to Render.html(result, layout),
        )) {
            val file = environment.applicationDirectory.resolve("build/out").resolve("$name.$format")
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
            val golden = environment.applicationDirectory.resolve("src/test/resources/golden").resolve("$name.$format")
            if (System.getenv("MANTRA_UPDATE_GOLDEN") == "1") {
                Files.createDirectories(golden.parent)
                Files.writeString(golden, content)
            }
            assertEquals(Files.readString(golden), content, "$name.$format changed")
        }
    }

    private fun writeNumericValues(result: CalculationResult, file: Path) {
        val values = result.nodes.values.flatMap { node ->
            node.values.mapNotNull { (coord, value) ->
                (value as? Value.Num)?.let {
                    (node.id + if (coord.isEmpty()) "" else "@" + coord.joinToString("/")) to
                        it.value.toPlainString()
                }
            } + if (node.dims.isNotEmpty() && node.type.isNumeric) {
                val reductions = mutableListOf<Pair<String, String>>()
                (result.view.reduce(node.id).value as? Value.Num)?.let {
                    reductions +=
                        "${node.id}@*" to it.value.toPlainString()
                }
                if (node.dims.size > 1) {
                    node.dims.forEachIndexed { axis, dimension ->
                        node.values.keys.map { it[axis] }.distinct().forEach { member ->
                            val fixed = mapOf(dimension to member)
                            (result.view.reduce(node.id, fixed).value as? Value.Num)?.let { value ->
                                reductions +=
                                    (node.id + "@" + node.dims.joinToString("/") { fixed[it] ?: "*" }) to
                                    value.value.toPlainString()
                            }
                        }
                    }
                }
                reductions
            } else {
                emptyList()
            }
        }.toMap()
        Files.writeString(file, WorkbenchJson.write(values))
    }

    internal fun compareWorkbook(result: CalculationResult, layout: LayoutSpec, relativeName: Path) {
        val name = relativeName.toString()
        val out = environment.applicationDirectory.resolve("build/out")
        Files.createDirectories(out.resolve(name).parent)
        ExcelExport.workbook(result, layout).use { export ->
            export.write(out.resolve("$name.xlsx"))
            assertEquals(emptyList(), export.report.fallbacks, "$name formula fallbacks")
            assertEquals(emptyList(), export.report.evaluationErrors, "$name evaluation errors")
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            var compared = 0
            fun compare(address: String?, value: Value, context: String) {
                val ref = CellReference(requireNotNull(address) { "$name: missing $context" })
                val cell = export.workbook.getSheet(ref.sheetName).getRow(ref.row).getCell(ref.col.toInt())
                val actual = evaluator.evaluate(cell)
                assertTrue(actual?.cellType != CellType.ERROR, "$name $context: workbook error $actual")
                val equal = when (value) {
                    is Value.Num ->
                        actual?.cellType == CellType.NUMERIC &&
                            abs(actual.numberValue - value.value.toDouble()) <=
                            maxOf(1e-6, abs(value.value.toDouble()) * 1e-12)
                    is Value.Bool ->
                        actual?.cellType == CellType.BOOLEAN &&
                            actual.booleanValue == value.value
                    is Value.Text ->
                        actual?.cellType == CellType.STRING &&
                            actual.stringValue == value.value
                    is Value.Kw ->
                        actual?.cellType == CellType.STRING &&
                            actual.stringValue == value.name
                    is Value.Date ->
                        actual?.cellType == CellType.NUMERIC &&
                            DateUtil.getLocalDateTime(
                                actual.numberValue,
                                export.workbook.isDate1904,
                            ).let { dateTime ->
                                dateTime.toLocalDate() == value.value &&
                                    dateTime.toLocalTime() == java.time.LocalTime.MIDNIGHT
                            }
                    Value.Nil ->
                        actual == null || actual.cellType == CellType.BLANK ||
                            (actual.cellType == CellType.STRING && actual.stringValue.isEmpty())
                    else -> error("unsupported scalar: $value")
                }
                assertTrue(equal, "$name $context: engine=$value workbook=$actual")
                compared++
            }
            result.nodes.values.forEach { node ->
                node.values.forEach { (coord, value) ->
                    if (value is Value.Vec) {
                        value.items.forEachIndexed { rowIndex, row ->
                            require(row is Value.MapV) {
                                "Only table vectors have workbook records: ${node.id}"
                            }
                            row.entries.forEach { (key, entry) ->
                                val column = (key as Value.Kw).name
                                compare(
                                    export.tableAddress(node.id, rowIndex, column),
                                    entry,
                                    "${node.id}[$rowIndex].$column",
                                )
                            }
                        }
                    } else {
                        compare(export.address(node.id, coord), value, "${node.id}$coord")
                    }
                }
                val aggregateAddress = export.address("aggregate.${node.id}")
                if (node.dims.isNotEmpty() && (node.ratio != null || aggregateAddress != null)) {
                    val reduced = result.view.reduce(node.id).value
                    if (reduced != null) {
                        compare(aggregateAddress, reduced, "aggregate.${node.id}")
                    }
                }
            }
            Render.completePaper(result, layout).tables.flatMap { table ->
                table.rows.flatMap { it.valueAddresses.filterNotNull() }
            }.filter { it.aggregate }.distinctBy { it.nodeId to it.fixed }.forEach { address ->
                val reduced = result.view.reduce(address.nodeId, address.fixed).value
                if (reduced != null) {
                    compare(
                        export.aggregateAddress(address.nodeId, address.fixed),
                        reduced,
                        "aggregate.${address.nodeId}${address.fixed}",
                    )
                }
            }
            assertTrue(compared > 0, "$name must compare workbook values")
        }
    }
}
