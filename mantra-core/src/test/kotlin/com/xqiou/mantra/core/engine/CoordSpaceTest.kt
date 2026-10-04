package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunCancellationSource
import com.xqiou.mantra.core.api.RunControl
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunFailureKind
import com.xqiou.mantra.core.api.RunLimits
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordSpaceTest {
    @Test
    fun `direct fixed axes narrow a 200 by 10 scope before product and visit charging`() {
        val domains = mapOf(
            "series" to (1..200).map { "S$it" },
            "period" to (1..10).map { "P$it" },
        )
        val space = CoordSpace({ dimension: String -> domains[dimension].orEmpty() }, { it: String -> it })
        val context = RunContext.begin(
            CalculationOptions(limits = RunLimits(maxCoordinateProduct = 1, maxCoordinateVisits = 1)),
        )
        assertEquals(
            listOf(listOf("S175", "P8")),
            space.coordinates(listOf("series", "period"), mapOf("series" to "S175", "period" to "P8"), context),
        )
        val usage = context.finish()
        assertEquals(1L, usage[RunCounter.COORDINATE_PRODUCT])
        assertEquals(1L, usage[RunCounter.COORDINATE_VISITS])
    }

    @Test
    fun `ancestor filtering preserves declared child order and complete contradictory scope is absent`() {
        val domains = mapOf("child" to listOf("C2", "C1", "C3"), "parent" to listOf("P1", "P2"))
        val space = CoordSpace(
            { dimension: String -> domains[dimension].orEmpty() },
            { it: String -> it },
            { dimension, _ ->
                if (dimension == "child") {
                    CoordSpace.ParentRelation(
                        "parent",
                        mapOf("C2" to "P2", "C1" to "P1", "C3" to "P1"),
                    )
                } else {
                    null
                }
            },
        )
        val context = RunContext.begin(CalculationOptions())
        assertEquals(
            listOf(listOf("C1"), listOf("C3")),
            space.coordinates(listOf("child"), mapOf("parent" to "P1"), context),
        )
        assertNull(space.coordinate(listOf("child"), mapOf("child" to "C2", "parent" to "P1"), context))
        assertEquals(listOf("C1"), space.coordinate(listOf("child"), mapOf("child" to "C1", "parent" to "P1"), context))
        context.finish()
    }

    @Test
    fun `domain replacement invalidates current keys while an independent frozen scope keeps the old domain`() {
        val domains = linkedMapOf("member" to listOf("A", "B"))
        val oldDomains = domains.toMap()
        val live = CoordSpace({ dimension: String -> domains[dimension].orEmpty() }, { it: String -> it })
        val frozen = CoordSpace({ dimension: String -> oldDomains[dimension].orEmpty() }, { it: String -> it })
        val before = RunContext.begin(CalculationOptions())
        assertEquals(listOf(listOf("B")), live.coordinates(listOf("member"), mapOf("member" to "B"), before))
        before.finish()
        domains["member"] = listOf("A", "C")
        val after = RunContext.begin(CalculationOptions())
        assertEquals(emptyList(), live.coordinates(listOf("member"), mapOf("member" to "B"), after))
        assertEquals(listOf(listOf("C")), live.coordinates(listOf("member"), mapOf("member" to "C"), after))
        assertEquals(listOf(listOf("B")), frozen.coordinates(listOf("member"), mapOf("member" to "B"), after))
        assertFailsWith<IllegalArgumentException> { live.validateFixed(setOf("member"), mapOf("member" to "B"), after) }
        after.finish()
    }

    @Test
    fun `empty scope has no visits scalar scope has one and unresolved public members retain argument errors`() {
        val space = CoordSpace({ _: String -> emptyList<String>() }, { it: String -> it })
        val context = RunContext.begin(CalculationOptions())
        assertEquals(emptyList(), space.coordinates(listOf("empty"), emptyMap(), context))
        assertEquals(listOf(emptyList()), space.coordinates(emptyList(), emptyMap(), context))
        val failure = assertFailsWith<IllegalArgumentException> {
            space.validateFixed(setOf("empty"), mapOf("empty" to "missing"), context)
        }
        assertTrue(failure.message.orEmpty().contains("Unknown member"))
        assertEquals(1L, context.finish()[RunCounter.COORDINATE_VISITS])
    }

    @Test
    fun `a cancelled fresh request cannot bypass controls through warmed key indexes`() {
        val domain = listOf("A", "B")
        val space = CoordSpace({ _: String -> domain }, { it: String -> it })
        val first = RunContext.begin(CalculationOptions())
        space.coordinates(listOf("member"), emptyMap(), first)
        first.finish()
        val signal = RunCancellationSource()
        val next = RunContext.begin(CalculationOptions(control = RunControl(signal)))
        signal.cancel()
        val failure = assertFailsWith<RunAbortedException> {
            space.coordinates(listOf("member"), mapOf("member" to "A"), next)
        }
        assertEquals(RunFailureKind.CANCELLED, failure.failure.kind)
        assertEquals(0L, next.finish()[RunCounter.COORDINATE_VISITS])
    }

    @Test
    fun `concurrent immutable domain readers each use their own owner epoch`() {
        val domain = listOf("A", "B", "C")
        val space = CoordSpace({ _: String -> domain }, { it: String -> it })
        val workers = Executors.newFixedThreadPool(3)
        try {
            val futures = domain.map { key ->
                workers.submit(
                    Callable {
                        val context = RunContext.begin(CalculationOptions())
                        val selected = space.coordinates(listOf("member"), mapOf("member" to key), context)
                        selected to context.finish()
                    },
                )
            }
            futures.forEachIndexed { index, future ->
                val (selected, usage) = future.get(10, TimeUnit.SECONDS)
                assertEquals(listOf(listOf(domain[index])), selected)
                assertEquals(1L, usage[RunCounter.COORDINATE_VISITS])
                assertFailsWith<UnsupportedOperationException> { (selected as MutableList<List<String>>).clear() }
            }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
