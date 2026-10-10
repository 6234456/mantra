package com.xqiou.mantra.excel

/** Ordered, typed record selection; neither SUMIF coercion nor case-insensitive Excel equality. */
internal fun tableQuery(
    source: X,
    criteria: X,
    amountColumn: X?,
    materialize: (X.Scalar) -> X.Scalar,
    chargeScans: (Long) -> Unit,
): X.Scalar {
    chargeScans(1)
    if (source is X.Branches || criteria is X.Branches || amountColumn is X.Branches) {
        throw Untranslatable("table query conditional collection shapes are unavailable")
    }
    val failure = Ex.fn("NA")
    val errors = excelOrderedErrors(
        listOfNotNull(source, criteria, amountColumn).map {
            excelEagerErrors(it, materialize = materialize)
        },
        materialize,
    )
    fun finish(body: X.Scalar) = retainExcelEagerErrors(errors, body) as X.Scalar
    val rows = source as? X.Vec ?: return finish(failure)
    if (rows.deferred) return finish(failure)
    val tests = criteria as? X.MapX ?: return finish(failure)
    if (tests.keywordKeys == null) throw Untranslatable("table query criteria key types are unavailable")
    if (tests.keywordKeys == false) return finish(failure)
    val criterionValues = linkedMapOf<String, X>()
    tests.keys.forEachIndexed { index, key ->
        chargeScans(1)
        criterionValues[key] = tests.values[index]
    }
    criterionValues.values.forEach {
        chargeScans(1)
        if (it !is X.Scalar && it != X.Nil || it is X.Scalar && it.kind == XKind.DATE) return finish(failure)
        if (it is X.Scalar && it.kind == XKind.ANY && it != Ex.EMPTY) {
            throw Untranslatable("table query criterion scalar type is unavailable")
        }
    }
    if (tests.liveKeys != null) throw Untranslatable("table query criteria keys must be fixed")
    val column = amountColumn?.let {
        if (it !is X.Scalar || it.kind != XKind.KEYWORD) return finish(failure)
        if (!Ex.isTextLiteral(it)) throw Untranslatable("table query amount column must be a literal keyword")
        it.text.removeSurrounding("\"").replace("\"\"", "\"")
    }
    val records = rows.items.map {
        chargeScans(1)
        val record = it as? X.MapX ?: return finish(failure)
        if (record.keywordKeys == null) throw Untranslatable("table query record key types are unavailable")
        if (record.keywordKeys == false) return finish(failure)
        if (record.liveKeys != null) throw Untranslatable("table query record keys must be fixed")
        linkedMapOf<String, X>().apply {
            record.keys.forEachIndexed { index, key ->
                chargeScans(1)
                val raw = record.values[index]
                put(
                    key,
                    if (key in record.implicitZeroColumns && raw is X.Scalar) {
                        Ex.iff(Ex.cmp("=", raw, Ex.EMPTY), Ex.ZERO, raw)
                    } else {
                        raw
                    },
                )
            }
        }
    }
    fun compact(value: X.Scalar) = if (value.text.length > 500) materialize(value) else value
    fun conjunction(values: List<X.Scalar>): X.Scalar = values.fold(Ex.TRUE) { previous, value ->
        compact(Ex.iff(previous, value, Ex.FALSE))
    }
    val terms = records.mapIndexed { index, record ->
        val matches = criterionValues.map { (key, criterion) ->
            chargeScans(1)
            record[key]?.let { tableValueEquals(it, criterion) } ?: Ex.FALSE
        }
        val selected = conjunction(listOfNotNull(rows.presence?.get(index)) + matches)
        val amount = if (column == null) {
            Ex.num(1)
        } else {
            chargeScans(1)
            val raw = record[column]
            if (raw is X.Scalar && raw.kind == XKind.NUM) {
                Ex.iff(Ex.fn("ISNUMBER", raw, kind = XKind.BOOL), raw, failure)
            } else {
                failure
            }
        }
        compact(Ex.iff(selected, amount, Ex.ZERO))
    }
    var subtotal = Ex.ZERO
    var chunk = mutableListOf<X.Scalar>()
    var length = 0
    for (term in terms) {
        chargeScans(1)
        if (chunk.isNotEmpty() && (chunk.size >= 200 || length + term.text.length > 4000)) {
            subtotal = compact(Ex.fn("SUM", listOf(subtotal) + chunk))
            chunk = mutableListOf()
            length = 0
        }
        chunk += term
        length += term.text.length + 1
    }
    return finish(if (chunk.isEmpty()) subtotal else compact(Ex.fn("SUM", listOf(subtotal) + chunk)))
}

private fun tableValueEquals(left: X, right: X): X.Scalar {
    if (right is X.Scalar && right.kind == XKind.DATE) return Ex.fn("NA")
    if (left !is X.Scalar && left != X.Nil) return Ex.FALSE
    val a = left as? X.Scalar ?: Ex.EMPTY
    val b = right as? X.Scalar ?: Ex.EMPTY
    fun knownNonNil(value: X.Scalar) = value.text.toBigDecimalOrNull() != null ||
        value in listOf(Ex.TRUE, Ex.FALSE) || Ex.isTextLiteral(value)

    // A worksheet stores nil and empty text as the same blank value. Nonempty literal text
    // remains safe, but guessing for other text comparisons would silently change selection.
    fun nil(value: X.Scalar, other: X.Scalar): X.Scalar = when {
        knownNonNil(value) -> Ex.FALSE
        value == Ex.EMPTY -> Ex.TRUE
        value.kind == XKind.TEXT -> if (knownNonNil(other) && other.text != "\"\"") {
            Ex.FALSE
        } else {
            throw Untranslatable("table query cannot distinguish live nil from empty text")
        }
        value.kind == XKind.ANY -> throw Untranslatable("table query scalar type is unavailable")
        else -> Ex.cmp("=", value, Ex.EMPTY)
    }
    val leftNil = nil(a, b)
    val rightNil = nil(b, a)
    val equal = when {
        a.kind != b.kind -> Ex.FALSE
        a.kind == XKind.TEXT || a.kind == XKind.KEYWORD -> Ex.fn("EXACT", a, b, kind = XKind.BOOL)
        a.kind == XKind.NUM -> Ex.fn(
            "AND",
            Ex.fn("ISNUMBER", a, kind = XKind.BOOL),
            Ex.fn("ISNUMBER", b, kind = XKind.BOOL),
            Ex.cmp("=", a, b),
            kind = XKind.BOOL,
        )
        a.kind == XKind.BOOL -> Ex.fn(
            "AND",
            Ex.fn("ISLOGICAL", a, kind = XKind.BOOL),
            Ex.fn("ISLOGICAL", b, kind = XKind.BOOL),
            Ex.cmp("=", a, b),
            kind = XKind.BOOL,
        )
        else -> Ex.FALSE
    }
    return Ex.iff(leftNil, rightNil, Ex.iff(rightNil, Ex.FALSE, equal))
}
