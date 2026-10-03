package com.xqiou.mantra.acceptance

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.SourceBinding
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.ParameterSet
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.BoundSources
import com.xqiou.mantra.workbench.WorkspaceCatalog
import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same format, workbook and workbench contracts apply to every application and every case. */
class ApplicationAcceptanceTest {
    private val directory = Path.of(requireNotNull(System.getProperty("mantra.appDir")))
    private val schema = Mantra.loadSchema(directory.resolve("schema.mantra"))
    private val layout = Render.loadLayout(directory.resolve("layout.mantra"))

    @Test
    fun `all cases render stable papers and recalculate every workbook scalar`() {
        val cases = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith("case-") && it.toString().endsWith(".mantra") }
                .sorted().toList()
        }
        assertTrue(cases.isNotEmpty(), "application must supply cases")
        val out = directory.resolve("build/out")
        Files.createDirectories(out)
        cases.forEach { path ->
            val supplied = Mantra.loadCase(path)
            val bound = BoundSources.load(supplied, schema, path, directory).case
            val parameters = Files.list(directory).use { files ->
                files.filter { it.fileName.toString().startsWith("params-") && it.toString().endsWith(".mantra") }
                    .sorted().map { Mantra.loadParameters(it) }.toList()
            }
            val variants: List<List<ParameterSet>> = listOf(emptyList<ParameterSet>()) + parameters.map { listOf(it) }
            variants.forEach { sets ->
                val result = Mantra.calculate(schema, bound, sets)
                assertTrue(result.succeeded, "${path.fileName}: ${result.diagnostics}")
                val name = path.fileName.toString().removeSuffix(".mantra") +
                    sets.joinToString("") { "-" + it.id.substringAfterLast('/') }
                val amounts = result.nodes.values.flatMap { node ->
                    node.values.mapNotNull { (coord, value) ->
                        (value as? Value.Num)?.let {
                            (node.id + if (coord.isEmpty()) "" else "@" + coord.joinToString("/")) to
                                it.value.toPlainString()
                        }
                    }
                }.toMap()
                Files.writeString(out.resolve("$name-values.json"), WorkbenchJson.write(amounts))
                val outputs = mapOf(
                    "txt" to Render.text(result, layout, includeAudit = true),
                    "html" to Render.html(result, layout),
                )
                outputs.forEach { (format, content) ->
                    Files.writeString(out.resolve("$name.$format"), content)
                    val golden = directory.resolve("src/test/resources/golden/$name.$format")
                    if (System.getenv("MANTRA_UPDATE_GOLDEN") == "1") {
                        Files.createDirectories(golden.parent)
                        Files.writeString(golden, content)
                    }
                    assertEquals(Files.readString(golden), content, "$name.$format changed")
                }
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
                            is Value.Bool -> actual?.cellType == CellType.BOOLEAN && actual.booleanValue == value.value
                            is Value.Text -> actual?.cellType == CellType.STRING && actual.stringValue == value.value
                            is Value.Kw -> actual?.cellType == CellType.STRING && actual.stringValue == value.name
                            is Value.Date ->
                                actual?.cellType == CellType.STRING &&
                                    actual.stringValue == value.value.toString()
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
                    }
                    assertTrue(compared > 0, "$name must compare workbook values")
                }
            }
        }
    }

    @Test
    fun `sample import templates load typed data through the public source API`() {
        val catalog = WorkspaceCatalog(directory)
        val templates = catalog.importTemplates().data["templates"] as List<*>
        assertTrue(templates.isNotEmpty())
        val caseFile = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith("case-") && it.toString().endsWith(".mantra") }
                .sorted().findFirst().orElseThrow()
        }
        Files.list(directory.resolve("import-templates")).use { files ->
            files.sorted().forEach { template ->
                val fields = (Json.parse(Files.readString(template)) as Value.MapV).entries
                val name = (fields.getValue(Value.Kw("name")) as Value.Text).value
                val options = (fields.getValue(Value.Kw("options")) as Value.MapV).entries
                    .mapKeys { (key, _) -> (key as Value.Kw).name } + ("path" to Value.Text("data/$name.csv"))
                val supplied = CaseData.empty().copy(
                    sources = listOf(SourceBinding("csv", options, SourceLocation(name, 1, 1))),
                )
                val loaded = BoundSources.load(supplied, schema, caseFile, directory)
                assertTrue(loaded.case.inputs.isNotEmpty(), "$name must import sample data")
                assertEquals(1, loaded.files.size)
            }
        }
    }

    @Test
    fun `the generic workbench opens every case without application adapters`() {
        val catalog = WorkspaceCatalog(directory)
        val cases = (catalog.workspace().data["cases"] as List<*>).map { it as Map<*, *> }
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val id = case["id"] as String
            for (document in listOf("structure", "run", "paper", "parameters", "diagnostics")) {
                assertTrue(catalog.document(id, document).data.isNotEmpty(), "$id $document")
            }
            assertTrue(catalog.exportPreview(id).data.isNotEmpty())
        }
    }
}
