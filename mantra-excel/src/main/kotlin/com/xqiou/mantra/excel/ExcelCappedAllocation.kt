package com.xqiou.mantra.excel

import java.math.RoundingMode

/**
 * Symbolic water filling followed by largest remainders. An asset is saturated exactly when the
 * requested amount exceeds the total allocation at its cap/weight threshold. This avoids a
 * circular spreadsheet dependency or a result copied from the calculation engine.
 *
 * Intermediate cells keep editable allocations within Excel's formula-size limits. Missing cap
 * keys are unlimited; nonpositive weights or caps receive zero. Capacity excess stays unallocated.
 */
internal fun cappedAllocation(
    amount: X.Scalar,
    weights: X.MapX,
    caps: X.MapX,
    scale: X.Scalar,
    materialize: (X.Scalar) -> X.Scalar,
): X.MapX {
    val keys = weights.keys
    if (keys.isEmpty()) return X.MapX(emptyList(), emptyList())
    if (keys.size > 12) throw Untranslatable("alloc/capped with more than 12 members")
    fun scalar(value: X, label: String): X.Scalar =
        value as? X.Scalar ?: throw Untranslatable("alloc/capped requires numeric $label")
    fun sum(items: List<X.Scalar>) = Ex.fn("SUM", items.ifEmpty { listOf(Ex.ZERO) })
    fun keep(value: X.Scalar) = materialize(value)
    val capByKey = caps.keys.zip(caps.values).toMap()
    val weight = weights.values.map { keep(Ex.fn("MAX", Ex.ZERO, scalar(it, "weights"))) }
    val cap = keys.map { key -> capByKey[key]?.let { keep(Ex.fn("MAX", Ex.ZERO, scalar(it, "caps"))) } }
    val eligible = keys.indices.map { index ->
        keep(
            Ex.fn(
                "AND",
                Ex.cmp(">", weight[index], Ex.ZERO),
                cap[index]?.let {
                    Ex.cmp(">", it, Ex.ZERO)
                } ?: Ex.TRUE,
                kind = XKind.BOOL,
            ),
        )
    }
    val saturated = keys.indices.map { index ->
        val limit = cap[index]
        if (limit == null) {
            Ex.FALSE
        } else {
            val threshold = keep(Ex.iff(eligible[index], Ex.div(limit, weight[index]), Ex.ZERO))
            val filledAtThreshold = keep(
                sum(
                    keys.indices.map { other ->
                        val proportional = Ex.mul(weight[other], threshold)
                        val limited = cap[other]?.let { Ex.fn("MIN", it, proportional) } ?: proportional
                        Ex.iff(eligible[other], limited, Ex.ZERO)
                    },
                ),
            )
            keep(Ex.fn("AND", eligible[index], Ex.cmp(">", amount, filledAtThreshold), kind = XKind.BOOL))
        }
    }
    val open = keys.indices.map {
        keep(Ex.fn("AND", eligible[it], Ex.fn("NOT", saturated[it], kind = XKind.BOOL), kind = XKind.BOOL))
    }
    val fixed = keys.indices.map { keep(Ex.iff(saturated[it], cap[it] ?: Ex.ZERO, Ex.ZERO)) }
    val remaining = keep(Ex.sub(amount, sum(fixed)))
    val openWeight = keep(sum(keys.indices.map { Ex.iff(open[it], weight[it], Ex.ZERO) }))
    val factor = keep(Ex.pow10(scale))
    val quotas = keys.indices.map { index ->
        keep(
            Ex.iff(
                Ex.fn(
                    "AND",
                    open[index],
                    Ex.cmp(">", openWeight, Ex.ZERO),
                    Ex.cmp(">", remaining, Ex.ZERO),
                    kind = XKind.BOOL,
                ),
                Ex.mul(Ex.div(Ex.mul(remaining, weight[index]), openWeight), factor),
                Ex.ZERO,
            ),
        )
    }
    val floors = quotas.map { keep(Ex.fn("INT", it)) }
    val remainders = keys.indices.map { keep(Ex.sub(quotas[it], floors[it])) }
    val units = keep(Ex.sub(Ex.round(Ex.mul(remaining, factor), Ex.ZERO, RoundingMode.HALF_UP), sum(floors)))
    val values = keys.indices.map { index ->
        val ahead = keys.indices.filter { it != index }.map { other ->
            Ex.iff(
                open[other],
                Ex.iff(
                    Ex.cmp(if (other < index) ">=" else ">", remainders[other], remainders[index]),
                    Ex.num(1),
                    Ex.ZERO,
                ),
                Ex.ZERO,
            )
        }
        val rank = keep(Ex.add(Ex.num(1), sum(ahead)))
        val roundedShare = Ex.div(Ex.add(floors[index], Ex.iff(Ex.cmp("<=", rank, units), Ex.num(1), Ex.ZERO)), factor)
        keep(
            Ex.iff(
                Ex.cmp(">", amount, Ex.ZERO),
                Ex.iff(
                    saturated[index],
                    Ex.round(fixed[index], scale, RoundingMode.HALF_UP),
                    Ex.iff(open[index], roundedShare, Ex.ZERO),
                ),
                Ex.ZERO,
            ),
        )
    }
    return X.MapX(keys, values)
}
