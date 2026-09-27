package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.nio.file.Files
import java.nio.file.Path
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixtureContractTest {
    private val examples = listOf(
        "de-est-2025" to "case-mustermann.mantra",
        "ifrs-ias36-corporate-assets" to "case-ie8.mantra",
        "sap-co-product-cost" to "case-demo.mantra",
    )
    private val golden = Path.of("mantra-workbench/src/test/resources/golden")
    private val schemaDirectory = Path.of("docs/workbench/schema")

    @Test
    fun `fixture layout follows the case binding rather than a sibling file`() {
        val temp = Files.createTempDirectory("mantra-fixture-layout-")
        try {
            val directory = temp.resolve("sample")
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("schema.mantra"), """
                (schema test/example {:title "Default paper" :mainline [main]}
                  (section main "Main" {:panel true} (field amount "Amount") (total sum "Sum"))
                  (input amount :decimal))
            """.trimIndent())
            Files.writeString(directory.resolve("layout.mantra"), """
                (layout test/paper {:preset :de-staffel-4 :title "Bound paper"} (table main))
            """.trimIndent())
            val casePath = directory.resolve("case.mantra")
            fun writeCase(layout: String?) {
                val binding = layout?.let { " :layout \"$it\"" }.orEmpty()
                Files.writeString(casePath, "(case one {:schema \"test/example\"$binding} (inputs {:amount 12.5}))")
            }
            fun paperTitle(): String {
                val entry = Fixtures.write(casePath, temp.resolve("out"), workspaceRoot = temp)
                val paper = entry.files.getValue("paper").removePrefix("/fixtures/")
                val document = com.xqiou.mantra.core.data.Json.parse(Files.readString(temp.resolve("out/$paper")))
                    as com.xqiou.mantra.core.model.Value.MapV
                val data = document.entries.getValue(com.xqiou.mantra.core.model.Value.Kw("data"))
                    as com.xqiou.mantra.core.model.Value.MapV
                return (data.entries.getValue(com.xqiou.mantra.core.model.Value.Kw("title"))
                    as com.xqiou.mantra.core.model.Value.Text).value
            }
            writeCase(null)
            assertEquals("Default paper", paperTitle())
            writeCase("test/paper")
            assertEquals("Bound paper", paperTitle())
            writeCase("test/other")
            assertFailsWith<IllegalArgumentException> { paperTitle() }
        } finally {
            Files.walk(temp).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `presentation hints project as stable structure and paper fields`() {
        val directory = Path.of("examples/de-est-2025")
        val view = CalculationView.of(Mantra.calculate(
            Mantra.loadSchema(directory.resolve("schema.mantra")),
            Mantra.loadCase(directory.resolve("case-mustermann.mantra")),
        ))
        val structure = WorkbenchDocuments.structure(view)
        assertEquals("abrechnungsergebnis", structure["headline"])
        assertEquals("Veranlagungsmerkmale", (structure["groupTitles"] as Map<*, *>)["assessment"])
        val input = (structure["generalInputs"] as List<*>).map { it as Map<*, *> }.first { it["id"] == "veranlagungsart" }
        assertEquals("assessment", input["group"])
        val resultNode = (structure["nodes"] as Map<*, *>)["abrechnungsergebnis"] as Map<*, *>
        assertEquals("Erstattung", (resultNode["signLabels"] as Map<*, *>)["negative"])
        val paper = WorkbenchDocuments.paper(view, Render.loadLayout(directory.resolve("layout.mantra")))
        assertEquals("Nachzahlung", (paper["headline"] as Map<*, *>)["label"])
        assertTrue((paper["inputGroups"] as List<*>).any {
            (it as Map<*, *>)["key"] == "assessment" && "veranlagungsart" in (it["inputs"] as List<*>)
        })
    }

    @Test
    fun `three acceptance cases generate stable browser fixtures satisfying their schemas`() {
        val temp = Files.createTempDirectory("mantra-wp3-fixtures-")
        try {
            val cases = examples.map { (directory, case) -> Path.of("examples", directory, case) }
            val entries = Fixtures.writeMany(cases, temp)
            assertEquals(examples.map { (directory, case) -> "$directory/$case" }, entries.map { it.id })
            assertEquals("Eheleute Erika und Max Mustermann", entries.first().title)
            assertEquals(Files.readString(golden.resolve("index.json")), Files.readString(temp.resolve("index.json")))
            val schemas = Files.list(schemaDirectory).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
            }.associate { path ->
                "https://mantra.local/workbench/schema/${path.fileName}" to Files.readString(path)
            }
            val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemas(schemas)
            }
            entries.forEach { entry ->
                listOf("structure", "run", "paper", "diagnostics", "parameters").forEach { name ->
                    val file = "$name.json"
                    val relative = entry.files.getValue(name).removePrefix("/fixtures/")
                    val generated = Files.readString(temp.resolve(relative))
                    assertEquals(Files.readString(golden.resolve(relative)), generated, "${entry.id}/$file changed")
                    val schema = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/$name.schema.json"))
                    val errors = schema.validate(generated, InputFormat.JSON)
                    assertTrue(errors.isEmpty(), "${entry.id}/$file: $errors")
                }
            }
        } finally {
            Files.walk(temp).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `numeric values cannot enter the versioned value wire format`() {
        val schemas = Files.list(schemaDirectory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { path -> "https://mantra.local/workbench/schema/${path.fileName}" to Files.readString(path) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/value.schema.json"))
        assertFalse(schema.validate("12.50", InputFormat.JSON).isEmpty())
        assertTrue(schema.validate(WorkbenchJson.write(mapOf("n" to "12.50")), InputFormat.JSON).isEmpty())
    }

    @Test
    fun `value limit diagnostics retain the dimension address`() {
        val schema = Mantra.loadSchema(SourceText("schema.mantra", """
            (schema test/limit {}
              (dimension member {:members [:A :B]})
              (section result "Result" {:per member}
                (field principal "Principal")
                (line value "Value" principal)))
        """.trimIndent()), SourceResolver { _, _ -> null })
        val supplied = Mantra.loadCase(SourceText("case.mantra", "(case c)"))
            .copy(inputs = mapOf("principal" to Value.MapV(linkedMapOf(
                Value.Kw("A") to Value.num(1),
                Value.Kw("B") to Value.Num(BigDecimal("1E-1001")),
            ))))
        val result = Mantra.calculate(schema, supplied)
        val finding = result.diagnostics.single { it.code == "MANTRA-VALUE-LIMIT" }
        assertEquals(listOf("B"), finding.coord)
        val document = WorkbenchDocuments.diagnostics(result.diagnostics)
        val encoded = (document["diagnostics"] as List<*>).single() as Map<*, *>
        assertEquals(mapOf("node" to "value", "coord" to listOf("B")), encoded["address"])
    }

    @Test
    fun `panel paper is generated when an explicit layout omits that panel`() {
        val directory = Path.of("examples/ifrs-ias36-corporate-assets")
        val result = Mantra.calculate(
            Mantra.loadSchema(directory.resolve("schema.mantra")),
            Mantra.loadCase(directory.resolve("case-ie8.mantra")),
        )
        val view = CalculationView.of(result)
        val layout = Render.loadLayout(directory.resolve("layout.mantra"))
        val panel = view.structure.panels.first().id
        val withoutPanel = layout.copy(tables = layout.tables.filterNot { it.sectionId == panel })
        val paper = WorkbenchDocuments.paper(view, withoutPanel, panel)
        val tables = paper["tables"] as List<*>
        assertEquals(panel, (tables.single() as Map<*, *>)["id"])
    }

    @Test
    fun `bound formula slot exposes original formula and current binding separately`() {
        val directory = Path.of("examples/ifrs-ias36-corporate-assets")
        val result = Mantra.calculate(
            Mantra.loadSchema(directory.resolve("schema.mantra")),
            Mantra.loadCase(directory.resolve("case-custom-weight.mantra")),
        )
        val structure = WorkbenchDocuments.structure(CalculationView.of(result))
        val slots = structure["formulaSlots"] as List<*>
        val weighting = slots.single { (it as Map<*, *>)["id"] == "weighting" } as Map<*, *>
        assertEquals("(* remaining-life remaining-life)", weighting["binding"])
        assertTrue((weighting["defaultFormula"] as String).startsWith("(if weight-by-life"))
        val nodes = structure["nodes"] as Map<*, *>
        val node = nodes["weighting"] as Map<*, *>
        assertEquals("formula-slot", node["kind"])
        assertEquals(true, node["userDefined"])
    }
}
