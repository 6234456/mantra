package com.xqiou.mantra.excel

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.MantraException
import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellation
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import com.xqiou.mantra.render.layout.Presets
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcelReadControlTest {
    private fun calculated() = Mantra.calculate(
        Mantra.loadSchema(
            SourceText(
                "read.mantra",
                """
            (schema test/read
              (dimension member {:members [:A :B]})
              (input source-amount :decimal {:default 4})
              (section main "Main" {:per member} (line value "Value" (* source-amount 2))))
                """.trimIndent(),
            ),
            SourceResolver { _, _ -> null },
        ),
    )

    @Test
    fun `paper and workbook work share a bounded independent reader with detached usage`() {
        val result = calculated()
        val rejected = assertFailsWith<MantraException> {
            ExcelExport.workbook(
                result,
                Presets.IFRS_SCHEDULE,
                ExcelOptions(reading = CalculationOptions(limits = RunLimits(maxHostScans = 0))),
            )
        }
        assertTrue(rejected.diagnostics.any { it.code == "MANTRA-RUN-LIMIT" })
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            val usage = assertNotNull(export.report.readingUsage)
            assertTrue(usage[RunCounter.HOST_SCANS] > 0)
            assertTrue(usage[RunCounter.COORDINATE_VISITS] > 0)
            val scans = usage[RunCounter.HOST_SCANS]
            assertTrue(export.bytes().isNotEmpty())
            assertEquals(scans, usage[RunCounter.HOST_SCANS])
        }
    }

    @Test
    fun `cancelled and expired exports fail before returning a workbook and leave the view reusable`() {
        val result = calculated()
        val options = listOf(
            CalculationOptions(control = RunControl(cancellation = RunCancellation { true })),
            CalculationOptions(control = RunControl(deadline = Instant.EPOCH)),
        )
        options.zip(listOf("MANTRA-RUN-CANCELLED", "MANTRA-RUN-DEADLINE")).forEach { (reading, code) ->
            val rejected = assertFailsWith<MantraException> {
                ExcelExport.workbook(result, Presets.IFRS_SCHEDULE, ExcelOptions(reading = reading))
            }
            assertTrue(rejected.diagnostics.any { it.code == code })
        }
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.fallbacks.isEmpty())
            assertTrue(export.report.evaluationErrors.isEmpty())
        }
    }

    @Test
    fun `cancellation observed during construction is a technical failure and cannot be swallowed by POI`() {
        val polls = AtomicInteger()
        val control = RunControl(cancellation = RunCancellation { polls.incrementAndGet() > 40 })
        val result = calculated()
        val rejected = assertFailsWith<MantraException> {
            ExcelExport.workbook(
                result,
                Presets.IFRS_SCHEDULE,
                ExcelOptions(reading = CalculationOptions(control = control)),
            )
        }
        assertTrue(polls.get() > 40)
        assertTrue(rejected.diagnostics.any { it.code == "MANTRA-RUN-CANCELLED" })
        ExcelExport.workbook(result, Presets.IFRS_SCHEDULE).use { export ->
            assertTrue(export.report.evaluationErrors.isEmpty())
        }
    }
}
