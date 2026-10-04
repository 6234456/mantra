package com.xqiou.mantra.excel

import com.xqiou.mantra.core.model.FunctionDecl
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.number
import com.xqiou.mantra.core.read.roundingMode
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
import com.xqiou.mantra.core.view.Coord
import com.xqiou.normein.dsl.form.DslForm
import com.xqiou.normein.dsl.form.DslFormPostfix
import com.xqiou.normein.dsl.form.DslFormReadResult
import com.xqiou.normein.dsl.form.DslFormReader
import com.xqiou.normein.dsl.form.DslFormSequenceKind
import java.math.RoundingMode

/** Resolves schema names to workbook references while translating a formula. */
interface ExcelResolver {
    /**
     * Reference to [nodeId] seen from a context: the aligned cell when the node's dimensions are all
     * fixed by the context, otherwise the node's member cells as an [X.Range], or an empty [X.MapX]
     * when no members exist. Literal parameters (vectors, maps) are returned as values.
     */
    fun reference(nodeId: String, contextDims: List<String>, contextCoord: Coord): X?

    /** Field of the current member record of a dimension (`cgu.carrying-amount`). */
    fun record(dim: String, key: String, field: String): X?

    fun isNode(nodeId: String): Boolean

    fun isDimension(name: String): Boolean

    fun periodKeys(dimension: String): X.Vec? = null

    fun previous(nodeId: String, contextDims: List<String>, contextCoord: Coord): ExcelPrevious =
        throw Untranslatable("continuous periods are unavailable")

    fun materialize(value: X.Scalar): X.Scalar = value

    fun checkpoint() = Unit

    fun chargeScans(amount: Long = 1) = Unit

    /** A translated live computation may never be substituted with the generation-time value. */
    fun requireDynamicFormula() = Unit

    /** Unfold a bounded callback; implementations must gate every helper by [enabled] / run. */
    fun convergence(
        init: X.Scalar,
        iterations: X.Scalar,
        tolerance: X.Scalar,
        enabled: X.Scalar,
        callback: (previous: X.Scalar, run: X.Scalar) -> X.Scalar,
    ): X.Scalar = throw Untranslatable("bounded convergence tables are unavailable")
}

/**
 * Translates Normein expressions into Excel formulas that recompute the same result. `let`
 * bindings and `defn` calls are inlined, so the output only uses functions available in every
 * Excel version and LibreOffice (IF, AND, OR, NOT, MIN, MAX, SUM, INT, ROUND, ROUNDDOWN, ROUNDUP,
 * TRUNC, ABS, INDEX, NPV, PMT). Constructs without a faithful formula raise [Untranslatable].
 */
class FormulaTranslator(private val resolver: ExcelResolver, functions: List<FunctionDecl>) {

    class Ctx(val dims: List<String>, val coord: Coord, val env: Map<String, X> = emptyMap(), val depth: Int = 0) {
        fun with(bindings: Map<String, X>) = Ctx(dims, coord, env + bindings, depth)
        fun call(bindings: Map<String, X>) = Ctx(dims, coord, bindings, depth + 1)
        fun closure(bindings: Map<String, X>) = Ctx(dims, coord, env + bindings, depth + 1)
    }

    private class Defn(val params: List<String>, val body: DslForm)

    // Per-translator lexical translation state; never a process-wide value or trace side channel.
    private var lazyMaterializationGuard: X.Scalar? = null

    private fun <T> withLazyGuard(guard: X.Scalar, body: () -> T): T {
        val previous = lazyMaterializationGuard
        lazyMaterializationGuard = previous?.let { Ex.iff(it, guard, Ex.FALSE) } ?: guard
        return try {
            body()
        } finally {
            lazyMaterializationGuard = previous
        }
    }

    private fun materialize(value: X.Scalar): X.Scalar {
        val guard = lazyMaterializationGuard ?: return resolver.materialize(value)
        val gated = Ex.iff(guard, value, Ex.EMPTY).copy(
            kind = value.kind,
            numericOrNil = value.numericOrNil,
            booleanOrNil = value.booleanOrNil,
        )
        return resolver.materialize(gated)
    }

    private fun eagerErrors(value: X, forceSequence: Boolean = false): X.Scalar =
        excelEagerErrors(value, forceSequence, ::materialize)

    private fun retainEagerErrors(errors: X.Scalar, body: X): X {
        val compact = if (errors.text.length > 500) materialize(errors) else errors
        return retainExcelEagerErrors(compact, body)
    }

    private fun guarded(form: DslForm, ctx: Ctx, guard: X.Scalar): X = withLazyGuard(guard) { translate(form, ctx) }

    private fun not(guard: X.Scalar) = Ex.fn("NOT", guard, kind = XKind.BOOL)

    private val defns: Map<String, Defn> = functions.mapNotNull { decl ->
        val root =
            (
                DslFormReader().readDocument(
                    decl.source,
                ) as? DslFormReadResult.Success
                )?.document?.root as? DslForm.Sequence
                ?: return@mapNotNull null
        val params =
            (root.values.getOrNull(2) as? DslForm.Sequence)?.values?.mapNotNull {
                it.symbol
            }?.filterNot { it.startsWith("^") }
                ?: return@mapNotNull null
        val body = root.values.drop(3).lastOrNull { it.string == null } ?: return@mapNotNull null
        decl.name to Defn(params, body)
    }.toMap()

    fun scalar(form: DslForm, ctx: Ctx): X.Scalar = toScalar(translate(form, ctx))

    internal fun translateGuarded(form: DslForm, ctx: Ctx, enabled: X.Scalar): X = withLazyGuard(enabled) {
        translate(form, ctx)
    }

    fun translate(form: DslForm, ctx: Ctx): X {
        resolver.chargeScans()
        form.number?.let { return Ex.num(it) }
        form.string?.let { return Ex.text(it) }
        form.keyword?.let { return Ex.text(it) }
        form.symbol?.let { return symbol(it, ctx) }
        return when (form) {
            is DslForm.Postfix -> {
                val target = form.target.symbol ?: throw Untranslatable("unsupported postfix target")
                val path = form.suffixes.map {
                    (it as? DslFormPostfix.Member)?.value
                        ?: throw Untranslatable("bracket access")
                }
                symbol((listOf(target) + path).joinToString("."), ctx)
            }
            is DslForm.Sequence -> when (form.kind) {
                DslFormSequenceKind.VECTOR -> X.Vec(form.values.map { translate(it, ctx) })
                DslFormSequenceKind.MAP -> {
                    if (form.values.size % 2 != 0) throw Untranslatable("odd map literal")
                    val pairs = form.values.chunked(2)
                    X.MapX(
                        pairs.map { (k, _) ->
                            k.keyword ?: k.string
                                ?: throw Untranslatable("map keys must be keywords")
                        },
                        pairs.map { (_, v) -> translate(v, ctx) },
                    )
                }
                DslFormSequenceKind.LIST -> call(form, ctx)
                else -> throw Untranslatable("${form.kind.name.lowercase()} literal")
            }
            is DslForm.Atom -> throw Untranslatable("atom ${form.sourceText}")
        }
    }

    // ── Names ───────────────────────────────────────────────────────────────────────────────────

    private fun symbol(qualified: String, ctx: Ctx): X {
        if (qualified.startsWith("mantra/")) {
            val id = qualified.removePrefix("mantra/")
            return if (resolver.isNode(id)) node(id, ctx) else throw Untranslatable("unknown node $qualified")
        }
        val name = qualified
        when (name) {
            "true" -> return Ex.TRUE
            "false" -> return Ex.FALSE
            "nil" -> return X.Nil
        }
        ctx.env[name]?.let { return it }
        if ('.' in name) {
            val (head, field) = name.split('.', limit = 2)
            ctx.env[head]?.let { record ->
                if (record is X.MapX) {
                    return record.values.getOrNull(record.keys.indexOf(field))
                        ?: throw Untranslatable("$head has no field $field")
                }
                throw Untranslatable("field access on non-record $head")
            }
            if (head == "periods" && field.endsWith(".keys")) {
                return resolver.periodKeys(field.removeSuffix(".keys"))
                    ?: throw Untranslatable("unknown period root $name")
            }
            if (head ==
                "all"
            ) {
                return resolver.reference(field, emptyList(), emptyList()) ?: throw Untranslatable("all.$field")
            }
            if (head in ctx.dims) {
                val key = ctx.coord[ctx.dims.indexOf(head)]
                return resolver.record(head, key, field) ?: throw Untranslatable("$head.$field")
            }
            throw Untranslatable("field access $name")
        }
        val definition = defns[name] ?: defns[name.removePrefix("profile/")]
        if (definition != null) {
            return X.Callable { values ->
                if (ctx.depth > 24) throw Untranslatable("recursion in $name")
                if (definition.params.size != values.size) throw Untranslatable("$name arity")
                translate(definition.body, ctx.call(definition.params.zip(values).toMap()))
            }
        }
        if (resolver.isNode(name)) return node(name, ctx)
        if (resolver.isDimension(name)) throw Untranslatable("member record $name used as a value")
        throw Untranslatable("unknown name $name")
    }

    private fun node(name: String, ctx: Ctx): X =
        resolver.reference(name, ctx.dims, ctx.coord) ?: throw Untranslatable("reference to $name")

    // ── Calls ───────────────────────────────────────────────────────────────────────────────────

    private fun args(form: DslForm.Sequence) = form.values.drop(1)

    private fun call(form: DslForm.Sequence, ctx: Ctx): X {
        val args = args(form)
        val head =
            form.listHead ?: return invokeCallable(translate(form.values.first(), ctx), args.map { translate(it, ctx) })
        ctx.env[head]?.let { return invokeCallable(it, args.map { form -> translate(form, ctx) }) }
        fun s(i: Int) = scalar(args[i], ctx)
        fun all() = args.map { scalar(it, ctx) }
        fun arity(min: Int, max: Int = min) {
            if (args.size < min || args.size > max) throw Untranslatable("$head arity ${args.size}")
        }
        translateDateCall(head, args) { scalar(it, ctx) }?.let { return it }
        if (head == "prev" && defns[head] == null) {
            arity(2)
            val target = args[0].symbol?.removePrefix("mantra/")
                ?: throw Untranslatable("prev target must be a schema node")
            val prior = resolver.previous(target, ctx.dims, ctx.coord)
            return if (prior.first) translate(args[1], ctx) else prior.value ?: X.Nil
        }
        return when (head) {
            "+" -> if (args.isEmpty()) Ex.ZERO else Ex.chain("+", all(), Ex.ADD)
            "-" -> if (args.size == 1) Ex.neg(s(0)) else Ex.chain("-", all(), Ex.ADD)
            "*" -> if (args.isEmpty()) Ex.num(1) else Ex.chain("*", all(), Ex.MUL)
            "/" -> Ex.chain("/", all(), Ex.MUL)
            "inc" -> Ex.add(s(0), Ex.num(1))
            "dec" -> Ex.sub(s(0), Ex.num(1))
            "abs" -> Ex.fn("ABS", s(0))
            "min", "max" -> Ex.fn(head.uppercase(), args.flatMap { aggregateArgs(translate(it, ctx)) })
            "=", "not=", "<", "<=", ">", ">=" -> comparison(head, all())
            "pos?" -> Ex.cmp(">", s(0), Ex.ZERO)
            "neg?" -> Ex.cmp("<", s(0), Ex.ZERO)
            "zero?" -> Ex.cmp("=", s(0), Ex.ZERO)
            "some?" -> Ex.fn("NOT", isNil(s(0)), kind = XKind.BOOL)
            "nil?" -> isNil(s(0))
            "not" -> Ex.fn("NOT", truthy(s(0)), kind = XKind.BOOL)
            "and", "or" -> {
                var remaining = Ex.TRUE
                val values = args.map { argument ->
                    val value = toScalar(guarded(argument, ctx, remaining))
                    val continues = truthy(value).let { if (head == "or") not(it) else it }
                    remaining = Ex.iff(remaining, continues, Ex.FALSE)
                    value
                }
                if (head == "and") and(values) else or(values)
            }
            "if", "if-not" -> {
                arity(2, 3)
                val test = truthy(s(0)).let { if (head == "if-not") Ex.fn("NOT", it, kind = XKind.BOOL) else it }
                branches(
                    listOf(test to guarded(args[1], ctx, test)),
                    args.getOrNull(2)?.let { guarded(it, ctx, not(test)) },
                )
            }
            "when", "when-not" -> {
                val test = truthy(s(0)).let { if (head == "when-not") Ex.fn("NOT", it, kind = XKind.BOOL) else it }
                branches(listOf(test to guarded(args.last(), ctx, test)), null)
            }
            "cond" -> {
                val cases = mutableListOf<Pair<X.Scalar, X>>()
                var default: X? = null
                var remaining = Ex.TRUE
                args.chunked(2).forEach { pair ->
                    if (pair.size != 2) throw Untranslatable("cond without result")
                    val (test, value) = pair
                    if (test.keyword == "else" || test.symbol == "true") {
                        default = guarded(value, ctx, remaining)
                    } else if (default == null) {
                        val condition = truthy(toScalar(guarded(test, ctx, remaining)))
                        val selected = Ex.iff(remaining, condition, Ex.FALSE)
                        cases += condition to guarded(value, ctx, selected)
                        remaining = Ex.iff(remaining, not(condition), Ex.FALSE)
                    }
                }
                branches(cases, default)
            }
            "case" -> {
                val subject = s(0)
                val rest = args.drop(1)
                var remaining = Ex.TRUE
                val cases = rest.chunked(2).filter { it.size == 2 }.map { (key, value) ->
                    val condition = Ex.cmp("=", subject, scalar(key, ctx))
                    val selected = Ex.iff(remaining, condition, Ex.FALSE)
                    val translated = guarded(value, ctx, selected)
                    remaining = Ex.iff(remaining, not(condition), Ex.FALSE)
                    condition to translated
                }
                branches(cases, if (rest.size % 2 == 1) guarded(rest.last(), ctx, remaining) else null)
            }
            "let" -> {
                val bindings =
                    (args.firstOrNull() as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.VECTOR }?.values
                        ?: throw Untranslatable("let without bindings")
                if (bindings.size % 2 != 0) throw Untranslatable("odd let bindings")
                var local = ctx
                val errors = mutableListOf<X.Scalar>()
                bindings.chunked(2).forEach { pair ->
                    val name = pair[0].symbol ?: throw Untranslatable("destructuring let")
                    val preceding = excelOrderedErrors(errors, ::materialize)
                    val enabled = if (preceding == Ex.ZERO) {
                        Ex.TRUE
                    } else {
                        not(Ex.fn("ISERROR", preceding, kind = XKind.BOOL))
                    }
                    val value = withLazyGuard(enabled) {
                        val binding = translate(pair[1], local)
                        // Probe and body both read the eagerly computed binding. Hoist before
                        // duplicating a long scalar into ISERROR, under both the enclosing
                        // branch and preceding-binding success guard. Deferred producers stay
                        // deferred and no helper evaluates after an earlier binding fails.
                        if (binding is X.Scalar && binding.text.length > 500) materialize(binding) else binding
                    }
                    errors += eagerErrors(value)
                    local = local.with(mapOf(name to value))
                }
                val bindingErrors = excelOrderedErrors(errors, ::materialize)
                val enabled = if (bindingErrors == Ex.ZERO) {
                    Ex.TRUE
                } else {
                    not(Ex.fn("ISERROR", bindingErrors, kind = XKind.BOOL))
                }
                retainEagerErrors(bindingErrors, guarded(args.last(), local, enabled))
            }
            "fn" -> {
                val parameters = (args.firstOrNull() as? DslForm.Sequence)
                    ?.takeIf { it.kind == DslFormSequenceKind.VECTOR }?.values
                    ?.mapNotNull { it.symbol }?.filterNot { it.startsWith("^") }
                    ?: throw Untranslatable("fn requires named parameters")
                val body = args.drop(1).singleOrNull() ?: throw Untranslatable("fn requires one expression")
                X.Callable { values ->
                    if (ctx.depth > 24) throw Untranslatable("lexical function recursion")
                    if (parameters.size != values.size) throw Untranslatable("lexical function arity")
                    translate(body, ctx.closure(parameters.zip(values).toMap()))
                }
            }
            "decimal/round" -> {
                val mode =
                    args.getOrNull(2)?.keyword?.let { roundingMode(it) ?: throw Untranslatable("rounding mode $it") }
                        ?: RoundingMode.HALF_UP
                Ex.round(s(0), args.getOrNull(1)?.let { scalar(it, ctx) } ?: Ex.ZERO, mode)
            }
            "decimal/floor" -> Ex.round(s(0), args.getOrNull(1)?.let { scalar(it, ctx) } ?: Ex.ZERO, RoundingMode.FLOOR)
            "decimal/ceil" -> Ex.round(
                s(0),
                args.getOrNull(1)?.let {
                    scalar(it, ctx)
                } ?: Ex.ZERO,
                RoundingMode.CEILING,
            )
            "decimal/truncate" -> Ex.fn("TRUNC", s(0), args.getOrNull(1)?.let { scalar(it, ctx) } ?: Ex.ZERO)
            "decimal/clamp" -> Ex.fn("MIN", Ex.fn("MAX", s(0), s(1)), s(2))
            "decimal/divide" -> {
                val quotient = Ex.div(s(0), s(1))
                val value = if (args.size >= 3) {
                    val mode =
                        args.getOrNull(3)?.keyword?.let {
                            roundingMode(it) ?: throw Untranslatable("rounding mode $it")
                        }
                            ?: RoundingMode.HALF_UP
                    Ex.round(quotient, s(2), mode)
                } else {
                    quotient
                }
                Ex.iff(Ex.cmp("=", s(1), Ex.ZERO), Ex.EMPTY, value)
            }
            "dim/sum" -> Ex.fn("SUM", dimensionSumArgs(translate(args[0], ctx)).ifEmpty { listOf(Ex.ZERO) })
            "sum" -> {
                arity(1)
                standardSum(translate(args[0], ctx), args[0], ::materialize, resolver::chargeScans)
            }
            "map" -> {
                arity(2)
                val function = translate(args[0], ctx)
                val source = translate(args[1], ctx) as? X.Vec ?: throw Untranslatable("map source must be a vector")
                X.Vec(
                    DeferredExcelItems(source.items.withIndex().toList()) { indexed ->
                        val present = source.presence?.get(indexed.index)
                        if (present == null) {
                            invokeCallable(function, listOf(indexed.value))
                        } else {
                            val value = withLazyGuard(present) { invokeCallable(function, listOf(indexed.value)) }
                            X.Branches(listOf(present to value, null to X.Nil))
                        }
                    },
                    excelOrderedErrors(listOf(eagerErrors(function), eagerErrors(source)), ::materialize),
                    deferred = true,
                    presence = source.presence,
                )
            }
            "vec" -> {
                arity(1)
                when (val source = translate(args[0], ctx)) {
                    is X.Vec -> X.Vec(source.items.toList(), source.creationErrors, presence = source.presence)
                    X.Nil -> X.Vec(emptyList())
                    else -> throw Untranslatable("vec cannot faithfully materialize ${source::class.simpleName}")
                }
            }
            "dim/rollup" -> {
                arity(3, 5)
                if (args.size != 3 && args.size != 5) throw Untranslatable("dim/rollup arity")
                rollup(
                    translate(args[0], ctx),
                    translate(args[1], ctx),
                    s(2),
                    args.getOrNull(3)?.keyword,
                    args.getOrNull(4)?.let { translate(it, ctx) },
                )
            }
            "dim/min" -> dimensionExtreme(translate(args[0], ctx), "MIN")
            "dim/max" -> dimensionExtreme(translate(args[0], ctx), "MAX")
            "vals" -> when (val m = translate(args[0], ctx)) {
                is X.Range -> X.Vec(m.cells)
                is X.MapX -> X.Vec(m.values, presence = m.liveKeys?.map { Ex.cmp("<>", it, Ex.EMPTY) })
                else -> throw Untranslatable("vals of ${m::class.simpleName}")
            }
            "count" -> {
                val value = translate(args[0], ctx)
                val length = when (value) {
                    is X.Range -> value.keys.size
                    is X.Vec -> value.items.size
                    is X.MapX -> value.keys.size
                    else -> throw Untranslatable("count")
                }
                val count = when {
                    value is X.Vec && value.presence != null -> Ex.fn(
                        "SUM",
                        value.presence.map {
                            Ex.iff(it, Ex.num(1), Ex.ZERO)
                        },
                    )
                    value is X.MapX && value.liveKeys != null -> Ex.fn(
                        "SUM",
                        value.liveKeys.map {
                            Ex.iff(Ex.cmp("<>", it, Ex.EMPTY), Ex.num(1), Ex.ZERO)
                        },
                    )
                    else -> Ex.num(length.toLong())
                }
                retainEagerErrors(eagerErrors(value, forceSequence = true), count)
            }
            "apply" -> {
                val fnName = args.firstOrNull()?.symbol ?: throw Untranslatable("apply of computed function")
                val items = args.drop(1).flatMap { aggregateArgs(translate(it, ctx)) }
                when (fnName) {
                    "min" -> Ex.fn("MIN", items)
                    "max" -> Ex.fn("MAX", items)
                    "+" -> Ex.fn("SUM", items)
                    else -> throw Untranslatable("apply $fnName")
                }
            }
            "get" -> get(args, ctx)
            "nth" -> nth(
                translate(args[0], ctx),
                args[1].number?.intValueExact() ?: throw Untranslatable("nth with computed index"),
                args.getOrNull(2)?.let {
                    translate(it, ctx)
                },
            )
            "calc/converge" -> {
                arity(4)
                resolver.requireDynamicFormula()
                val function = translate(args[0], ctx)
                val initial = s(1)
                val maximum = s(2)
                val tolerance = s(3)
                val creationErrors = eagerErrors(function)
                val selected = lazyMaterializationGuard ?: Ex.TRUE
                val enabled = if (creationErrors == Ex.ZERO) {
                    selected
                } else {
                    Ex.iff(selected, not(Ex.fn("ISERROR", creationErrors, kind = XKind.BOOL)), Ex.FALSE)
                }
                val unfolded = resolver.convergence(initial, maximum, tolerance, enabled) { previous, run ->
                    withLazyGuard(run) { toScalar(invokeCallable(function, listOf(previous))) }
                }
                retainEagerErrors(creationErrors, unfolded)
            }
            "calc/stepwise" -> stepwise(s(0), translate(args[1], ctx))
            "alloc/pro-rata" -> proRata(s(0), translate(args[1], ctx), s(2))
            "alloc/capped" -> {
                fun memberMap(form: DslForm): X.MapX = when (val value = translate(form, ctx)) {
                    is X.MapX -> value
                    is X.Range -> X.MapX(value.keys, value.cells)
                    else -> throw Untranslatable("alloc/capped needs member maps")
                }
                cappedAllocation(s(0), memberMap(args[1]), memberMap(args[2]), s(3), ::materialize)
            }
            "alloc/waterfall" -> waterfall(s(0), translate(args[1], ctx))
            "table/band" -> band(s(0), translate(args[1], ctx), args.getOrNull(2)?.let { scalar(it, ctx) })
            "fin/npv" -> {
                // Mapped lets retain construction errors before reaching this consumer. Keep
                // those complete cash flows in live cells rather than duplicating their guards
                // across one overlong NPV; materialize also honors the enclosing lazy IF/run.
                val flows = aggregateArgs(translate(args[1], ctx)).map { flow ->
                    if (flow.text.length > 500) materialize(flow) else flow
                }
                Ex.round(Ex.fn("NPV", listOf(s(0)) + flows), s(2), RoundingMode.HALF_UP)
            }
            "fin/df" -> Ex.round(
                Ex.div(Ex.num(1), Ex.bin("^", Ex.add(Ex.num(1), s(0)), s(1), Ex.POW)),
                s(2),
                RoundingMode.HALF_UP,
            )
            "fin/pmt" -> Ex.round(Ex.fn("PMT", s(0), s(1), Ex.neg(s(2))), s(3), RoundingMode.HALF_UP)
            "str" -> Ex.chain("&", all(), Ex.CONCAT, XKind.TEXT)
            else -> {
                val defn = defns[head] ?: defns[head.removePrefix("profile/")] ?: throw Untranslatable("function $head")
                if (ctx.depth > 24) throw Untranslatable("recursion in $head")
                if (defn.params.size != args.size) throw Untranslatable("$head arity")
                val values = args.map { translate(it, ctx) }
                translate(defn.body, ctx.call(defn.params.zip(values).toMap()))
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    fun toScalar(x: X): X.Scalar = when (x) {
        is X.Scalar -> x
        X.Nil -> Ex.EMPTY
        is X.Branches -> {
            val scalars = x.cases.map { (c, v) -> c to toScalar(v) }
            val default = scalars.lastOrNull { it.first == null }?.second
            val conditional = scalars.filter { it.first != null }
            conditional.foldRight(
                default ?: Ex.EMPTY,
            ) { (c, v), acc -> Ex.iff(c!!, v, acc) }
        }
        is X.Range -> throw Untranslatable("member map used as a single value")
        is X.Vec, is X.MapX -> throw Untranslatable("collection used as a single value")
        is X.Callable -> throw Untranslatable("function used as a single value")
    }

    private fun invokeCallable(function: X, values: List<X>): X {
        val callable = function as? X.Callable ?: throw Untranslatable("local value is not callable")
        return retainEagerErrors(eagerErrors(callable), callable.invoke(values))
    }

    private fun branches(cases: List<Pair<X.Scalar, X>>, default: X?): X {
        val all =
            cases.map { it.first as X.Scalar? to it.second } + listOfNotNull(default?.let { null as X.Scalar? to it })
        return if (all.all {
                it.second is X.Scalar || it.second == X.Nil
            }
        ) {
            toScalar(X.Branches(all))
        } else {
            X.Branches(all)
        }
    }

    /** Clojure truthiness: only nil and false are falsey. */
    private fun isNil(x: X.Scalar): X.Scalar = when {
        Ex.isTextLiteral(x) -> Ex.FALSE
        x.kind == XKind.DATE || x.numericOrNil -> Ex.cmp("=", x, Ex.EMPTY)
        x.booleanOrNil -> Ex.cmp("=", x, Ex.EMPTY)
        else -> throw Untranslatable("nil test cannot distinguish nullable text from empty text")
    }

    fun truthy(x: X.Scalar): X.Scalar = when {
        Ex.isTextLiteral(x) -> Ex.TRUE
        x.booleanOrNil -> Ex.iff(Ex.cmp("=", x, Ex.EMPTY), Ex.FALSE, x)
        x.numericOrNil || x.kind == XKind.DATE -> Ex.cmp("<>", x, Ex.EMPTY)
        else -> throw Untranslatable("truthiness cannot distinguish nullable text from empty text")
    }

    private fun and(items: List<X.Scalar>): X.Scalar = if (items.isEmpty()) {
        Ex.TRUE
    } else {
        items.dropLast(1).foldRight(items.last()) { item, acc -> Ex.iff(truthy(item), acc, item) }
    }

    private fun or(items: List<X.Scalar>): X.Scalar = items.foldRight(Ex.EMPTY) { item, acc ->
        Ex.iff(truthy(item), item, acc)
    }

    private fun comparison(op: String, items: List<X.Scalar>): X.Scalar {
        val excel = when (op) {
            "not=" -> "<>"
            else -> op
        }
        if (items.size == 2) return Ex.cmp(excel, items[0], items[1])
        return Ex.fn("AND", items.zipWithNext { a, b -> Ex.cmp(excel, a, b) }, kind = XKind.BOOL)
    }

    private fun aggregateArgs(x: X): List<X.Scalar> = when (x) {
        is X.Range -> listOf(Ex.atom(x.text))
        is X.Vec -> {
            val values = x.items.flatMap(::aggregateArgs)
            val errors = x.creationErrors ?: Ex.ZERO
            if (values.isEmpty() && errors != Ex.ZERO) {
                listOf(errors)
            } else {
                values.map {
                    toScalar(retainEagerErrors(errors, it))
                }
            }
        }
        is X.MapX -> x.values.flatMap(::aggregateArgs)
        else -> listOf(toScalar(x))
    }

    private fun dimensionSumArgs(x: X): List<X.Scalar> = when (x) {
        is X.Range -> listOf(Ex.atom(x.text))
        is X.Vec -> {
            val values = x.items.flatMap(::dimensionSumArgs)
            val errors = x.creationErrors ?: Ex.ZERO
            if (values.isEmpty() && errors != Ex.ZERO) {
                listOf(errors)
            } else {
                values.map {
                    toScalar(retainEagerErrors(errors, it))
                }
            }
        }
        is X.MapX -> x.values.flatMap(::dimensionSumArgs)
        X.Nil -> listOf(Ex.ZERO)
        is X.Scalar -> listOf(Ex.iff(Ex.cmp("=", x, Ex.EMPTY), Ex.ZERO, x))
        else -> throw Untranslatable("dim/sum source is not a numeric collection")
    }

    /** Real member identities, never the virtual slot strings, select dynamic map values. */
    internal fun mapLookup(map: X.MapX, key: X.Scalar, default: X = X.Nil): X {
        val literal = key.text.takeIf { Ex.isTextLiteral(key) }?.removeSurrounding("\"")?.replace("\"\"", "\"")
        if (map.liveKeys == null && literal != null) return map.values.getOrNull(map.keys.indexOf(literal)) ?: default
        val identities = map.liveKeys ?: map.keys.map(Ex::text)
        val branches = X.Branches(
            identities.mapIndexed { index, candidate ->
                Ex.fn("AND", Ex.cmp("<>", candidate, Ex.EMPTY), Ex.cmp("=", candidate, key), kind = XKind.BOOL) to
                    map.values[index]
            } + listOf(null to default),
        )
        return if (branches.cases.all { it.second is X.Scalar || it.second == X.Nil }) toScalar(branches) else branches
    }

    private fun dimensionExtreme(value: X, operation: String): X.Scalar {
        fun leaves(source: X): List<X.Scalar> = when (source) {
            X.Nil -> emptyList()
            is X.Range -> source.cells
            is X.MapX -> source.values.flatMap(::leaves)
            is X.Vec -> source.items.flatMap(::leaves)
            is X.Scalar -> listOf(source)
            is X.Branches -> listOf(toScalar(source))
            else -> throw Untranslatable("$operation needs a numeric member collection")
        }
        val candidates = leaves(value).map { candidate ->
            // Cell arguments let MIN/MAX ignore nonnumeric/absent values; errors remain errors.
            materialize(
                Ex.iff(
                    Ex.fn("ISERROR", candidate, kind = XKind.BOOL),
                    candidate,
                    Ex.iff(Ex.fn("ISNUMBER", candidate, kind = XKind.BOOL), candidate, Ex.EMPTY),
                ),
            )
        }
        if (candidates.isEmpty()) return Ex.EMPTY
        fun extreme(items: List<X.Scalar>): X.Scalar {
            val chunks = if (items.size > 200) items.chunked(200).map { materialize(extreme(it)) } else items
            val count = Ex.fn("COUNT", chunks)
            return Ex.iff(Ex.cmp("=", count, Ex.ZERO), Ex.EMPTY, Ex.fn(operation, chunks))
        }
        // Preserve an error even when every candidate is absent or nonnumeric.
        return retainEagerErrors(eagerErrors(value, forceSequence = true), extreme(candidates)).let(::toScalar)
    }

    private fun get(args: List<DslForm>, ctx: Ctx): X {
        val key = args[1].keyword?.let(Ex::text) ?: args[1].string?.let(Ex::text) ?: scalar(args[1], ctx)
        val default = args.getOrNull(2)?.let { translate(it, ctx) }
        fun pick(m: X): X = when (m) {
            is X.Range -> mapLookup(X.MapX(m.keys, m.cells), key, default ?: X.Nil)
            is X.MapX -> mapLookup(m, key, default ?: X.Nil)
            is X.Branches -> X.Branches(m.cases.map { (c, v) -> c to pick(v) }).let {
                if (it.cases.all { case -> case.second is X.Scalar || case.second == X.Nil }) toScalar(it) else it
            }
            else -> throw Untranslatable("get on ${m::class.simpleName}")
        }
        return pick(translate(args[0], ctx))
    }

    private fun nth(v: X, index: Int, default: X?): X = when (v) {
        is X.Vec -> {
            val selected = v.items.getOrNull(index) ?: default ?: throw Untranslatable("nth out of range")
            val errors = if (v.deferred) {
                excelOrderedErrors(
                    listOf(v.creationErrors ?: Ex.ZERO) + v.items.take(index + 1).map { eagerErrors(it) },
                    ::materialize,
                )
            } else {
                eagerErrors(v)
            }
            retainEagerErrors(errors, selected)
        }
        is X.Range -> v.cells.getOrNull(index) ?: default ?: throw Untranslatable("nth out of range")
        is X.Branches -> X.Branches(v.cases.map { (c, value) -> c to nth(value, index, default) }).let {
            if (it.cases.all { case -> case.second is X.Scalar || case.second == X.Nil }) toScalar(it) else it
        }
        else -> throw Untranslatable("nth on ${v::class.simpleName}")
    }

    private fun stepwise(amount: X.Scalar, bands: X): X.Scalar = when (bands) {
        is X.Branches -> toScalar(X.Branches(bands.cases.map { (c, v) -> c to stepwise(amount, v) }))
        is X.Vec -> {
            var lower: X.Scalar = Ex.ZERO
            val terms = bands.items.map { band ->
                val pair = (band as? X.Vec)?.items ?: throw Untranslatable("stepwise band shape")
                if (pair.size != 2) throw Untranslatable("stepwise band size")
                val rate = toScalar(pair[1])
                val upper = pair[0]
                val top = if (upper == X.Nil) amount else Ex.fn("MIN", amount, toScalar(upper))
                val slice = Ex.fn("MAX", Ex.ZERO, if (lower.text == "0") top else Ex.sub(top, lower))
                if (upper != X.Nil) lower = toScalar(upper)
                Ex.mul(slice, rate)
            }
            Ex.chain("+", terms, Ex.ADD)
        }
        else -> throw Untranslatable("stepwise bands")
    }

    private fun keyedValues(x: X, what: String): Pair<List<String>, List<X.Scalar>> = when (x) {
        is X.Range -> x.keys to x.cells
        is X.MapX -> x.keys to x.values.map(::toScalar)
        else -> throw Untranslatable("$what needs a member map")
    }

    private fun rollup(
        values: X,
        relation: X,
        target: X.Scalar,
        boundary: String? = null,
        ordered: X? = null,
    ): X.Scalar {
        fun map(source: X): X.MapX = when (source) {
            is X.MapX -> source
            is X.Range -> X.MapX(source.keys, source.cells)
            else -> throw Untranslatable("dim/rollup needs member maps")
        }
        val source = map(values)
        val parents = map(relation)
        val identities = source.liveKeys ?: source.keys.map(Ex::text)
        val relationIdentities = parents.liveKeys ?: parents.keys.map(Ex::text)
        fun parent(key: X.Scalar) = toScalar(mapLookup(parents, key))
        val related = identities.map { key ->
            Ex.iff(Ex.cmp("=", key, Ex.EMPTY), Ex.TRUE, Ex.cmp("<>", parent(key), Ex.EMPTY))
        }
        var valid = if (related.isEmpty()) Ex.TRUE else Ex.fn("AND", related, XKind.BOOL)
        val reduced = if (boundary == null) {
            val terms = identities.mapIndexed { index, key ->
                Ex.iff(
                    Ex.fn("AND", Ex.cmp("<>", key, Ex.EMPTY), Ex.cmp("=", parent(key), target), kind = XKind.BOOL),
                    toScalar(source.values[index]),
                    Ex.ZERO,
                )
            }
            Ex.fn("SUM", terms.ifEmpty { listOf(Ex.ZERO) })
        } else {
            if (boundary != "first" && boundary != "last") throw Untranslatable("dim/rollup boundary $boundary")
            val sequence = (ordered as? X.Vec)?.items ?: throw Untranslatable("dim/rollup needs ordered keys")
            val keys = sequence.map(::toScalar)
            if (keys.any { !Ex.isTextLiteral(it) } || keys.distinct().size != keys.size) {
                throw Untranslatable("dim/rollup order must be a unique declared keyword sequence")
            }
            val inOrder = relationIdentities.map { key ->
                Ex.iff(
                    Ex.cmp("=", key, Ex.EMPTY),
                    Ex.TRUE,
                    if (keys.isEmpty()) Ex.FALSE else Ex.fn("OR", keys.map { Ex.cmp("=", it, key) }, XKind.BOOL),
                )
            }
            valid = Ex.fn("AND", listOf(valid) + inOrder, XKind.BOOL)
            val candidates = if (boundary == "first") keys else keys.reversed()
            candidates.foldRight(Ex.ZERO) { key, rest ->
                val value = toScalar(mapLookup(source, key))
                val selected = Ex.iff(Ex.fn("ISNUMBER", value, kind = XKind.BOOL), value, Ex.EMPTY)
                val result = Ex.iff(Ex.cmp("=", parent(key), target), selected, rest)
                if (result.text.length > 1000) materialize(result) else result
            }
        }
        return Ex.iff(valid, reduced, Ex.fn("NA"))
    }

    /**
     * Largest-remainder allocation as plain formulas: every key gets the floored share plus one unit
     * when its remainder ranks within the units still missing to the rounded amount (ties by order).
     */
    private fun proRata(amount: X.Scalar, weights: X, scale: X.Scalar): X {
        val (keys, w) = keyedValues(weights, "alloc/pro-rata")
        if (keys.isEmpty()) return X.MapX(emptyList(), emptyList())
        if (keys.size > 12) throw Untranslatable("alloc/pro-rata with more than 12 members")
        val liveKeys = (weights as? X.MapX)?.liveKeys
        val present = keys.indices.map { index -> liveKeys?.get(index)?.let { Ex.cmp("<>", it, Ex.EMPTY) } ?: Ex.TRUE }
        val amounts = w.mapIndexed { index, value ->
            Ex.iff(present[index], Ex.iff(Ex.cmp("=", value, Ex.EMPTY), Ex.ZERO, value), Ex.ZERO)
        }
        val total = materialize(Ex.fn("SUM", amounts))
        val factor = materialize(Ex.pow10(scale))
        val scaled = scale.text != "0"
        val quotas = keys.indices.map { index ->
            val quotient = Ex.div(Ex.mul(amount, amounts[index]), total)
            materialize(if (scaled) Ex.mul(quotient, factor) else quotient)
        }
        val floors = quotas.map { materialize(Ex.fn("INT", it)) }
        val remainders = keys.indices.map { materialize(Ex.sub(quotas[it], floors[it])) }
        val units = materialize(
            Ex.sub(
                Ex.fn("ROUND", if (scaled) Ex.mul(amount, factor) else amount, Ex.ZERO),
                Ex.fn("SUM", floors),
            ),
        )
        val values = keys.indices.map { index ->
            val ahead = keys.indices.filter { it != index }.map { other ->
                Ex.iff(
                    present[other],
                    Ex.cmp(
                        if (other < index) ">=" else ">",
                        remainders[other],
                        remainders[index],
                    ),
                    Ex.FALSE,
                )
            }
            val rank = materialize(Ex.chain("+", listOf(Ex.num(1)) + ahead, Ex.ADD))
            val value = Ex.add(floors[index], Ex.fn("IF", Ex.cmp("<=", rank, units), Ex.num(1), Ex.ZERO))
            Ex.iff(present[index], if (scaled) Ex.div(value, factor) else value, Ex.EMPTY)
        }
        return X.MapX(keys, values, liveKeys)
    }

    /** Sequential allocation: each key receives min(cap, what is left after the keys before it). */
    private fun waterfall(amount: X.Scalar, caps: X): X {
        val map = when (caps) {
            is X.MapX -> caps
            is X.Range -> X.MapX(caps.keys, caps.cells)
            else -> throw Untranslatable("alloc/waterfall needs a member map")
        }
        var remaining = Ex.fn("MAX", Ex.ZERO, amount)
        val values = map.keys.indices.map { index ->
            val present = map.liveKeys?.get(index)?.let { Ex.cmp("<>", it, Ex.EMPTY) } ?: Ex.TRUE
            val cap = toScalar(map.values[index])
            val share = Ex.iff(
                Ex.cmp("=", cap, Ex.EMPTY),
                remaining,
                Ex.fn("MIN", remaining, Ex.fn("MAX", Ex.ZERO, cap)),
            )
            val value = materialize(Ex.iff(present, share, Ex.EMPTY))
            remaining = materialize(Ex.fn("MAX", Ex.ZERO, Ex.sub(remaining, Ex.iff(present, value, Ex.ZERO))))
            value
        }
        return X.MapX(map.keys, values, map.liveKeys)
    }

    /** Value of the last band whose threshold is not above x (thresholds ascending). */
    private fun band(x: X.Scalar, table: X, default: X.Scalar?): X.Scalar {
        val rows =
            (table as? X.Vec)?.items?.map { row ->
                (row as? X.Vec)?.items
                    ?: throw Untranslatable("table/band row shape")
            }
                ?: throw Untranslatable("table/band table must be a literal")
        return rows.fold(default ?: Ex.EMPTY) { acc, row ->
            Ex.iff(Ex.cmp(">=", x, toScalar(row[0])), toScalar(row[1]), acc)
        }
    }
}
