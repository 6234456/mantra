package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.RunCancellation
import com.xqiou.mantra.core.api.RunCounter
import com.xqiou.mantra.core.api.RunStage
import java.time.Instant

/** Must be used inside the runner's context.at(LOADING, exactCaseAddress) scope. */
internal fun <T> RunContext.withLoadControl(action: (CaseLoadControl) -> T): T {
    checkpoint()
    val control = ScopedCaseLoadControl(this)
    return try {
        action(control)
    } finally {
        // Clear the strong epoch reference even when an external resolver retains its capability.
        control.expire()
    }
}

private class ScopedCaseLoadControl(context: RunContext) : CaseLoadControl {
    private var context: RunContext? = context

    override val deadline: Instant get() = active().deadline
    override val cancellation: RunCancellation get() = active().options.control.cancellation

    override fun checkpoint() {
        active().checkpoint()
    }

    override fun chargeParticipatingBytes(amount: Long) {
        active().charge(RunCounter.PARTICIPATING_BYTES, amount)
    }

    override fun chargeInputRows(amount: Long) {
        val current = active()
        current.at(RunStage.IMPORTING) { current.charge(RunCounter.INPUT_ROWS, amount) }
    }

    private fun active(): RunContext =
        checkNotNull(context) { "Case loading control has expired" }.also { it.checkpoint() }

    fun expire() {
        context = null
    }
}
