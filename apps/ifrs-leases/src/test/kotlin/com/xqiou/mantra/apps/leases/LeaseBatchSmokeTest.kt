package com.xqiou.mantra.apps.leases

import com.xqiou.mantra.core.api.RuntimeVersions
import com.xqiou.mantra.core.data.Json
import com.xqiou.mantra.core.model.Value
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeaseBatchSmokeTest {
    @TempDir lateinit var output: Path

    @Test
    fun `six contracts share one template with exact stocks flows and partials`() {
        val root = Path.of(System.getProperty("mantra.appDir"))
        LeaseBatchDemo.main(
            arrayOf(
                "--package-root",
                root.toString(),
                "--reference",
                root.resolve("batch/reference-small.jsonl").toString(),
                "--reference-manifest",
                root.resolve("batch/reference-small.manifest.json").toString(),
                "--out",
                output.toString(),
                "--engine-version",
                RuntimeVersions.mantra,
                "--directory-policy",
                "trusted-local",
                "--max-seconds",
                "120",
            ),
        )
        val summary = Json.parse(Files.readString(output.resolve("summary.json"))) as Value.MapV
        fun number(map: Value.MapV, key: String) = (map.entries[Value.Kw(key)] as Value.Num).value.longValueExact()
        assertEquals(6L, number(summary, "checkedCases"))
        assertEquals(672L, number(summary, "exactNumericComparisons"))
        assertEquals(0L, number(summary, "technicalFailedCases"))
        assertEquals(0L, number(summary, "validationFailedCases"))
        val compilation = summary.entries[Value.Kw("compilation")] as Value.MapV
        assertEquals(22L, number(compilation, "executionPlanCompilations"))
        assertEquals(22L, number(compilation, "semanticCompilerCalls"))
        val statistics = summary.entries[Value.Kw("statistics")] as Value.MapV
        assertTrue(number(statistics, "sessionOpens") > 0)
        assertEquals(number(statistics, "sessionOpens"), number(statistics, "successfulSessionCloses"))
        assertEquals(0L, number(statistics, "executionPlanCompilations"))
        assertEquals(0L, number(statistics, "valueOnlyEvidenceMaterializations"))
        val lines = Files.readAllLines(output.resolve("actual.jsonl"))
        assertEquals(6, lines.size)
        assertEquals(
            6,
            lines.map {
                ((Json.parse(it) as Value.MapV).entries[Value.Kw("caseId")] as Value.Text).value
            }.toSet().size,
        )
    }
}
