package com.xqiou.mantra.excel

import com.xqiou.mantra.core.engine.Coord
import com.xqiou.mantra.core.model.FunctionDecl
import com.xqiou.mantra.core.read.keyword
import com.xqiou.mantra.core.read.listHead
import com.xqiou.mantra.core.read.number
import com.xqiou.mantra.core.read.roundingMode
import com.xqiou.mantra.core.read.string
import com.xqiou.mantra.core.read.symbol
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
     * fixed by the context, otherwise the node's member cells as an [X.Range]. Literal parameters
     * (vectors, maps) are returned as values.
     */
    fun reference(nodeId: String, contextDims: List<String>, contextCoord: Coord): X?

    /** Field of the current member record of a dimension (`cgu.carrying-amount`). */
    fun record(dim: String, key: String, field: String): X?

    fun isNode(nodeId: String): Boolean

    fun isDimension(name: String): Boolean
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
    }

    private class Defn(val params: List<String>, val body: DslForm)

    private val defns: Map<String, Defn> = functions.mapNotNull { decl ->
        val root = (DslFormReader().readDocument(decl.source) as? DslFormReadResult.Success)?.document?.root as? DslForm.Sequence
            ?: return@mapNotNull null
        val params = (root.values.getOrNull(2) as? DslForm.Sequence)?.values?.mapNotNull { it.symbol }?.filterNot { it.startsWith("^") }
            ?: return@mapNotNull null
        val body = root.values.drop(3).lastOrNull { it.string == null } ?: return@mapNotNull null
        decl.name to Defn(params, body)
    }.toMap()

    fun scalar(form: DslForm, ctx: Ctx): X.Scalar = toScalar(translate(form, ctx))

    fun translate(form: DslForm, ctx: Ctx): X {
        form.number?.let { return Ex.num(it) }
        form.string?.let { return Ex.text(it) }
        form.keyword?.let { return Ex.text(it) }
        form.symbol?.let { return symbol(it, ctx) }
        return when (form) {
            is DslForm.Postfix -> {
                val target = form.target.symbol ?: throw Untranslatable("unsupported postfix target")
                val path = form.suffixes.map { (it as? DslFormPostfix.Member)?.value ?: throw Untranslatable("bracket access") }
                symbol((listOf(target) + path).joinToString("."), ctx)
            }
            is DslForm.Sequence -> when (form.kind) {
                DslFormSequenceKind.VECTOR -> X.Vec(form.values.map { translate(it, ctx) })
                DslFormSequenceKind.MAP -> {
                    if (form.values.size % 2 != 0) throw Untranslatable("odd map literal")
                    val pairs = form.values.chunked(2)
                    X.MapX(pairs.map { (k, _) -> k.keyword ?: k.string ?: throw Untranslatable("map keys must be keywords") }, pairs.map { (_, v) -> translate(v, ctx) })
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
                if (record is X.MapX) return record.values.getOrNull(record.keys.indexOf(field))
                    ?: throw Untranslatable("$head has no field $field")
                throw Untranslatable("field access on non-record $head")
            }
            if (head == "all") return resolver.reference(field, emptyList(), emptyList()) ?: throw Untranslatable("all.$field")
            if (head in ctx.dims) {
                val key = ctx.coord[ctx.dims.indexOf(head)]
                return resolver.record(head, key, field) ?: throw Untranslatable("$head.$field")
            }
            throw Untranslatable("field access $name")
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
        val head = form.listHead ?: throw Untranslatable("computed call head")
        val args = args(form)
        fun s(i: Int) = scalar(args[i], ctx)
        fun all() = args.map { scalar(it, ctx) }
        fun arity(min: Int, max: Int = min) {
            if (args.size < min || args.size > max) throw Untranslatable("$head arity ${args.size}")
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
            "some?" -> Ex.cmp("<>", s(0), Ex.EMPTY)
            "nil?" -> Ex.cmp("=", s(0), Ex.EMPTY)
            "not" -> Ex.fn("NOT", truthy(s(0)), kind = XKind.BOOL)
            "and" -> Ex.fn("AND", all().map(::truthy), kind = XKind.BOOL)
            "or" -> or(all())
            "if", "if-not" -> {
                arity(2, 3)
                val test = truthy(s(0)).let { if (head == "if-not") Ex.fn("NOT", it, kind = XKind.BOOL) else it }
                branches(listOf(test to translate(args[1], ctx)), args.getOrNull(2)?.let { translate(it, ctx) })
            }
            "when", "when-not" -> {
                val test = truthy(s(0)).let { if (head == "when-not") Ex.fn("NOT", it, kind = XKind.BOOL) else it }
                branches(listOf(test to translate(args.last(), ctx)), null)
            }
            "cond" -> {
                val cases = mutableListOf<Pair<X.Scalar, X>>()
                var default: X? = null
                args.chunked(2).forEach { pair ->
                    if (pair.size != 2) throw Untranslatable("cond without result")
                    val (test, value) = pair
                    if (test.keyword == "else" || test.symbol == "true") {
                        default = translate(value, ctx)
                    } else if (default == null) {
                        cases += truthy(scalar(test, ctx)) to translate(value, ctx)
                    }
                }
                branches(cases, default)
            }
            "case" -> {
                val subject = s(0)
                val rest = args.drop(1)
                val cases = rest.chunked(2).filter { it.size == 2 }.map { (key, value) -> Ex.cmp("=", subject, scalar(key, ctx)) to translate(value, ctx) }
                branches(cases, if (rest.size % 2 == 1) translate(rest.last(), ctx) else null)
            }
            "let" -> {
                val bindings = (args.firstOrNull() as? DslForm.Sequence)?.takeIf { it.kind == DslFormSequenceKind.VECTOR }?.values
                    ?: throw Untranslatable("let without bindings")
                var local = ctx
                bindings.chunked(2).forEach { pair ->
                    val name = pair[0].symbol ?: throw Untranslatable("destructuring let")
                    local = local.with(mapOf(name to translate(pair[1], local)))
                }
                translate(args.last(), local)
            }
            "decimal/round" -> {
                val mode = args.getOrNull(2)?.keyword?.let { roundingMode(it) ?: throw Untranslatable("rounding mode $it") } ?: RoundingMode.HALF_UP
                Ex.round(s(0), args.getOrNull(1)?.let { scalar(it, ctx) } ?: Ex.ZERO, mode)
            }
            "decimal/floor" -> Ex.round(s(0), args.getOrNull(1)?.let { scalar(it, ctx) } ?: Ex.ZERO, RoundingMode.FLOOR)
            "decimal/ceil" -> Ex.round(s(0), args.getOrNull(1)?.let { scalar(it, ctx) } ?: Ex.ZERO, RoundingMode.CEILING)
            "decimal/truncate" -> Ex.fn("TRUNC", s(0), args.getOrNull(1)?.let { scalar(it, ctx) } ?: Ex.ZERO)
            "decimal/clamp" -> Ex.fn("MIN", Ex.fn("MAX", s(0), s(1)), s(2))
            "decimal/divide" -> {
                val quotient = Ex.div(s(0), s(1))
                val value = if (args.size >= 3) {
                    val mode = args.getOrNull(3)?.keyword?.let { roundingMode(it) ?: throw Untranslatable("rounding mode $it") } ?: RoundingMode.HALF_UP
                    Ex.round(quotient, s(2), mode)
                } else {
                    quotient
                }
                Ex.iff(Ex.cmp("=", s(1), Ex.ZERO), Ex.ZERO, value)
            }
            "dim/sum", "sum" -> Ex.fn("SUM", aggregateArgs(translate(args[0], ctx)).ifEmpty { listOf(Ex.ZERO) })
            "map" -> {
                arity(2)
                val fn = args[0] as? DslForm.Sequence
                    ?: throw Untranslatable("map requires fn")
                if (fn.kind != DslFormSequenceKind.LIST || fn.listHead != "fn") throw Untranslatable("map requires fn")
                val binding = (fn.values.getOrNull(1) as? DslForm.Sequence)
                    ?.takeIf { it.kind == DslFormSequenceKind.VECTOR }?.values
                    ?.singleOrNull()?.symbol ?: throw Untranslatable("map requires one named parameter")
                val body = fn.values.drop(2).singleOrNull() ?: throw Untranslatable("map requires one expression")
                val source = translate(args[1], ctx) as? X.Vec ?: throw Untranslatable("map source must be a vector")
                X.Vec(source.items.map { item -> translate(body, ctx.with(mapOf(binding to item))) })
            }
            "dim/rollup" -> rollup(translate(args[0], ctx), translate(args[1], ctx), s(2))
            "dim/min" -> Ex.fn("MIN", aggregateArgs(translate(args[0], ctx)))
            "dim/max" -> Ex.fn("MAX", aggregateArgs(translate(args[0], ctx)))
            "vals" -> when (val m = translate(args[0], ctx)) {
                is X.Range -> m
                is X.MapX -> X.Vec(m.values)
                else -> throw Untranslatable("vals of ${m::class.simpleName}")
            }
            "count" -> when (val c = translate(args[0], ctx)) {
                is X.Range -> Ex.num(c.keys.size.toLong())
                is X.Vec -> Ex.num(c.items.size.toLong())
                is X.MapX -> Ex.num(c.keys.size.toLong())
                else -> throw Untranslatable("count")
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
            "nth" -> nth(translate(args[0], ctx), args[1].number?.intValueExact() ?: throw Untranslatable("nth with computed index"), args.getOrNull(2)?.let { translate(it, ctx) })
            "calc/stepwise" -> stepwise(s(0), translate(args[1], ctx))
            "alloc/pro-rata" -> proRata(s(0), translate(args[1], ctx), s(2))
            "alloc/waterfall" -> waterfall(s(0), translate(args[1], ctx))
            "table/band" -> band(s(0), translate(args[1], ctx), args.getOrNull(2)?.let { scalar(it, ctx) })
            "fin/npv" -> Ex.round(Ex.fn("NPV", listOf(s(0)) + aggregateArgs(translate(args[1], ctx))), s(2), RoundingMode.HALF_UP)
            "fin/df" -> Ex.round(Ex.div(Ex.num(1), Ex.bin("^", Ex.add(Ex.num(1), s(0)), s(1), Ex.POW)), s(2), RoundingMode.HALF_UP)
            "fin/pmt" -> Ex.round(Ex.fn("PMT", s(0), s(1), Ex.neg(s(2))), s(3), RoundingMode.HALF_UP)
            "str" -> Ex.chain("&", all(), Ex.CONCAT, XKind.TEXT)
            else -> {
                val defn = defns[head] ?: defns[head.removePrefix("profile/")] ?: throw Untranslatable("function $head")
                if (ctx.depth > 24) throw Untranslatable("recursion in $head")
                if (defn.params.size != args.size) throw Untranslatable("$head arity")
                translate(defn.body, ctx.call(defn.params.zip(args.map { translate(it, ctx) }).toMap()))
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
            conditional.foldRight(default ?: neutralFor(conditional.map { it.second })) { (c, v), acc -> Ex.iff(c!!, v, acc) }
        }
        is X.Range -> throw Untranslatable("member map used as a single value")
        is X.Vec, is X.MapX -> throw Untranslatable("collection used as a single value")
    }

    private fun neutralFor(values: List<X.Scalar>): X.Scalar =
        if (values.isNotEmpty() && values.all { it.kind == XKind.NUM }) Ex.ZERO else Ex.EMPTY

    private fun branches(cases: List<Pair<X.Scalar, X>>, default: X?): X {
        val all = cases.map { it.first as X.Scalar? to it.second } + listOfNotNull(default?.let { null as X.Scalar? to it })
        return if (all.all { it.second is X.Scalar || it.second == X.Nil }) toScalar(X.Branches(all)) else X.Branches(all)
    }

    /** Clojure truthiness: only nil and false are falsey. */
    fun truthy(x: X.Scalar): X.Scalar = if (x.kind == XKind.BOOL) x else Ex.cmp("<>", x, Ex.EMPTY)

    private fun or(items: List<X.Scalar>): X.Scalar =
        if (items.all { it.kind == XKind.BOOL }) {
            Ex.fn("OR", items, kind = XKind.BOOL)
        } else {
            items.dropLast(1).foldRight(items.last()) { item, acc -> Ex.iff(truthy(item), item, acc) }
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
        is X.Vec -> x.items.flatMap(::aggregateArgs)
        is X.MapX -> x.values.flatMap(::aggregateArgs)
        else -> listOf(toScalar(x))
    }

    private fun get(args: List<DslForm>, ctx: Ctx): X {
        val key = args[1].keyword ?: args[1].string ?: throw Untranslatable("get with computed key")
        val default = args.getOrNull(2)?.let { translate(it, ctx) }
        fun pick(m: X): X = when (m) {
            is X.Range -> m.cells.getOrNull(m.keys.indexOf(key)) ?: default ?: X.Nil
            is X.MapX -> m.values.getOrNull(m.keys.indexOf(key)) ?: default ?: X.Nil
            is X.Branches -> X.Branches(m.cases.map { (c, v) -> c to pick(v) }).let { if (it.cases.all { case -> case.second is X.Scalar || case.second == X.Nil }) toScalar(it) else it }
            else -> throw Untranslatable("get on ${m::class.simpleName}")
        }
        return pick(translate(args[0], ctx))
    }

    private fun nth(v: X, index: Int, default: X?): X = when (v) {
        is X.Vec -> v.items.getOrNull(index) ?: default ?: throw Untranslatable("nth out of range")
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

    private fun rollup(values: X, relation: X, target: X.Scalar): X.Scalar {
        val (keys, amounts) = keyedValues(values, "dim/rollup")
        val (relationKeys, parents) = keyedValues(relation, "dim/rollup relation")
        val terms = keys.mapIndexed { index, key ->
            val parentIndex = relationKeys.indexOf(key)
            if (parentIndex < 0) throw Untranslatable("dim/rollup relation missing $key")
            Ex.iff(Ex.cmp("=", parents[parentIndex], target), amounts[index], Ex.ZERO)
        }
        return Ex.fn("SUM", terms.ifEmpty { listOf(Ex.ZERO) })
    }

    /**
     * Largest-remainder allocation as plain formulas: every key gets the floored share plus one unit
     * when its remainder ranks within the units still missing to the rounded amount (ties by order).
     */
    private fun proRata(amount: X.Scalar, weights: X, scale: X.Scalar): X {
        val (keys, w) = keyedValues(weights, "alloc/pro-rata")
        if (keys.size > 12) throw Untranslatable("alloc/pro-rata with more than 12 members")
        val total = if (weights is X.Range) Ex.fn("SUM", Ex.atom(weights.text)) else Ex.chain("+", w, Ex.ADD)
        val factor = Ex.pow10(scale)
        val scaled = scale.text != "0"
        fun q(m: Int): X.Scalar = Ex.div(Ex.mul(amount, w[m]), total).let { if (scaled) Ex.mul(it, factor) else it }
        fun floor(m: Int) = Ex.fn("INT", q(m))
        fun remainder(m: Int) = Ex.sub(q(m), floor(m))
        val units = Ex.sub(Ex.fn("ROUND", if (scaled) Ex.mul(amount, factor) else amount, Ex.ZERO), Ex.chain("+", keys.indices.map(::floor), Ex.ADD))
        val values = keys.indices.map { j ->
            val ahead = keys.indices.filter { it != j }.map { m -> Ex.cmp(if (m < j) ">=" else ">", remainder(m), remainder(j)) }
            val rank = if (ahead.isEmpty()) Ex.num(1) else Ex.chain("+", listOf(Ex.num(1)) + ahead, Ex.ADD)
            val value = Ex.add(floor(j), Ex.fn("IF", Ex.cmp("<=", rank, units), Ex.num(1), Ex.ZERO))
            if (scaled) Ex.div(value, factor) else value
        }
        return X.MapX(keys, values)
    }

    /** Sequential allocation: each key receives min(cap, what is left after the keys before it). */
    private fun waterfall(amount: X.Scalar, caps: X): X {
        val keys: List<String>
        val c: List<X>
        when (caps) {
            is X.MapX -> { keys = caps.keys; c = caps.values }
            is X.Range -> { keys = caps.keys; c = caps.cells }
            else -> throw Untranslatable("alloc/waterfall needs a member map")
        }
        if (c.dropLast(1).any { it == X.Nil }) throw Untranslatable("alloc/waterfall: only the last capacity may be unlimited")
        val positive = Ex.fn("MAX", Ex.ZERO, amount)
        val values = keys.indices.map { j ->
            val left = if (j == 0) positive else Ex.fn("MAX", Ex.ZERO, Ex.sub(positive, Ex.chain("+", c.take(j).map { toScalar(it) }, Ex.ADD)))
            if (c[j] == X.Nil) left else Ex.fn("MIN", Ex.fn("MAX", Ex.ZERO, toScalar(c[j])), left)
        }
        return X.MapX(keys, values)
    }

    /** Value of the last band whose threshold is not above x (thresholds ascending). */
    private fun band(x: X.Scalar, table: X, default: X.Scalar?): X.Scalar {
        val rows = (table as? X.Vec)?.items?.map { row -> (row as? X.Vec)?.items ?: throw Untranslatable("table/band row shape") }
            ?: throw Untranslatable("table/band table must be a literal")
        return rows.fold(default ?: Ex.EMPTY) { acc, row -> Ex.iff(Ex.cmp(">=", x, toScalar(row[0])), toScalar(row[1]), acc) }
    }
}
