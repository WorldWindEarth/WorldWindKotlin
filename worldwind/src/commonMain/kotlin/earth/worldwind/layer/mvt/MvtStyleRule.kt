package earth.worldwind.layer.mvt

import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.TextAttributes

/**
 * One declarative rule in an [MvtRuleBasedStyle]. Matches features by source layer name,
 * optional [filter] over properties, and tile-zoom range, then resolves zoom-interpolated
 * paint properties into a [ShapeAttributes].
 *
 * Rules paint in declaration order — earlier rules in the list paint under later rules. The
 * [zOrder] field also feeds [MvtStyle.zOrderFor] so cross-tile compositing matches the
 * within-tile order.
 *
 * @param sourceLayer  MVT layer name to match (e.g. `"streets"`, `"water_polygons"`). Use
 *                     [MvtStyle.zOrderFor] canonicals where they apply.
 * @param filter       predicate over the feature's property map; defaults to [MvtFilter.Always].
 * @param minZoom      smallest tile zoom that activates this rule (inclusive); below = skipped.
 * @param maxZoom      largest tile zoom that activates this rule (inclusive); above = skipped.
 *                     [Int.MAX_VALUE] = no upper bound.
 * @param zOrder       paint order against rules in other tiles. Use [MvtStyle.Z_*] constants.
 * @param geometryType when non-null, only matches features of that geometry type. Useful for
 *                     splitting a layer that carries both polygon and line variants (e.g.
 *                     Shortbread's `street_polygons` vs `streets`).
 * @param paint        resolves zoom-interpolated paint properties into a [ShapeAttributes].
 */
class MvtStyleRule(
    val sourceLayer: String,
    val filter: MvtFilter = MvtFilter.Always,
    val minZoom: Int = 0,
    val maxZoom: Int = Int.MAX_VALUE,
    val zOrder: Int = 0,
    val geometryType: MvtGeometryType? = null,
    val paint: PaintSpec,
) {

    /**
     * Resolve [paint] for one feature into a concrete [ShapeAttributes]. Called once per
     * matching feature during tile assembly. Results are NOT cached — short-lived attribute
     * instances flow into the batched tile and are consumed by color packing.
     *
     * [properties] feeds `["get", key]` / `["case", …, ["get", "kind"], …]` expressions.
     */
    fun resolve(zoom: Int, properties: Map<String, Any?> = emptyMap()): ShapeAttributes =
        paint.build(zoom, properties)

    fun matches(
        layerName: String,
        geomType: MvtGeometryType,
        zoom: Int,
        properties: Map<String, Any?>,
    ): Boolean {
        if (layerName != sourceLayer) return false
        if (geometryType != null && geometryType != geomType) return false
        if (zoom < minZoom || zoom > maxZoom) return false
        return filter.matches(properties)
    }

    /**
     * Bundle of zoom-interpolated paint properties for one rule. Fields default to "draw
     * nothing"; supply only what your rule needs and the rest stays inert.
     *
     * Shape paint (polygons / lines):
     *   - fillColor (+ optional fillOpacity) → polygon fill
     *   - lineColor + lineWidth (+ optional lineOpacity) → polygon/line stroke
     * Both shape paint groups can coexist on one rule — a polygon with both fill and outline.
     *
     * Text paint (point labels):
     *   - textField → name of the feature property whose value becomes the label text.
     *     If null, this rule produces no label. If the property is missing/empty on a
     *     given feature, that feature is skipped silently.
     *   - textColor, textSize → the text glyphs; textSize is in pixels.
     *   - textHaloColor + textHaloWidth → optional outline behind text for readability
     *     over busy backgrounds.
     *   - fontFamily, fontWeight → platform-resolved (system default if null).
     *
     * Shape and text paint are independent: a rule with both will produce both renderable
     * types for matching features. In practice text rules apply to POINT features and shape
     * rules to POLYGON/LINESTRING, so the geometryType gate keeps them separate.
     */
    class PaintSpec(
        // ----- Shape paint -----
        val fillColor: MvtExpression<Color>? = null,
        val fillOpacity: MvtExpression<Float>? = null,
        val lineColor: MvtExpression<Color>? = null,
        val lineWidth: MvtExpression<Float>? = null,
        val lineOpacity: MvtExpression<Float>? = null,
        /**
         * Optional **casing** stroke — Mapbox's "road shoulder" effect. When set, the layer
         * emits a SECOND line stroke for the same feature at a wider [lineCasingWidth] in
         * [lineCasingColor], painted under the main line. Used for road outlines (white road
         * over dark grey casing), railway double-stripes, etc.
         */
        val lineCasingColor: MvtExpression<Color>? = null,
        /**
         * Casing width in pixels. Should be > [lineWidth]; the visible casing thickness on
         * either side of the fill line is `(lineCasingWidth - lineWidth) / 2`.
         */
        val lineCasingWidth: MvtExpression<Float>? = null,
        val shadowMode: ShadowMode = ShadowMode.DISABLED,
        // ----- Text paint -----
        val textField: String? = null,
        val textColor: MvtExpression<Color>? = null,
        val textSize: MvtExpression<Float>? = null,
        val textHaloColor: MvtExpression<Color>? = null,
        val textHaloWidth: MvtExpression<Float>? = null,
        val fontFamily: String? = null,
        val fontWeight: FontWeight = FontWeight.NORMAL,
        /**
         * Placement strategy for labels. [LabelPlacement.POINT] is the default and matches
         * POINT features at their geographic anchor; [LabelPlacement.LINE] places one label
         * per matching LINESTRING feature at its midpoint, rotated to follow the local
         * tangent. Set via the DSL's `text(field) { placement = ... }` block.
         */
        val textPlacement: LabelPlacement = LabelPlacement.POINT,
        /**
         * Mapbox `text-max-width` — maximum label width in **em** units (multiples of font
         * size). When set, [buildText] inserts `\n` at the last word boundary before each
         * overflow, producing a multi-line label. Default unset = no wrapping.
         */
        val textMaxWidth: MvtExpression<Float>? = null,
        // ----- Icon paint -----
        /**
         * Mapbox-style `icon-image` — name of the icon to look up in the layer's sprite
         * atlas. Strings can include `{property}` placeholders for per-feature substitution
         * (e.g. `"poi-{kind}"`), matching Mapbox GL's `icon-image` template form.
         */
        val iconImage: MvtExpression<String>? = null,
        /** Multiplier applied to the icon's pixel size; defaults to 1.0. */
        val iconSize: MvtExpression<Float>? = null,
        /**
         * Offset in icon-pixel space, applied to the icon's anchor point. Mapbox's
         * `icon-offset` is a 2D vector; we accept the X component here for the common case
         * of horizontal text-anchored icons.
         */
        val iconOffset: MvtExpression<Float>? = null,
        /**
         * One of `"center" | "top" | "bottom" | "left" | "right"` controlling where on the
         * icon the geographic anchor sits. Defaults to `center`.
         */
        val iconAnchor: MvtExpression<String>? = null,
    ) {

        /** True when this rule has at least one shape paint property set. */
        val hasShape: Boolean get() = fillColor != null || (lineColor != null && lineWidth != null)

        /** True when this rule has a label text spec. */
        val hasText: Boolean get() = textField != null

        /** True when this rule has an icon spec. */
        val hasIcon: Boolean get() = iconImage != null

        /** True when this rule has a line casing — emits a second stroke under the line. */
        val hasCasing: Boolean get() = lineCasingColor != null && lineCasingWidth != null

        fun build(zoom: Int, properties: Map<String, Any?> = emptyMap()): ShapeAttributes {
            val ctx = MvtExpression.EvalContext(zoom.toDouble(), properties)
            return ShapeAttributes().apply {
                isDrawInterior = fillColor != null
                isDrawOutline = lineColor != null && lineWidth != null
                shadowMode = this@PaintSpec.shadowMode
                fillColor?.evaluate(ctx)?.let { c ->
                    val alpha = fillOpacity?.evaluate(ctx) ?: c.alpha
                    interiorColor = Color(c.red, c.green, c.blue, alpha)
                }
                lineColor?.evaluate(ctx)?.let { c ->
                    val alpha = lineOpacity?.evaluate(ctx) ?: c.alpha
                    outlineColor = Color(c.red, c.green, c.blue, alpha)
                }
                lineWidth?.evaluate(ctx)?.let { outlineWidth = it }
            }
        }

        /**
         * Resolve the label text + [TextAttributes] for one feature. Returns `null` if this
         * rule isn't a text rule ([hasText] is false), or if the feature has no value for
         * [textField], or the value's string form is empty.
         *
         * The text is `properties[textField].toString().trim()` — MVT property values can
         * arrive as String / Long / Double / Boolean, all of which have sensible string
         * forms for label use.
         */
        fun buildText(zoom: Int, properties: Map<String, Any?>): LabelSpec? {
            if (textField == null) return null
            val raw = properties[textField] ?: return null
            val rawText = raw.toString().trim()
            if (rawText.isEmpty()) return null
            val ctx = MvtExpression.EvalContext(zoom.toDouble(), properties)
            // Resolve all PaintSpec fields up front — references inside `apply { }` would
            // shadow against TextAttributes' properties of the same names (e.g. `textColor`
            // becomes `this.textColor: Color`, not `this@PaintSpec.textColor: MvtExpression`).
            val resolvedTextColor = textColor?.evaluate(ctx)
            val sizePx = textSize?.evaluate(ctx)?.toInt()?.coerceAtLeast(1) ?: DEFAULT_TEXT_SIZE
            val resolvedFont = Font(fontFamily ?: DEFAULT_FONT_FAMILY, fontWeight, sizePx)
            val resolvedHaloColor = textHaloColor?.evaluate(ctx)
            val resolvedHaloWidth = textHaloWidth?.evaluate(ctx)
            // Word-wrap if textMaxWidth is set. Maxwidth is in em units (× font size).
            val text = textMaxWidth?.evaluate(ctx)?.let { emWidth ->
                if (emWidth <= 0f) rawText
                else wrapText(rawText, resolvedFont, emWidth * sizePx)
            } ?: rawText
            val attrs = TextAttributes().apply {
                resolvedTextColor?.let { textColor.copy(it) }
                font.copy(resolvedFont)
                if (resolvedHaloColor != null && resolvedHaloWidth != null && resolvedHaloWidth > 0f) {
                    outlineColor.copy(resolvedHaloColor)
                    outlineWidth = resolvedHaloWidth
                    isOutlineEnabled = true
                } else {
                    isOutlineEnabled = false
                }
            }
            return LabelSpec(text, attrs, sizePx)
        }

        /**
         * Resolve the casing-stroke [ShapeAttributes] for one feature. Returns null if this
         * rule has no casing paint. The result is meant to be emitted as a SEPARATE line
         * shape at a slightly lower z-order than [build]'s result, so the wider casing paints
         * underneath the narrower fill.
         */
        fun buildCasing(zoom: Int, properties: Map<String, Any?> = emptyMap()): ShapeAttributes? {
            if (!hasCasing) return null
            val ctx = MvtExpression.EvalContext(zoom.toDouble(), properties)
            val color = lineCasingColor!!.evaluate(ctx) ?: return null
            val width = lineCasingWidth!!.evaluate(ctx) ?: return null
            if (width <= 0f) return null
            return ShapeAttributes().apply {
                isDrawInterior = false
                isDrawOutline = true
                shadowMode = this@PaintSpec.shadowMode
                val alpha = lineOpacity?.evaluate(ctx) ?: color.alpha
                outlineColor = Color(color.red, color.green, color.blue, alpha)
                outlineWidth = width
            }
        }

        /**
         * Resolve this rule's icon paint for one feature. Returns null when [iconImage] is
         * unset, or when the resolved icon name (after `{property}` substitution) is empty.
         * Caller is responsible for looking up the name in an [MvtSpriteAtlas].
         */
        fun buildIcon(zoom: Int, properties: Map<String, Any?>): IconSpec? {
            val expr = iconImage ?: return null
            val ctx = MvtExpression.EvalContext(zoom.toDouble(), properties)
            val rawName = expr.evaluate(ctx) ?: return null
            val name = substituteTemplate(rawName, properties).takeIf { it.isNotEmpty() } ?: return null
            val size = iconSize?.evaluate(ctx) ?: 1f
            val offset = iconOffset?.evaluate(ctx) ?: 0f
            val anchor = iconAnchor?.evaluate(ctx) ?: "center"
            return IconSpec(name, size, offset, anchor)
        }

        companion object {
            private const val DEFAULT_TEXT_SIZE = 14
            private const val DEFAULT_FONT_FAMILY = "sans-serif"

            /**
             * Greedy word-wrap: split [text] on spaces and insert `\n` at the last word
             * boundary that keeps each line ≤ [maxWidthPx] using [font]'s measured advance.
             * Single words longer than the max width stay on their own line (don't split
             * inside a word — Mapbox doesn't either).
             */
            internal fun wrapText(text: String, font: Font, maxWidthPx: Float): String {
                if (maxWidthPx <= 0f || ' ' !in text) return text
                val words = text.split(' ').filter { it.isNotEmpty() }
                if (words.isEmpty()) return text
                val sb = StringBuilder(text.length + 4)
                var lineWidth = 0f
                val spaceWidth = font.measureText(" ")
                for ((i, w) in words.withIndex()) {
                    val wWidth = font.measureText(w)
                    val needsSpace = i > 0 && lineWidth > 0f
                    val projected = lineWidth + (if (needsSpace) spaceWidth else 0f) + wWidth
                    if (lineWidth > 0f && projected > maxWidthPx) {
                        sb.append('\n')
                        sb.append(w)
                        lineWidth = wWidth
                    } else {
                        if (needsSpace) { sb.append(' '); lineWidth += spaceWidth }
                        sb.append(w)
                        lineWidth += wWidth
                    }
                }
                return sb.toString()
            }

            /**
             * Expand Mapbox-style `{property}` placeholders in [template] against [properties].
             * Missing keys expand to the empty string (matching Mapbox semantics).
             */
            internal fun substituteTemplate(template: String, properties: Map<String, Any?>): String {
                if ('{' !in template) return template
                val sb = StringBuilder(template.length)
                var i = 0
                while (i < template.length) {
                    val c = template[i]
                    if (c == '{') {
                        val end = template.indexOf('}', i + 1)
                        if (end > 0) {
                            val key = template.substring(i + 1, end)
                            sb.append(properties[key]?.toString() ?: "")
                            i = end + 1
                            continue
                        }
                    }
                    sb.append(c)
                    i++
                }
                return sb.toString()
            }
        }
    }

    /**
     * Resolved icon payload. The [name] is the atlas entry to look up; [size] is a multiplier
     * on the atlas entry's native pixel size; [offset] is a horizontal offset in icon-pixel
     * units; [anchor] picks where on the icon the geographic point sits.
     */
    class IconSpec(val name: String, val size: Float, val offset: Float, val anchor: String)

    /**
     * Resolved label payload. [pixelSize] is read by [MvtLabelGroup]'s collision pass to
     * size its screen-space bbox — [earth.worldwind.render.Font] is an `expect class` and
     * doesn't expose its size in commonMain, so we keep it separate.
     */
    class LabelSpec(val text: String, val attributes: TextAttributes, val pixelSize: Int)

    /**
     * Where the label sits on the feature it labels.
     * - [POINT] places at the geographic anchor; only matches POINT features.
     * - [LINE]  places at the midpoint of a LINESTRING feature, oriented along the local
     *           tangent. Mirrors Mapbox GL's `symbol-placement: line-center`.
     *
     * Per-glyph baseline-following over the full curve (Mapbox's `symbol-placement: line`)
     * is a separate primitive and not yet supported.
     */
    enum class LabelPlacement { POINT, LINE }
}

/**
 * Style backed by an ordered list of [MvtStyleRule]s. For each feature, iterates rules in
 * order and returns the first match's resolved attributes. The same first match's [zOrder]
 * is reported from [zOrderFor].
 *
 * Rule order = paint order = z-order tiebreak. Put earlier rules where you want them under
 * later rules. Within a tile, this layer relies on the rule list itself for cross-feature
 * ordering; the per-rule [MvtStyleRule.zOrder] is used by [MvtVectorLayer] to coordinate
 * cross-tile compositing (water-tile under road-tile etc.).
 *
 * Use the [mvtStyle] DSL to author one of these declaratively, or pass a pre-built list of
 * rules to the constructor.
 */
class MvtRuleBasedStyle(val rules: List<MvtStyleRule>) : MvtStyle {

    /** Zoom-blind callers resolve at zoom 0; use the zoom-aware overload for proper interpolation. */
    override fun styleFor(
        layerName: String,
        geometryType: MvtGeometryType,
        properties: Map<String, Any?>,
    ): ShapeAttributes? = styleFor(layerName, geometryType, zoom = 0, properties = properties)

    /** Zoom-aware overload — [MvtVectorLayer] passes the tile's z. */
    fun styleFor(
        layerName: String,
        geometryType: MvtGeometryType,
        zoom: Int,
        properties: Map<String, Any?>,
    ): ShapeAttributes? {
        val rule = firstMatching(layerName, geometryType, zoom, properties) ?: return null
        return rule.resolve(zoom)
    }

    override fun zOrderFor(
        layerName: String,
        geometryType: MvtGeometryType,
        properties: Map<String, Any?>,
    ): Int = firstMatching(layerName, geometryType, zoom = Int.MAX_VALUE / 2, properties)?.zOrder ?: 0

    fun firstMatching(
        layerName: String,
        geometryType: MvtGeometryType,
        zoom: Int,
        properties: Map<String, Any?>,
    ): MvtStyleRule? = rules.firstOrNull { it.matches(layerName, geometryType, zoom, properties) }

    /**
     * First rule whose [MvtStyleRule.matches] is true AND which also satisfies [predicate].
     * Lets one feature carry both a shape paint (matched by one rule) and a text paint
     * (matched by another) — useful for roads whose stroke and label come from distinct
     * style entries.
     */
    fun firstMatching(
        layerName: String,
        geometryType: MvtGeometryType,
        zoom: Int,
        properties: Map<String, Any?>,
        predicate: (MvtStyleRule) -> Boolean,
    ): MvtStyleRule? = rules.firstOrNull {
        predicate(it) && it.matches(layerName, geometryType, zoom, properties)
    }
}
