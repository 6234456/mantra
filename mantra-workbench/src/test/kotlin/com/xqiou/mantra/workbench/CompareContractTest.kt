package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompareContractTest {
    private val directory = Path.of("apps/de-est")
    private val golden = Path.of(
        "mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/compare-2026.json",
    )

    private fun registry(): SchemaRegistry {
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { path -> "https://mantra.local/workbench/schema/${path.fileName}" to Files.readString(path) }
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
    }

    @Test
    fun `ESt comparison matches versioned golden and wire schema`() {
        val comparison = WorkspaceCatalog(directory).use { catalog ->
            catalog.compare("case-mustermann.mantra", null, listOf("de.est/params-2026"))
        }
        val data = comparison.data
        val generated = WorkbenchJson.write(WorkbenchJson.envelope(comparison.revision, "test", "test", data))
        if (System.getenv("MANTRA_UPDATE_GOLDEN") == "1") Files.writeString(golden, generated + "\n")
        val goldenValue = Json.parse(Files.readString(golden)) as Value.MapV
        val actualValue = Json.parse(generated) as Value.MapV
        assertEquals(Value.Text(WorkbenchJson.CONTRACT), goldenValue.entries[Value.Kw("contract")])
        assertEquals(Value.Text(WorkbenchJson.CONTRACT), actualValue.entries[Value.Kw("contract")])
        assertEquals(goldenValue.entries[Value.Kw("data")], actualValue.entries[Value.Kw("data")])
        val schemaCheck = registry().getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/compare.schema.json"),
        )
        val goldenErrors = schemaCheck.validate(Files.readString(golden), InputFormat.JSON)
        val generatedErrors = schemaCheck.validate(generated, InputFormat.JSON)
        assertTrue(goldenErrors.isEmpty(), goldenErrors.toString())
        assertTrue(generatedErrors.isEmpty(), generatedErrors.toString())
        val mainline = data["mainline"] as List<*>
        assertEquals(
            listOf("zve", "est", "zuschlagsteuern", "abrechnung"),
            mainline.map { (it as Map<*, *>)["panel"] },
        )
        val first = mainline.first() as Map<*, *>
        assertEquals(mapOf("n" to "-156.00"), first["delta"])
        assertEquals(12, (data["parameterChanges"] as List<*>).size)
    }

    @Test
    fun `parameter layers retain declared order references and case precedence`() {
        val schema = Mantra.loadSchema(directory.resolve("schema.mantra"))
        val case = Mantra.loadCase(directory.resolve("case-mustermann.mantra"))
            .copy(params = mapOf("tarif-gfb" to Value.num("12500")))
        val set = Mantra.loadParameters(directory.resolve("params-2026.mantra"))
        val later = set.copy(
            id = "later",
            values = mapOf("tarif-gfb" to Value.num("12400")),
            references = mapOf("tarif-gfb" to "later reference"),
        )
        val view = CalculationView.of(Mantra.calculate(schema, case, listOf(set, later)))
        val document = WorkbenchDocuments.parameters(view)
        val row = (document["parameters"] as List<*>).map { it as Map<*, *> }.first { it["id"] == "tarif-gfb" }
        val layers = row["layers"] as List<*>
        assertEquals(listOf("schema", "parameters", "parameters", "case"), layers.map { (it as Map<*, *>)["layer"] })
        assertEquals(
            listOf("12096", "12348", "12400", "12500"),
            layers.map {
                ((it as Map<*, *>)["value"] as Map<*, *>)["n"]
            },
        )
        assertEquals("later reference", (layers[2] as Map<*, *>)["reference"])
        assertEquals(true, (layers[3] as Map<*, *>)["declared"])
        assertEquals(mapOf("value" to mapOf("n" to "12500"), "layer" to "case"), row["effective"])
        val encoded = WorkbenchJson.write(WorkbenchJson.envelope("0000000000000000", "test", "test", document))
        val check = registry().getSchema(
            SchemaLocation.of("https://mantra.local/workbench/schema/parameters.schema.json"),
        )
        assertTrue(check.validate(encoded, InputFormat.JSON).isEmpty())
        assertFailsWith<UnsupportedOperationException> {
            (view.node("tarif-gfb").parameterLayers as MutableList).clear()
        }
    }
}
