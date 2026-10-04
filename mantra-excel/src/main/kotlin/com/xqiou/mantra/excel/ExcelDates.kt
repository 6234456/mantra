package com.xqiou.mantra.excel

import com.xqiou.mantra.core.read.string
import com.xqiou.normein.dsl.form.DslForm
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal fun excelDate(value: LocalDate): X.Scalar {
    val days = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 31), value)
    val leapAdjustment = if (value >= LocalDate.of(1900, 3, 1)) 1 else 0
    return Ex.num(days + leapAdjustment).copy(kind = XKind.DATE)
}

/** Date values remain serial dates, so editing source dates recomputes every projection. */
internal fun translateDateCall(head: String, args: List<DslForm>, scalar: (DslForm) -> X.Scalar): X? {
    if (!head.startsWith("date/") && head != "date?") return null
    fun value(index: Int) = scalar(args[index])
    fun date(index: Int) = value(index).let { if (it.kind == XKind.TEXT) parseDate(it, "uuuu-MM-dd") else it }
    fun typed(value: X.Scalar) = value.copy(kind = XKind.DATE)
    fun year(index: Int) = Ex.fn("YEAR", date(index))
    fun month(index: Int) = Ex.fn("MONTH", date(index))
    fun day(index: Int) = Ex.fn("DAY", date(index))
    fun firstMonth(index: Int) = typed(Ex.fn("DATE", year(index), month(index), Ex.num(1)))
    fun quarterMonth(index: Int) =
        Ex.add(Ex.mul(Ex.fn("INT", Ex.div(Ex.sub(month(index), Ex.num(1)), Ex.num(3))), Ex.num(3)), Ex.num(1))
    fun quarterStart(index: Int) = typed(Ex.fn("DATE", year(index), quarterMonth(index), Ex.num(1)))
    return when (head) {
        "date?" -> value(0).let { input ->
            if (input.kind == XKind.DATE) {
                Ex.cmp("<>", input, Ex.EMPTY)
            } else {
                Ex.iff(Ex.fn("ISERROR", input, kind = XKind.BOOL), input, Ex.FALSE).copy(
                    kind = XKind.BOOL,
                    numericOrNil = false,
                    booleanOrNil = true,
                )
            }
        }
        "date/parse" -> {
            val pattern = args.getOrNull(1)?.string ?: "uuuu-MM-dd"
            val text = args[0].string
            if (text !=
                null
            ) {
                runCatching {
                    excelDate(
                        LocalDate.parse(
                            text.trim(),
                            if (args.size ==
                                1
                            ) {
                                DateTimeFormatter.ISO_LOCAL_DATE
                            } else {
                                DateTimeFormatter.ofPattern(pattern)
                            },
                        ),
                    )
                }.getOrDefault(Ex.EMPTY)
            } else if (value(0).kind == XKind.DATE) {
                value(0)
            } else {
                parseDate(value(0), pattern, smart = args.size > 1)
            }
        }
        "date/format" -> Ex.fn(
            "TEXT",
            date(0),
            Ex.text(excelDatePattern(args.getOrNull(1)?.string ?: "uuuu-MM-dd")),
            kind = XKind.TEXT,
        )
        "date/year" -> year(0)
        "date/month" -> month(0)
        "date/day-of-month" -> day(0)
        "date/day-of-week" -> Ex.fn("WEEKDAY", date(0), Ex.num(2))
        "date/quarter" -> Ex.add(Ex.fn("INT", Ex.div(Ex.sub(month(0), Ex.num(1)), Ex.num(3))), Ex.num(1))
        "date/plus-days", "date/minus-days" -> {
            val amount = value(1)
            val shifted = if (head == "date/plus-days") Ex.add(date(0), amount) else Ex.sub(date(0), amount)
            typed(Ex.iff(Ex.cmp("=", amount, Ex.fn("TRUNC", amount)), shifted, Ex.EMPTY))
        }
        "date/plus-months", "date/minus-months", "date/plus-years", "date/minus-years" -> {
            var shift = value(1)
            if (head.endsWith("years")) shift = Ex.mul(shift, Ex.num(12))
            if (head.contains("minus")) shift = Ex.neg(shift)
            val amount = value(1)
            typed(Ex.iff(Ex.cmp("=", amount, Ex.fn("TRUNC", amount)), Ex.fn("EDATE", date(0), shift), Ex.EMPTY))
        }
        "date/days-between" -> Ex.sub(date(1), date(0))
        "date/months-between" -> {
            val months = Ex.add(Ex.mul(Ex.sub(year(1), year(0)), Ex.num(12)), Ex.sub(month(1), month(0)))
            // LocalDate.until(MONTHS) packs proleptic months and day-of-month into base 32.
            Ex.fn("TRUNC", Ex.div(Ex.add(Ex.mul(months, Ex.num(32)), Ex.sub(day(1), day(0))), Ex.num(32)))
        }
        "date/before?" -> Ex.cmp("<", date(0), date(1))
        "date/after?" -> Ex.cmp(">", date(0), date(1))
        "date/min", "date/max" -> typed(Ex.fn(if (head == "date/min") "MIN" else "MAX", args.indices.map(::date)))
        "date/start-of-month" -> firstMonth(0)
        "date/end-of-month" -> typed(Ex.fn("EOMONTH", date(0), Ex.ZERO))
        "date/start-of-quarter" -> quarterStart(0)
        "date/end-of-quarter" -> typed(Ex.fn("EOMONTH", quarterStart(0), Ex.num(2)))
        "date/iso-week" -> {
            val thursday = Ex.add(date(0), Ex.sub(Ex.num(4), Ex.fn("WEEKDAY", date(0), Ex.num(2))))
            val january = Ex.fn("DATE", Ex.fn("YEAR", thursday), Ex.num(1), Ex.num(1))
            Ex.add(Ex.fn("INT", Ex.div(Ex.sub(thursday, january), Ex.num(7))), Ex.num(1))
        }
        "date/iso-week-year" -> Ex.fn("YEAR", Ex.add(date(0), Ex.sub(Ex.num(4), Ex.fn("WEEKDAY", date(0), Ex.num(2)))))
        "date/overlap-days" -> Ex.fn(
            "MAX",
            Ex.ZERO,
            Ex.sub(Ex.fn("MIN", date(1), date(3)), Ex.fn("MAX", date(0), date(2))),
        )
        "date/period-overlap?" -> Ex.cmp("<", Ex.fn("MAX", date(0), date(2)), Ex.fn("MIN", date(1), date(3)))
        else -> throw Untranslatable("date function $head")
    }
}

private fun excelDatePattern(pattern: String): String {
    val converted = pattern.replace("uuuu", "yyyy")
    if (Regex("[a-zA-Z]").findAll(converted).any { it.value !in listOf("y", "M", "d") }) {
        throw Untranslatable("date pattern $pattern")
    }
    return converted
}

private fun parseDate(input: X.Scalar, pattern: String, smart: Boolean = false): X.Scalar {
    val tokens = Regex("uuuu|yyyy|MM|dd").findAll(pattern).toList()
    if (tokens.size != 3 || tokens.map { it.value }.toSet().let { "MM" !in it || "dd" !in it }) {
        throw Untranslatable("date parse pattern $pattern")
    }
    val value = Ex.fn("TRIM", input, kind = XKind.TEXT)
    fun partText(token: String): X.Scalar {
        val match = tokens.first { if (token == "year") it.value in listOf("uuuu", "yyyy") else it.value == token }
        return Ex.fn(
            "MID",
            value,
            Ex.num(match.range.first.toLong() + 1),
            Ex.num(match.value.length.toLong()),
            kind = XKind.TEXT,
        )
    }
    fun part(token: String) = Ex.fn("VALUE", partText(token))
    val year = part("year")
    val month = part("MM")
    val day = part("dd")
    val adjustedDay = if (smart) {
        Ex.fn(
            "MIN",
            day,
            Ex.fn("DAY", Ex.fn("EOMONTH", Ex.fn("DATE", year, month, Ex.num(1)), Ex.ZERO)),
        )
    } else {
        day
    }
    val parsed = Ex.fn("DATE", year, month, adjustedDay)
    val formatted = Ex.fn("TEXT", parsed, Ex.text(excelDatePattern(pattern)), kind = XKind.TEXT)
    val valid = if (!smart) {
        Ex.cmp("=", formatted, value)
    } else {
        val digits = listOf("year" to "0000", "MM" to "00", "dd" to "00").map { (token, format) ->
            Ex.cmp("=", Ex.fn("TEXT", part(token), Ex.text(format), kind = XKind.TEXT), partText(token))
        }
        val separators = pattern.indices.filter { index -> tokens.none { index in it.range } }.map { index ->
            Ex.cmp(
                "=",
                Ex.fn("MID", value, Ex.num(index.toLong() + 1), Ex.num(1), kind = XKind.TEXT),
                Ex.text(pattern[index].toString()),
            )
        }
        Ex.fn(
            "AND",
            digits + separators + listOf(
                Ex.cmp("=", Ex.fn("LEN", value), Ex.num(pattern.length.toLong())),
                Ex.cmp(">=", month, Ex.num(1)),
                Ex.cmp("<=", month, Ex.num(12)),
                Ex.cmp(">=", day, Ex.num(1)),
                Ex.cmp("<=", day, Ex.num(31)),
            ),
            XKind.BOOL,
        )
    }
    val result = Ex.fn("IFERROR", Ex.iff(valid, parsed, Ex.EMPTY), Ex.EMPTY)
    // Malformed date text is nil; an error evaluating the source text remains an error.
    return Ex.iff(Ex.fn("ISERROR", input, kind = XKind.BOOL), input, result).copy(kind = XKind.DATE)
}
