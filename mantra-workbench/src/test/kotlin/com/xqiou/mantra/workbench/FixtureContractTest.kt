package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `three acceptance cases generate stable browser fixtures satisfying their schemas`() {
        val temp = Files.createTempDirectory("mantra-wp3-fixtures-")
        try {
            val cases = examples.map { (directory, case) -> Path.of("examples", directory, case) }
            val entries = Fixtures.writeMany(cases, temp)
            assertEquals(examples.map { (directory, case) -> "$directory/$case" }, entries.map { it.id })
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
                listOf("structure", "run", "paper", "diagnostics").forEach { name ->
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
