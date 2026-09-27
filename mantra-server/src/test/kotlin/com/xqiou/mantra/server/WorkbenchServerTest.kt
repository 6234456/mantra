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
            val styleNonce = Regex("name=\"mantra-style-nonce\" content=\"([a-f0-9]{36})\"").find(index.body)?.groupValues?.get(1)
            assertTrue(styleNonce != null)
            assertContains(index.headers, "style-src 'self' 'nonce-$styleNonce'")
            val preview = "/api/v1/cases/sample%2Fcase.mantra/preview"
            assertEquals(403, request(port, preview, "POST").status)
            assertEquals(400, request(port, preview, "POST", headers = mapOf("X-Mantra-Token" to token)).status)
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

    @Test fun `edit preview commit undo and redo honor token revision and atomic validation`() {
        val root = workspace()
        val ui = temp.resolve("edit-ui")
        Files.createDirectories(ui)
        Files.writeString(ui.resolve("index.html"), "<html><head></head><body>workbench</body></html>")
        WorkbenchServer(root, 0, ui).use { server ->
            server.start()
            val port = server.localPort
            val path = "/api/v1/cases/sample%2Fcase.mantra"
            val token = Regex("name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"")
                .find(request(port, "/").body)!!.groupValues[1]
            val headers = mapOf("X-Mantra-Token" to token)
            val original = Files.readString(root.resolve("sample/case.mantra"))
            val revision = ObjectMapper().readTree(request(port, "$path/run").body)["revision"].asText()
            fun post(route: String, body: String) = request(port, "$path/$route", "POST", headers = headers, body = body.toByteArray())
            val edit = """{"baseRevision":"$revision","operations":[{"op":"setInput","address":{"node":"amount"},"text":"1.234,56"}]}"""
            val preview = post("preview", edit)
            assertEquals(200, preview.status, preview.body)
            assertEquals(original, Files.readString(root.resolve("sample/case.mantra")))
            assertEquals(400, post("preview", """{"baseRevision":"$revision","baseRevision":"$revision","operations":[]}""").status)
            assertEquals(400, post("preview", """{"baseRevision":"$revision","operations":[{"op":"setInput","address":{"node":"amount"},"value":12.5}]}""").status)
            val proposed = ObjectMapper().readTree(preview.body)["data"]["proposedRevision"].asText()
            val committed = post("edits", edit)
            assertEquals(200, committed.status, committed.body)
            assertEquals(proposed, ObjectMapper().readTree(committed.body)["revision"].asText())
            assertContains(Files.readString(root.resolve("sample/case.mantra")), ":amount 1234.56")
            val stale = post("edits", edit)
            assertEquals(409, stale.status, stale.body)
            assertContains(stale.body, "\"currentRevision\":\"$proposed\"")
            val invalid = post("edits", """{"baseRevision":"$proposed","operations":[{"op":"setInput","address":{"node":"amount"},"value":"wrong"}]}""")
            assertEquals(422, invalid.status, invalid.body)
            assertContains(Files.readString(root.resolve("sample/case.mantra")), ":amount 1234.56")
            val undo = post("undo", """{"baseRevision":"$proposed"}""")
            assertEquals(200, undo.status, undo.body)
            assertEquals(original, Files.readString(root.resolve("sample/case.mantra")))
            val redo = post("redo", """{"baseRevision":"$revision"}""")
            assertEquals(200, redo.status, redo.body)
            assertContains(Files.readString(root.resolve("sample/case.mantra")), ":amount 1234.56")
        }
    }

    @Test fun `formula authoring provides scoped completion hover and semantic checks without writing`() {
        val root = temp.resolve("authoring-workspace")
        val sample = root.resolve("sample")
        Files.createDirectories(sample)
        Files.list(Path.of("examples/ifrs-ias36-corporate-assets")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".mantra") }.forEach { Files.copy(it, sample.resolve(it.fileName)) }
        }
        val ui = temp.resolve("authoring-ui")
        Files.createDirectories(ui)
        Files.writeString(ui.resolve("index.html"), "<html><head></head><body>workbench</body></html>")
        WorkbenchServer(root, 0, ui).use { server ->
            server.start()
            val port = server.localPort
            val path = "/api/v1/cases/sample%2Fcase-ie8.mantra/authoring"
            val token = Regex("name=\"mantra-session-token\" content=\"([a-f0-9]{64})\"")
                .find(request(port, "/").body)!!.groupValues[1]
            val headers = mapOf("X-Mantra-Token" to token)
            val caseFile = sample.resolve("case-ie8.mantra")
            val original = Files.readString(caseFile)
            fun post(action: String, source: String, cursor: Int? = null): Response {
                val target = """{"kind":"formulaSlot","id":"weighting"}"""
                val encoded = ObjectMapper().writeValueAsString(source)
                val body = """{"target":$target,"source":$encoded${cursor?.let { ",\"cursorOffset\":$it" } ?: ""}}"""
                return request(port, "$path/$action", "POST", headers = headers, body = body.toByteArray())
            }
            val completion = post("complete", "remaining-", 10)
            assertEquals(200, completion.status, completion.body)
            val items = ObjectMapper().readTree(completion.body)["data"]["items"]
            assertTrue(items.any { it["label"].asText() == "remaining-life" })
            assertFalse(items.any { it["label"].asText() == "allocable-corporate" })
            val emptyItems = ObjectMapper().readTree(post("complete", "", 0).body)["data"]["items"]
            assertTrue(emptyItems.any { it["label"].asText() == "remaining-life" })
            assertFalse(emptyItems.any { it["label"].asText() == "allocable-corporate" })
            val qualified = ObjectMapper().readTree(post("complete", "mantra/remaining-", 17).body)["data"]
            assertEquals("mantra/remaining-", qualified["query"].asText())
            assertTrue(qualified["items"].any { it["label"].asText() == "mantra/remaining-life" &&
                it["insertText"].asText() == "mantra/remaining-life" })
            val hover = post("hover", "(* carrying-amount 2)", 8)
            assertEquals(200, hover.status, hover.body)
            assertEquals("carrying-amount", ObjectMapper().readTree(hover.body)["data"]["hover"]["symbol"].asText())
            val qualifiedHover = post("hover", "mantra/carrying-amount", 10)
            assertEquals("mantra/carrying-amount", ObjectMapper().readTree(qualifiedHover.body)["data"]["hover"]["symbol"].asText())
            val valid = post("check", "(if weight-by-life (decimal/divide remaining-life (dim/min all.remaining-life) 4) 1)")
            assertEquals(200, valid.status, valid.body)
            assertTrue(ObjectMapper().readTree(valid.body)["data"]["valid"].asBoolean(), valid.body)
            val qualifiedCheck = post("check", "(if weight-by-life (decimal/divide mantra/remaining-life (dim/min all.remaining-life) 4) 1)")
            assertTrue(ObjectMapper().readTree(qualifiedCheck.body)["data"]["valid"].asBoolean(), qualifiedCheck.body)
            val disallowed = post("check", "allocable-corporate")
            assertEquals(200, disallowed.status, disallowed.body)
            assertFalse(ObjectMapper().readTree(disallowed.body)["data"]["valid"].asBoolean(), disallowed.body)
            val malformed = post("check", "(if weight-by-life")
            assertFalse(ObjectMapper().readTree(malformed.body)["data"]["valid"].asBoolean(), malformed.body)
            assertEquals(original, Files.readString(caseFile))
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
