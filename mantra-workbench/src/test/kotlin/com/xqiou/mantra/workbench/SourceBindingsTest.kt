package com.xqiou.mantra.workbench

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.workbench.json.WorkbenchJson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SourceBindingsTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var temp: Path

    @Test fun `wide CSV supplies member values with provenance while case inputs win`() {
        Files.writeString(
            temp.resolve("schema.mantra"),
            """
            (schema test/source {:title "Source" :mainline [main]}
              (dimension person {:members [{:key :A :label "A"} {:key :B :label "B"}]})
              (input wage :decimal {:per person})
              (section main "Main" {:panel true :per person}
                (field wage "Wage") (total sum "Sum")))
            """.trimIndent(),
        )
        Files.writeString(
            temp.resolve("case.mantra"),
            """
            (case sample {:schema "test/source"}
              (sources (csv {:path "pay.csv" :mode :wide :member-column "Person"
                             :columns {"Wage" "wage"}}))
              (inputs {:wage {:A 10}}))
            """.trimIndent(),
        )
        val csv = temp.resolve("pay.csv")
        Files.writeString(csv, "Person;Wage\nA;1.234,56\nB;200,00\n")
        val catalog = workspaceCatalog(temp)
        assertContains(WorkbenchJson.write(catalog.sources("case.mantra").data), "\"overridden\":[\"wage@A\"]")
        val stamp = catalog.workspaceStamp()
        kotlin.test.assertEquals(catalog.workspace().revision, stamp.revision)
        val first = catalog.document("case.mantra", "run")
        val body = WorkbenchJson.write(first.data)
        assertContains(body, "\"A\":{\"value\":{\"n\":\"10\"}")
        assertContains(body, "\"B\":{\"value\":{\"n\":\"200.00\"}")
        assertContains(body, "\"origin\":\"source:csv:pay.csv\"")
        Files.writeString(csv, "Person;Wage\nA;1.234,56\nB;250,00\n")
        val changedStamp = catalog.workspaceStamp(stamp)
        assertNotEquals(stamp.revision, changedStamp.revision)
        kotlin.test.assertEquals(catalog.workspace().revision, changedStamp.revision)
        val second = catalog.document("case.mantra", "run")
        assertNotEquals(first.revision, second.revision)
        assertContains(WorkbenchJson.write(second.data), "\"B\":{\"value\":{\"n\":\"250.00\"}")
        val output = Files.createDirectories(temp.resolve("fixtures"))
        val entry = Fixtures.write(temp.resolve("case.mantra"), output, workspaceRoot = temp)
        val fixtureRun = Files.readString(
            output.resolve((entry.files.getValue("run") as String).removePrefix("/fixtures/")),
        )
        assertContains(fixtureRun, "\"origin\":\"source:csv:pay.csv\"")
        assertContains(fixtureRun, "\"B\":{\"value\":{\"n\":\"250.00\"}")
    }

    @Test fun `template limit does not leave an unusable 129th file`() {
        val directory = Files.createDirectories(temp.resolve("import-templates"))
        repeat(128) { index ->
            Files.writeString(
                directory.resolve("template$index.json"),
                """{"name":"template$index","format":"csv","options":{}}""",
            )
        }
        val catalog = workspaceCatalog(temp)
        val error = assertFailsWith<WorkspaceException> {
            catalog.saveImportTemplate("overflow", "csv", mapOf("mode" to Value.Text("wide")))
        }
        kotlin.test.assertEquals(WorkspaceProblem.TOO_LARGE, error.problem)
        kotlin.test.assertEquals(128L, Files.list(directory).use { it.count() })
        kotlin.test.assertEquals(128, (catalog.importTemplates().data["templates"] as List<*>).size)
    }

    @Test fun `ambiguous three digit CSV sample requires explicit number format`() {
        val inspection = ImportFiles.inspect("maybe.csv", "csv", "input;value\namount;12.345\n".toByteArray())
        kotlin.test.assertEquals(true, inspection["numericAmbiguous"])
        kotlin.test.assertEquals(null, inspection["decimal"])
    }
}
