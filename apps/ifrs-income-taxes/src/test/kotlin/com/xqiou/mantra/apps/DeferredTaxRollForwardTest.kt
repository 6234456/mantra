package com.xqiou.mantra.apps

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CalculationResult
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.excel.ExcelExport
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.CaseTextEditor
import com.xqiou.mantra.workbench.ExplainAddress
import com.xqiou.mantra.workbench.WorkspaceCatalog
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent Decimal facts, formulas and references were frozen before the schema was authored. */
class DeferredTaxRollForwardTest {
    private val dir = Path.of("apps/ifrs-income-taxes")
    private val documents = dir.resolve("roll-forward")
    private val schema = Mantra.loadSchema(documents.resolve("schema.mantra"))
    private val layout = Render.loadLayout(documents.resolve("layout.mantra"))
    private val names = listOf("demo", "zero", "rate-change", "asset-liability", "unreconciled")

    @TempDir lateinit var temporary: Path

    private fun calculate(name: String, scale: Int = 2) = Mantra.calculateForAudit(
        schema,
        Mantra.loadCase(documents.resolve("case-$name.mantra")),
        if (scale == 4) listOf(Mantra.loadParameters(documents.resolve("params-precision.mantra"))) else emptyList(),
    )

    private fun reference(name: String, scale: Int, suffix: String = "values") = Json.parse(
        Files.readString(dir.resolve("independent/roll-forward/references/$name-scale$scale-$suffix.json")),
    ) as Value.MapV

    private fun numericReferences(name: String, scale: Int) = reference(name, scale).entries.map { (key, value) ->
        (key as Value.Kw).name to (value as Value.Text).value.toBigDecimal()
    }.toMap()

    private fun coordinate(result: CalculationResult, address: String): Pair<String, Map<String, String>> {
        val id = address.substringBefore('@')
        val suffix = address.substringAfter('@', "")
        val fixed = if (suffix == "*") {
            emptyMap()
        } else {
            result.node(id).dims.zip(suffix.split('/')).filter { it.second != "*" }.toMap()
        }
        return id to fixed
    }

    private fun value(result: CalculationResult, address: String): Value? {
        val id = address.substringBefore('@')
        val suffix = address.substringAfter('@', "")
        return if ('*' in suffix) {
            val (_, fixed) = coordinate(result, address)
            result.view.reduce(id, fixed).value
        } else {
            result.node(id).value(if (suffix.isEmpty()) emptyList() else suffix.split('/'))
        }
    }

    @Test
    fun `strict rate lookup sums and counts preserve the independently frozen tax cases`() {
        var compact = Files.readString(documents.resolve("schema.mantra"))
        val rate = "(sum (map (fn [entry] (if (= entry.period tax-year.key) entry.rate 0)) tax-rates))"
        assertTrue(compact.contains(rate))
        compact = compact.replace(rate, "(table/sum-where tax-rates {:period tax-year.key} :rate)")
            .replace("(line closing-tax-rate ", "(info closing-tax-rate ")
        for (table in listOf("tax-rates", "reported-tax-closings")) {
            val count = "(sum (map (fn [entry] (if (= entry.period tax-year.key) 1 0)) $table))"
            assertTrue(compact.contains(count), table)
            compact = compact.replace(count, "(table/count-where $table {:period tax-year.key})")
        }
        val shorthand = Mantra.loadSchema(SourceText("compact-tax.mantra", compact), SourceResolver { _, _ -> null })
        for (name in names) {
            for (scale in listOf(2, 4)) {
                val actual = Mantra.calculateForAudit(
                    shorthand,
                    Mantra.loadCase(documents.resolve("case-$name.mantra")),
                    if (scale ==
                        4
                    ) {
                        listOf(Mantra.loadParameters(documents.resolve("params-precision.mantra")))
                    } else {
                        emptyList()
                    },
                )
                assertTrue(actual.succeeded, "$name/$scale: ${actual.diagnostics}")
                assertEquals(calculate(name, scale).validationPassed, actual.validationPassed)
                numericReferences(name, scale).forEach { (address, expected) ->
                    val result = value(actual, address)
                    assertTrue(result is Value.Num, "$name/$scale/$address: $result")
                    assertEquals(0, expected.compareTo(result.value), "$name/$scale/$address")
                }
            }
        }
    }

    @Test
    fun `all coordinates and stock flow reductions agree with the independent oracle`() {
        names.forEach { name ->
            for (scale in listOf(2, 4)) {
                val result = calculate(name, scale)
                assertTrue(result.succeeded, "$name/$scale: ${result.diagnostics}")
                val summary = reference(name, scale, "summary")
                assertEquals(
                    (summary.entries.getValue(Value.Kw("validationPassed")) as Value.Bool).value,
                    result.validationPassed,
                    "$name/$scale",
                )
                val periods = (summary.entries.getValue(Value.Kw("failedPeriods")) as Value.Vec).items.map {
                    (it as Value.Text).value
                }
                assertEquals(periods.map { listOf(it) }, result.diagnostics.map { it.coord })
                assertTrue(result.diagnostics.all { it.code == "MANTRA-RECONCILE-FAILED" })
                numericReferences(name, scale).forEach { (address, expected) ->
                    val actual = value(result, address)
                    assertTrue(actual is Value.Num, "$name/$scale/$address: numeric expectation, got $actual")
                    assertEquals(0, expected.compareTo(actual.value), "$name/$scale/$address")
                }
            }
        }
    }

    @Test
    fun `all scenarios render four formats with independently verified workbook values`() {
        val out = Files.createDirectories(dir.resolve("build/out/deferred-tax"))
        names.forEach { name ->
            for (scale in listOf(2, 4)) {
                val result = calculate(name, scale)
                val base = "$name-scale$scale"
                val html = Render.html(result, layout)
                val text = Render.text(result, layout, includeAudit = true)
                assertTrue(html.contains("IAS 12 deferred-tax roll-forward"))
                assertTrue(text.contains("Closing signed deferred tax"))
                Files.writeString(out.resolve("$base.html"), html)
                Files.writeString(out.resolve("$base.txt"), text)
                val pdf = Render.pdf(result, layout)
                assertTrue(pdf.take(5).toByteArray().contentEquals("%PDF-".toByteArray()))
                Files.write(out.resolve("$base.pdf"), pdf)
                ExcelExport.workbook(result, layout).use { export ->
                    assertEquals(emptyList(), export.report.fallbacks, "$base formula fallbacks")
                    assertEquals(emptyList(), export.report.evaluationErrors, "$base evaluation errors")
                    val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
                    numericReferences(name, scale).forEach { (address, expected) ->
                        val id = address.substringBefore('@')
                        val suffix = address.substringAfter('@', "")
                        val target = if ('*' in suffix) {
                            val (_, fixed) = coordinate(result, address)
                            export.aggregateAddress(id, fixed)
                        } else {
                            export.address(id, if (suffix.isEmpty()) emptyList() else suffix.split('/'))
                        }
                        // A partial reduction without a visible cross total is verified in the engine;
                        // XLSX must contain every full coordinate and every displayed aggregate.
                        if (target == null && '*' in suffix && suffix != "*") return@forEach
                        val ref = CellReference(assertNotNull(target, "$base/$address"))
                        val cell = export.workbook.getSheet(ref.sheetName).getRow(ref.row).getCell(ref.col.toInt())
                        val actual = assertNotNull(evaluator.evaluate(cell), "$base/$address")
                        assertEquals(CellType.NUMERIC, actual.cellType, "$base/$address")
                        assertEquals(expected.toDouble(), actual.numberValue, 1e-8, "$base/$address")
                    }
                    export.write(out.resolve("$base.xlsx"))
                }
            }
        }
    }

    @Test
    fun `rate changes preserve PL OCI provenance and currency residue`() {
        val result = calculate("rate-change")
        assertEquals(0, BigDecimal("5.01").compareTo(result.decimal("pl-rate-effect", "Mixed", "P2")))
        assertEquals(0, BigDecimal("1.01").compareTo(result.decimal("oci-rate-effect", "Mixed", "P2")))
        assertEquals(0, result.decimal("pl-tax-movement", "Mixed", "P2").signum())
        assertEquals(0, result.decimal("oci-tax-movement", "Mixed", "P2").signum())
        assertEquals(
            result.value("closing-deferred-tax", "Mixed", "P2"),
            result.value("opening-deferred-tax", "Mixed", "P3"),
        )
        assertEquals(
            0,
            BigDecimal("30.05").compareTo((result.view.reduce("opening-deferred-tax").value as Value.Num).value),
        )
        assertEquals(
            0,
            BigDecimal("24.04").compareTo((result.view.reduce("closing-deferred-tax").value as Value.Num).value),
        )
        assertEquals(
            "ifrs.ias12/deferred-tax-precision",
            calculate("rate-change", 4).node("currency-scale").parameterSource,
        )
        val gross = calculate("asset-liability")
        assertEquals(0, gross.decimal("period-closing-deferred-tax", "P3").signum())
        assertEquals(0, BigDecimal("250").compareTo(gross.decimal("period-closing-tax-asset", "P3")))
        assertEquals(0, BigDecimal("250").compareTo(gross.decimal("period-closing-tax-liability", "P3")))
    }

    @Test
    fun `Excel input changes update rate effects and later closing stocks`() {
        ExcelExport.workbook(calculate("demo"), layout).use { export ->
            val source = CellReference(assertNotNull(export.tableAddress("tax-items", 0, "opening-pl-difference")))
            val input = export.workbook.getSheet(source.sheetName).getRow(source.row).getCell(source.col.toInt())
            input.setCellValue(12000.0)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.clearAllCachedResultValues()
            fun amount(id: String, vararg coords: String): Double {
                val ref = CellReference(assertNotNull(export.address(id, coords.toList())))
                val cell = export.workbook.getSheet(ref.sheetName).getRow(ref.row).getCell(ref.col.toInt())
                val actual = evaluator.evaluate(cell)
                assertEquals(CellType.NUMERIC, actual.cellType)
                return actual.numberValue
            }
            assertEquals(650.0, amount("pl-rate-effect", "Plant", "P2"), 1e-8)
            assertEquals(3000.0, amount("period-closing-deferred-tax", "P3"), 1e-8)
            assertEquals(600.0, amount("deferred-tax-reconciliation", "P3"), 1e-8)
        }
    }

    @Test
    fun `fractional movements and rate changes reconcile at rounded bucket boundaries`() {
        val original = Mantra.loadCase(documents.resolve("case-rate-change.mantra"))
        val row = Value.MapV(
            mapOf(
                Value.Kw("item") to Value.Kw("Mixed"),
                Value.Kw("period") to Value.Kw("P2"),
                Value.Kw("pl-change") to Value.num("0.04"),
                Value.Kw("oci-change") to Value.num("-0.07"),
            ),
        )
        val result = Mantra.calculateForAudit(
            schema,
            original.copy(
                inputs = original.inputs + (
                    "difference-movements" to Value.Vec(listOf(row))
                    ),
            ),
        )
        // Independent decimal arithmetic: round(100.09 × .30) = 30.03;
        // round(20.08 × .30) = 6.02. Movements are .01 and -.03, total close 36.05.
        assertEquals(0, BigDecimal("0.01").compareTo(result.decimal("pl-tax-movement", "Mixed", "P2")))
        assertEquals(0, BigDecimal("-0.03").compareTo(result.decimal("oci-tax-movement", "Mixed", "P2")))
        assertEquals(0, BigDecimal("36.05").compareTo(result.decimal("closing-deferred-tax", "Mixed", "P2")))
        assertEquals(0, result.decimal("measurement-crossfoot", "Mixed", "P2").signum())
        assertEquals(0, BigDecimal("24.04").compareTo(result.decimal("closing-deferred-tax", "Mixed", "P3")))
        ExcelExport.workbook(result, layout).use { export ->
            assertEquals(emptyList(), export.report.fallbacks)
            val source = CellReference(assertNotNull(export.tableAddress("difference-movements", 0, "pl-change")))
            export.workbook.getSheet(source.sheetName).getRow(source.row).getCell(source.col.toInt()).setCellValue(0.08)
            val evaluator = export.workbook.creationHelper.createFormulaEvaluator()
            evaluator.clearAllCachedResultValues()
            val target = CellReference(assertNotNull(export.address("closing-deferred-tax", listOf("Mixed", "P2"))))
            val cell = export.workbook.getSheet(target.sheetName).getRow(target.row).getCell(target.col.toInt())
            val actual = evaluator.evaluate(cell)
            // round(100.13 × .30) + round(20.08 × .30) = 30.04 + 6.02.
            assertEquals(CellType.NUMERIC, actual.cellType)
            assertEquals(36.06, actual.numberValue, 1e-8)
        }
    }

    @Test
    fun `reported mismatches remain nonblocking at the tolerance boundary`() {
        val original = Mantra.loadCase(documents.resolve("case-demo.mantra"))
        val reportedRows = (original.inputs.getValue("reported-tax-closings") as Value.Vec).items
        fun changed(reported: String) = Mantra.calculate(
            schema,
            original.copy(
                inputs = original.inputs + (
                    "reported-tax-closings" to Value.Vec(
                        reportedRows.mapIndexed { index, row ->
                            if (index == 1) {
                                val updated =
                                    (row as Value.MapV).entries + (Value.Kw("signed-tax") to Value.num(reported))
                                Value.MapV(updated)
                            } else {
                                row
                            }
                        },
                    )
                    ),
            ),
        )
        assertTrue(changed("2700.01").validationPassed)
        val failed = changed("2700.01000001")
        assertTrue(failed.succeeded)
        assertFalse(failed.validationPassed)
        assertEquals(0, BigDecimal("2700").compareTo(failed.decimal("period-closing-deferred-tax", "P2")))
        assertEquals("MANTRA-RECONCILE-FAILED", failed.diagnostics.single().code)
        assertEquals(listOf("P2"), failed.diagnostics.single().coord)
    }

    @Test
    fun `generic workbench discovers explains edits and exports the new schema`() {
        val local = Files.createDirectories(temporary.resolve("roll-forward"))
        Files.list(documents).use { paths ->
            paths.filter { it.toString().endsWith(".mantra") }.forEach { Files.copy(it, local.resolve(it.fileName)) }
        }
        WorkspaceCatalog(temporary).use { catalog ->
            val cases = catalog.workspace().data["cases"] as List<*>
            assertTrue(cases.filterIsInstance<Map<*, *>>().any { it["id"] == "roll-forward/case-demo.mantra" })
            val case = "roll-forward/case-demo.mantra"
            for (name in listOf("structure", "run", "paper", "parameters", "diagnostics")) {
                assertTrue(catalog.document(case, name).data.isNotEmpty(), name)
            }
            val explain = catalog.explain(case, ExplainAddress("pl-rate-effect", listOf("Plant", "P2"))).data
            assertTrue(explain.isNotEmpty())
            val original = Files.readAllBytes(local.resolve("case-demo.mantra"))
            val revision = catalog.document(case, "run").revision
            val committed = catalog.commitEdits(
                case,
                revision,
                listOf(
                    CaseTextEditor.Operation.SetCell(
                        "tax-items",
                        "Plant",
                        "opening-pl-difference",
                        Value.num(12000),
                        "id",
                    ),
                ),
            )
            assertFalse(original.contentEquals(Files.readAllBytes(local.resolve("case-demo.mantra"))))
            assertEquals(false, catalog.document(case, "run").data["validationPassed"])
            assertTrue(catalog.exportPreview(case).data.isNotEmpty())
            for (format in listOf("html", "txt", "xlsx", "pdf")) {
                assertTrue(catalog.export(case, format).isNotEmpty(), format)
            }
            catalog.undo(case, committed.revision)
            assertTrue(original.contentEquals(Files.readAllBytes(local.resolve("case-demo.mantra"))))
        }
    }
}
