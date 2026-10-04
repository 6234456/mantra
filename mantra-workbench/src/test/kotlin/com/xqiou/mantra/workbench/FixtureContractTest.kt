package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.render.Render
import com.xqiou.mantra.workbench.json.WorkbenchDocuments
import com.xqiou.mantra.workbench.json.WorkbenchJson
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FixtureContractTest {
    private val examples = listOf(
        "de-est" to "case-mustermann.mantra",
        "ifrs-impairment" to "case-demo.mantra",
        "cost-accounting" to "case-demo.mantra",
        "ifrs-income-taxes" to "case-demo.mantra",
        "ifrs-income-taxes" to "case-unreconciled.mantra",
        "ifrs-income-taxes" to "case-zero-profit.mantra",
        "fixed-assets" to "case-demo.mantra",
        "ifrs-leases" to "case-demo.mantra",
        "ifrs-impairment" to "case-discounted-viu.mantra",
        "ifrs-impairment" to "case-capped.mantra",
        "de-est" to "versions/2025.3/case-consumer-2025.mantra",
        "de-est" to "versions/2025.3/case-consumer-rate-400.mantra",
        "de-gewst" to "case-rate-400.mantra",
        "circular-calculation" to "bonus/case-bonus-main.mantra",
        "circular-calculation" to "gross-up/case-gross-up-main.mantra",
    )
    private val golden = Path.of("mantra-workbench/src/test/resources/golden")
    private val schemaDirectory = Path.of("docs/workbench/schema")

    private fun canonicalFixture(text: String): Value {
        val stepsKey = Value.Kw("steps")
        val branchesKey = Value.Kw("branches")
        val eventKey = Value.Kw("eventId")
        fun visit(value: Value): Value = when (value) {
            is Value.Vec -> Value.Vec(value.items.map(::visit))
            is Value.MapV -> {
                val isTrace = value.entries[stepsKey] is Value.Vec && value.entries[branchesKey] is Value.Vec
                // Opaque identities vary by attempt, but their sharing inside one trace is evidence.
                val events = linkedMapOf<String, String>()
                if (isTrace) {
                    listOf(stepsKey, branchesKey).forEach { key ->
                        (value.entries.getValue(key) as Value.Vec).items.forEach { entry ->
                            val id = ((entry as? Value.MapV)?.entries?.get(eventKey) as? Value.Text)?.value
                            if (id != null) events.getOrPut(id) { "event-${events.size}" }
                        }
                    }
                }
                fun event(entry: Value): Value = if (entry is Value.MapV) {
                    Value.MapV(
                        entry.entries.mapValues { (key, field) ->
                            if (key == eventKey && field is Value.Text) {
                                Value.Text(events.getOrPut(field.value) { "event-${events.size}" })
                            } else {
                                visit(field)
                            }
                        },
                    )
                } else {
                    visit(entry)
                }
                Value.MapV(
                    value.entries.mapValues { (key, field) ->
                        if (isTrace && (key == stepsKey || key == branchesKey)) {
                            Value.Vec((field as Value.Vec).items.map(::event))
                        } else {
                            visit(field)
                        }
                    },
                )
            }
            else -> value
        }
        return visit(Json.parse(text))
    }

    @Test
    fun `golden canonicalization preserves invocation values and same-trace event associations`() {
        fun document(
            first: String,
            second: String,
            branch: String = second,
            invocation: Long = 0,
            result: String = "5",
        ) = WorkbenchJson.write(
            mapOf(
                "data" to mapOf(
                    "steps" to listOf(
                        mapOf("text" to "(+ 2 3)", "eventId" to first, "invocationIndex" to 0),
                        mapOf(
                            "text" to "(next)",
                            "eventId" to second,
                            "invocationIndex" to invocation,
                            "value" to mapOf("n" to result),
                        ),
                    ),
                    "branches" to listOf(
                        mapOf(
                            "text" to "(next)",
                            "eventId" to branch,
                            "invocationIndex" to invocation,
                            "selected" to true,
                        ),
                    ),
                    "truncated" to false,
                ),
            ),
        )
        val expected = canonicalFixture(document("attempt-a/first", "attempt-a/next"))
        assertEquals(expected, canonicalFixture(document("attempt-b/first", "attempt-b/next")))
        assertNotEquals(
            expected,
            canonicalFixture(document("attempt-b/first", "attempt-b/next", branch = "attempt-b/first")),
        )
        assertNotEquals(expected, canonicalFixture(document("attempt-b/first", "attempt-b/next", invocation = 1)))
        assertNotEquals(expected, canonicalFixture(document("attempt-b/first", "attempt-b/next", result = "6")))
        val repeatedTrace = document("attempt-a/first", "attempt-a/next")
        val independentTrace = document("attempt-c/first", "attempt-c/next")
        assertEquals(
            canonicalFixture("{\"one\":$repeatedTrace,\"two\":$repeatedTrace}"),
            canonicalFixture("{\"one\":$repeatedTrace,\"two\":$independentTrace}"),
        )
    }

    @Test
    fun `fixture layout follows the authored binding or conventional schema layout`() {
        val temp = Files.createTempDirectory("mantra-fixture-layout-")
        try {
            val directory = temp.resolve("sample")
            Files.createDirectories(directory)
            Files.writeString(
                directory.resolve("schema.mantra"),
                """
                (schema test/example {:title "Default paper" :mainline [main]}
                  (section main "Main" {:panel true} (field amount "Amount") (total sum "Sum"))
                  (input amount :decimal))
                """.trimIndent(),
            )
            Files.writeString(
                directory.resolve("layout.mantra"),
                """
                (layout test/paper {:preset :de-staffel-4 :title "Bound paper"} (table main))
                """.trimIndent(),
            )
            val casePath = directory.resolve("case.mantra")
            fun writeCase(layout: String?) {
                val binding = layout?.let { " :layout \"$it\"" }.orEmpty()
                Files.writeString(casePath, "(case one {:schema \"test/example\"$binding} (inputs {:amount 12.5}))")
            }
            fun paperTitle(): String {
                val entry = Fixtures.write(casePath, temp.resolve("out"), workspaceRoot = temp)
                val paper = (entry.files.getValue("paper") as String).removePrefix("/fixtures/")
                val document = com.xqiou.mantra.core.data.Json.parse(Files.readString(temp.resolve("out/$paper")))
                    as com.xqiou.mantra.core.model.Value.MapV
                val data = document.entries.getValue(com.xqiou.mantra.core.model.Value.Kw("data"))
                    as com.xqiou.mantra.core.model.Value.MapV
                return (
                    data.entries.getValue(com.xqiou.mantra.core.model.Value.Kw("title"))
                        as com.xqiou.mantra.core.model.Value.Text
                    ).value
            }
            writeCase(null)
            assertEquals("Bound paper", paperTitle())
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
        val directory = Path.of("apps/de-est")
        val view = CalculationView.of(
            Mantra.calculate(
                Mantra.loadSchema(directory.resolve("schema.mantra")),
                Mantra.loadCase(directory.resolve("case-mustermann.mantra")),
            ),
        )
        val structure = WorkbenchDocuments.structure(view)
        assertEquals("abrechnungsergebnis", structure["headline"])
        assertEquals("Veranlagungsmerkmale", (structure["groupTitles"] as Map<*, *>)["assessment"])
        val input = (structure["generalInputs"] as List<*>).map { it as Map<*, *> }.first {
            it["id"] ==
                "veranlagungsart"
        }
        assertEquals("assessment", input["group"])
        val resultNode = (structure["nodes"] as Map<*, *>)["abrechnungsergebnis"] as Map<*, *>
        assertEquals("Erstattung", (resultNode["signLabels"] as Map<*, *>)["negative"])
        val paper = WorkbenchDocuments.paper(view, Render.loadLayout(directory.resolve("layout.mantra")))
        assertEquals("Nachzahlung", (paper["headline"] as Map<*, *>)["label"])
        assertTrue(
            (paper["inputGroups"] as List<*>).any {
                (it as Map<*, *>)["key"] == "assessment" && "veranlagungsart" in (it["inputs"] as List<*>)
            },
        )
    }

    @Test
    fun `all eight applications generate stable browser fixtures satisfying their schemas`() {
        val temp = Files.createTempDirectory("mantra-wp3-fixtures-")
        try {
            val cases = examples.map { (directory, case) -> Path.of("apps", directory, case) }
            val entries = Fixtures.writeMany(
                cases,
                temp,
                explainAddresses = mapOf(
                    "de-est/case-mustermann.mantra" to listOf(
                        ExplainAddress("ermaessigung-35a"),
                        ExplainAddress("zu-versteuerndes-einkommen"),
                        ExplainAddress("tarifliche-est"),
                    ),
                    "ifrs-impairment/case-demo.mantra" to listOf(
                        ExplainAddress("recoverable-amount", listOf("B")),
                        ExplainAddress("allocation-key", listOf("B")),
                        ExplainAddress("all.weighted-amount"),
                        ExplainAddress("weighted-amount", listOf("A")),
                        ExplainAddress("weighted-amount", listOf("B")),
                        ExplainAddress("weighted-amount", listOf("C")),
                    ),
                    "cost-accounting/case-demo.mantra" to
                        listOf(
                            ExplainAddress("direct-primary-total"),
                            ExplainAddress("aggregate.actual-weighted-unit"),
                        ),
                    "ifrs-income-taxes/case-demo.mantra" to
                        listOf(
                            ExplainAddress("effective-tax-rate", listOf("North")),
                            ExplainAddress("aggregate.effective-tax-rate"),
                        ),
                    "fixed-assets/case-demo.mantra" to listOf(
                        ExplainAddress("carrying-closing", listOf("Machine", "P1")),
                        ExplainAddress("carrying-closing", listOf("Machine", "P2")),
                        ExplainAddress("carrying-opening", listOf("Machine", "P2")),
                        ExplainAddress("aggregate.carrying-closing", listOf("asset=Machine")),
                        ExplainAddress("aggregate.depreciation", listOf("asset=Machine")),
                    ),
                    "ifrs-leases/case-demo.mantra" to listOf(
                        ExplainAddress("liability-closing", listOf("Office", "P2")),
                        ExplainAddress("liability-opening", listOf("Office", "P2")),
                        ExplainAddress("aggregate.liability-closing", listOf("lease=Office")),
                    ),
                    "ifrs-impairment/case-discounted-viu.mantra" to listOf(
                        ExplainAddress("present-value", listOf("A", "P2")),
                        ExplainAddress("recoverable-amount", listOf("A")),
                    ),
                    "ifrs-impairment/case-capped.mantra" to listOf(
                        ExplainAddress("asset-allocated-loss", listOf("Workshop", "Machine")),
                    ),
                    "de-est/versions/2025.3/case-consumer-2025.mantra" to listOf(
                        ExplainAddress("verlustvortrag"),
                        ExplainAddress("loss-used"),
                        ExplainAddress("closing-loss"),
                    ),
                    "de-est/versions/2025.3/case-consumer-rate-400.mantra" to listOf(
                        ExplainAddress("gewst-messbetrag", listOf("A")),
                        ExplainAddress("gewst-due", listOf("A")),
                        ExplainAddress("ermaessigung-35"),
                    ),
                    "circular-calculation/bonus/case-bonus-main.mantra" to listOf(ExplainAddress("converged-amount")),
                    "circular-calculation/gross-up/case-gross-up-main.mantra" to
                        listOf(ExplainAddress("converged-amount")),
                ),
            )
            assertEquals(
                examples.map { (directory, case) ->
                    "$directory/$case"
                },
                entries.take(examples.size).map { it.id },
            )
            assertEquals("de-est/versions/2024.1/case-source-2024.mantra", entries.last().id)
            assertEquals(examples.size + 1, entries.size)
            assertEquals("Eheleute Erika und Max Mustermann", entries.first().title)
            if (System.getenv("MANTRA_UPDATE_GOLDEN") == "1") {
                Files.walk(temp).use { files ->
                    files.filter(Files::isRegularFile).forEach { path ->
                        val destination = golden.resolve(temp.relativize(path))
                        Files.createDirectories(destination.parent)
                        Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
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
                entry.files.filterKeys {
                    it in
                        setOf("structure", "run", "paper", "diagnostics", "parameters", "export-preview") ||
                        it.startsWith("export-preview:")
                }.forEach { (key, url) ->
                    val name = key.substringBefore(':')
                    val relative = (url as String).removePrefix("/fixtures/")
                    val generated = Files.readString(temp.resolve(relative))
                    assertEquals(
                        canonicalFixture(Files.readString(golden.resolve(relative))),
                        canonicalFixture(generated),
                        "${entry.id}/$key changed",
                    )
                    val schema = registry.getSchema(
                        SchemaLocation.of("https://mantra.local/workbench/schema/$name.schema.json"),
                    )
                    val errors = schema.validate(generated, InputFormat.JSON)
                    assertTrue(errors.isEmpty(), "${entry.id}/$key: $errors")
                }
                @Suppress("UNCHECKED_CAST")
                val explains = entry.files["explains"] as? Map<String, String> ?: emptyMap()
                explains.values.forEach { path ->
                    val relative = path.removePrefix("/fixtures/")
                    val generated = Files.readString(temp.resolve(relative))
                    assertEquals(
                        canonicalFixture(Files.readString(golden.resolve(relative))),
                        canonicalFixture(generated),
                        "${entry.id}/$relative changed",
                    )
                    val schema = registry.getSchema(
                        SchemaLocation.of("https://mantra.local/workbench/schema/explain.schema.json"),
                    )
                    assertTrue(schema.validate(generated, InputFormat.JSON).isEmpty(), "${entry.id}/$relative")
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
        val schema = Mantra.loadSchema(
            SourceText(
                "schema.mantra",
                """
            (schema test/limit {}
              (dimension member {:members [:A :B]})
              (section result "Result" {:per member}
                (field principal "Principal")
                (line value "Value" principal)))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        )
        val supplied = Mantra.loadCase(SourceText("case.mantra", "(case c)"))
            .copy(
                inputs = mapOf(
                    "principal" to Value.MapV(
                        linkedMapOf(
                            Value.Kw("A") to Value.num(1),
                            Value.Kw("B") to Value.Num(BigDecimal("1E-1001")),
                        ),
                    ),
                ),
            )
        val result = Mantra.calculate(schema, supplied)
        val finding = result.diagnostics.single { it.code == "MANTRA-VALUE-LIMIT" }
        assertEquals(listOf("B"), finding.coord)
        val document = WorkbenchDocuments.diagnostics(result.diagnostics)
        val encoded = (document["diagnostics"] as List<*>).single() as Map<*, *>
        assertEquals(mapOf("case" to null, "node" to "value", "coord" to listOf("B")), encoded["address"])
    }

    @Test
    fun `panel paper is generated when an explicit layout omits that panel`() {
        val directory = Path.of("apps/ifrs-impairment")
        val result = Mantra.calculate(
            Mantra.loadSchema(directory.resolve("schema.mantra")),
            Mantra.loadCase(directory.resolve("case-demo.mantra")),
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
        val directory = Path.of("apps/ifrs-impairment")
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
