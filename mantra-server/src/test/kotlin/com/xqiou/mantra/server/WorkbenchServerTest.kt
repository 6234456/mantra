package com.xqiou.mantra.server

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.fasterxml.jackson.databind.ObjectMapper
import com.xqiou.mantra.workbench.ExportBudget
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.ByteArrayInputStream
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

    private data class Response(val status: Int, val headers: String, val bytes: ByteArray) {
        val body: String get() = bytes.toString(Charsets.UTF_8)
    }

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
            val bytes = socket.getInputStream().readAllBytes()
            // ISO-8859-1 maps each byte to one character, so the header offset stays exact for binary XLSX bodies.
            val response = bytes.toString(Charsets.ISO_8859_1)
            val split = response.indexOf("\r\n\r\n")
            val header = response.substring(0, split)
            return Response(header.lineSequence().first().split(' ')[1].toInt(), header,
                bytes.copyOfRange(split + 4, bytes.size))
        }
    }

    private class EventStream(port: Int, host: String = "127.0.0.1:$port") : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 7_000 }
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val status: Int

        init {
            socket.getOutputStream().write(
                "GET /api/v1/events HTTP/1.1\r\nHost: $host\r\nConnection: keep-alive\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            status = reader.readLine().split(' ')[1].toInt()
            while (reader.readLine().isNotEmpty()) Unit
        }

        fun event(name: String): Map<*, *> {
            var event: String? = null
            var data: String? = null
            while (true) {
                val line = reader.readLine() ?: error("Event stream closed before $name")
                if (line.startsWith("event: ")) event = line.removePrefix("event: ")
                if (line.startsWith("data: ")) data = line.removePrefix("data: ")
                if (line.isEmpty() && event == name && data != null)
                    return ObjectMapper().readValue(data, Map::class.java)
            }
        }

        override fun close() = socket.close()
    }

    @Test fun `events report content changes with workspace revision and release stream slots`() {
        val root = workspace()
        WorkbenchServer(root, 0).use { server ->
            server.start()
            EventStream(server.localPort).use { first ->
                assertEquals(200, first.status)
                val initial = first.event("revision")["revision"] as String
                assertEquals(ObjectMapper().readTree(request(server.localPort, "/api/v1/workspace").body)["revision"].asText(), initial)
                EventStream(server.localPort).use { second ->
                    assertEquals(200, second.status)
                    assertEquals(initial, second.event("revision")["revision"])
                    assertEquals(503, request(server.localPort, "/api/v1/events").status)
                    assertEquals(200, request(server.localPort, "/api/v1/workspace").status)
                    val case = root.resolve("sample/case.mantra")
                    Files.writeString(case, Files.readString(case).replace("12.5", "14.5"))
                    val changed = first.event("documentChanged")
                    assertEquals(listOf("sample/case.mantra"), changed["paths"])
                    assertFalse(initial == changed["revision"])
                    assertEquals(ObjectMapper().readTree(request(server.localPort, "/api/v1/workspace").body)["revision"].asText(), changed["revision"])
                    val modified = Files.getLastModifiedTime(case)
                    Files.writeString(case, Files.readString(case).replace("14.5", "15.5"))
                    Files.setLastModifiedTime(case, modified)
                    assertEquals(listOf("sample/case.mantra"), first.event("documentChanged")["paths"])
                    val added = root.resolve("sample/new.mantra")
                    Files.writeString(added, "(parameters new {})")
                    assertEquals(listOf("sample/new.mantra"), first.event("documentChanged")["paths"])
                    Files.delete(added)
                    assertEquals(listOf("sample/new.mantra"), first.event("documentChanged")["paths"])
                    Files.writeString(added, "x".repeat(1_048_577))
                    assertEquals("MANTRA-WORKBENCH-TOO-LARGE", first.event("workspaceError")["code"])
                    Files.delete(added)
                    val recovered = first.event("revision")["revision"] as String
                    assertEquals(ObjectMapper().readTree(request(server.localPort, "/api/v1/workspace").body)["revision"].asText(), recovered)
                }
            }
            // Closed streams are removed on the next heartbeat; a fresh reader receives the current revision.
            Thread.sleep(1_200)
            EventStream(server.localPort).use { stream ->
                assertEquals(200, stream.status)
                assertTrue((stream.event("revision")["revision"] as String).isNotEmpty())
            }
            assertEquals(403, request(server.localPort, "/api/v1/events", host = "evil.example:${server.localPort}").status)
            assertEquals(400, request(server.localPort, "/api/v1/events?unexpected=1").status)
        }
    }

    @Test fun `events continue to update for a valid workspace above 64 MiB`() {
        val root = temp.resolve("large-workspace")
        Files.createDirectories(root)
        val padding = " ".repeat(62 * 1024)
        repeat(1_100) { index -> Files.writeString(root.resolve("p$index.mantra"), "(parameters p$index {})\n$padding") }
        val size = Files.list(root).use { paths -> paths.mapToLong(Files::size).sum() }
        assertTrue(size > 64L * 1024 * 1024)
        WorkbenchServer(root, 0).use { server ->
            server.start()
            EventStream(server.localPort).use { stream ->
                assertEquals(200, stream.status)
                val initial = stream.event("revision")["revision"] as String
                Files.writeString(root.resolve("p999.mantra"), "(parameters p999 {})\n${padding}x")
                val changed = stream.event("documentChanged")
                assertEquals(listOf("p999.mantra"), changed["paths"])
                assertFalse(initial == changed["revision"])
            }
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
            assertEquals(400, request(port, "/api/v1/cases/sample%2Fcase.mantra/compare", "POST",
                headers = mapOf("X-Mantra-Token" to token)).status)
            assertEquals(400, request(port, "/api/v1/cases/sample%2Fcase.mantra/explain").status)
            assertEquals(404, request(port, "/api/v1/cases/..%2F..%2Fsecret.mantra/structure").status)
            assertEquals(404, request(port, "/%2e%2e/secret.txt").status)
            assertEquals(413, request(port, preview, "POST", headers = mapOf(
                "X-Mantra-Token" to token, "Content-Length" to "${1024 * 1024 + 1}"),
            ).status)
        }
    }

    @Test fun `export preview and downloads come from the selected layout and generated workbook`() {
        val root = workspace()
        Files.writeString(root.resolve("sample/layout.mantra"),
            "(layout test/export {:preset :de-staffel-4 :title \"Custom export\"} (table main))")
        WorkbenchServer(root, 0).use { server ->
            server.start()
            val path = "/api/v1/cases/sample%2Fcase.mantra"
            val preview = request(server.localPort, "$path/export-preview")
            assertEquals(200, preview.status, preview.body)
            validate("export-preview", preview.body)
            assertContains(preview.body, "\"selectedSheet\"")
            assertContains(preview.body, "\"formulaCells\"")
            assertContains(preview.body, "\"fallbacks\"")
            val sheet = Regex("\"selectedSheet\":\"([^\"]+)\"").find(preview.body)!!.groupValues[1]
            val selected = request(server.localPort, "$path/export-preview?sheet=${java.net.URLEncoder.encode(sheet, Charsets.UTF_8)}")
            assertEquals(200, selected.status, selected.body)
            assertEquals(404, request(server.localPort, "$path/export-preview?sheet=missing").status)
            assertEquals(400, request(server.localPort, "$path/export-preview?other=x").status)
            val changedLayout = request(server.localPort, "$path/export-preview?layout=test%2Fexport")
            assertEquals(200, changedLayout.status, changedLayout.body)
            assertFalse(changedLayout.body.contains(Regex("\"revision\":\"([a-f0-9]{16})\"")
                .find(preview.body)!!.value))
            assertEquals(422, request(server.localPort, "$path/export-preview?layout=missing").status)
            val xlsx = request(server.localPort, "$path/export.xlsx")
            assertEquals(200, xlsx.status)
            assertContains(xlsx.headers, "spreadsheetml.sheet")
            assertContains(xlsx.headers, "content-disposition: attachment", ignoreCase = true)
            val previewData = ObjectMapper().readTree(preview.body)["data"]
            XSSFWorkbook(ByteArrayInputStream(xlsx.bytes)).use { workbook ->
                val sheetNames = (0 until workbook.numberOfSheets).map(workbook::getSheetName)
                assertEquals(previewData["sheets"].map { it["name"].asText() }, sheetNames)
                assertEquals(previewData["report"]["names"].asInt(), workbook.allNames.size)
                val formulaCells = workbook.sumOf { sheet -> sheet.sumOf { row -> row.count { it.cellType == CellType.FORMULA } } }
                assertEquals(previewData["report"]["formulaCells"].asInt(), formulaCells)
                val formula = workbook.asSequence().flatMap { sheet -> sheet.asSequence() }
                    .flatMap { row -> row.asSequence() }.first { it.cellType == CellType.FORMULA }
                val formulaSheet = formula.sheet.sheetName
                val formulaAddress = CellReference(formula.rowIndex, formula.columnIndex).formatAsString()
                val formulaPreview = request(server.localPort,
                    "$path/export-preview?sheet=${java.net.URLEncoder.encode(formulaSheet, Charsets.UTF_8)}")
                val previewCell = ObjectMapper().readTree(formulaPreview.body)["data"]["preview"]["cells"]
                    .first { it["address"].asText() == formulaAddress }
                assertEquals(formula.cellFormula, previewCell["formula"].asText())
            }
            val changedXlsx = request(server.localPort, "$path/export.xlsx?layout=test%2Fexport")
            assertEquals(200, changedXlsx.status)
            XSSFWorkbook(ByteArrayInputStream(changedXlsx.bytes)).use { workbook ->
                val cell = workbook.getSheetAt(0).getRow(0).getCell(0).stringCellValue
                val firstPreviewCell = ObjectMapper().readTree(changedLayout.body)["data"]["preview"]["cells"][0]
                assertEquals(firstPreviewCell["value"].asText(), cell)
                assertEquals("Custom export", cell)
            }
            val html = request(server.localPort, "$path/export.html")
            assertEquals(200, html.status)
            assertContains(html.body, "<html")
            val text = request(server.localPort, "$path/export.txt")
            assertEquals(200, text.status)
            assertContains(text.body, "Amount")
        }
    }

    @Test fun `export workbook and response budgets return 413 without partial files`() {
        val root = workspace()
        val path = "/api/v1/cases/sample%2Fcase.mantra"
        WorkbenchServer(root, 0, exportBudget = ExportBudget(maxCells = 1)).use { server ->
            server.start()
            for (route in listOf("export-preview", "export.xlsx")) {
                val response = request(server.localPort, "$path/$route")
                assertEquals(413, response.status, response.body)
                assertContains(response.body, "MANTRA-WORKBENCH-TOO-LARGE")
            }
        }
        WorkbenchServer(root, 0, exportBudget = ExportBudget(maxBytes = 64)).use { server ->
            server.start()
            assertEquals(200, request(server.localPort, "$path/export-preview").status)
            val response = request(server.localPort, "$path/export.xlsx")
            assertEquals(413, response.status, response.body)
            assertContains(response.body, "MANTRA-WORKBENCH-TOO-LARGE")
            assertFalse(response.bytes.take(2).toByteArray().contentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
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

    @Test fun `explain returns a bounded source trace through the address route`() {
        WorkbenchServer(Path.of("examples"), 0).use { server ->
            server.start()
            val path = "/api/v1/cases/de-est-2025%2Fcase-mustermann.mantra/explain"
            val response = request(server.localPort, "$path?address=ermaessigung-35a")
            assertEquals(200, response.status, response.body)
            validate("explain", response.body)
            assertContains(response.body, "\"node\":\"ermaessigung-35a\"")
            assertContains(response.body, "\"n\":\"740.0\"")
            assertContains(response.body, "\"n\":\"240.0\"")
            assertContains(response.body, "\"n\":\"500.0\"")
            val nested = request(server.localPort, "$path?address=ermaessigung-35a&depth=2")
            assertEquals(200, nested.status, nested.body)
            validate("explain", nested.body)
            assertContains(nested.body, "\"explanation\":")
            assertEquals(400, request(server.localPort, "$path?address=ermaessigung-35a&depth=6").status)
            assertEquals(404, request(server.localPort, "$path?address=missing").status)
            assertEquals(400, request(server.localPort, "$path?address=ermaessigung-35a&extra=x").status)
        }
    }

    @Test fun `explain resolves a dimensioned choice and its option difference`() {
        WorkbenchServer(Path.of("examples"), 0).use { server ->
            server.start()
            val path = "/api/v1/cases/ifrs-ias36-corporate-assets%2Fcase-ie8.mantra/explain?address=recoverable-amount%40B"
            val response = request(server.localPort, path)
            assertEquals(200, response.status, response.body)
            validate("explain", response.body)
            assertContains(response.body, "\"coord\":[\"B\"]")
            assertContains(response.body, "\"options\":[")
            assertContains(response.body, "\"difference\":")
            val nested = request(server.localPort, "$path&depth=2")
            assertEquals(200, nested.status, nested.body)
            validate("explain", nested.body)
            val mapPath = "/api/v1/cases/ifrs-ias36-corporate-assets%2Fcase-ie8.mantra/explain?address=allocation-key%40B&depth=2"
            val mapReference = request(server.localPort, mapPath)
            assertEquals(200, mapReference.status, mapReference.body)
            validate("explain", mapReference.body)
            assertContains(mapReference.body, "\"node\":\"all.weighted-amount\"")
            val directMap = request(server.localPort,
                "/api/v1/cases/ifrs-ias36-corporate-assets%2Fcase-ie8.mantra/explain?address=all.weighted-amount")
            assertEquals(200, directMap.status, directMap.body)
            validate("explain", directMap.body)
            val mapper = ObjectMapper()
            val allReference = mapper.readTree(mapReference.body).path("data").path("references")
                .first { it.path("address").path("node").asText() == "all.weighted-amount" }
            assertEquals(allReference.path("value"), mapper.readTree(directMap.body).path("data").path("result").path("value"))
        }
    }

    @Test fun `ordinary member-map references retain fixed dimension members`() {
        val root = workspace()
        Files.writeString(root.resolve("sample/schema.mantra"), """
            (schema test/member-map {:mainline [summary]}
              (dimension area {:members [:A :B]})
              (dimension year {:members [:Y1 :Y2]})
              (section detail "Detail" {:per [area year]}
                (line detail-value "Amount" (if (= year.key :Y1) 10 20)))
              (section summary "Summary" {:per area :panel true}
                (line subtotal "Subtotal" (dim/sum detail-value))))
        """.trimIndent())
        Files.writeString(root.resolve("sample/case.mantra"), "(case one {:schema \"test/member-map\"})")
        WorkbenchServer(root, 0).use { server ->
            server.start()
            val path = "/api/v1/cases/sample%2Fcase.mantra/explain?address=subtotal%40A&depth=2"
            val response = request(server.localPort, path)
            assertEquals(200, response.status, response.body)
            validate("explain", response.body)
            val references = ObjectMapper().readTree(response.body).path("data").path("references")
            val memberMap = references.first { it.path("kind").asText() == "member-map" }
            assertEquals("all.detail-value", memberMap.path("address").path("node").asText())
            assertEquals("area=A", memberMap.path("address").path("coord")[0].asText())
            assertEquals(memberMap.path("value"), memberMap.path("explanation").path("result").path("value"))
            val direct = request(server.localPort,
                "/api/v1/cases/sample%2Fcase.mantra/explain?address=all.detail-value%40area%3DA")
            assertEquals(200, direct.status, direct.body)
            validate("explain", direct.body)
            assertEquals(memberMap.path("value"), ObjectMapper().readTree(direct.body).path("data").path("result").path("value"))
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

    @Test fun `compare calculates a variant without writing and validates its request`() {
        val root = workspace()
        Files.writeString(root.resolve("sample/schema.mantra"), """
            (schema test/example {:title "Test" :mainline [main]}
              (param adjustment 1)
              (section main "Main" {:panel true}
                (line adjusted "Adjusted" (+ base-value adjustment))
                (total sum "Sum"))
              (input base-value :decimal))
        """.trimIndent())
        Files.writeString(root.resolve("sample/case.mantra"), """
            (case one {:schema "test/example"} (inputs {:base-value 12.5}))
        """.trimIndent())
        Files.writeString(root.resolve("sample/other.mantra"), """
            (case one {:schema "test/example" :layout "missing/layout"} (inputs {:base-value 20}))
        """.trimIndent())
        Files.writeString(root.resolve("sample/bad-binding.mantra"), """
            (case one {:schema "test/example" :parameters "invalid"} (inputs {:base-value 20}))
        """.trimIndent())
        Files.writeString(root.resolve("sample/params.mantra"), """
            (parameters test/variant {:for "test/example"} (values {:adjustment 3}))
        """.trimIndent())
        val before = Files.readString(root.resolve("sample/case.mantra"))
        val ui = temp.resolve("dist")
        Files.createDirectories(ui)
        Files.writeString(ui.resolve("index.html"), "<html><head></head><body>workbench</body></html>")
        WorkbenchServer(root, 0, ui).use { server ->
            server.start()
            val port = server.localPort
            val token = Regex("content=\"([a-f0-9]{64})\"")
                .find(request(port, "/").body)!!.groupValues[1]
            val path = "/api/v1/cases/sample%2Fcase.mantra/compare"
            fun post(json: String) = request(port, path, "POST", headers = mapOf("X-Mantra-Token" to token),
                body = json.toByteArray())
            val variant = post("""{"variant":{"parameters":["test/variant"]}}""")
            assertEquals(200, variant.status, variant.body)
            validate("compare", variant.body)
            assertContains(variant.body, "\"delta\":{\"n\":\"2\"}")
            assertContains(variant.body, "\"parameters\":[\"test/variant\"]")
            val other = post("""{"variant":{"case":"sample/other.mantra"}}""")
            assertEquals(200, other.status, other.body)
            validate("compare", other.body)
            assertContains(other.body, "\"case\":\"sample/other.mantra\"")
            assertEquals(200, post("""{"variant":{"case":"sample/bad-binding.mantra","parameters":[]}}""").status)
            assertFalse(other.body.contains(Regex("\"revision\":\"([a-f0-9]{16})\"").find(variant.body)!!.value))
            for (invalid in listOf("{}", "{", """{"variant":{"parameters":[3]}}""",
                    """{"variant":{"parameters":["missing"]}}""",
                    """{"variant":{"case":"/sample/other.mantra"}}""",
                    """{"variant":{"case":"sample/../sample/other.mantra"}}""",
                    """{"variant":{"case":"sample/other.mantra","extra":1}}""",
                    """{"variant":{"case":"sample/other.mantra","case":"sample/case.mantra"}}""")) {
                assertTrue(post(invalid).status in setOf(400, 422), invalid)
            }
            assertEquals(403, request(port, path, "POST", body = "{}".toByteArray()).status)
        }
        assertEquals(before, Files.readString(root.resolve("sample/case.mantra")))
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
