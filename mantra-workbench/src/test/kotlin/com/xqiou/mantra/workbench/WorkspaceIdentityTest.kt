package com.xqiou.mantra.workbench

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkspaceIdentityTest : WorkspaceCatalogTestOwner() {
    @TempDir lateinit var temporary: Path

    private val files = linkedMapOf(
        "schema.mantra" to """
            (schema identity/example {:version "1" :mainline [main]}
              (input base-amount :decimal)
              (section main "Main" {:panel true}
                (info answer "Answer" (* base-amount 2))))
        """.trimIndent(),
        "layout.mantra" to "(layout identity/layout {:title \"Identity example\"})",
        "case.mantra" to "(case example {:schema \"identity/example\" :layout \"identity/layout\"}" +
            " (inputs {:base-amount 10}))",
    )

    private fun createWorkspace(name: String): Path = Files.createDirectory(temporary.resolve(name)).also { root ->
        files.forEach { (path, text) -> Files.writeString(root.resolve(path), text) }
    }

    private fun workspaceId(result: WorkspaceCatalog.DocumentResult): String = result.data["workspaceId"] as String

    @Test fun `workspace and snapshot share stable canonical identity across catalog restarts`() {
        val root = createWorkspace("stable")
        val before = files.keys.associateWith { Files.readAllBytes(root.resolve(it)) }
        val first = workspaceCatalog(root)
        val workspace = first.workspace()
        val snapshot = first.templateSources("case.mantra")
        val identity = workspaceId(workspace)
        assertTrue(identity.matches(Regex("[0-9a-f]{64}")))
        assertEquals(identity, workspaceId(snapshot))
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(("mantra.workspace/1" + '\u0000' + root.toRealPath()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, identity)
        first.close()
        val reopened = workspaceCatalog(root.resolve("."))
        assertEquals(identity, workspaceId(reopened.workspace()))
        assertEquals(identity, workspaceId(reopened.templateSources("case.mantra")))
        val afterNames = Files.list(root).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }
        assertEquals(before.keys, afterNames)
        before.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve(name))) }
        assertFalse(first.envelope(snapshot).contains(root.toString()))
        assertFalse(first.envelope(workspace).contains(root.toString()))
    }

    @Test fun `identical copied graphs keep source identities but have distinct workspace identities`() {
        val first = workspaceCatalog(createWorkspace("first"))
        val second = workspaceCatalog(createWorkspace("second"))
        val firstSnapshot = first.templateSources("case.mantra")
        val secondSnapshot = second.templateSources("case.mantra")
        assertEquals(firstSnapshot.revision, secondSnapshot.revision)
        assertEquals(firstSnapshot.data["baseRevisions"], secondSnapshot.data["baseRevisions"])
        assertEquals(firstSnapshot.data["documents"], secondSnapshot.data["documents"])
        assertNotEquals(workspaceId(firstSnapshot), workspaceId(secondSnapshot))
        assertNotEquals(workspaceId(first.workspace()), workspaceId(second.workspace()))
    }

    @Test fun `symlink aliases resolve to the same workspace identity`() {
        val root = createWorkspace("canonical")
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), root)
        val canonical = workspaceCatalog(root)
        val throughAlias = workspaceCatalog(alias)
        assertEquals(workspaceId(canonical.workspace()), workspaceId(throughAlias.workspace()))
        assertEquals(
            workspaceId(canonical.templateSources("case.mantra")),
            workspaceId(throughAlias.templateSources("case.mantra")),
        )
    }

    @Test fun `identity-bearing workspace and source envelopes conform to their response schemas`() {
        val catalog = workspaceCatalog(createWorkspace("schema"))
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        listOf("workspace" to catalog.workspace(), "template-sources" to catalog.templateSources("case.mantra"))
            .forEach { (name, result) ->
                val location = SchemaLocation.of("https://mantra.local/workbench/schema/$name.schema.json")
                val schema = registry.getSchema(location)
                assertTrue(schema.validate(catalog.envelope(result), InputFormat.JSON).isEmpty())
            }
    }
}
