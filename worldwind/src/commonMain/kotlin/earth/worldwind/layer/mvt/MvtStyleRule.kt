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
     * Resolve [paint] at the given tile zoom into a concrete [ShapeAttributes]. Called once
     * per matching feature during tile assembly. Results are NOT cached — short-lived
     * attribute instances flow into the batched tile and are consumed by color packing.
     */
    fun resolve(zoom: Int): ShapeAttributes = paint.build(zoom)

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
        val fillColor: MvtZoomInterp<Color>? = null,
        val fillOpacity: MvtZoomInterp<Float>? = null,
        val lineColor: MvtZoomInterp<Color>? = null,
        val lineWidth: MvtZoomInterp<Float>? = null,
        val lineOpacity: MvtZoomInterp<Float>? = null,
        val shadowMode: ShadowMode = ShadowMode.DISABLED,
        // ----- Text paint -----
        val textField: String? = null,
        val textColor: MvtZoomInterp<Color>? = null,
        val textSize: MvtZoomInterp<Float>? = null,
        val textHaloColor: MvtZoomInterp<Color>? = null,
        val textHaloWidth: MvtZoomInterp<Float>? = null,
        val fontFamily: String? = null,
        val fontWeight: FontWeight = FontWeight.NORMAL,
        /**
         * Placement strategy for labels. [LabelPlacement.POINT] is the default and matches
         * POINT features at their geographic anchor; [LabelPlacement.LINE] places one label
         * per matching LINESTRING feature at its midpoint, rotated to follow the local
         * tangent. Set via the DSL's `text(field) { placement = ... }` block.
         */
        val textPlacement: LabelPlacement = LabelPlacement.POINT,
    ) {

        /** True when this rule has at least one shape paint property set. */
        val hasShape: Boolean get() = fillColor != null || (lineColor != null && lineWidth != null)

        /** True when this rule has a label text spec. */
        val hasText: Boolean get() = textField != null

        fun build(zoom: Int): ShapeAttributes = ShapeAttributes().apply {
            isDrawInterior = fillColor != null
            isDrawOutline = lineColor != null && lineWidth != null
            shadowMode = this@PaintSpec.shadowMode
            fillColor?.let {
                val c = it.valueAt(zoom)
                val alpha = fillOpacity?.valueAt(zoom) ?: c.alpha
                interiorColor = Color(c.red, c.green, c.blue, alpha)
            }
            lineColor?.let {
                val c = it.valueAt(zoom)
                val alpha = lineOpacity?.valueAt(zoom) ?: c.alpha
                outlineColor = Color(c.red, c.green, c.blue, alpha)
            }
            lineWidth?.let { outlineWidth = it.valueAt(zoom) }
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
            val text = raw.toString().trim()
            if (text.isEmpty()) return null
            // Resolve all PaintSpec fields up front — references inside `apply { }` would
            // shadow against TextAttributes' properties of the same names (e.g. `textColor`
            // becomes `this.textColor: Color`, not `this@PaintSpec.textColor: MvtZoomInterp`).
            val resolvedTextColor = textColor?.valueAt(zoom)
            val sizePx = textSize?.valueAt(zoom)?.toInt()?.coerceAtLeast(1) ?: DEFAULT_TEXT_SIZE
            val resolvedFont = Font(fontFamily ?: DEFAULT_FONT_FAMILY, fontWeight, sizePx)
            val resolvedHaloColor = textHaloColor?.valueAt(zoom)
            val resolvedHaloWidth = textHaloWidth?.valueAt(zoom)
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

        companion object {
            private const val DEFAULT_TEXT_SIZE = 14
            private const val DEFAULT_FONT_FAMILY = "sans-serif"
        }
    }

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
