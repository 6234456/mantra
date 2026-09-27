package com.xqiou.mantra.server

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkbenchServerTest {
    @TempDir lateinit var temp: Path

    private fun workspace(): Path {
        val root = temp.resolve("workspace")
        Files.createDirectories(root.resolve("sample"))
        Files.writeString(root.resolve("sample/schema.mantra"), """
            (schema test/example {:title "Test" :mainline [main]}
              (section main "Main" {:panel true} (field amount "Amount") (total sum "Sum"))
              (input amount :decimal))
        """.trimIndent())
        Files.writeString(root.resolve("sample/case.mantra"), """
            (case one {:schema "test/example" :title "Case one"}
              (inputs {:amount 12.5}))
        """.trimIndent())
        return root
    }

    private data class Response(val status: Int, val headers: String, val body: String)

    private fun validate(name: String, body: String) {
        val files = Files.list(Path.of("docs/workbench/schema")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".schema.json") }.toList()
        }
        val schemas = files.associate { file ->
            "https://mantra.local/workbench/schema/${file.fileName}" to Files.readString(file)
        }
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { it.schemas(schemas) }
        val schema = registry.getSchema(SchemaLocation.of("https://mantra.local/workbench/schema/$name.schema.json"))
        val errors = schema.validate(body, InputFormat.JSON)
        assertTrue(errors.isEmpty(), "$name: $errors")
    }

    private fun request(port: Int, path: String, method: String = "GET", host: String = "127.0.0.1:$port",
                        headers: Map<String, String> = emptyMap(), body: ByteArray = byteArrayOf()): Response {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
            val output = socket.getOutputStream()
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $host\r\nConnection: close\r\n")
                headers.forEach { (key, value) -> append("$key: $value\r\n") }
                if (headers.keys.none { it.equals("Content-Length", ignoreCase = true) })
                    append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
            socket.shutdownOutput()
            val response = socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
            val split = response.indexOf("\r\n\r\n")
            val header = response.substring(0, split)
            return Response(header.lineSequence().first().split(' ')[1].toInt(), header, response.substring(split + 4))
        }
    }

    @Test fun `read only documents use the contract envelope and reject unknown cases and panels`() {
        WorkbenchServer(workspace(), 0).use { server ->
            server.start()
            val workspace = request(server.localPort, "/api/v1/workspace")
            assertEquals(200, workspace.status)
            assertContains(workspace.body, "\"id\":\"sample/case.mantra\"")
            assertContains(workspace.body, "\"schemas\"")
            validate("workspace", workspace.body)
            val case = "sample%2Fcase.mantra"
            val structure = request(server.localPort, "/api/v1/cases/$case/structure")
            val run = request(server.localPort, "/api/v1/cases/$case/run")
            val paper = request(server.localPort, "/api/v1/cases/$case/paper?panel=main")
            val diagnostics = request(server.localPort, "/api/v1/cases/$case/diagnostics")
            val parameters = request(server.localPort, "/api/v1/cases/$case/parameters")
            for (response in listOf(structure, run, paper, diagnostics, parameters)) {
                assertEquals(200, response.status, response.body)
                assertContains(response.body, "\"contract\":\"mantra.workbench/1\"")
                assertContains(response.body, "\"revision\":")
            }
            listOf("structure", "run", "paper", "diagnostics", "parameters")
                .zip(listOf(structure, run, paper, diagnostics, parameters))
                .forEach { (name, response) -> validate(name, response.body) }
            assertContains(run.body, "\"n\":\"12.5\"")
            assertContains(paper.body, "\"id\":\"main\"")
            assertEquals(404, request(server.localPort, "/api/v1/cases/$case/paper?panel=missing").status)
            assertEquals(404, request(server.localPort, "/api/v1/cases/missing/structure").status)
            assertEquals(400, request(server.localPort, "/api/v1/cases/$case/run?layout=x").status)
            assertEquals(400, request(server.localPort, "/api/v1/cases/$case/paper?panel=x&panel=y").status)
        }
    }

    @Test fun `host token path traversal and request size are enforced`() {
        val root = workspace()
        val ui = temp.resolve("dist")
        Files.createDirectories(ui)
        Files.writeString(ui.resolve("index.html"), "<html><head></head><body>workbench</body></html>")
        WorkbenchServer(root, 0, ui).use { server ->
            server.start()
            val port = server.localPort
            assertEquals(403, request(port, "/api/v1/workspace", host = "evil.example:$port").status)
            assertEquals(403, request(port, "/api/v1/workspace", host = "localhost:$port").status)
            assertFalse(request(port, "/api/v1/workspace").headers.contains("Access-Control-Allow-Origin"))
            val index = request(port, "/cases/sample%2Fcase.mantra/overview")
            assertEquals(200, index.status)
            val token = Regex("name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"").find(index.body)?.groupValues?.get(1)
            assertTrue(token != null)
            val preview = "/api/v1/cases/sample%2Fcase.mantra/preview"
            assertEquals(403, request(port, preview, "POST").status)
            assertEquals(501, request(port, preview, "POST", headers = mapOf("X-Mantra-Token" to token)).status)
            assertEquals(501, request(port, "/api/v1/cases/sample%2Fcase.mantra/compare", "POST",
                headers = mapOf("X-Mantra-Token" to token)).status)
            assertEquals(501, request(port, "/api/v1/cases/sample%2Fcase.mantra/explain?address=sum").status)
            assertEquals(404, request(port, "/api/v1/cases/..%2F..%2Fsecret.mantra/structure").status)
            assertEquals(404, request(port, "/%2e%2e/secret.txt").status)
            assertEquals(413, request(port, preview, "POST", headers = mapOf(
                "X-Mantra-Token" to token, "Content-Length" to "${1024 * 1024 + 1}"),
            ).status)
        }
    }

    @Test fun `symlink escapes and oversized documents cannot be read`() {
        val root = workspace()
        val outside = temp.resolve("outside.mantra")
        Files.writeString(outside, "(case out {:schema \"test/example\"})")
        Files.createSymbolicLink(root.resolve("sample/out.mantra"), outside)
        WorkbenchServer(root, 0).use { server ->
            server.start()
            assertEquals(404, request(server.localPort, "/api/v1/cases/sample%2Fout.mantra/run").status)
        }
        Files.writeString(root.resolve("sample/large.mantra"), " ".repeat(65_537))
        WorkbenchServer(root, 0).use { server ->
            server.start()
            assertEquals(413, request(server.localPort, "/api/v1/workspace").status)
        }
    }

    @Test fun `all three acceptance workspaces can be read through HTTP`() {
        WorkbenchServer(Path.of("examples"), 0).use { server ->
            server.start()
            for (id in listOf(
                "de-est-2025/case-mustermann.mantra",
                "ifrs-ias36-corporate-assets/case-ie8.mantra",
                "sap-co-product-cost/case-demo.mantra",
            )) {
                val encoded = id.replace("/", "%2F")
                val structure = request(server.localPort, "/api/v1/cases/$encoded/structure")
                val run = request(server.localPort, "/api/v1/cases/$encoded/run")
                val paper = request(server.localPort, "/api/v1/cases/$encoded/paper")
                assertEquals(200, structure.status, "$id: ${structure.body}")
                assertEquals(200, run.status, "$id: ${run.body}")
                assertEquals(200, paper.status, "$id: ${paper.body}")
                assertContains(run.body, "\"succeeded\":true")
            }
        }
    }

    @Test fun `declared parameter and layout bindings affect documents and revisions`() {
        val root = workspace()
        Files.writeString(root.resolve("sample/schema.mantra"), """
            (schema test/example {:title "Test" :mainline [main]}
              (param adjustment 1)
              (section main "Main" {:panel true}
                (line adjusted "Adjusted" (+ base-value adjustment))
                (total sum "Sum"))
              (input base-value :decimal))
        """.trimIndent())
        Files.writeString(root.resolve("sample/params.mantra"), """
            (parameters test/variant {:for "test/example"}
              (values {:adjustment 3}))
        """.trimIndent())
        Files.writeString(root.resolve("sample/layout.mantra"), "(layout test/brief {:preset :de-staffel-4} (table main))")
        Files.writeString(root.resolve("sample/case.mantra"), """
            (case one {:schema "test/example" :parameters ["test/variant"] :layout "test/brief"}
              (inputs {:base-value 12.5}))
        """.trimIndent())
        WorkbenchServer(root, 0).use { server ->
            server.start()
            val path = "/api/v1/cases/sample%2Fcase.mantra"
            val before = request(server.localPort, "$path/run")
            assertEquals(200, before.status, before.body)
            assertContains(before.body, "\"source\":\"test/variant\"")
            val paper = request(server.localPort, "$path/paper?layout=test%2Fbrief")
            assertEquals(200, paper.status, paper.body)
            val parameters = request(server.localPort, "$path/parameters")
            assertEquals(200, parameters.status, parameters.body)
            validate("parameters", parameters.body)
            assertContains(parameters.body, "\"set\":\"test/variant\"")
            assertContains(parameters.body, "\"layer\":\"parameters\"")
            val oldRevision = Regex("\"revision\":\"([a-f0-9]{16})\"").find(before.body)!!.groupValues[1]
            Files.writeString(root.resolve("sample/layout.mantra"), "(layout test/brief {:preset :de-staffel-4 :zero \"0\"} (table main))")
            val after = request(server.localPort, "$path/run")
            assertEquals(200, after.status, after.body)
            assertFalse(after.body.contains("\"revision\":\"$oldRevision\""))
        }
    }

    @Test fun `unimplemented source binding reports a diagnostic without calculating`() {
        val root = workspace()
        Files.writeString(root.resolve("sample/case.mantra"), """
            (case one {:schema "test/example"} (sources (csv {:path "imports/data.csv"})) (inputs {:amount 12.5}))
        """.trimIndent())
        WorkbenchServer(root, 0).use { server ->
            server.start()
            val workspace = request(server.localPort, "/api/v1/workspace")
            assertEquals(200, workspace.status, workspace.body)
            assertContains(workspace.body, "MANTRA-CASE-FORM")
            val run = request(server.localPort, "/api/v1/cases/sample%2Fcase.mantra/run")
            assertEquals(422, run.status)
            assertContains(run.body, "MANTRA-WORKBENCH-DOCUMENT")
        }
    }
}
