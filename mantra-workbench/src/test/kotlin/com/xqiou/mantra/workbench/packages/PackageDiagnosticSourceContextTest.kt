package com.xqiou.mantra.workbench.packages

import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.workbench.WorkspaceException
import com.xqiou.mantra.workbench.WorkspaceProblem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackageDiagnosticSourceContextTest {
    @TempDir lateinit var directory: Path
    private val json = ObjectMapper()

    @Test fun `package diagnostic source is captured and survives archive deletion`() {
        val fixture = PackageHostFixture(directory)
        val snapshot = fixture.snapshot("context", "1.0.0", technical = true)
        val catalog = PackageWorkspaceCatalog(listOf(PackageMount("bundle", snapshot)))
        val case = "bundle/cases/demo.mantra"
        val diagnostics = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            catalog.document(case, "diagnostics"),
        )
        val revision = diagnostics["revision"].asText()
        val findings = diagnostics["data"]["document"]["data"]["diagnostics"]
        val index = findings.indexOfFirst { !it["location"].isNull }
        require(index >= 0)
        Files.delete(directory.resolve("context-1.0.0.jar"))
        val response = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            catalog.sourceContext(case, index, revision),
        )
        assertEquals("mantra.packages/1", response["contract"].asText())
        assertEquals(revision, response["revision"].asText())
        assertEquals(false, response["data"]["succeeded"].asBoolean())
        val context = response["data"]["document"]
        assertEquals("mantra.workbench/4", context["contract"].asText())
        assertEquals(revision, context["revision"].asText())
        assertEquals(case, context["data"]["case"].asText())
        assertEquals(findings[index]["location"], context["data"]["location"])
        assertContains(context["data"]["lines"].toString(), "(/ base-value 0)")
        assertEquals(
            WorkspaceProblem.CONFLICT,
            assertFailsWith<WorkspaceException> { catalog.sourceContext(case, index, "0".repeat(64)) }.problem,
        )
        assertEquals(
            WorkspaceProblem.NOT_FOUND,
            assertFailsWith<WorkspaceException> { catalog.sourceContext(case, 99, revision) }.problem,
        )
    }
}
