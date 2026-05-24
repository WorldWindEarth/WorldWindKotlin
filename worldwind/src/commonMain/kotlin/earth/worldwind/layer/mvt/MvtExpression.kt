package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlin.math.ln
import kotlin.math.pow

/**
 * Mapbox-style expression tree. Each node evaluates to `T?` against an [EvalContext]
 * (current tile zoom + feature property map); null propagates when an upstream node has no
 * value (e.g. `Get` on a missing property).
 *
 * Supported subset of the Mapbox GL spec:
 *   - Literals: numbers, strings, booleans, colors, arrays.
 *   - Sources: `["zoom"]`, `["get", key]`, `["has", key]`, `["literal", value]`.
 *   - Comparisons: `==`, `!=`, `<`, `<=`, `>`, `>=`.
 *   - Boolean: `all`, `any`, `!`.
 *   - Arithmetic: `+`, `-`, `*`, `/` (variadic where Mapbox is variadic).
 *   - Conditionals: `case`, `match`, `step`.
 *   - Interpolation: `interpolate ["linear"]` and `interpolate ["exponential", base]` for
 *     `Float` and `Color`.
 *   - Coercion: `to-number`, `to-string`, `to-boolean`.
 *
 * Not supported (returns null / falls through to default):
 *   - `feature-state`, `geometry-type`, color expression functions (`rgb`/`rgba`/`to-color`),
 *     string functions (`concat`/`downcase`/`upcase`/`length`), cubic-bezier curves.
 *
 * Construction is allocation-free at evaluation; trees are built once at style time and
 * evaluated per-feature/per-frame.
 */
sealed class MvtExpression<out T> {

    /** Tile-zoom + feature properties. Construct once per feature-and-frame. */
    class EvalContext(val zoom: Double, val properties: Map<String, Any?>) {
        companion object {
            /** Zoom = 0, no properties — for constant-only expressions in test code. */
            val EMPTY = EvalContext(0.0, emptyMap())
        }
    }

    abstract fun evaluate(ctx: EvalContext): T?

    // ---- Literals & sources ----------------------------------------------------

    class Literal<T>(val value: T) : MvtExpression<T>() {
        override fun evaluate(ctx: EvalContext): T = value
    }

    object Zoom : MvtExpression<Double>() {
        override fun evaluate(ctx: EvalContext): Double = ctx.zoom
    }

    class Get(val key: String) : MvtExpression<Any?>() {
        override fun evaluate(ctx: EvalContext): Any? = ctx.properties[key]
    }

    class Has(val key: String) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean = ctx.properties.containsKey(key)
    }

    // ---- Comparisons -----------------------------------------------------------

    /** Equality via Kotlin `==`. Numbers compare by toDouble for cross-type matches. */
    class Eq(val a: MvtExpression<*>, val b: MvtExpression<*>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean = looseEquals(a.evaluate(ctx), b.evaluate(ctx))
    }

    class Neq(val a: MvtExpression<*>, val b: MvtExpression<*>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean = !looseEquals(a.evaluate(ctx), b.evaluate(ctx))
    }

    class Lt(val a: MvtExpression<*>, val b: MvtExpression<*>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean? {
            val av = numberOrNull(a.evaluate(ctx)) ?: return null
            val bv = numberOrNull(b.evaluate(ctx)) ?: return null
            return av < bv
        }
    }

    class Lte(val a: MvtExpression<*>, val b: MvtExpression<*>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean? {
            val av = numberOrNull(a.evaluate(ctx)) ?: return null
            val bv = numberOrNull(b.evaluate(ctx)) ?: return null
            return av <= bv
        }
    }

    class Gt(val a: MvtExpression<*>, val b: MvtExpression<*>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean? {
            val av = numberOrNull(a.evaluate(ctx)) ?: return null
            val bv = numberOrNull(b.evaluate(ctx)) ?: return null
            return av > bv
        }
    }

    class Gte(val a: MvtExpression<*>, val b: MvtExpression<*>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean? {
            val av = numberOrNull(a.evaluate(ctx)) ?: return null
            val bv = numberOrNull(b.evaluate(ctx)) ?: return null
            return av >= bv
        }
    }

    // ---- Boolean combinators ---------------------------------------------------

    class AllOf(val children: List<MvtExpression<Boolean>>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean = children.all { it.evaluate(ctx) == true }
    }

    class AnyOf(val children: List<MvtExpression<Boolean>>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean = children.any { it.evaluate(ctx) == true }
    }

    class Not(val child: MvtExpression<Boolean>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean = child.evaluate(ctx) != true
    }

    // ---- Arithmetic ------------------------------------------------------------

    /** Variadic sum. Mapbox `+` accepts ≥ 2 operands; empty list → 0. */
    class Add(val operands: List<MvtExpression<*>>) : MvtExpression<Double>() {
        override fun evaluate(ctx: EvalContext): Double? {
            var acc = 0.0
            for (op in operands) acc += numberOrNull(op.evaluate(ctx)) ?: return null
            return acc
        }
    }

    /** Variadic difference (left-fold): `a - b - c` = `(a - b) - c`. */
    class Sub(val operands: List<MvtExpression<*>>) : MvtExpression<Double>() {
        override fun evaluate(ctx: EvalContext): Double? {
            if (operands.isEmpty()) return 0.0
            var acc = numberOrNull(operands[0].evaluate(ctx)) ?: return null
            for (i in 1 until operands.size) acc -= numberOrNull(operands[i].evaluate(ctx)) ?: return null
            return acc
        }
    }

    class Mul(val operands: List<MvtExpression<*>>) : MvtExpression<Double>() {
        override fun evaluate(ctx: EvalContext): Double? {
            var acc = 1.0
            for (op in operands) acc *= numberOrNull(op.evaluate(ctx)) ?: return null
            return acc
        }
    }

    class Div(val a: MvtExpression<*>, val b: MvtExpression<*>) : MvtExpression<Double>() {
        override fun evaluate(ctx: EvalContext): Double? {
            val av = numberOrNull(a.evaluate(ctx)) ?: return null
            val bv = numberOrNull(b.evaluate(ctx)) ?: return null
            return av / bv
        }
    }

    // ---- Conditionals ----------------------------------------------------------

    /**
     * `["case", cond1, val1, cond2, val2, …, default]` — first true condition wins. Both
     * sides of each branch are evaluated lazily — non-matching value-side expressions never
     * run.
     */
    class Case<T>(
        val branches: List<Branch<T>>,
        val default: MvtExpression<T>,
    ) : MvtExpression<T>() {
        class Branch<T>(val condition: MvtExpression<Boolean>, val value: MvtExpression<T>)

        override fun evaluate(ctx: EvalContext): T? {
            for (b in branches) if (b.condition.evaluate(ctx) == true) return b.value.evaluate(ctx)
            return default.evaluate(ctx)
        }
    }

    /**
     * `["match", input, label, value, label, value, …, default]` — first label that
     * equals the input wins. Labels can be a single value or a list (the latter is the
     * variadic-label Mapbox extension).
     */
    class Match<T>(
        val input: MvtExpression<*>,
        val branches: List<Branch<T>>,
        val default: MvtExpression<T>,
    ) : MvtExpression<T>() {
        class Branch<T>(val labels: List<Any?>, val value: MvtExpression<T>)

        override fun evaluate(ctx: EvalContext): T? {
            val inputValue = input.evaluate(ctx)
            for (b in branches) {
                for (label in b.labels) if (looseEquals(inputValue, label)) return b.value.evaluate(ctx)
            }
            return default.evaluate(ctx)
        }
    }

    /**
     * `["step", input, base, stop1, val1, stop2, val2, …]` — piecewise-constant. Returns
     * [base] when `input < stops[0]`; otherwise the value of the largest stop ≤ input.
     */
    class Step<T>(
        val input: MvtExpression<*>,
        val base: MvtExpression<T>,
        val stops: List<Pair<Double, MvtExpression<T>>>,
    ) : MvtExpression<T>() {
        override fun evaluate(ctx: EvalContext): T? {
            val x = numberOrNull(input.evaluate(ctx)) ?: return base.evaluate(ctx)
            var chosen: MvtExpression<T> = base
            for ((z, v) in stops) {
                if (x >= z) chosen = v else break
            }
            return chosen.evaluate(ctx)
        }
    }

    // ---- Interpolation ---------------------------------------------------------

    sealed class Interpolation {
        object Linear : Interpolation()
        class Exponential(val base: Double) : Interpolation()
    }

    /**
     * Linear or exponential interpolation between typed stops. [lerp] supplies the per-T
     * blend (lerp(a, b, t) where t ∈ [0, 1]); standard implementations live in [Interpolators].
     */
    class Interpolate<T>(
        val interpolation: Interpolation,
        val input: MvtExpression<*>,
        val stops: List<Pair<Double, MvtExpression<T>>>,
        private val lerp: (T, T, Double) -> T,
    ) : MvtExpression<T>() {
        init { require(stops.isNotEmpty()) { "interpolate needs at least one stop" } }

        override fun evaluate(ctx: EvalContext): T? {
            val x = numberOrNull(input.evaluate(ctx)) ?: return stops[0].second.evaluate(ctx)
            if (x <= stops.first().first) return stops.first().second.evaluate(ctx)
            if (x >= stops.last().first) return stops.last().second.evaluate(ctx)
            for (i in 1 until stops.size) {
                val (hiZ, hiE) = stops[i]
                if (x <= hiZ) {
                    val (loZ, loE) = stops[i - 1]
                    val t = when (val mode = interpolation) {
                        Interpolation.Linear -> (x - loZ) / (hiZ - loZ)
                        is Interpolation.Exponential -> {
                            val b = mode.base
                            if (b == 1.0) (x - loZ) / (hiZ - loZ)
                            else (b.pow(x - loZ) - 1.0) / (b.pow(hiZ - loZ) - 1.0)
                        }
                    }
                    val lo = loE.evaluate(ctx) ?: return null
                    val hi = hiE.evaluate(ctx) ?: return null
                    return lerp(lo, hi, t)
                }
            }
            return stops.last().second.evaluate(ctx)
        }
    }

    // ---- Coercion --------------------------------------------------------------

    class ToNumber(val child: MvtExpression<*>) : MvtExpression<Double>() {
        override fun evaluate(ctx: EvalContext): Double? = numberOrNull(child.evaluate(ctx))
    }

    class ToString(val child: MvtExpression<*>) : MvtExpression<String>() {
        override fun evaluate(ctx: EvalContext): String? = when (val v = child.evaluate(ctx)) {
            null -> null
            is String -> v
            else -> v.toString()
        }
    }

    class ToBoolean(val child: MvtExpression<*>) : MvtExpression<Boolean>() {
        override fun evaluate(ctx: EvalContext): Boolean? = when (val v = child.evaluate(ctx)) {
            null -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            else -> true
        }
    }

    /**
     * Built-in lerp functions for the value types interpolation actually targets in MVT
     * styling: floats and colors. Plugged into [Interpolate]'s [lerp] parameter at
     * construction.
     */
    object Interpolators {
        val FLOAT: (Float, Float, Double) -> Float = { a, b, t -> (a + (b - a) * t).toFloat() }
        val DOUBLE: (Double, Double, Double) -> Double = { a, b, t -> a + (b - a) * t }
        val COLOR: (Color, Color, Double) -> Color = { a, b, t ->
            val tf = t.toFloat()
            Color(
                a.red + (b.red - a.red) * tf,
                a.green + (b.green - a.green) * tf,
                a.blue + (b.blue - a.blue) * tf,
                a.alpha + (b.alpha - a.alpha) * tf,
            )
        }
    }

    companion object {
        /** Mapbox loose-equality semantics: numbers compare by toDouble; otherwise structural. */
        internal fun looseEquals(a: Any?, b: Any?): Boolean {
            if (a == null && b == null) return true
            if (a == null || b == null) return false
            if (a is Number && b is Number) return a.toDouble() == b.toDouble()
            return a == b
        }

        /** Coerce a JSON-typed Any? to Double; non-numeric strings parse as best-effort. */
        internal fun numberOrNull(v: Any?): Double? = when (v) {
            null -> null
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            is String -> v.toDoubleOrNull()
            else -> null
        }

        // Used by ToNumber's "use natural log for fractional bases" path; kept here so the
        // companion is the single home for shared math.
        @Suppress("unused") internal fun ln2(): Double = ln(2.0)
    }
}

/**
 * Typed entry-points for the most common expression shapes. These let DSL/style code stay
 * concise; full Mapbox expressions still flow through [MvtExpression] subclasses directly.
 */
object MvtExpr {
    fun literal(v: Float): MvtExpression<Float> = MvtExpression.Literal(v)
    fun literal(v: Color): MvtExpression<Color> = MvtExpression.Literal(v)
    fun literal(v: String): MvtExpression<String> = MvtExpression.Literal(v)
    fun literal(v: Boolean): MvtExpression<Boolean> = MvtExpression.Literal(v)

    fun linearFloats(vararg stops: Pair<Int, Float>): MvtExpression<Float> =
        MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Linear,
            input = MvtExpression.Zoom,
            stops = stops.map { (z, v) -> z.toDouble() to MvtExpression.Literal(v) },
            lerp = MvtExpression.Interpolators.FLOAT,
        )

    fun linearColors(vararg stops: Pair<Int, Color>): MvtExpression<Color> =
        MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Linear,
            input = MvtExpression.Zoom,
            stops = stops.map { (z, v) -> z.toDouble() to MvtExpression.Literal(v) },
            lerp = MvtExpression.Interpolators.COLOR,
        )
}
