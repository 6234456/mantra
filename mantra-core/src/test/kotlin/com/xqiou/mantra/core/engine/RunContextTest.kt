package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.RunAddress
import com.xqiou.mantra.core.api.RunCancellationSource
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunFailureKind
import com.xqiou.mantra.core.api.RunLimits
import com.xqiou.mantra.core.api.RunStage
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunContextTest {
    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }

    @Test
    fun `source and target work share a mapping limit and failure identifies the target stage`() {
        val context = RunContext.begin(CalculationOptions(limits = RunLimits(maxLinkMappings = 2)), MutableClock())
        context.at(RunStage.LOADING, RunAddress(caseKey = "source")) {
            context.charge(RunCounter.CASES)
        }
        context.at(RunStage.BINDING, RunAddress("consumer", "first")) {
            context.charge(RunCounter.LINK_MAPPINGS)
        }
        context.at(RunStage.BINDING, RunAddress("consumer", "second")) {
            context.charge(RunCounter.LINK_MAPPINGS)
        }
        val failure = assertFailsWith<RunAbortedException> {
            context.at(RunStage.BINDING, RunAddress("consumer", "third", listOf("A"))) {
                context.charge(RunCounter.LINK_MAPPINGS)
            }
        }
        assertEquals("MANTRA-RUN-LIMIT", failure.failure.code)
        assertEquals(RunStage.BINDING, failure.failure.stage)
        assertEquals(RunAddress("consumer", "third", listOf("A")), failure.failure.address)
        assertEquals(2L, failure.failure.limit)
        assertEquals(3L, failure.failure.attempted)
        assertEquals(2L, failure.usage[RunCounter.LINK_MAPPINGS])
        assertEquals(1L, context.finish()[RunCounter.CASES])
    }

    @Test
    fun `overflow is rejected before updating a cumulative counter even with a maximum Long limit`() {
        val context = RunContext.begin(
            CalculationOptions(limits = RunLimits(maxHostScans = Long.MAX_VALUE)),
            MutableClock(),
        )
        context.charge(RunCounter.HOST_SCANS, Long.MAX_VALUE - 1)
        val failure = assertFailsWith<RunAbortedException> { context.charge(RunCounter.HOST_SCANS, 2) }
        assertTrue(failure.failure.overflow)
        assertEquals(Long.MAX_VALUE, failure.failure.attempted)
        assertEquals(Long.MAX_VALUE - 1, failure.usage[RunCounter.HOST_SCANS])
        assertEquals(Long.MAX_VALUE - 1, context.finish()[RunCounter.HOST_SCANS])
    }

    @Test
    fun `a later empty axis cancels a potentially overflowing product and scalar scope has one coordinate`() {
        val context = RunContext.begin(CalculationOptions(), MutableClock())
        assertEquals(0L, context.coordinateProduct(listOf(Long.MAX_VALUE, Long.MAX_VALUE, 0)))
        assertEquals(1L, context.coordinateProduct(emptyList()))
        assertEquals(1L, context.finish()[RunCounter.COORDINATE_PRODUCT])
        val overflowing = RunContext.begin(
            CalculationOptions(limits = RunLimits(maxCoordinateProduct = Long.MAX_VALUE)),
            MutableClock(),
        )
        val failure = assertFailsWith<RunAbortedException> {
            overflowing.coordinateProduct(listOf(Long.MAX_VALUE, 2), capacity = Long.MAX_VALUE)
        }
        assertTrue(failure.failure.overflow)
        assertEquals(RunCounter.COORDINATE_PRODUCT, failure.failure.counter)
        assertEquals(0L, overflowing.finish()[RunCounter.COORDINATE_PRODUCT])
    }

    @Test
    fun `preflight does not claim execution and snapshot counters cannot change after publication`() {
        val context = RunContext.begin(CalculationOptions(limits = RunLimits(maxTasks = 2)), MutableClock())
        context.preflight(RunCounter.TASKS, 2)
        assertEquals(0L, context.snapshot()[RunCounter.TASKS])
        context.charge(RunCounter.TASKS)
        val first = context.snapshot()
        context.charge(RunCounter.TASKS)
        assertEquals(1L, first[RunCounter.TASKS])
        val last = context.finish()
        assertEquals(2L, last[RunCounter.TASKS])
        assertSame(last, context.finish())
        assertFailsWith<UnsupportedOperationException> {
            (last.counters as MutableMap<RunCounter, Long>)[RunCounter.TASKS] = 100
        }
        assertFailsWith<IllegalStateException> { context.charge(RunCounter.TASKS) }
    }

    @Test
    fun `already cancelled or expired epochs still expose frozen zero usage`() {
        val signal = RunCancellationSource().also { it.cancel() }
        val cancelled = assertFailsWith<RunAbortedException> {
            RunContext.begin(CalculationOptions(control = RunControl(signal)), MutableClock())
        }
        assertEquals(RunFailureKind.CANCELLED, cancelled.failure.kind)
        assertTrue(cancelled.usage.counters.values.all { it == 0L })
        val clock = MutableClock()
        val expired = assertFailsWith<RunAbortedException> {
            RunContext.begin(CalculationOptions(control = RunControl(deadline = clock.now)), clock)
        }
        assertEquals(RunFailureKind.DEADLINE, expired.failure.kind)
        assertEquals(clock.now, expired.failure.deadline)
    }

    @Test
    fun `earlier caller deadline applies at the formula address and detaches its coordinate`() {
        val clock = MutableClock()
        val limit = clock.now.plusSeconds(2)
        val context = RunContext.begin(CalculationOptions(control = RunControl(deadline = limit)), clock)
        val coord = mutableListOf("P1", "A")
        val failure = assertFailsWith<RunAbortedException> {
            context.at(RunStage.FORMULA, RunAddress("source", "closing", coord)) {
                coord[0] = "P2"
                clock.now = limit
                context.checkpoint()
            }
        }
        assertEquals(RunStage.FORMULA, failure.failure.stage)
        assertEquals(listOf("P1", "A"), failure.failure.address!!.coord)
        assertEquals(Duration.ofSeconds(2), failure.usage.elapsed)
        assertEquals(limit, context.finish().deadline)
    }

    @Test
    fun `load capabilities expire on success and callback failure without corrupting the epoch`() {
        val context = RunContext.begin(CalculationOptions(), MutableClock())
        lateinit var retained: CaseLoadControl
        context.withLoadControl { control ->
            retained = control
            control.chargeParticipatingBytes(11)
            control.chargeInputRows()
        }
        assertFailsWith<IllegalStateException> { retained.chargeParticipatingBytes(1) }
        lateinit var failed: CaseLoadControl
        assertFailsWith<IllegalArgumentException> {
            context.withLoadControl { control ->
                failed = control
                throw IllegalArgumentException("loader failure")
            }
        }
        assertFailsWith<IllegalStateException> { failed.checkpoint() }
        context.checkpoint()
        val usage = context.finish()
        assertEquals(11L, usage[RunCounter.PARTICIPATING_BYTES])
        assertEquals(1L, usage[RunCounter.INPUT_ROWS])
    }

    @Test
    fun `wrong thread control use leaves the creating thread able to finish`() {
        val context = RunContext.begin(CalculationOptions(), MutableClock())
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit(
                Callable {
                    assertFailsWith<IllegalStateException> { context.charge(RunCounter.TASKS) }
                    assertFailsWith<IllegalStateException> { context.finish() }
                },
            ).get(10, TimeUnit.SECONDS)
            context.charge(RunCounter.TASKS)
            assertEquals(1L, context.finish()[RunCounter.TASKS])
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
