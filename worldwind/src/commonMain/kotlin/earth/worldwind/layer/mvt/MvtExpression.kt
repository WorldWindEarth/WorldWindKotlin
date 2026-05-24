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

    /**
     * Tile-zoom + feature properties + optional feature state. Construct once per feature
     * and frame. [featureState] surfaces dynamic per-feature data (hover/select highlight,
     * pulse animation phase) — read by [FeatureState] expressions.
     */
    class EvalContext(
        val zoom: Double,
        val properties: Map<String, Any?>,
        val featureState: Map<String, Any?>? = null,
        val geometryType: MvtGeometryType? = null,
        /** Arc-length parameter along the current line feature, in [0, 1]. Read by [LineProgress]. */
        val lineProgress: Double = 0.0,
    ) {
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

    /**
     * `["line-progress"]` — arc-length parameter along the current line feature, in [0, 1].
     * Read from [EvalContext.lineProgress] which the layer fills when sampling a line
     * gradient at progress points along each line subdivision.
     */
    object LineProgress : MvtExpression<Double>() {
        override fun evaluate(ctx: EvalContext): Double = ctx.lineProgress
    }

    /**
     * `["geometry-type"]` — returns the feature's geometry type as one of `"Point"`,
     * `"LineString"`, `"Polygon"`, or `"Unknown"` (Mapbox spelling). Useful for filter
     * expressions like `["==", ["geometry-type"], "Polygon"]`.
     */
    object GeometryType : MvtExpression<String>() {
        override fun evaluate(ctx: EvalContext): String = when (ctx.geometryType) {
            MvtGeometryType.POINT -> "Point"
            MvtGeometryType.LINESTRING -> "LineString"
            MvtGeometryType.POLYGON -> "Polygon"
            else -> "Unknown"
        }
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
        /**
         * Cubic Bezier control points (x1, y1, x2, y2) — same semantics as Mapbox's
         * `["cubic-bezier", x1, y1, x2, y2]`. Implemented via Newton-Raphson over the X
         * coordinate to find the curve parameter t at the requested input.
         */
        class CubicBezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Interpolation()
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
                        is Interpolation.CubicBezier -> {
                            val linear = (x - loZ) / (hiZ - loZ)
                            cubicBezierY(linear, mode.x1, mode.y1, mode.x2, mode.y2)
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

    // ---- Color constructors ----------------------------------------------------

    /** `["rgb", r, g, b]` — RGB channels in 0..255; produces a Color with alpha = 1. */
    class Rgb(
        val r: MvtExpression<*>, val g: MvtExpression<*>, val b: MvtExpression<*>,
    ) : MvtExpression<Color>() {
        override fun evaluate(ctx: EvalContext): Color? {
            val red = numberOrNull(r.evaluate(ctx)) ?: return null
            val green = numberOrNull(g.evaluate(ctx)) ?: return null
            val blue = numberOrNull(b.evaluate(ctx)) ?: return null
            return Color(
                (red / 255.0).toFloat().coerceIn(0f, 1f),
                (green / 255.0).toFloat().coerceIn(0f, 1f),
                (blue / 255.0).toFloat().coerceIn(0f, 1f),
                1f,
            )
        }
    }

    /** `["rgba", r, g, b, a]` — channels in 0..255 for RGB and 0..1 for alpha. */
    class Rgba(
        val r: MvtExpression<*>, val g: MvtExpression<*>,
        val b: MvtExpression<*>, val a: MvtExpression<*>,
    ) : MvtExpression<Color>() {
        override fun evaluate(ctx: EvalContext): Color? {
            val red = numberOrNull(r.evaluate(ctx)) ?: return null
            val green = numberOrNull(g.evaluate(ctx)) ?: return null
            val blue = numberOrNull(b.evaluate(ctx)) ?: return null
            val alpha = numberOrNull(a.evaluate(ctx)) ?: return null
            return Color(
                (red / 255.0).toFloat().coerceIn(0f, 1f),
                (green / 255.0).toFloat().coerceIn(0f, 1f),
                (blue / 255.0).toFloat().coerceIn(0f, 1f),
                alpha.toFloat().coerceIn(0f, 1f),
            )
        }
    }

    // ---- String operations -----------------------------------------------------

    /** `["concat", arg, arg, …]` — joins string-coerced operands. */
    class Concat(val parts: List<MvtExpression<*>>) : MvtExpression<String>() {
        override fun evaluate(ctx: EvalContext): String {
            val sb = StringBuilder()
            for (p in parts) p.evaluate(ctx)?.let { sb.append(it.toString()) }
            return sb.toString()
        }
    }

    class Downcase(val child: MvtExpression<*>) : MvtExpression<String>() {
        override fun evaluate(ctx: EvalContext): String? = child.evaluate(ctx)?.toString()?.lowercase()
    }

    class Upcase(val child: MvtExpression<*>) : MvtExpression<String>() {
        override fun evaluate(ctx: EvalContext): String? = child.evaluate(ctx)?.toString()?.uppercase()
    }

    /** Length in characters of a string or number of items in an array. */
    class Length(val child: MvtExpression<*>) : MvtExpression<Double>() {
        override fun evaluate(ctx: EvalContext): Double? = when (val v = child.evaluate(ctx)) {
            null -> null
            is String -> v.length.toDouble()
            is List<*> -> v.size.toDouble()
            else -> null
        }
    }

    // ---- Feature state ---------------------------------------------------------

    /**
     * `["feature-state", key]` — reads dynamic per-feature state set on the layer at
     * runtime (typically for hover / select highlight). Looks up [key] in
     * [EvalContext.featureState] (set per-feature via [MvtVectorLayer.setFeatureState]).
     * Returns null when no state is set for the current feature.
     */
    class FeatureState(val key: String) : MvtExpression<Any?>() {
        override fun evaluate(ctx: EvalContext): Any? = ctx.featureState?.get(key)
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

        /**
         * Solve `y(t)` for the cubic-bezier curve with endpoints (0,0)…(1,1) and control
         * points (x1,y1) and (x2,y2) at input `x`. Uses Newton-Raphson on the X coordinate
         * (≤ 8 iterations) to find the curve parameter t, then evaluates the Y polynomial.
         * Output is clamped to [0, 1] — caller is responsible for re-mapping to value range.
         */
        internal fun cubicBezierY(x: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
            // Bezier as polynomial: P(t) = 3(1-t)²t·c1 + 3(1-t)t²·c2 + t³
            // Coefficients pulled out for both axes.
            val ax = 3.0 * x1 - 3.0 * x2 + 1.0
            val bx = -6.0 * x1 + 3.0 * x2
            val cx = 3.0 * x1
            val ay = 3.0 * y1 - 3.0 * y2 + 1.0
            val by = -6.0 * y1 + 3.0 * y2
            val cy = 3.0 * y1
            // Newton-Raphson on x → t.
            var t = x.coerceIn(0.0, 1.0)
            for (i in 0 until 8) {
                val px = ((ax * t + bx) * t + cx) * t - x
                if (kotlin.math.abs(px) < 1e-6) break
                val dx = (3.0 * ax * t + 2.0 * bx) * t + cx
                if (kotlin.math.abs(dx) < 1e-6) break
                t -= px / dx
            }
            return (((ay * t + by) * t + cy) * t).coerceIn(0.0, 1.0)
        }
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
