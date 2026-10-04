package com.xqiou.mantra.excel

/** Runtime error effect of constructing a value, without invoking functions or unused producers. */
internal fun excelEagerErrors(
    value: X,
    forceSequence: Boolean = false,
    materialize: (X.Scalar) -> X.Scalar = { it },
): X.Scalar = when (value) {
    is X.Scalar -> if (value.text.toBigDecimalOrNull() != null || value in listOf(Ex.TRUE, Ex.FALSE, Ex.EMPTY) ||
        Ex.isTextLiteral(value)
    ) {
        Ex.ZERO
    } else {
        Ex.iff(Ex.fn("ISERROR", value, kind = XKind.BOOL), value, Ex.ZERO)
            .copy(kind = XKind.NUM, numericOrNil = true, booleanOrNil = false)
    }
    is X.Vec -> {
        val creation = value.creationErrors ?: Ex.ZERO
        if (value.deferred && !forceSequence) {
            creation
        } else {
            excelOrderedErrors(
                listOf(creation) + value.items.map { excelEagerErrors(it, materialize = materialize) },
                materialize,
            )
        }
    }
    is X.MapX -> excelOrderedErrors(value.values.map { excelEagerErrors(it, materialize = materialize) }, materialize)
    is X.Range -> excelOrderedErrors(value.cells.map { excelEagerErrors(it, materialize = materialize) }, materialize)
    is X.Branches -> {
        val fallback = value.cases.lastOrNull { it.first == null }?.second
        val default = fallback?.let { excelEagerErrors(it, materialize = materialize) } ?: Ex.ZERO
        value.cases.filter { it.first != null }.foldRight(default) { (condition, selected), rest ->
            Ex.iff(condition!!, excelEagerErrors(selected, materialize = materialize), rest)
        }
    }
    is X.Callable -> value.creationErrors ?: Ex.ZERO
    X.Nil -> Ex.ZERO
}

/** Every probe yields zero or its original error; ordered SUM retains the first selected error. */
internal fun excelOrderedErrors(probes: List<X.Scalar>, materialize: (X.Scalar) -> X.Scalar = { it }): X.Scalar {
    val active = probes.filter { it != Ex.ZERO }.map {
        if (it.text.length > 500) materialize(it) else it
    }
    if (active.isEmpty()) return Ex.ZERO
    if (active.size == 1) return active.single()
    val chunks = mutableListOf<X.Scalar>()
    var chunk = mutableListOf<X.Scalar>()
    var length = 0
    for (value in active) {
        if (chunk.isNotEmpty() && (length + value.text.length > 4000 || chunk.size >= 200)) {
            chunks += materialize(Ex.fn("SUM", chunk))
            chunk = mutableListOf()
            length = 0
        }
        chunk += value
        length += value.text.length + 1
    }
    if (chunk.isNotEmpty()) {
        chunks += Ex.fn("SUM", chunk).let {
            if (it.text.length > 500) materialize(it) else it
        }
    }
    return when {
        chunks.size == 1 -> chunks.single()
        chunks.size >= active.size -> Ex.fn("SUM", chunks)
        else -> excelOrderedErrors(chunks, materialize)
    }
}

/** Lets construct bindings before body, including bindings whose value the body never references. */
internal fun retainExcelEagerErrors(errors: X.Scalar, body: X): X {
    if (errors == Ex.ZERO) return body
    fun scalar(value: X.Scalar) = Ex.iff(
        Ex.fn("ISERROR", errors, kind = XKind.BOOL),
        errors,
        value,
    ).copy(kind = value.kind, numericOrNil = value.numericOrNil, booleanOrNil = value.booleanOrNil)
    return when (body) {
        is X.Scalar -> scalar(body)
        X.Nil -> scalar(Ex.EMPTY)
        is X.Callable -> X.Callable(
            excelOrderedErrors(listOf(errors, body.creationErrors ?: Ex.ZERO)),
            body.invoke,
        )
        is X.Vec -> {
            val creation = excelOrderedErrors(listOf(errors, body.creationErrors ?: Ex.ZERO))
            X.Vec(body.items, creation, body.deferred)
        }
        is X.MapX -> {
            if (body.keys.isEmpty()) throw Untranslatable("eager-error let cannot return an empty map")
            X.MapX(body.keys, body.values.map { retainExcelEagerErrors(errors, it) })
        }
        is X.Range -> {
            if (body.keys.isEmpty()) throw Untranslatable("eager-error let cannot return an empty member map")
            X.MapX(body.keys, body.cells.map { scalar(it) })
        }
        is X.Branches -> throw Untranslatable("eager-error let needs a scalar or uniform value body")
    }
}

/** The callback is translated only when a consumer asks for an element, under its actual lazy guard. */
internal class DeferredExcelItems(private val source: List<X>, private val transform: (X) -> X) : AbstractList<X>() {
    override val size: Int get() = source.size

    // Deliberately no cross-consumer cache: materialized helpers belong to the consumer's IF/run guard.
    override fun get(index: Int): X = transform(source[index])
}
