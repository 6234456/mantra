package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.CalculationOptions
import com.xqiou.mantra.core.api.RunAddress
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunFailure
import com.xqiou.mantra.core.api.RunFailureKind
import com.xqiou.mantra.core.api.RunStage
import com.xqiou.mantra.core.api.RunUsage
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.EnumMap

internal class RunAbortedException(val failure: RunFailure, val usage: RunUsage) : RuntimeException(failure.code)

/** One owner-thread epoch, shared by every source and target in a case graph. */
internal class RunContext private constructor(val options: CalculationOptions, private val clock: Clock) {
    private val owner = Thread.currentThread()
    val startedAt: Instant = clock.instant()
    val deadline: Instant = minOf(
        options.control.deadline ?: Instant.MAX,
        try {
            startedAt.plus(options.limits.maxDuration)
        } catch (_: DateTimeException) {
            Instant.MAX
        } catch (_: ArithmeticException) {
            Instant.MAX
        },
    )
    private val counters = EnumMap<RunCounter, Long>(RunCounter::class.java)
    private var stage = RunStage.LOADING
    private var address: RunAddress? = null
    private var failure: RunFailure? = null
    private var finished: RunUsage? = null

    fun nodeAddress(nodeId: String, coord: List<String> = emptyList()): RunAddress =
        RunAddress(address?.caseKey, nodeId, coord)

    fun checkpoint() {
        checkOwner()
        check(finished == null) { "Run context has already finished" }
        failure?.let { throw RunAbortedException(it, snapshot()) }
        if (options.control.cancellation.isCancelled()) abort(RunFailureKind.CANCELLED)
        if (!clock.instant().isBefore(deadline)) abort(RunFailureKind.DEADLINE)
    }

    fun charge(counter: RunCounter, amount: Long = 1) {
        require(counter.cumulative) { "Use highWater for depth and product" }
        require(amount >= 0) { "A charge cannot be negative" }
        checkpoint()
        val attempted = attempted(counter, amount)
        counters[counter] = attempted
    }

    /** Preflight future allocation without claiming that its work has already happened. */
    fun preflight(counter: RunCounter, amount: Long) {
        require(counter.cumulative && amount >= 0)
        checkpoint()
        attempted(counter, amount)
    }

    fun highWater(counter: RunCounter, value: Long, capacity: Long = Long.MAX_VALUE) {
        require(!counter.cumulative && value >= 0 && capacity >= 0)
        checkpoint()
        val limit = minOf(options.limits.maximum(counter), capacity)
        if (value > limit) abort(RunFailureKind.LIMIT, counter, limit, value)
        counters[counter] = maxOf(counters[counter] ?: 0, value)
    }

    /** Empty axes yield zero, including when other axis products would overflow. */
    fun coordinateProduct(cardinalities: List<Long>, capacity: Long = Int.MAX_VALUE.toLong()): Long {
        require(cardinalities.all { it >= 0 })
        checkpoint()
        var product = if (cardinalities.any { it == 0L }) 0L else 1L
        if (product != 0L) {
            cardinalities.forEach { size ->
                if (size > Long.MAX_VALUE / product) {
                    abort(
                        RunFailureKind.LIMIT,
                        RunCounter.COORDINATE_PRODUCT,
                        minOf(options.limits.maxCoordinateProduct, capacity),
                        Long.MAX_VALUE,
                        overflow = true,
                    )
                }
                product *= size
            }
        }
        highWater(RunCounter.COORDINATE_PRODUCT, product, capacity)
        return product
    }

    fun <T> at(nextStage: RunStage, nextAddress: RunAddress? = address, action: () -> T): T {
        checkOwner()
        val previousStage = stage
        val previousAddress = address
        stage = nextStage
        address = nextAddress?.copy(coord = Collections.unmodifiableList(ArrayList(nextAddress.coord)))
        return try {
            // A failure at entry belongs to the stage/address that was about to execute.
            checkpoint()
            action()
        } finally {
            stage = previousStage
            address = previousAddress
        }
    }

    fun snapshot(): RunUsage {
        checkOwner()
        return finished ?: RunUsage(
            RunCounter.entries.associateWith { counters[it] ?: 0 },
            startedAt,
            deadline,
            Duration.between(startedAt, clock.instant()).let { if (it.isNegative) Duration.ZERO else it },
        )
    }

    /** Freezes exactly once, including after failure; no view receives this mutable object. */
    fun finish(): RunUsage {
        checkOwner()
        return finished ?: snapshot().also { finished = it }
    }

    private fun attempted(counter: RunCounter, amount: Long): Long {
        val current = counters[counter] ?: 0
        val limit = options.limits.maximum(counter)
        if (amount > Long.MAX_VALUE - current) {
            abort(RunFailureKind.LIMIT, counter, limit, Long.MAX_VALUE, overflow = true)
        }
        val attempted = current + amount
        if (attempted > limit) abort(RunFailureKind.LIMIT, counter, limit, attempted)
        return attempted
    }

    private fun abort(
        kind: RunFailureKind,
        counter: RunCounter? = null,
        limit: Long? = null,
        attempted: Long? = null,
        overflow: Boolean = false,
    ): Nothing {
        val problem = RunFailure(kind, stage, address, counter, limit, attempted, overflow, deadline)
        failure = problem
        // Also covers a cancellation/deadline observed by begin(), before its caller receives ctx.
        throw RunAbortedException(problem, snapshot())
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Run contexts must stay on their creating thread" }
    }

    companion object {
        fun begin(options: CalculationOptions, clock: Clock = Clock.systemUTC()): RunContext =
            RunContext(options, clock).also { it.checkpoint() }
    }
}
