package com.xqiou.mantra.core.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Host-side value model of the calculation engine. It is deliberately small: every value that
 * crosses the boundary to the Normein kernel is converted from and to this model, so results,
 * traces and renderers never depend on Normein runtime classes.
 */
sealed interface Value {
    data object Nil : Value {
        override fun toString(): String = "nil"
    }

    data class Num(val value: BigDecimal) : Value {
        override fun toString(): String = value.toPlainString()
    }

    data class Bool(val value: Boolean) : Value {
        override fun toString(): String = value.toString()
    }

    /** A namespace-free keyword such as `:combined`; [name] excludes the colon. */
    data class Kw(val name: String) : Value {
        override fun toString(): String = ":$name"
    }

    data class Text(val value: String) : Value {
        override fun toString(): String = "\"$value\""
    }

    data class Date(val value: LocalDate) : Value {
        override fun toString(): String = value.toString()
    }

    data class Vec(val items: List<Value>) : Value {
        override fun toString(): String = items.joinToString(" ", "[", "]")
    }

    /** Ordered map; keys are values themselves (usually keywords or numbers). */
    data class MapV(val entries: Map<Value, Value>) : Value {
        override fun toString(): String = entries.entries.joinToString(" ", "{", "}") { (k, v) -> "$k $v" }
    }

    companion object {
        val ZERO: Value = Num(BigDecimal.ZERO)

        fun num(value: Long): Value = Num(BigDecimal.valueOf(value))
        fun num(value: String): Value = Num(BigDecimal(value))
    }
}

val Value.isNil: Boolean get() = this == Value.Nil

/** Numeric view of a value; `nil` counts as zero because absent amounts contribute nothing. */
fun Value.decimalOrZero(): BigDecimal = when (this) {
    is Value.Num -> value
    else -> BigDecimal.ZERO
}

fun Value.decimalOrNull(): BigDecimal? = (this as? Value.Num)?.value

/** Clojure truthiness: only `nil` and `false` are falsey. */
val Value.truthy: Boolean get() = !(this == Value.Nil || this == Value.Bool(false))
