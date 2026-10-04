package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.CompilationStatistics
import com.xqiou.mantra.core.api.RunCounter

/** Construction-local probe; only its immutable snapshot enters a compiled template. */
internal class CompilationMeter(private val context: RunContext) {
    private var syntax = 0L
    private var semantic = 0L
    private var plans = 0L
    fun syntax() {
        context.charge(RunCounter.HOST_SCANS)
        syntax = Math.addExact(syntax, 1)
    }
    fun semantic() {
        context.charge(RunCounter.HOST_SCANS)
        semantic = Math.addExact(semantic, 1)
    }
    fun plan() {
        context.charge(RunCounter.HOST_SCANS)
        plans = Math.addExact(plans, 1)
    }
    fun snapshot(formulas: Long) = CompilationStatistics(syntax, semantic, plans, formulas)
}
