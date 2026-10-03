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

    // Excel specifications: https://support.microsoft.com/en-us/excel/excel-specifications-and-limits
    private const val MAX_FUNCTION_ARGUMENTS = 255
    private const val MAX_FORMULA_LENGTH = 8192
    private const val MAX_FUNCTION_DEPTH = 64
    private val absoluteCell = Regex("^('(?:[^']|'')+'!)\\$([A-Z]{1,3})\\$([1-9][0-9]*)$")

    private data class CellRef(val sheet: String, val column: String, val row: Int)

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

    fun fn(name: String, args: List<X.Scalar>, kind: XKind = XKind.NUM): X.Scalar =
        if (name == "SUM" && args.size > MAX_FUNCTION_ARGUMENTS) boundedSum(args, kind) else function(name, args, kind)

    private fun function(name: String, args: List<X.Scalar>, kind: XKind) = X.Scalar(
        "$name(" + args.joinToString(",") {
            it.text
        } + ")",
        ATOM,
        kind,
    )

    fun fn(name: String, vararg args: X.Scalar, kind: XKind = XKind.NUM) = fn(name, args.toList(), kind)

    /** Preserve the argument sequence: only forward adjacent numeric cell references form a range. */
    private fun compactSumReferences(args: List<X.Scalar>): List<X.Scalar> = buildList {
        fun reference(value: X.Scalar): CellRef? {
            if (value.kind != XKind.NUM || value.prec != ATOM) return null
            val parts = absoluteCell.matchEntire(value.text)?.groupValues ?: return null
            val row = parts[3].toIntOrNull() ?: return null
            return CellRef(parts[1], parts[2], row)
        }
        var index = 0
        while (index < args.size) {
            val first = reference(args[index])
            var end = index
            var last = first
            if (first != null) {
                while (end + 1 < args.size) {
                    val next = reference(args[end + 1]) ?: break
                    if (next.sheet != first.sheet || next.column != first.column || next.row != last!!.row + 1) break
                    last = next
                    end++
                }
            }
            add(
                if (end == index) args[index] else atom(args[index].text + ":\$${last!!.column}\$${last.row}"),
            )
            index = end + 1
        }
    }

    private fun boundedSum(args: List<X.Scalar>, kind: XKind): X.Scalar {
        val values = compactSumReferences(args)
        var consumed = minOf(values.size, MAX_FUNCTION_ARGUMENTS)
        var result = function("SUM", values.take(consumed), kind)
        // Feed the prefix subtotal into the next SUM first. Balanced groups would reassociate
        // floating-point additions and could change which input error Excel returns first.
        while (consumed < values.size) {
            val end = minOf(values.size, consumed + MAX_FUNCTION_ARGUMENTS - 1)
            result = function("SUM", listOf(result) + values.subList(consumed, end), kind)
            consumed = end
        }
        return result
    }

    /** POI accepts some formulas beyond Excel's limits; export those through the explicit fallback. */
    internal fun validateFormula(formula: String) {
        if (formula.length > MAX_FORMULA_LENGTH) {
            throw Untranslatable("formula exceeds Excel's $MAX_FORMULA_LENGTH character limit")
        }
        val parentheses = ArrayDeque<Boolean>()
        var functionDepth = 0
        var index = 0
        while (index < formula.length) {
            val character = formula[index]
            if (character == '"' || character == '\'') {
                // Excel doubles quotes inside string literals and quoted worksheet names.
                val quote = character
                index++
                while (index < formula.length) {
                    if (formula[index] != quote) {
                        index++
                    } else if (index + 1 < formula.length && formula[index + 1] == quote) {
                        index += 2
                    } else {
                        index++
                        break
                    }
                }
                continue
            }
            when (character) {
                '(' -> {
                    var end = index - 1
                    while (end >= 0 && formula[end].isWhitespace()) end--
                    var start = end
                    while (start >= 0 && (formula[start].isLetterOrDigit() || formula[start] in "_.")) start--
                    val isFunction = start < end && (formula[start + 1].isLetter() || formula[start + 1] == '_')
                    parentheses.addLast(isFunction)
                    if (isFunction && ++functionDepth > MAX_FUNCTION_DEPTH) {
                        throw Untranslatable("formula exceeds Excel's $MAX_FUNCTION_DEPTH nested function limit")
                    }
                }
                ')' -> if (parentheses.removeLastOrNull() == true) functionDepth--
            }
            index++
        }
    }

    /** Left-associative binary operation; the right operand is wrapped at equal precedence. */
    fun bin(op: String, a: X.Scalar, b: X.Scalar, prec: Int, kind: XKind = XKind.NUM): X.Scalar =
        X.Scalar(paren(a, prec) + op + paren(b, prec + 1), prec, kind)

    fun chain(op: String, items: List<X.Scalar>, prec: Int, kind: XKind = XKind.NUM): X.Scalar =
        items.drop(1).fold(items.first()) {
                acc,
                item,
            ->
            bin(op, acc, item, prec, kind)
        }

    fun add(a: X.Scalar, b: X.Scalar) = bin("+", a, b, ADD)
    fun sub(a: X.Scalar, b: X.Scalar) = bin("-", a, b, ADD)
    fun mul(a: X.Scalar, b: X.Scalar) = bin("*", a, b, MUL)
    fun div(a: X.Scalar, b: X.Scalar) = bin("/", a, b, MUL)
    fun neg(a: X.Scalar) = X.Scalar("-" + paren(a, UNARY), UNARY, XKind.NUM)
    fun cmp(op: String, a: X.Scalar, b: X.Scalar) = bin(op, a, b, CMP, XKind.BOOL)
    fun iff(c: X.Scalar, a: X.Scalar, b: X.Scalar) = fn(
        "IF",
        c,
        a,
        b,
        kind = if (a.kind ==
            b.kind
        ) {
            a.kind
        } else {
            XKind.ANY
        },
    )

    fun pow10(scale: X.Scalar): X.Scalar {
        val literal = scale.text.toBigDecimalOrNull()
        return if (literal !=
            null
        ) {
            num(BigDecimal.ONE.movePointRight(literal.intValueExact()))
        } else {
            bin("^", atom("10"), scale, POW)
        }
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
                val alternative = if (mode ==
                    RoundingMode.HALF_EVEN
                ) {
                    mul(num(2), fn("ROUND", div(s, num(2)), ZERO))
                } else {
                    fn("TRUNC", s)
                }
                unscaled(iff(tie, alternative, fn("ROUND", s, ZERO)))
            }
            RoundingMode.UNNECESSARY -> x
        }
    }
}
