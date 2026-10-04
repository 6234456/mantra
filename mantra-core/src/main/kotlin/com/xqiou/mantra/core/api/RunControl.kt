package com.xqiou.mantra.core.api

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** A signal may be observed on execution threads. Implementations must be thread-safe. */
fun interface RunCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE: RunCancellation = RunCancellation { false }
    }
}

/** Only cancellation is transferable; this object never transfers a kernel session or run context. */
class RunCancellationSource : RunCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() {
        cancelled.set(true)
    }
    override fun isCancelled(): Boolean = cancelled.get()
}

data class RunControl(val cancellation: RunCancellation = RunCancellation.NONE, val deadline: Instant? = null)
