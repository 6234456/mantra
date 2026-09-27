package com.xqiou.mantra.excel

import java.math.BigDecimal
import java.math.RoundingMode

/** Value kind of a translated expression; used to choose boolean vs. value semantics. */
enum class XKind { NUM, BOOL, TEXT, ANY }

/**
 * Symbolic Excel expression produced while translating a Normein formula. Only [Scalar] can be
 * written into a cell; the other shapes exist while translating vector-, map- and member-valued
 * sub-expressions (e.g. `(nth (cond …) 1)`, `(dim/sum all.x)`, `(get (alloc/pro-rata …) :k)`).
 */
sealed interface X {
    data class Scalar(val text: String, val prec: Int, val kind: XKind) : X

    data object Nil : X

    data class Vec(val items: List<X>) : X

    data class MapX(val keys: List<String>, val values: List<X>) : X

    /** Member map stored in contiguous cells; [text] is the range (or its name), [cells] the members. */
    data class Range(val text: String, val keys: List<String>, val cells: List<Scalar>) : X

    /** Conditional between non-scalar alternatives; a `null` condition is the default branch. */
    data class Branches(val cases: List<Pair<Scalar?, X>>) : X
}

class Untranslatable(val reason: String) : RuntimeException(reason)

/** Precedence-aware construction of Excel formula text. */
object Ex {
    const val CMP = 1
    const val CONCAT = 2
    const val ADD = 3
    const val MUL = 4
    const val POW = 5
    const val UNARY = 6
    const val ATOM = 9

    fun atom(text: String, kind: XKind = XKind.NUM) = X.Scalar(text, ATOM, kind)

    fun num(value: BigDecimal): X.Scalar {
        val text = value.stripTrailingZeros().toPlainString()
        return if (value.signum() < 0) X.Scalar(text, UNARY, XKind.NUM) else atom(text)
    }

    fun num(value: Long) = num(BigDecimal.valueOf(value))

    fun text(value: String) = atom("\"" + value.replace("\"", "\"\"") + "\"", XKind.TEXT)

    val TRUE = atom("TRUE", XKind.BOOL)
    val FALSE = atom("FALSE", XKind.BOOL)
    val EMPTY = atom("\"\"", XKind.ANY)
    val ZERO = atom("0")

    fun paren(x: X.Scalar, min: Int): String = if (x.prec < min) "(${x.text})" else x.text

    fun fn(name: String, args: List<X.Scalar>, kind: XKind = XKind.NUM) =
        X.Scalar("$name(" + args.joinToString(",") { it.text } + ")", ATOM, kind)

    fun fn(name: String, vararg args: X.Scalar, kind: XKind = XKind.NUM) = fn(name, args.toList(), kind)

    /** Left-associative binary operation; the right operand is wrapped at equal precedence. */
    fun bin(op: String, a: X.Scalar, b: X.Scalar, prec: Int, kind: XKind = XKind.NUM): X.Scalar =
        X.Scalar(paren(a, prec) + op + paren(b, prec + 1), prec, kind)

    fun chain(op: String, items: List<X.Scalar>, prec: Int, kind: XKind = XKind.NUM): X.Scalar =
        items.drop(1).fold(items.first()) { acc, item -> bin(op, acc, item, prec, kind) }

    fun add(a: X.Scalar, b: X.Scalar) = bin("+", a, b, ADD)
    fun sub(a: X.Scalar, b: X.Scalar) = bin("-", a, b, ADD)
    fun mul(a: X.Scalar, b: X.Scalar) = bin("*", a, b, MUL)
    fun div(a: X.Scalar, b: X.Scalar) = bin("/", a, b, MUL)
    fun neg(a: X.Scalar) = X.Scalar("-" + paren(a, UNARY), UNARY, XKind.NUM)
    fun cmp(op: String, a: X.Scalar, b: X.Scalar) = bin(op, a, b, CMP, XKind.BOOL)
    fun iff(c: X.Scalar, a: X.Scalar, b: X.Scalar) = fn("IF", c, a, b, kind = if (a.kind == b.kind) a.kind else XKind.ANY)

    fun pow10(scale: X.Scalar): X.Scalar {
        val literal = scale.text.toBigDecimalOrNull()
        return if (literal != null) num(BigDecimal.ONE.movePointRight(literal.intValueExact())) else bin("^", atom("10"), scale, POW)
    }

    private fun isZero(x: X.Scalar) = x.text.toBigDecimalOrNull()?.signum() == 0

    /** Rounds with Java/BigDecimal semantics; Excel ROUND equals HALF_UP (half away from zero). */
    fun round(x: X.Scalar, scale: X.Scalar, mode: RoundingMode): X.Scalar {
        val factor = pow10(scale)
        fun scaled(e: X.Scalar) = if (isZero(scale)) e else mul(e, factor)
        fun unscaled(e: X.Scalar) = if (isZero(scale)) e else div(e, factor)
        return when (mode) {
            RoundingMode.HALF_UP -> fn("ROUND", x, scale)
            RoundingMode.FLOOR -> unscaled(fn("INT", scaled(x)))
            RoundingMode.CEILING -> unscaled(neg(fn("INT", neg(scaled(x)))))
            RoundingMode.DOWN -> fn("ROUNDDOWN", x, scale)
            RoundingMode.UP -> fn("ROUNDUP", x, scale)
            RoundingMode.HALF_EVEN, RoundingMode.HALF_DOWN -> {
                val s = scaled(x)
                val tie = cmp("=", fn("ABS", sub(s, fn("TRUNC", s))), atom("0.5"))
                val alternative = if (mode == RoundingMode.HALF_EVEN) mul(num(2), fn("ROUND", div(s, num(2)), ZERO)) else fn("TRUNC", s)
                unscaled(iff(tie, alternative, fn("ROUND", s, ZERO)))
            }
            RoundingMode.UNNECESSARY -> x
        }
    }
}
