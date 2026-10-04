package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.SourceLocation
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.ParticipatingSource
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.CaseLink
import com.xqiou.mantra.core.model.CaseLinkMapping
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.SchemaReference
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelLinkProvenanceTest {
    private fun schema(source: String) = Mantra.loadSchema(
        SourceText("schema.mantra", source.trimIndent()),
        SourceResolver { _, _ -> null },
    )

    @Test
    fun `materialized linked input persists exact source provenance without an external workbook link`() {
        val source =
            schema(
                """
            (schema test/source {:version "2026-v1"}
              (dimension period {:members [:P1 :P2]})
              (input source-amount :decimal {:default 200})
              (section main "Main" {:per period} (line exported "Exported" (* source-amount 2))))
        """,
            )
        val target =
            schema(
                """
            (schema test/consumer {:version "1"}
              (input linked :decimal)
              (section main "Main" (line doubled "Doubled" (* linked 2))))
        """,
            )
        val mapping = CaseLinkMapping(
            InputAddress("exported", listOf("P2")),
            InputAddress("linked"),
            SourceLocation("consumer.mantra", 2, 1),
        )
        val declaration = CaseLink("source", SchemaReference(source.id, "2026-v1"), listOf(mapping), mapping.location)
        val cases = listOf(
            Triple("source", source, CaseData.empty("source-case")),
            Triple("consumer", target, CaseData.empty("consumer-case").copy(links = listOf(declaration))),
        ).associate { (key, model, data) ->
            val identity = CanonicalCaseKey(key)
            identity to PreparedCasePackage(
                identity,
                data.id,
                model.identity,
                model,
                data,
                emptyList(),
                listOf(ParticipatingSource("$key/schema", SourceRole.SCHEMA, "0".repeat(64), 0)),
                "$key-revision",
            )
        }
        val resolver = object : CasePackageResolver {
            override fun identify(reference: CaseReference, control: CaseLoadControl) = CanonicalCaseKey(reference.path)
            override fun load(key: CanonicalCaseKey, control: CaseLoadControl) = cases.getValue(key)
        }
        CaseGraphRunner(resolver).use { runner ->
            val graph = runner.run(CaseRunRequest(CaseReference("consumer")))
            assertTrue(graph.succeeded, graph.diagnostics.toString())
            val result = assertNotNull(graph.result)
            val revision = graph.cases.getValue(CanonicalCaseKey("source")).revision
            ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
                val address = CellReference(assertNotNull(export.address("linked")))
                XSSFWorkbook(ByteArrayInputStream(export.bytes())).use { reopened ->
                    val linked = reopened.getSheet(address.sheetName).getRow(address.row).getCell(address.col.toInt())
                    assertEquals(400.0, linked.numericCellValue)
                    val comment = assertNotNull(linked.cellComment).string.string
                    listOf(
                        "caseKey: source",
                        "caseId: source-case",
                        "schema.id: test/source",
                        "schema.version: 2026-v1",
                        "revision: $revision",
                        "from.node: exported",
                        "from.coord: P2",
                    )
                        .forEach { assertTrue(it in comment, comment) }
                    assertTrue(reopened.externalLinksTable.isEmpty())
                }
                assertTrue(export.report.fallbacks.isEmpty())
                assertTrue(export.report.evaluationErrors.isEmpty())
            }
        }
    }
}
