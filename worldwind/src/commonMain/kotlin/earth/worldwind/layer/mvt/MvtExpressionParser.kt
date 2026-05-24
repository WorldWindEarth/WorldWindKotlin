package earth.worldwind.layer.mvt

import earth.worldwind.render.Color
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses Mapbox-GL JSON expression arrays into [MvtExpression] trees. Supports the
 * subset documented on [MvtExpression] (literals + sources + comparisons + boolean +
 * arithmetic + case/match/step + interpolate + coercion).
 *
 * Two typed entry-points cover the paint-property use cases:
 *   - [parseFloat] — for numeric properties (`line-width`, `text-size`, `text-halo-width`).
 *   - [parseColor] — for color properties (`fill-color`, `line-color`, `text-color`).
 *
 * Sub-expressions used inside conditionals/branches are parsed via [parseAny], whose
 * concrete value type depends on the leaf literals encountered.
 *
 * Unsupported operators return `null`; the caller decides whether that falls back to a
 * default or skips the property entirely.
 */
object MvtExpressionParser {

    /** Parse a JSON value/array as a Float-valued expression. */
    fun parseFloat(el: JsonElement): MvtExpression<Float>? = when (el) {
        is JsonPrimitive -> el.floatOrNull?.let { MvtExpression.Literal(it) }
        is JsonArray -> parseTyped(el, TargetType.FLOAT, parseColor = null)?.uncheckedAs()
        is JsonObject -> null
    }

    /** Parse a JSON value/array as a Color-valued expression. */
    fun parseColor(el: JsonElement, parseColor: (String) -> Color?): MvtExpression<Color>? = when (el) {
        is JsonPrimitive -> el.contentOrNull?.let(parseColor)?.let { MvtExpression.Literal(it) }
        is JsonArray -> parseTyped(el, TargetType.COLOR, parseColor)?.uncheckedAs()
        is JsonObject -> null
    }

    /** Parse a JSON value/array with no target-type hint. Used for sub-expressions. */
    fun parseAny(el: JsonElement, parseColor: ((String) -> Color?)?): MvtExpression<*>? = when (el) {
        is JsonPrimitive -> parsePrimitive(el)
        is JsonArray -> parseTyped(el, TargetType.ANY, parseColor)
        is JsonObject -> null
    }

    private enum class TargetType { FLOAT, COLOR, BOOLEAN, STRING, ANY }

    @Suppress("UNCHECKED_CAST")
    private fun <T> MvtExpression<*>.uncheckedAs(): MvtExpression<T> = this as MvtExpression<T>

    private fun parsePrimitive(p: JsonPrimitive): MvtExpression<*> = when {
        p.booleanOrNull != null -> MvtExpression.Literal(p.boolean)
        p.intOrNull != null -> MvtExpression.Literal(p.int)
        p.doubleOrNull != null -> MvtExpression.Literal(p.double)
        else -> MvtExpression.Literal(p.contentOrNull ?: "")
    }

    private val JsonPrimitive.boolean: Boolean get() = booleanOrNull == true
    private val JsonPrimitive.int: Int get() = intOrNull ?: 0
    private val JsonPrimitive.double: Double get() = doubleOrNull ?: 0.0

    private fun parseTyped(
        arr: JsonArray, target: TargetType, parseColor: ((String) -> Color?)?,
    ): MvtExpression<*>? {
        if (arr.isEmpty()) return null
        val opPrim = arr[0] as? JsonPrimitive ?: return null
        val op = opPrim.contentOrNull ?: return null
        val args = arr.drop(1)
        return when (op) {
            "literal" -> parseLiteral(args, target, parseColor)
            "zoom" -> MvtExpression.Zoom
            "geometry-type" -> MvtExpression.GeometryType
            "get" -> parseGet(args)
            "has" -> parseHas(args)
            "==" -> parseBinaryComparison(args, parseColor) { a, b -> MvtExpression.Eq(a, b) }
            "!=" -> parseBinaryComparison(args, parseColor) { a, b -> MvtExpression.Neq(a, b) }
            "<" -> parseBinaryComparison(args, parseColor) { a, b -> MvtExpression.Lt(a, b) }
            "<=" -> parseBinaryComparison(args, parseColor) { a, b -> MvtExpression.Lte(a, b) }
            ">" -> parseBinaryComparison(args, parseColor) { a, b -> MvtExpression.Gt(a, b) }
            ">=" -> parseBinaryComparison(args, parseColor) { a, b -> MvtExpression.Gte(a, b) }
            "all" -> MvtExpression.AllOf(args.mapNotNull { parseAny(it, parseColor)?.uncheckedAs() })
            "any" -> MvtExpression.AnyOf(args.mapNotNull { parseAny(it, parseColor)?.uncheckedAs() })
            "!" -> args.firstOrNull()
                ?.let { parseAny(it, parseColor) }
                ?.let { MvtExpression.Not(it.uncheckedAs()) }
            "+" -> MvtExpression.Add(args.mapNotNull { parseAny(it, parseColor) })
            "-" -> MvtExpression.Sub(args.mapNotNull { parseAny(it, parseColor) })
            "*" -> MvtExpression.Mul(args.mapNotNull { parseAny(it, parseColor) })
            "/" -> {
                val a = args.getOrNull(0)?.let { parseAny(it, parseColor) } ?: return null
                val b = args.getOrNull(1)?.let { parseAny(it, parseColor) } ?: return null
                MvtExpression.Div(a, b)
            }
            "case" -> parseCase<Any?>(args, target, parseColor)
            "match" -> parseMatch<Any?>(args, target, parseColor)
            "step" -> parseStep<Any?>(args, target, parseColor)
            "interpolate" -> parseInterpolate<Any?>(args, target, parseColor)
            "to-number" -> args.firstOrNull()?.let { parseAny(it, parseColor)?.let { e -> MvtExpression.ToNumber(e) } }
            "to-string" -> args.firstOrNull()?.let { parseAny(it, parseColor)?.let { e -> MvtExpression.ToString(e) } }
            "to-boolean" -> args.firstOrNull()?.let { parseAny(it, parseColor)?.let { e -> MvtExpression.ToBoolean(e) } }
            "rgb" -> {
                val r = args.getOrNull(0)?.let { parseAny(it, parseColor) } ?: return null
                val g = args.getOrNull(1)?.let { parseAny(it, parseColor) } ?: return null
                val b = args.getOrNull(2)?.let { parseAny(it, parseColor) } ?: return null
                MvtExpression.Rgb(r, g, b)
            }
            "rgba" -> {
                val r = args.getOrNull(0)?.let { parseAny(it, parseColor) } ?: return null
                val g = args.getOrNull(1)?.let { parseAny(it, parseColor) } ?: return null
                val b = args.getOrNull(2)?.let { parseAny(it, parseColor) } ?: return null
                val a = args.getOrNull(3)?.let { parseAny(it, parseColor) } ?: return null
                MvtExpression.Rgba(r, g, b, a)
            }
            "to-color" -> args.firstOrNull()?.let { parseAny(it, parseColor) }
            "concat" -> MvtExpression.Concat(args.mapNotNull { parseAny(it, parseColor) })
            "downcase" -> args.firstOrNull()?.let { parseAny(it, parseColor)?.let { e -> MvtExpression.Downcase(e) } }
            "upcase" -> args.firstOrNull()?.let { parseAny(it, parseColor)?.let { e -> MvtExpression.Upcase(e) } }
            "length" -> args.firstOrNull()?.let { parseAny(it, parseColor)?.let { e -> MvtExpression.Length(e) } }
            "feature-state" -> {
                val key = (args.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
                MvtExpression.FeatureState(key)
            }
            else -> null
        }
    }

    private fun parseLiteral(
        args: List<JsonElement>, target: TargetType, parseColor: ((String) -> Color?)?,
    ): MvtExpression<*>? {
        val v = args.firstOrNull() ?: return null
        if (target == TargetType.COLOR && v is JsonPrimitive && parseColor != null) {
            val c = v.contentOrNull?.let(parseColor) ?: return null
            return MvtExpression.Literal(c)
        }
        return when (v) {
            is JsonPrimitive -> parsePrimitive(v)
            is JsonArray -> MvtExpression.Literal(v.toList())
            is JsonObject -> null
        }
    }

    private fun parseGet(args: List<JsonElement>): MvtExpression<*>? {
        val key = (args.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
        return MvtExpression.Get(key)
    }

    private fun parseHas(args: List<JsonElement>): MvtExpression<*>? {
        val key = (args.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
        return MvtExpression.Has(key)
    }

    private fun parseBinaryComparison(
        args: List<JsonElement>,
        parseColor: ((String) -> Color?)?,
        ctor: (MvtExpression<*>, MvtExpression<*>) -> MvtExpression<*>,
    ): MvtExpression<*>? {
        val a = args.getOrNull(0)?.let { parseAny(it, parseColor) } ?: return null
        val b = args.getOrNull(1)?.let { parseAny(it, parseColor) } ?: return null
        return ctor(a, b)
    }

    private fun <T> parseCase(
        args: List<JsonElement>, target: TargetType, parseColor: ((String) -> Color?)?,
    ): MvtExpression<T>? {
        if (args.size < 3 || args.size % 2 == 0) return null
        val branches = mutableListOf<MvtExpression.Case.Branch<T>>()
        var i = 0
        while (i + 1 < args.size) {
            val cond = parseAny(args[i], parseColor)?.uncheckedAs<Boolean>() ?: return null
            val value = parseValueForTarget<T>(args[i + 1], target, parseColor) ?: return null
            branches += MvtExpression.Case.Branch(cond, value)
            i += 2
        }
        val default = parseValueForTarget<T>(args.last(), target, parseColor) ?: return null
        return MvtExpression.Case(branches, default)
    }

    private fun <T> parseMatch(
        args: List<JsonElement>, target: TargetType, parseColor: ((String) -> Color?)?,
    ): MvtExpression<T>? {
        // ["match", input, label, value, ..., default] — total args after the op = even.
        if (args.size < 4 || args.size % 2 != 0) return null
        val input = parseAny(args[0], parseColor) ?: return null
        val branches = mutableListOf<MvtExpression.Match.Branch<T>>()
        var i = 1
        while (i + 1 < args.size) {
            val labelEl = args[i]
            val labels = when (labelEl) {
                is JsonArray -> labelEl.mapNotNull { (it as? JsonPrimitive)?.let(::primitiveValue) }
                is JsonPrimitive -> listOf(primitiveValue(labelEl))
                else -> return null
            }
            val value = parseValueForTarget<T>(args[i + 1], target, parseColor) ?: return null
            branches += MvtExpression.Match.Branch(labels, value)
            i += 2
        }
        val default = parseValueForTarget<T>(args.last(), target, parseColor) ?: return null
        return MvtExpression.Match(input, branches, default)
    }

    private fun <T> parseStep(
        args: List<JsonElement>, target: TargetType, parseColor: ((String) -> Color?)?,
    ): MvtExpression<T>? {
        // ["step", input, base, stop1, val1, stop2, val2, ...]
        if (args.size < 4 || args.size % 2 != 0) return null
        val input = parseAny(args[0], parseColor) ?: return null
        val base = parseValueForTarget<T>(args[1], target, parseColor) ?: return null
        val stops = mutableListOf<Pair<Double, MvtExpression<T>>>()
        var i = 2
        while (i + 1 < args.size) {
            val z = (args[i] as? JsonPrimitive)?.doubleOrNull ?: return null
            val v = parseValueForTarget<T>(args[i + 1], target, parseColor) ?: return null
            stops += z to v
            i += 2
        }
        return MvtExpression.Step(input, base, stops)
    }

    private fun <T> parseInterpolate(
        args: List<JsonElement>, target: TargetType, parseColor: ((String) -> Color?)?,
    ): MvtExpression<T>? {
        // ["interpolate", ["linear"|["exponential", base]], inputExpr, stop, val, stop, val, ...]
        // Total args after the op = curve + input + 2N stops = even, and ≥ 4 (one stop).
        if (args.size < 4 || args.size % 2 != 0) return null
        val interp = parseInterpolationCurve(args[0]) ?: return null
        val input = parseAny(args[1], parseColor) ?: return null
        val stops = mutableListOf<Pair<Double, MvtExpression<T>>>()
        var i = 2
        while (i + 1 < args.size) {
            val z = (args[i] as? JsonPrimitive)?.doubleOrNull ?: return null
            val v = parseValueForTarget<T>(args[i + 1], target, parseColor) ?: return null
            stops += z to v
            i += 2
        }
        if (stops.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        val lerp: (T, T, Double) -> T = when (target) {
            TargetType.FLOAT -> MvtExpression.Interpolators.FLOAT as (T, T, Double) -> T
            TargetType.COLOR -> MvtExpression.Interpolators.COLOR as (T, T, Double) -> T
            else -> return null
        }
        return MvtExpression.Interpolate(interp, input, stops, lerp)
    }

    private fun parseInterpolationCurve(el: JsonElement): MvtExpression.Interpolation? {
        if (el !is JsonArray || el.isEmpty()) return null
        val name = (el[0] as? JsonPrimitive)?.contentOrNull ?: return null
        return when (name) {
            "linear" -> MvtExpression.Interpolation.Linear
            "exponential" -> {
                val base = (el.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: 1.0
                MvtExpression.Interpolation.Exponential(base)
            }
            "cubic-bezier" -> {
                val x1 = (el.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
                val y1 = (el.getOrNull(2) as? JsonPrimitive)?.doubleOrNull ?: return null
                val x2 = (el.getOrNull(3) as? JsonPrimitive)?.doubleOrNull ?: return null
                val y2 = (el.getOrNull(4) as? JsonPrimitive)?.doubleOrNull ?: return null
                MvtExpression.Interpolation.CubicBezier(x1, y1, x2, y2)
            }
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> parseValueForTarget(
        el: JsonElement, target: TargetType, parseColor: ((String) -> Color?)?,
    ): MvtExpression<T>? = when (target) {
        TargetType.FLOAT -> parseFloat(el) as MvtExpression<T>?
        TargetType.COLOR -> parseColor?.let { parseColor(el, it) as MvtExpression<T>? }
        TargetType.BOOLEAN, TargetType.STRING, TargetType.ANY ->
            parseAny(el, parseColor) as MvtExpression<T>?
    }

    private fun primitiveValue(p: JsonPrimitive): Any? = when {
        p.booleanOrNull != null -> p.booleanOrNull
        p.intOrNull != null -> p.intOrNull
        p.doubleOrNull != null -> p.doubleOrNull
        else -> p.contentOrNull
    }
}
