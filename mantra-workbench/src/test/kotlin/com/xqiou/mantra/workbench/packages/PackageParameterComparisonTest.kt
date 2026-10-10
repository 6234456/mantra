package com.xqiou.mantra.workbench.packages

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.packages.MigrationStore
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PackageParameterComparisonTest {
    @TempDir lateinit var directory: Path
    private val json = ObjectMapper()
    private val date = LocalDate.parse("2026-06-30")
    private fun tree(value: Any): JsonNode = json.valueToTree(value)
    private fun revision(catalog: PackageWorkspaceCatalog, case: String): String =
        tree(catalog.document(case, "run"))["revision"].asText()

    private fun change(response: JsonNode, node: String): JsonNode = response["data"]["document"]["data"]["changes"]
        .flatMap { it["items"].toList() }.single { it["node"].asText() == node }

    @Test fun `readonly captured alternatives produce independent values and explicit what-if evidence`() {
        val fixture = PackageHostFixture(directory)
        val snapshot = fixture.snapshot("comparison", "1.0.0", alternative = true, baseValue = 5)
        val catalog = PackageWorkspaceCatalog(listOf(PackageMount("bundle", snapshot)))
        val case = "bundle/cases/demo.mantra"
        val revision = revision(catalog, case)
        val descriptors = tree(catalog.workspace())["data"]["packages"][0]["parameters"]
        assertEquals(
            setOf("bundle/parameters/current.mantra", "bundle/parameters/alternative.mantra"),
            descriptors.map { it["id"].asText() }.toSet(),
        )
        assertTrue(descriptors.all { it["schema"].asText() == "host/demo" && it["schemaVersion"].asText() == "1.0.0" })
        Files.delete(directory.resolve("comparison-1.0.0.jar"))
        val selected = listOf("bundle/parameters/alternative.mantra")
        val response = tree(catalog.compare(case, selected, date, revision))
        val answer = change(response, "answer")
        assertEquals("10", answer["base"]["n"].asText())
        assertEquals("15", answer["variant"]["n"].asText())
        assertEquals("5", answer["delta"]["n"].asText())
        assertEquals(selected, response["data"]["document"]["data"]["variant"]["parameters"].map { it.asText() })
        val evidence = response["data"]["parameterSources"].single()
        assertEquals("what-if", evidence["mode"].asText())
        assertEquals("2026-06-30", evidence["effectiveDate"].asText())
        assertEquals("2027-01-01", evidence["validFrom"].asText())
        assertEquals(false, evidence["validForDate"].asBoolean())
        assertEquals(snapshot.descriptor("parameters/alternative.mantra").sha256, evidence["sha256"].asText())
        assertEquals("alternative", evidence["effectiveLayer"].asText())
        assertEquals("3", evidence["effectiveValue"]["n"].asText())
        val differentDate = tree(catalog.compare(case, selected, LocalDate.parse("2027-06-30"), revision))
        assertEquals(answer, change(differentDate, "answer"))
        assertNotEquals(response["revision"], differentDate["revision"])
        assertTrue(differentDate["data"]["parameterSources"].single()["validForDate"].asBoolean())
        assertEquals(revision, revision(catalog, case), "Read-only comparison must not change baseline bindings")
        val baseline = catalog.evaluate(case, audit = false).graph.result!!.view.node("answer").value()
        assertEquals(Value.Num(10.toBigDecimal()), baseline)
        val layered = tree(catalog.compare(case, listOf("bundle/parameters/current.mantra") + selected, date, revision))
        assertEquals("15", change(layered, "answer")["variant"]["n"].asText())
        val reversed = tree(catalog.compare(case, selected + "bundle/parameters/current.mantra", date, revision))
        assertEquals(0, reversed["data"]["document"]["data"]["changes"].size())
        assertEquals("current", reversed["data"]["parameterSources"].single()["set"].asText())
    }

    @Test fun `editable host facts remain unchanged and stale baseline revisions are rejected`() {
        val fixture = PackageHostFixture(directory)
        val snapshot = fixture.snapshot("editable-comparison", "1.0.0", alternative = true)
        val original = fixture.original.replace(":base-value 0", ":base-value 5")
        val store = MemoryHostStore(original)
        val case = "bundle/cases/demo.mantra"
        val catalog = PackageWorkspaceCatalog(
            listOf(PackageMount("bundle", snapshot)),
            editable = listOf(EditablePackageCase(case, store, "host-case")),
        )
        val revision = revision(catalog, case)
        val response = tree(catalog.compare(case, listOf("bundle/parameters/alternative.mantra"), date, revision))
        assertEquals("15", change(response, "answer")["variant"]["n"].asText())
        assertEquals(original, store.text)
        assertEquals(0, store.writes)
        assertEquals(revision, revision(catalog, case))
        store.text += "\n;; outside host edit with equal inputs\n"
        val stale = assertFailsWith<WorkspaceException> {
            catalog.compare(case, listOf("bundle/parameters/alternative.mantra"), date, revision)
        }
        assertEquals(WorkspaceProblem.CONFLICT, stale.problem)
        assertEquals(revision(catalog, case), stale.currentRevision)
        assertEquals(0, store.writes)
        store.text = original.dropLast(1) + " (params {:rate 9}))"
        val overridden =
            tree(catalog.compare(case, listOf("bundle/parameters/alternative.mantra"), date, revision(catalog, case)))
        assertEquals(0, overridden["data"]["document"]["data"]["changes"].size())
        val evidence = overridden["data"]["parameterSources"].single()
        assertEquals("case", evidence["effectiveLayer"].asText())
        assertTrue(evidence["overriddenByCase"].asBoolean())
        assertEquals("9", evidence["effectiveValue"]["n"].asText())
        assertEquals("3", evidence["selectedSetValue"]["n"].asText())
        assertEquals(0, store.writes)
    }

    @Test fun `parameter IDs must retain full mount scope and exact package schema`() {
        val fixture = PackageHostFixture(directory)
        val old = fixture.snapshot("comparison-old", "1.0.0", alternative = true, baseValue = 5)
        val newer = fixture.snapshot("comparison-new", "2.0.0", alternative = true, baseValue = 5)
        val catalog = PackageWorkspaceCatalog(listOf(PackageMount("nested/bundle", old), PackageMount("other", newer)))
        val case = "nested/bundle/cases/demo.mantra"
        val revision = revision(catalog, case)
        val eligible = "nested/bundle/parameters/alternative.mantra"
        val response = tree(catalog.compare(case, listOf(eligible), date, revision))
        assertEquals("15", change(response, "answer")["variant"]["n"].asText())
        for (invalid in listOf(
            emptyList(),
            listOf(eligible, eligible),
            List(9) { "nested/bundle/parameters/unknown-$it.mantra" },
            listOf("alternative"),
            listOf("other/parameters/alternative.mantra"),
            listOf("nested/bundle/../other/parameters/alternative.mantra"),
            listOf("nested/bundle/schema.mantra"),
            listOf("/etc/passwd"),
        )) {
            assertFailsWith<IllegalArgumentException>(invalid.toString()) {
                catalog.compare(case, invalid, date, revision)
            }
        }
    }

    @Test fun `external host changes during comparison cannot mix old baseline and new variant facts`() {
        val fixture = PackageHostFixture(directory)
        val snapshot = fixture.snapshot("racing-comparison", "1.0.0", alternative = true)
        val original = fixture.original.replace(":base-value 0", ":base-value 5")
        var current = original
        var changeAfterRead = false
        var reads = 0
        val store = object : MigrationStore {
            override fun read(casePath: String): SourceText {
                reads++
                val captured = SourceText(casePath, current, casePath)
                if (changeAfterRead) {
                    current = original.replace(":base-value 5", ":base-value 50")
                    changeAfterRead = false
                }
                return captured
            }
            override fun commit(
                casePath: String,
                sourceSha256: String,
                candidate: SourceText,
                authorize: (write: () -> Unit) -> Unit,
            ) {
                error("Read-only comparison must never commit host sources")
            }
        }
        val case = "bundle/cases/demo.mantra"
        val catalog = PackageWorkspaceCatalog(
            listOf(PackageMount("bundle", snapshot)),
            editable = listOf(EditablePackageCase(case, store, "host-case")),
        )
        val revision = revision(catalog, case)
        val beforeReads = reads
        changeAfterRead = true
        val response = tree(catalog.compare(case, listOf("bundle/parameters/alternative.mantra"), date, revision))
        assertEquals(beforeReads + 1, reads, "The variant reuses all host facts captured by the baseline")
        val answer = change(response, "answer")
        assertEquals("10", answer["base"]["n"].asText())
        assertEquals("15", answer["variant"]["n"].asText())
        assertEquals("5", answer["delta"]["n"].asText())
        assertEquals(original.replace(":base-value 5", ":base-value 50"), current)
        assertNotEquals(revision, revision(catalog, case))
    }
}
