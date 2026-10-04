package com.xqiou.mantra.core.api

import com.xqiou.mantra.core.engine.RunAbortedException
import com.xqiou.mantra.core.engine.RunBoundary
import com.xqiou.mantra.core.engine.RunContext
import com.xqiou.mantra.core.view.AggregationResult
import com.xqiou.mantra.core.view.CalculationView
import com.xqiou.mantra.core.view.Coord
import com.xqiou.mantra.core.view.GuardAlignment
import com.xqiou.mantra.core.view.ViewNode

/**
 * One bounded read request over an immutable view. Share this session across a paper/export's cells.
 * Open, use and close on one thread; independent readers may use the same view on other threads.
 * Usage belongs to this read request and never changes the published calculation's usage.
 */
class CalculationReader private constructor(private val view: CalculationView, private val context: RunContext) :
    AutoCloseable {
    private var closed = false
    val usage: RunUsage get() = context.snapshot()

    private fun <T> read(action: () -> T): T {
        context.snapshot() // Validate owner before testing lifecycle.
        check(!closed) { "View read session is closed" }
        return try {
            context.checkpoint()
            action().also { context.checkpoint() }
        } catch (
            failure: RunAbortedException,
        ) {
            throw RunBoundary.exception(failure)
        }
    }

    fun checkpoint(): Unit = read { context.checkpoint() }
    fun chargeScans(amount: Long = 1): Unit = read { context.charge(RunCounter.HOST_SCANS, amount) }
    fun chargeCoordinateVisits(amount: Long = 1): Unit = read { context.charge(RunCounter.COORDINATE_VISITS, amount) }

    fun reduce(nodeId: String, fixed: Map<String, String> = emptyMap()): AggregationResult = read {
        view.reduceBound(nodeId, fixed, context)
    }
    fun reduce(otherView: CalculationView, nodeId: String, fixed: Map<String, String> = emptyMap()): AggregationResult =
        read { otherView.reduceBound(nodeId, fixed, context) }
    fun coordinate(nodeId: String, fixed: Map<String, String>): Coord? =
        read { view.coordinateBound(nodeId, fixed, context) }
    fun coordinate(otherView: CalculationView, nodeId: String, fixed: Map<String, String>): Coord? =
        read { otherView.coordinateBound(nodeId, fixed, context) }
    fun coordinates(nodeId: String, fixed: Map<String, String> = emptyMap()): List<Coord> =
        read { view.coordinatesBound(nodeId, fixed, context) }
    fun coordinates(otherView: CalculationView, nodeId: String, fixed: Map<String, String> = emptyMap()): List<Coord> =
        read { otherView.coordinatesBound(nodeId, fixed, context) }
    fun alignGuards(node: ViewNode, coord: Coord, memberKeys: (String) -> List<String>): GuardAlignment =
        read { view.alignGuardsBound(node, coord, memberKeys, context) }

    override fun close() {
        context.finish() // owner check precedes closed-state mutation; finish is idempotent.
        closed = true
    }

    companion object {
        internal fun open(view: CalculationView, options: CalculationOptions): CalculationReader = try {
            CalculationReader(view, RunContext.begin(options))
        } catch (failure: RunAbortedException) {
            throw RunBoundary.exception(failure)
        }
    }
}
