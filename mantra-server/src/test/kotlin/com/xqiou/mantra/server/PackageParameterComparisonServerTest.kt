package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.xqiou.mantra.core.api.RuntimeVersions
import com.xqiou.mantra.packages.PackageLimits
import com.xqiou.mantra.packages.PackageLoader
import com.xqiou.mantra.packages.PackageSnapshot
import com.xqiou.mantra.packages.SemanticVersion
import com.xqiou.mantra.workbench.packages.EditablePackageCase
import com.xqiou.mantra.workbench.packages.PackageMount
import com.xqiou.mantra.workbench.packages.PackageWorkspaceCatalog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageParameterComparisonServerTest {
    @TempDir lateinit var directory: Path
    private val json = ObjectMapper()
    private val case = "bundle/cases/demo.mantra"
    private val route = "/api/v1/package-cases/bundle%2Fcases%2Fdemo.mantra/"
    private val original = """
        (case demo {:schema "compare/example" :schema-version "1.0.0"}
          (inputs {:base-value 5}))
    """.trimIndent()

    private fun snapshot(name: String): PackageSnapshot {
        val root = Files.createDirectories(directory.resolve(name))
        val binding = mapOf("id" to "compare/example", "version" to "1.0.0", "versionMode" to "semver")
        val foreign = mapOf("id" to "compare/foreign", "version" to "1.0.0", "versionMode" to "semver")
        val files = mapOf(
            "schema.mantra" to """
                (schema compare/example {:version "1.0.0"}
                  (param rate 1) (input base-value :decimal)
                  (section main "Main" (line answer "Answer" (* base-value rate))))
            """.trimIndent(),
            "foreign-schema.mantra" to "(schema compare/foreign {:version \"1.0.0\"} (param rate 1))",
            "cases/demo.mantra" to original,
            "parameters/current.mantra" to "(parameters current {:for \"compare/example\"} (value rate 2))",
            "parameters/alternative.mantra" to """
                (parameters alternative {:for "compare/example" :valid-from "2027-01-01" :valid-until "2028-01-01"}
                  (value rate 3 {:reference "Fictional alternative rate"}))
            """.trimIndent(),
            "parameters/foreign.mantra" to "(parameters foreign {:for \"compare/foreign\"} (value rate 9))",
        )
        files.forEach { (path, text) ->
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        val manifest = mapOf(
            "format" to "mantra.package/1", "id" to "compare.$name", "version" to "1.0.0",
            "engine" to ">=0.4.0-0 <2.0.0",
            "resources" to files.map { (path, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                mapOf(
                    "path" to path,
                    "byteLength" to bytes.size,
                    "sha256" to httpHash(bytes),
                    "role" to when {
                        path.startsWith("cases/") -> "case"
                        path.startsWith("parameters/") -> "parameters"
                        else -> "schema"
                    },
                )
            },
            "schemas" to listOf(
                mapOf("schema" to binding, "path" to "schema.mantra"),
                mapOf("schema" to foreign, "path" to "foreign-schema.mantra"),
            ),
            "parameters" to listOf("current", "alternative", "foreign").map {
                mapOf(
                    "id" to it,
                    "schema" to if (it ==
                        "foreign"
                    ) {
                        foreign
                    } else {
                        binding
                    },
                    "path" to "parameters/$it.mantra",
                )
            },
            "layouts" to emptyList<Any>(),
            "cases" to listOf(
                mapOf(
                    "id" to "demo",
                    "schema" to binding,
                    "path" to "cases/demo.mantra",
                    "parameters" to listOf("current"),
                    "layout" to null,
                ),
            ),
            "dependencies" to emptyList<Any>(),
        )
        Files.writeString(root.resolve("manifest.json"), json.writeValueAsString(manifest))
        return PackageLoader.directory(
            root,
            SemanticVersion.parse(RuntimeVersions.mantra),
            PackageLimits(65_536, 65_536, 524_288, 64, 16, 128),
        )
    }

    private fun server(catalog: PackageWorkspaceCatalog): WorkbenchServer {
        val workspace = Files.createDirectories(directory.resolve("workspace"))
        val ui = Files.createDirectories(directory.resolve("ui"))
        Files.writeString(ui.resolve("index.html"), "<!doctype html><html><head></head><body>Test</body></html>")
        return WorkbenchServer(workspace, 0, ui, packageWorkspace = catalog).start()
    }

    private fun request(
        client: HttpClient,
        server: WorkbenchServer,
        path: String,
        body: Any? = null,
        token: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path"))
        if (body == null) {
            request.GET()
        } else {
            request.header("Content-Type", "application/json")
            token?.let { request.header("X-Mantra-Token", it) }
            request.POST(
                HttpRequest.BodyPublishers.ofString(if (body is String) body else json.writeValueAsString(body)),
            )
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun validate(body: String) {
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/packages.schema.json"))
        assertTrue(schema.validate(body, InputFormat.JSON).isEmpty(), body)
    }

    @Test fun `readonly HTTP comparison preserves captured values and dates without authorizing filesystem reads`() {
        val catalog =
            PackageWorkspaceCatalog(
                listOf(PackageMount("bundle", snapshot("main")), PackageMount("other", snapshot("other"))),
            )
        server(catalog).use { server ->
            HttpClient.newHttpClient().use { client ->
                val token = Regex("name=\"mantra-session-token\" content=\"([0-9a-f]{64})\"")
                    .find(request(client, server, "/").body())!!.groupValues[1]
                val baseline = request(client, server, route + "run")
                assertEquals(200, baseline.statusCode(), baseline.body())
                assertTrue(json.readTree(baseline.body())["revision"].isTextual, baseline.body())
                val revision = json.readTree(baseline.body())["revision"].asText()
                val payload = mapOf(
                    "variantParameters" to listOf("bundle/parameters/alternative.mantra"),
                    "effectiveDate" to "2026-06-30",
                    "expectedRevision" to revision,
                )
                assertEquals(403, request(client, server, route + "compare", payload).statusCode())
                Files.writeString(
                    directory.resolve("main/parameters/alternative.mantra"),
                    "outside edited private content",
                )
                val response = request(client, server, route + "compare", payload, token)
                assertEquals(200, response.statusCode(), response.body())
                validate(response.body())
                val data = json.readTree(response.body())["data"]
                val answer = data["document"]["data"]["changes"].flatMap { it["items"].toList() }.single {
                    it["node"].asText() ==
                        "answer"
                }
                assertEquals("10", answer["base"]["n"].asText())
                assertEquals("15", answer["variant"]["n"].asText())
                assertEquals("5", answer["delta"]["n"].asText())
                val evidence = data["parameterSources"].single()
                assertEquals("what-if", evidence["mode"].asText())
                assertEquals("2026-06-30", evidence["effectiveDate"].asText())
                assertFalse(evidence["validForDate"].asBoolean())
                assertFalse(response.body().contains("outside edited private content"))
                assertEquals(
                    json.readTree(baseline.body()),
                    json.readTree(request(client, server, route + "run").body()),
                )
                for (invalid in listOf(
                    payload - "effectiveDate", payload + ("effectiveDate" to "2026-02-30"),
                    payload + ("effectiveDate" to "2026-6-30"),
                    payload + ("effectiveDate" to ""), payload + ("effectiveDate" to "today"),
                    payload + ("effectiveDate" to "2026-06-30T00:00:00Z"),
                    payload +
                        (
                            "variantParameters" to
                                listOf("bundle/parameters/alternative.mantra", "bundle/parameters/alternative.mantra")
                            ),
                    payload + ("variantParameters" to emptyList<String>()),
                    payload + ("variantParameters" to List(9) { "bundle/parameters/$it.mantra" }),
                    payload + ("variantParameters" to listOf("other/parameters/alternative.mantra")),
                    payload + ("variantParameters" to listOf("bundle/parameters/foreign.mantra")),
                    payload + ("variantParameters" to listOf("../private.txt")),
                    payload + ("variantParameters" to listOf(1)),
                    payload + ("document" to "/etc/passwd"), payload + ("expectedRevision" to "bad"),
                    payload - "expectedRevision",
                )) {
                    assertEquals(
                        400,
                        request(client, server, route + "compare", invalid, token).statusCode(),
                        invalid.toString(),
                    )
                }
                assertEquals(
                    409,
                    request(
                        client,
                        server,
                        route + "compare",
                        payload + ("expectedRevision" to "0".repeat(64)),
                        token,
                    ).statusCode(),
                )
                assertEquals(
                    400,
                    request(client, server, route + "compare?document=private.txt", payload, token).statusCode(),
                )
                val duplicate = json.writeValueAsString(payload).dropLast(1) + ",\"effectiveDate\":\"2026-06-30\"}"
                assertEquals(400, request(client, server, route + "compare", duplicate, token).statusCode())
            }
        }
    }

    @Test fun `editable HTTP scenario never writes host sources and honors external edit revisions`() {
        val store = HttpCaseStore(original, "")
        val catalog = PackageWorkspaceCatalog(
            listOf(PackageMount("bundle", snapshot("editable"))),
            editable = listOf(EditablePackageCase(case, store, "root")),
        )
        server(catalog).use { server ->
            HttpClient.newHttpClient().use { client ->
                val token = Regex("name=\"mantra-session-token\" content=\"([0-9a-f]{64})\"")
                    .find(request(client, server, "/").body())!!.groupValues[1]
                val baseline = request(client, server, route + "run")
                assertEquals(200, baseline.statusCode(), baseline.body())
                assertTrue(json.readTree(baseline.body())["revision"].isTextual, baseline.body())
                val revision = json.readTree(baseline.body())["revision"].asText()
                val payload = mapOf(
                    "variantParameters" to listOf("bundle/parameters/alternative.mantra"),
                    "effectiveDate" to "2027-06-30",
                    "expectedRevision" to revision,
                )
                val response = request(client, server, route + "compare", payload, token)
                assertEquals(200, response.statusCode(), response.body())
                assertEquals(original, store.values["root"])
                assertEquals(0, store.writes)
                assertEquals(
                    revision,
                    json.readTree(request(client, server, route + "run").body())["revision"].asText(),
                )
                store.values["root"] += "\n;; same values, externally reviewed\n"
                assertEquals(409, request(client, server, route + "compare", payload, token).statusCode())
                assertEquals(0, store.writes)
            }
        }
    }
}
