package com.xqiou.mantra.workbench.packages

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.packages.DirectoryPolicy
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.workbench.CaseTextEditor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackageLinkedEditingTest {
    @TempDir lateinit var temporary: Path
    private val key = "bundle/cases/demo.mantra"
    private val json = ObjectMapper()

    private data class Host(
        val catalog: PackageWorkspaceCatalog,
        val store: MemoryHostStore,
        val snapshot: PackageSnapshot,
        val capturedBytes: Map<String, ByteArray>,
    )

    /** Source answer is independently 10 * 2 = 20; the consumer owns its other coordinates. */
    private fun host(): Host {
        val fixture = PackageHostFixture(temporary)
        val seed = fixture.snapshot("linked.edits", "1.0.0")
        val files = seed.manifest.resources.associate { it.path to seed.bytes(it.path) }.toMutableMap()
        files["schema.mantra"] = seed.source("schema.mantra").text.replace(
            "(input enabled :boolean)",
            """
                (input enabled :boolean)
                (dimension asset {:members [:A :B]})
                (dimension period {:members [:P1 :P2]})
                (input amount :decimal {:per [asset period] :default 0})
            """.trimIndent(),
        ).toByteArray(Charsets.UTF_8)
        val original = fixture.original.replace(
            "(inputs {:base-value 0 :enabled false})",
            """
                (inputs {:base-value 0 :enabled false :amount {:A {:P2 3} :B {:P1 4 :P2 5}}})
                (links {:path "source.mantra" :schema "host/demo" :schema-version "1.0.0"
                  :mappings [{:from {:node :answer :coord []} :to {:input :amount :coord [:A :P1]}}]})
            """.trimIndent(),
        )
        files["cases/demo.mantra"] = original.toByteArray(Charsets.UTF_8)
        files["cases/source.mantra"] = fixture.original.replace("(case demo", "(case source")
            .replace(":base-value 0", ":base-value 10").toByteArray(Charsets.UTF_8)
        val manifest = json.readTree(seed.manifestBytes()) as ObjectNode
        manifest.set<ArrayNode>(
            "resources",
            json.valueToTree(
                files.map { (path, bytes) ->
                    mapOf(
                        "path" to path,
                        "role" to when {
                            path.startsWith("cases/") -> "case"
                            path.startsWith("parameters/") -> "parameters"
                            else -> "schema"
                        },
                        "byteLength" to bytes.size,
                        "sha256" to hostHash(bytes),
                    )
                },
            ),
        )
        val cases = manifest["cases"] as ArrayNode
        cases.add((cases[0] as ObjectNode).deepCopy().put("id", "source").put("path", "cases/source.mantra"))
        val directory = temporary.resolve("capture")
        files.forEach { (path, bytes) ->
            val file = directory.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        Files.write(directory.resolve("manifest.json"), json.writeValueAsBytes(manifest))
        val snapshot = PackageLoader.directory(directory, fixture.engine, fixture.limits, DirectoryPolicy.TRUSTED_LOCAL)
        val store = MemoryHostStore(original)
        val catalog = PackageWorkspaceCatalog(
            listOf(PackageMount("bundle", snapshot)),
            editable = listOf(EditablePackageCase(key, store, "host-case.mantra")),
        )
        assertTrue(catalog.evaluate(key).graph.succeeded)
        assertEquals(Value.num("20"), catalog.evaluate(key).graph.result!!.view.value("amount", "A", "P1"))
        return Host(catalog, store, snapshot, files)
    }

    private fun revision(host: Host) = requireNotNull(host.catalog.evaluate(key).revision)
    private fun map(vararg entries: Pair<String, Value>) =
        Value.MapV(entries.associate { Value.Kw(it.first) to it.second })
    private fun capturedResourcesUnchanged(host: Host) {
        host.capturedBytes.forEach { (path, bytes) -> assertContentEquals(bytes, host.snapshot.bytes(path), path) }
    }

    private fun rejected(operation: CaseTextEditor.Operation) {
        val host = host()
        val original = host.store.text
        val revision = revision(host)
        for (previewOnly in listOf(true, false)) {
            val error = assertFailsWith<IllegalArgumentException> {
                host.catalog.edit(key, revision, listOf(operation), previewOnly)
            }
            assertEquals("A linked input is source-owned", error.message)
            assertEquals(original, host.store.text)
            assertEquals(0, host.store.writes)
            assertEquals(revision, revision(host))
            capturedResourcesUnchanged(host)
        }
    }

    @Test fun `whole-map replacement cannot bypass linked ownership`() {
        rejected(CaseTextEditor.Operation.SetInput("amount", map("A" to map("P2" to Value.num("9")))))
    }

    @Test fun `whole-map clear cannot bypass linked ownership`() {
        rejected(CaseTextEditor.Operation.ClearInput("amount"))
    }

    @Test fun `ancestor replacement cannot bypass linked ownership`() {
        rejected(CaseTextEditor.Operation.SetInput("amount", map("P2" to Value.num("9")), listOf("A")))
    }

    @Test fun `ancestor clear cannot bypass linked ownership`() {
        rejected(CaseTextEditor.Operation.ClearInput("amount", listOf("A")))
    }

    @Test fun `linked coordinate remains read-only`() {
        rejected(CaseTextEditor.Operation.SetInput("amount", Value.num("9"), listOf("A", "P1")))
        rejected(CaseTextEditor.Operation.ClearInput("amount", listOf("A", "P1")))
    }

    private fun candidateNumber(document: Map<String, Any?>, coordinate: String): String = json.valueToTree<ObjectNode>(
        document,
    )["data"]["document"]["data"]["run"]["values"]["amount"][coordinate]["value"]["n"].asText()

    @Test fun `unlinked sibling set and clear preview candidates without writes then commit`() {
        val host = host()
        val original = host.store.text
        val set = listOf(CaseTextEditor.Operation.SetInput("amount", Value.num("9"), listOf("A", "P2")))
        val preview = host.catalog.edit(key, revision(host), set, previewOnly = true)
        assertEquals("9", candidateNumber(preview, "A/P2"))
        assertEquals("20", candidateNumber(preview, "A/P1"))
        assertEquals(original, host.store.text)
        assertEquals(0, host.store.writes)
        host.catalog.edit(key, revision(host), set)
        assertEquals(Value.num("9"), host.catalog.evaluate(key).graph.result!!.view.value("amount", "A", "P2"))
        assertEquals(1, host.store.writes)
        val committed = host.store.text
        val clear = listOf(CaseTextEditor.Operation.ClearInput("amount", listOf("A", "P2")))
        val clearPreview = host.catalog.edit(key, revision(host), clear, previewOnly = true)
        assertEquals("0", candidateNumber(clearPreview, "A/P2"))
        assertEquals("20", candidateNumber(clearPreview, "A/P1"))
        assertEquals(committed, host.store.text)
        assertEquals(1, host.store.writes)
        host.catalog.edit(key, revision(host), clear)
        assertEquals(Value.ZERO, host.catalog.evaluate(key).graph.result!!.view.value("amount", "A", "P2"))
        assertEquals(Value.num("20"), host.catalog.evaluate(key).graph.result!!.view.value("amount", "A", "P1"))
        assertEquals(2, host.store.writes)
        capturedResourcesUnchanged(host)
    }

    @Test fun `unlinked ancestor map can be replaced and cleared`() {
        val host = host()
        val original = host.store.text
        val set = listOf(CaseTextEditor.Operation.SetInput("amount", map("P1" to Value.num("7")), listOf("B")))
        val preview = host.catalog.edit(key, revision(host), set, previewOnly = true)
        assertEquals("7", candidateNumber(preview, "B/P1"))
        assertEquals("20", candidateNumber(preview, "A/P1"))
        assertEquals(original, host.store.text)
        assertEquals(0, host.store.writes)
        host.catalog.edit(key, revision(host), set)
        val clear = listOf(CaseTextEditor.Operation.ClearInput("amount", listOf("B")))
        val committed = host.store.text
        val clearPreview = host.catalog.edit(key, revision(host), clear, previewOnly = true)
        assertEquals("0", candidateNumber(clearPreview, "B/P1"))
        assertEquals(committed, host.store.text)
        assertEquals(1, host.store.writes)
        host.catalog.edit(key, revision(host), clear)
        assertEquals(Value.ZERO, host.catalog.evaluate(key).graph.result!!.view.value("amount", "B", "P1"))
        assertEquals(Value.num("20"), host.catalog.evaluate(key).graph.result!!.view.value("amount", "A", "P1"))
        assertEquals(2, host.store.writes)
        capturedResourcesUnchanged(host)
    }
}
