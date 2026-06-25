package earth.worldwind.layer.mvt

import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.Color
import earth.worldwind.render.FontWeight
import earth.worldwind.shape.ShapeAttributes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for a **subset** of the Mapbox GL Style Specification, producing an
 * [MvtRuleBasedStyle] usable with [MvtVectorLayer]. The intent is "drop in a hand-authored
 * style.json or a downloaded one from Maptiler/Mapbox and have it just work for typical
 * basemap use cases" — not full spec fidelity.
 *
 * ### Supported
 *
 * - **Layer types**: `fill`, `line`, `symbol` (text-only — no icons).
 * - **Properties**: `id` (display name), `source-layer`, `filter`, `minzoom`, `maxzoom`,
 *   `paint`, `layout`.
 * - **Filter expressions** (legacy "filter" form only):
 *   `["==", k, v]`, `["!=", k, v]`, `["in", k, ...]`, `["!in", k, ...]`,
 *   `["has", k]`, `["!has", k]`, `["<", k, n]`, `["<=", k, n]`, `[">", k, n]`,
 *   `[">=", k, n]`, `["all", ...]`, `["any", ...]`, `["!", subFilter]`.
 *   `$type` is not supported — set [MvtStyleRule.geometryType] manually or use a layer-name
 *   convention.
 * - **Paint/layout values**:
 *   - Constants (number, color hex string `#rrggbb`/`#rrggbbaa`).
 *   - Legacy zoom-stop form: `{ "stops": [[zoom, value], …] }`.
 *   - **Not** supported: modern `["interpolate", …]` expressions, `["case", …]`,
 *     `["match", …]`, `["step", …]`, arithmetic, `["get", …]`, `["zoom"]`, feature-state.
 *     Throws [MvtStyleParseException] when encountered so misrendering doesn't go silent.
 * - **Paint/layout properties** (fill / line / symbol):
 *   - `fill-color`, `fill-opacity`
 *   - `line-color`, `line-width`, `line-opacity`
 *   - `text-field` (plain string `"{name}"` form or top-level property reference)
 *   - `text-color`, `text-size`, `text-halo-color`, `text-halo-width`,
 *     `text-font` (first entry; weight inferred from "bold"/"italic" suffix)
 *
 * ### Not supported (deliberately)
 *
 * - Modern expression DSL (`["interpolate", "linear", ["zoom"], …]`, `["case", …]`,
 *   `["match", …]`, arithmetic, string concatenation). Not yet implemented.
 * - Icons / sprites / images / patterns. Symbol layers without `text-field` are silently
 *   dropped.
 * - Layer types other than fill/line/symbol (heatmap, hillshade, raster, background).
 * - Layout properties beyond text-field/text-size/text-font.
 * - Source / sprite / glyphs / metadata blocks at the top of the document — parsed and
 *   ignored.
 * - Visibility = "none" on layout — handled, layer skipped.
 *
 * If your style relies on something unsupported, [MvtStyleParseException] surfaces a
 * specific error pointing at the offending node.
 */
object MvtMapboxStyleLoader {

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse a Mapbox GL style document into an [MvtRuleBasedStyle]. The returned style's
     * rules paint in the order the layers appear in the document — same paint-order
     * semantics as Mapbox GL itself.
     *
     * @throws MvtStyleParseException on unsupported expression operators or malformed
     *         documents. The message names the offending property and value so the
     *         caller can isolate the unsupported feature.
     */
    fun parse(json: String): MvtRuleBasedStyle {
        val root = JSON.parseToJsonElement(json).jsonObject
        val layers = (root["layers"] as? JsonArray)
            ?: throw MvtStyleParseException("Style document has no `layers` array")
        val rules = ArrayList<MvtStyleRule>(layers.size)
        var background: Color? = null
        for (layerEl in layers) {
            val layer = layerEl.jsonObject
            // Capture the `background` layer's color (constant form) — fills gaps MVT has no polygon for.
            if (layer["type"]?.jsonPrimitive?.contentOrNull == "background") {
                (layer["paint"] as? JsonObject)?.get("background-color")?.jsonPrimitive?.contentOrNull
                    ?.let(::parseColor)?.let { background = it }
                continue
            }
            val rule = parseLayer(layer) ?: continue
            rules += rule
        }
        return MvtRuleBasedStyle(rules, background)
    }

    private fun parseLayer(layer: JsonObject): MvtStyleRule? {
        val type = layer["type"]?.jsonPrimitive?.contentOrNull ?: return null

        // Skip layers with visibility=none.
        val layout = layer["layout"] as? JsonObject
        if (layout?.get("visibility")?.jsonPrimitive?.contentOrNull == "none") return null

        // Source-layer is required (we don't render the basemap "background" type).
        val sourceLayer = layer["source-layer"]?.jsonPrimitive?.contentOrNull
            ?: return null  // background / fully-derived layers — skip silently

        val filter = (layer["filter"] as? JsonArray)?.let(::parseFilter) ?: MvtFilter.Always
        val minZoom = layer["minzoom"]?.jsonPrimitive?.intOrNull ?: 0
        val maxZoom = layer["maxzoom"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE

        val paint = layer["paint"] as? JsonObject
        val paintSpec = when (type) {
            "fill" -> parseFillPaint(paint)
            "line" -> parseLinePaint(paint)
            "symbol" -> parseSymbolPaint(paint, layout)
            "fill-extrusion" -> parseFillExtrusionPaint(paint)
            else -> return null  // raster, hillshade, heatmap, background, … — skipped
        } ?: return null

        return MvtStyleRule(
            sourceLayer = sourceLayer,
            filter = filter,
            minZoom = minZoom,
            maxZoom = maxZoom,
            // Mapbox layers paint in declaration order; we rely on the rule list ordering for
            // the same effect.
            zOrder = 0,
            paint = paintSpec,
        )
    }

    // ---- Filter parsing ----------------------------------------------------------------

    /**
     * Parse a Mapbox legacy filter array. Returns [MvtFilter.Always] on unparseable input
     * rather than throwing — filters are commonly malformed by hand-authored styles and
     * "always include" is the safer default than crashing the whole style.
     */
    private fun parseFilter(arr: JsonArray): MvtFilter {
        if (arr.isEmpty()) return MvtFilter.Always
        val op = (arr[0] as? JsonPrimitive)?.contentOrNull ?: return MvtFilter.Always
        return when (op) {
            "==" -> parseGeometryTypeEq(arr) ?: MvtFilter.Eq(arr.literal(1), arr.value(2))
            "!=" -> parseGeometryTypeEq(arr)?.let { MvtFilter.Not(it) }
                ?: MvtFilter.NotEq(arr.literal(1), arr.value(2))
            "in" -> MvtFilter.In(arr.literal(1), arr.drop(2).map(::valueOf).toSet())
            "!in" -> MvtFilter.Not(MvtFilter.In(arr.literal(1), arr.drop(2).map(::valueOf).toSet()))
            "has" -> MvtFilter.Has(arr.literal(1))
            "!has" -> MvtFilter.Not(MvtFilter.Has(arr.literal(1)))
            "<" -> MvtFilter.NumericLt(arr.literal(1), arr.number(2))
            "<=" -> MvtFilter.NumericLte(arr.literal(1), arr.number(2))
            ">" -> MvtFilter.NumericGt(arr.literal(1), arr.number(2))
            ">=" -> MvtFilter.NumericGte(arr.literal(1), arr.number(2))
            "all" -> MvtFilter.AllOf(arr.drop(1).filterIsInstance<JsonArray>().map(::parseFilter))
            "any" -> MvtFilter.AnyOf(arr.drop(1).filterIsInstance<JsonArray>().map(::parseFilter))
            "!" -> {
                val sub = arr.getOrNull(1) as? JsonArray ?: return MvtFilter.Always
                MvtFilter.Not(parseFilter(sub))
            }
            else -> MvtFilter.Always  // Unknown operator — pass-through
        }
    }

    /**
     * Detect Mapbox's `["==", ["geometry-type"], "Polygon"]` / `["==", "$type", "Polygon"]`
     * forms and emit an [MvtFilter.GeometryTypeEq]. Returns null when the arr isn't a
     * geometry-type predicate so the caller falls back to the regular Eq path.
     */
    private fun parseGeometryTypeEq(arr: JsonArray): MvtFilter? {
        val arg1 = arr.getOrNull(1) ?: return null
        val isGeomTypeRef = when (arg1) {
            is JsonArray -> arg1.size == 1 && (arg1[0] as? JsonPrimitive)?.contentOrNull == "geometry-type"
            is JsonPrimitive -> arg1.contentOrNull == "\$type"
            else -> false
        }
        if (!isGeomTypeRef) return null
        val typeName = (arr.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: return null
        val type = when (typeName) {
            "Point" -> MvtGeometryType.POINT
            "LineString" -> MvtGeometryType.LINESTRING
            "Polygon" -> MvtGeometryType.POLYGON
            else -> return null
        }
        return MvtFilter.GeometryTypeEq(type)
    }

    // ---- Paint parsing -----------------------------------------------------------------

    private fun parseFillPaint(paint: JsonObject?): MvtStyleRule.PaintSpec? {
        if (paint == null) return null
        val fillColor = paint["fill-color"]?.let(::parseColorInterp)
        val fillOpacity = paint["fill-opacity"]?.let(::parseFloatInterp)
        // Pure layer-of-nothing → skip.
        if (fillColor == null) return null
        return MvtStyleRule.PaintSpec(
            fillColor = fillColor,
            fillOpacity = fillOpacity,
            shadowMode = ShadowMode.DISABLED,
        )
    }

    private fun parseFillExtrusionPaint(paint: JsonObject?): MvtStyleRule.PaintSpec? {
        if (paint == null) return null
        val height = paint["fill-extrusion-height"]?.let(::parseFloatInterp) ?: return null
        val base = paint["fill-extrusion-base"]?.let(::parseFloatInterp)
        val color = paint["fill-extrusion-color"]?.let(::parseColorInterp)
        val opacity = paint["fill-extrusion-opacity"]?.let(::parseFloatInterp)
        return MvtStyleRule.PaintSpec(
            fillColor = color,
            fillOpacity = opacity,
            fillExtrusionHeight = height,
            fillExtrusionBase = base,
            shadowMode = ShadowMode.DISABLED,
        )
    }

    private fun parseLinePaint(paint: JsonObject?): MvtStyleRule.PaintSpec? {
        if (paint == null) return null
        val lineColor = paint["line-color"]?.let(::parseColorInterp)
        val lineWidth = paint["line-width"]?.let(::parseFloatInterp)
        val lineOpacity = paint["line-opacity"]?.let(::parseFloatInterp)
        val dashArray = (paint["line-dasharray"] as? JsonArray)?.let { arr ->
            arr.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }.toFloatArray()
                .takeIf { it.isNotEmpty() }
        }
        val gradient = paint["line-gradient"]?.let(::parseColorInterp)
        if (lineColor == null || lineWidth == null) return null
        return MvtStyleRule.PaintSpec(
            lineColor = lineColor,
            lineWidth = lineWidth,
            lineOpacity = lineOpacity,
            lineDashArray = dashArray,
            lineGradient = gradient,
            shadowMode = ShadowMode.DISABLED,
        )
    }

    private fun parseSymbolPaint(paint: JsonObject?, layout: JsonObject?): MvtStyleRule.PaintSpec? {
        // text-field lives under layout; everything else under paint. Both may be null.
        // Symbol layers may carry either or both of text-field and icon-image. We accept the
        // layer if either is present.
        val rawTextField = layout?.get("text-field")?.jsonPrimitive?.contentOrNull
        val textField = rawTextField?.let { extractFieldName(it) }
        val iconImageExpr = layout?.get("icon-image")?.let(::parseStringInterp)
        if (textField == null && iconImageExpr == null) return null

        // textField / iconImageExpr being non-null implies layout was non-null; smart-cast
        // promotes layout from JsonObject? to JsonObject here, so safe calls aren't needed.
        val textSize = layout["text-size"]?.let(::parseFloatInterp)
            ?: MvtExpression.Literal(16f)
        val textColor = paint?.get("text-color")?.let(::parseColorInterp)
            ?: MvtExpression.Literal(Color(0f, 0f, 0f))
        val haloColor = paint?.get("text-halo-color")?.let(::parseColorInterp)
        val haloWidth = paint?.get("text-halo-width")?.let(::parseFloatInterp)
        val font = layout["text-font"]
        val (family, weight) = extractFontInfo(font)
        val iconSize = layout["icon-size"]?.let(::parseFloatInterp)
        val iconOffset = layout["icon-offset"]?.let(::parseFloatOffsetX)
        val iconAnchor = layout["icon-anchor"]?.let(::parseStringInterp)
            ?: layout["text-anchor"]?.let(::parseStringInterp)
        // Mapbox spec default for text-max-width is 10 em when the property is absent. Apply
        // only to symbol layers that actually carry text — pure-icon symbols don't wrap.
        val maxWidth = layout["text-max-width"]?.let(::parseFloatInterp)
            ?: if (textField != null) MvtExpression.Literal(10f) else null
        return MvtStyleRule.PaintSpec(
            textField = textField,
            textColor = textColor,
            textSize = textSize,
            textHaloColor = haloColor,
            textHaloWidth = haloWidth,
            fontFamily = family,
            fontWeight = weight,
            iconImage = iconImageExpr,
            iconSize = iconSize,
            iconOffset = iconOffset,
            iconAnchor = iconAnchor,
            textMaxWidth = maxWidth,
        )
    }

    /** String-valued property. Constants accepted as bare JSON strings; expressions deferred. */
    private fun parseStringInterp(el: JsonElement): MvtExpression<String>? = when (el) {
        is JsonPrimitive -> el.contentOrNull?.let { MvtExpression.Literal(it) }
        else -> null
    }

    /**
     * Mapbox `icon-offset` is `[dx, dy]`. We only support a single horizontal offset right
     * now (the most common case for label-anchored icons); the Y component is ignored.
     */
    private fun parseFloatOffsetX(el: JsonElement): MvtExpression<Float>? = when (el) {
        is JsonArray -> (el.getOrNull(0) as? JsonPrimitive)?.floatOrNull
            ?.let { MvtExpression.Literal(it) }
        is JsonPrimitive -> el.floatOrNull?.let { MvtExpression.Literal(it) }
        else -> null
    }

    /**
     * Map a Mapbox `text-field` expression to a property key. We support the two most
     * common forms: `"{name}"` (curly-brace template) and `"name"` (bare property name).
     * Anything more elaborate (concat, format expressions) returns null and the symbol
     * layer is skipped.
     */
    private fun extractFieldName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.count { it == '{' } == 1) {
            return trimmed.substring(1, trimmed.length - 1).takeIf { it.isNotEmpty() }
        }
        // Bare property name — accept if it looks like an identifier rather than free text.
        if (trimmed.all { it.isLetterOrDigit() || it == '_' || it == ':' }) return trimmed
        return null
    }

    /**
     * Extract a font family + weight from Mapbox's `text-font` (array of font names — first
     * is primary, others are fallback). Weight is inferred from substring presence; this is
     * imprecise but matches the common Mapbox convention ("Open Sans Bold", "Roboto Bold").
     */
    private fun extractFontInfo(font: JsonElement?): Pair<String?, FontWeight> {
        if (font == null) return null to FontWeight.NORMAL
        val first = when (font) {
            is JsonArray -> font.firstOrNull()?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> font.contentOrNull
            else -> null
        } ?: return null to FontWeight.NORMAL
        val lowered = first.lowercase()
        val weight = when {
            "italic" in lowered -> FontWeight.ITALIC
            "bold" in lowered -> FontWeight.BOLD
            else -> FontWeight.NORMAL
        }
        // Strip the weight suffix for the family.
        val family = first.replace(Regex("\\s+(Bold|Italic|Regular|Light|Medium)\\b", RegexOption.IGNORE_CASE), "").trim()
        return family.takeIf { it.isNotEmpty() } to weight
    }

    // ---- Value parsing (color / float) ---------------------------------------------------

    private fun parseColorInterp(el: JsonElement): MvtExpression<Color>? = when (el) {
        is JsonPrimitive -> el.contentOrNull?.let(::parseColor)?.let { MvtExpression.Literal(it) }
        is JsonObject -> parseLegacyColorStops(el)
        is JsonArray -> MvtExpressionParser.parseColor(el, ::parseColor) ?: unsupported("color expression", el)
    }

    private fun parseFloatInterp(el: JsonElement): MvtExpression<Float>? = when (el) {
        is JsonPrimitive -> el.floatOrNull?.let { MvtExpression.Literal(it) }
        is JsonObject -> parseLegacyFloatStops(el)
        is JsonArray -> MvtExpressionParser.parseFloat(el) ?: unsupported("float expression", el)
    }

    private fun parseLegacyColorStops(obj: JsonObject): MvtExpression<Color>? {
        val stops = obj["stops"] as? JsonArray ?: return unsupported("color expression without 'stops'", obj)
        val parsed = stops.mapNotNull { stop ->
            val pair = stop as? JsonArray ?: return@mapNotNull null
            val zoom = pair.getOrNull(0)?.jsonPrimitive?.intOrNull
                ?: pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.toInt()
                ?: return@mapNotNull null
            val color = parseColor(pair.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null)
                ?: return@mapNotNull null
            zoom.toDouble() to MvtExpression.Literal(color) as MvtExpression<Color>
        }
        if (parsed.isEmpty()) return null
        return MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Linear,
            input = MvtExpression.Zoom,
            stops = parsed,
            lerp = MvtExpression.Interpolators.COLOR,
        )
    }

    private fun parseLegacyFloatStops(obj: JsonObject): MvtExpression<Float>? {
        val stops = obj["stops"] as? JsonArray ?: return unsupported("float expression without 'stops'", obj)
        val parsed = stops.mapNotNull { stop ->
            val pair = stop as? JsonArray ?: return@mapNotNull null
            val zoom = pair.getOrNull(0)?.jsonPrimitive?.intOrNull
                ?: pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.toInt()
                ?: return@mapNotNull null
            val v = pair.getOrNull(1)?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
            zoom.toDouble() to MvtExpression.Literal(v) as MvtExpression<Float>
        }
        if (parsed.isEmpty()) return null
        return MvtExpression.Interpolate(
            interpolation = MvtExpression.Interpolation.Linear,
            input = MvtExpression.Zoom,
            stops = parsed,
            lerp = MvtExpression.Interpolators.FLOAT,
        )
    }

    /**
     * Parse a Mapbox color string. Supports `#rgb`, `#rrggbb`, `#rrggbbaa`, and basic
     * `rgb(r,g,b)` / `rgba(r,g,b,a)` syntax. Named colors and `hsl(...)` are intentionally
     * out of scope; the rare style that uses them can be patched at the JSON layer.
     */
    fun parseColor(s: String): Color? {
        val t = s.trim()
        if (t.startsWith("#")) {
            val hex = t.substring(1)
            return when (hex.length) {
                3 -> Color(
                    hex.substring(0, 1).repeat(2).toInt(16),
                    hex.substring(1, 2).repeat(2).toInt(16),
                    hex.substring(2, 3).repeat(2).toInt(16),
                )
                6 -> Color(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                )
                8 -> Color(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                    hex.substring(6, 8).toInt(16),
                )
                else -> null
            }
        }
        if (t.startsWith("rgb(") || t.startsWith("rgba(")) {
            val inside = t.substringAfter("(").substringBeforeLast(")").split(",").map { it.trim() }
            val r = inside.getOrNull(0)?.toIntOrNull() ?: return null
            val g = inside.getOrNull(1)?.toIntOrNull() ?: return null
            val b = inside.getOrNull(2)?.toIntOrNull() ?: return null
            val a = inside.getOrNull(3)?.toFloatOrNull()?.let { (it * 255).toInt().coerceIn(0, 255) } ?: 255
            return Color(r, g, b, a)
        }
        return null
    }

    // ---- Helpers ------------------------------------------------------------------------

    private fun JsonArray.literal(index: Int): String =
        (getOrNull(index) as? JsonPrimitive)?.contentOrNull
            ?: throw MvtStyleParseException("filter operand at index $index is not a string: ${getOrNull(index)}")

    private fun JsonArray.value(index: Int): Any? = valueOf(getOrNull(index))

    private fun JsonArray.number(index: Int): Double =
        (getOrNull(index) as? JsonPrimitive)?.doubleOrNull
            ?: throw MvtStyleParseException("filter operand at index $index is not a number: ${getOrNull(index)}")

    private fun valueOf(el: JsonElement?): Any? = when (el) {
        null -> null
        is JsonPrimitive -> when {
            el.booleanOrNull != null -> el.boolean
            el.intOrNull != null -> el.int.toLong()  // MVT property ints arrive as Long
            el.doubleOrNull != null -> el.double()
            else -> el.contentOrNull
        }
        else -> null
    }

    private fun JsonPrimitive.double() = doubleOrNull ?: 0.0

    private fun unsupported(what: String, where: JsonElement): Nothing =
        throw MvtStyleParseException("Unsupported Mapbox expression: $what — near $where")
}

class MvtStyleParseException(message: String) : RuntimeException(message)
