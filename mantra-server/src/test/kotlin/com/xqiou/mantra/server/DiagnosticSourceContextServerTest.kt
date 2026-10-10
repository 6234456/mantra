package com.xqiou.mantra.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticSourceContextServerTest {
    @TempDir lateinit var directory: Path
    private val json = ObjectMapper()

    private fun request(client: HttpClient, server: WorkbenchServer, path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:${server.localPort}$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun validate(name: String, response: String) {
        val schemas = Files.list(Path.of("docs/workbench/schema")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }.associate { "https://mantra.local/workbench/schema/${it.fileName}" to Files.readString(it) }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/$name.schema.json"))
        assertTrue(schema.validate(response, InputFormat.JSON).isEmpty(), response)
    }

    private fun workspace(): Path {
        val workspace = Files.createDirectory(directory.resolve("workspace"))
        Files.writeString(
            workspace.resolve("schema.mantra"),
            """
            (schema context/example
              (input source-amount :decimal)
              (section main "Main"
                (line result "Result" source-amount)
                (check positive "Positive" (> source-amount 0))))
            """.trimIndent(),
        )
        Files.writeString(
            workspace.resolve("case.mantra"),
            "(case one {:schema \"context/example\"} (inputs {:source-amount -1}))",
        )
        Files.writeString(workspace.resolve("unrelated.mantra"), "(parameters unrelated {})")
        Files.writeString(workspace.resolve("private.txt"), "private content must not be read")
        return workspace
    }

    @Test fun `live diagnostic source requires exact selector and root revision and rejects extra read authority`() {
        val workspace = workspace()
        WorkbenchServer(workspace, 0).use { server ->
            server.start()
            HttpClient.newHttpClient().use { client ->
                val route = "/api/v1/cases/case.mantra/diagnostic-source"
                val baseline = request(client, server, "/api/v1/cases/case.mantra/diagnostics")
                assertEquals(200, baseline.statusCode(), baseline.body())
                val diagnostics = json.readTree(baseline.body())
                val revision = diagnostics["revision"].asText()
                val query = "?diagnostic=0&expectedRevision=$revision"
                val source = request(client, server, route + query)
                assertEquals(200, source.statusCode())
                validate("source-context", source.body())
                val context = json.readTree(source.body())
                assertEquals(diagnostics["data"]["diagnostics"][0]["location"], context["data"]["location"])
                assertContains(context["data"]["lines"].toString(), "Positive")
                assertFalse(source.body().contains("private content"))
                for (invalid in listOf(
                    "", "?diagnostic=0", "?expectedRevision=$revision", "?diagnostic=-1&expectedRevision=$revision",
                    "?diagnostic=bad&expectedRevision=$revision", "?diagnostic=2147483648&expectedRevision=$revision",
                    "?diagnostic=0&expectedRevision=unknown", "$query&diagnostic=0", "$query&document=private.txt",
                    "$query&document=unrelated.mantra", "$query&case=outside.mantra", "$query&line=1",
                )) {
                    assertEquals(400, request(client, server, route + invalid).statusCode(), invalid)
                }
                assertEquals(
                    404,
                    request(client, server, route + "?diagnostic=7&expectedRevision=$revision").statusCode(),
                )
                assertEquals(
                    404,
                    request(client, server, "/api/v1/cases/unrelated.mantra/diagnostic-source$query").statusCode(),
                )
                val schema = workspace.resolve("schema.mantra")
                Files.writeString(schema, Files.readString(schema) + "\n;; same numeric outcome\n")
                val stale = request(client, server, route + query)
                assertEquals(409, stale.statusCode())
                assertTrue(json.readTree(stale.body())["error"]["currentRevision"].asText() != revision)
            }
        }
    }

    @Test fun `outside symlink cannot replace a previously authorized diagnostic source`() {
        val workspace = workspace()
        WorkbenchServer(workspace, 0).use { server ->
            server.start()
            HttpClient.newHttpClient().use { client ->
                val baseline = request(client, server, "/api/v1/cases/case.mantra/diagnostics")
                assertEquals(200, baseline.statusCode(), baseline.body())
                val diagnostics = json.readTree(baseline.body())
                val revision = diagnostics["revision"].asText()
                val schema = workspace.resolve("schema.mantra")
                val outside = directory.resolve("outside.mantra")
                Files.writeString(outside, Files.readString(schema) + "\n;; private outside content\n")
                Files.delete(schema)
                Files.createSymbolicLink(schema, outside)
                val response = request(
                    client,
                    server,
                    "/api/v1/cases/case.mantra/diagnostic-source?diagnostic=0&expectedRevision=$revision",
                )
                assertTrue(response.statusCode() in setOf(404, 422))
                assertFalse(response.body().contains("private outside content"))
            }
        }
    }

    @Test fun `package HTTP source uses captured resources and stale host overlays conflict`() {
        val fixture = PackageHttpFixture(directory.resolve("packages"))
        val catalog = fixture.catalog()
        fixture.store.values["root"] = fixture.original.replace(":divisor 1", ":divisor 0")
        val workspace = Files.createDirectory(directory.resolve("empty-workspace"))
        WorkbenchServer(workspace, 0, packageWorkspace = catalog).use { server ->
            server.start()
            HttpClient.newHttpClient().use { client ->
                val route = "/api/v1/package-cases/old%2Fcases%2Fdemo.mantra/"
                val response = request(client, server, route + "diagnostics")
                assertEquals(200, response.statusCode(), response.body())
                val diagnostics = json.readTree(response.body())
                val revision = diagnostics["revision"].asText()
                val findings = diagnostics["data"]["document"]["data"]["diagnostics"]
                val index = findings.indexOfFirst { !it["location"].isNull }
                assertTrue(index >= 0)
                fixture.jars.forEach(Files::delete)
                val query = "?diagnostic=$index&expectedRevision=$revision"
                val source = request(client, server, route + "diagnostic-source" + query)
                assertEquals(200, source.statusCode(), source.body())
                validate("packages", source.body())
                assertFalse(json.readTree(source.body())["data"]["succeeded"].asBoolean())
                val context: JsonNode = json.readTree(source.body())["data"]["document"]
                assertEquals(findings[index]["location"], context["data"]["location"])
                assertContains(context["data"]["lines"].toString(), "divisor")
                assertEquals(400, request(client, server, route + "diagnostic-source?diagnostic=0").statusCode())
                assertEquals(
                    400,
                    request(client, server, route + "diagnostic-source$query&document=manifest.json").statusCode(),
                )
                fixture.store.values["root"] += "\n;; host changed\n"
                assertEquals(409, request(client, server, route + "diagnostic-source" + query).statusCode())
            }
        }
    }

    @Test fun `HTTP diagnostic indexes retain planning warnings after ordinary cached runs`() {
        val workspace = workspace()
        val schema = workspace.resolve("schema.mantra")
        Files.writeString(
            schema,
            Files.readString(schema).replace("source-amount", "amount")
                .replace("\" amount)", "\" mantra/amount)").replace("(> amount 0)", "(> mantra/amount 0)"),
        )
        val case = workspace.resolve("case.mantra")
        Files.writeString(case, Files.readString(case).replace(":source-amount", ":amount"))
        WorkbenchServer(workspace, 0).use { server ->
            server.start()
            HttpClient.newHttpClient().use { client ->
                val root = "/api/v1/cases/case.mantra/"
                repeat(2) { assertEquals(200, request(client, server, root + "run").statusCode()) }
                val baseline = request(client, server, root + "diagnostics")
                assertEquals(200, baseline.statusCode(), baseline.body())
                val diagnostics = json.readTree(baseline.body())
                val revision = diagnostics["revision"].asText()
                val findings = diagnostics["data"]["diagnostics"]
                assertEquals(listOf("MANTRA-ID-SHADOWED", "MANTRA-CHECK-FAILED"), findings.map { it["code"].asText() })
                repeat(3) {
                    assertEquals(200, request(client, server, root + "run").statusCode())
                    assertEquals(diagnostics, json.readTree(request(client, server, root + "diagnostics").body()))
                    findings.forEachIndexed { index, finding ->
                        val response = request(
                            client,
                            server,
                            root + "diagnostic-source?diagnostic=$index&expectedRevision=$revision",
                        )
                        assertEquals(200, response.statusCode(), response.body())
                        val context = json.readTree(response.body())
                        assertEquals(finding["location"], context["data"]["location"])
                        assertEquals(revision, context["revision"].asText())
                    }
                }
            }
        }
    }
}
