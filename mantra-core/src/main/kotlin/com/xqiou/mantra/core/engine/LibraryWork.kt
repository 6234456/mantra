package com.xqiou.mantra.core.engine

import com.xqiou.normein.dsl.runtime.DslBudgetCounter
import com.xqiou.normein.dsl.runtime.DslFunctionRuntime

/** Actual-work instrumentation; counters are the active formula's kernel budget only. */
internal class LibraryWork(private val runtime: DslFunctionRuntime?) {
    fun scan(items: Long = 1) {
        runtime?.checkpoint()
        runtime?.charge(DslBudgetCounter.ITEMS_SCANNED, items)
    }

    fun numeric(operations: Long = 1) {
        runtime?.checkpoint()
        runtime?.charge(DslBudgetCounter.NUMERIC_OPERATIONS, operations)
    }

    fun iteration() {
        runtime?.checkpoint()
        runtime?.charge(DslBudgetCounter.ITERATIONS)
    }

    fun conversion() = runtime?.charge(DslBudgetCounter.CONVERSIONS)

    fun temporary(items: Long = 1) = runtime?.charge(DslBudgetCounter.TEMPORARY_ITEMS, items)

    fun <T> scanned(values: Iterable<T>): Iterable<T> = Iterable {
        val iterator = values.iterator()
        object : Iterator<T> {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): T {
                scan()
                return iterator.next()
            }
        }
    }

    fun <T> contains(values: Iterable<T>, wanted: T): Boolean {
        for (value in scanned(values)) if (value == wanted) return true
        return false
    }

    companion object {
        // Pure direct-algorithm unit tests have no execution runtime. Handlers always supply one.
        val NONE = LibraryWork(null)
    }
}
