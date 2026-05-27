package earth.worldwind.layer.mvt

import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.Color
import earth.worldwind.render.FontWeight

/**
 * Type-safe builder DSL for [MvtRuleBasedStyle]. Lets a style read as a flat list of
 * declarations:
 *
 * ```
 * val style = mvtStyle {
 *     rule("water_polygons") {
 *         zOrder = MvtStyle.Z_WATER
 *         paint { fill(Color(0.70f, 0.85f, 0.96f)) }
 *     }
 *     rule("streets") {
 *         filter = "kind" eq "motorway"
 *         minZoom = 6
 *         zOrder = MvtStyle.Z_ROAD_MOTORWAY
 *         paint {
 *             line(Color(0.91f, 0.39f, 0.32f))
 *             lineWidth = MvtZoomInterp.floats(6 to 0.5f, 10 to 1.5f, 14 to 3.5f)
 *         }
 *     }
 * }
 * ```
 *
 * Rules are emitted in declaration order — the same order they paint in (earlier under
 * later). The first rule whose [MvtStyleRule.matches] returns true is the one that styles
 * each feature; subsequent rules are ignored for that feature.
 */
@DslMarker
annotation class MvtStyleDsl

/** Top-level DSL entry: build an [MvtRuleBasedStyle]. */
fun mvtStyle(block: MvtStyleBuilder.() -> Unit): MvtRuleBasedStyle =
    MvtStyleBuilder().apply(block).build()

@MvtStyleDsl
class MvtStyleBuilder {
    private val rules = mutableListOf<MvtStyleRule>()

    /**
     * Declare one rule targeting [sourceLayer]. Configure inside the lambda — at minimum
     * call [MvtRuleBuilder.paint] with a fill or line.
     */
    fun rule(sourceLayer: String, block: MvtRuleBuilder.() -> Unit) {
        val b = MvtRuleBuilder(sourceLayer).apply(block)
        rules += b.build()
    }

    fun build(): MvtRuleBasedStyle = MvtRuleBasedStyle(rules.toList())
}

@MvtStyleDsl
class MvtRuleBuilder(private val sourceLayer: String) {
    var filter: MvtFilter = MvtFilter.Always
    var minZoom: Int = 0
    var maxZoom: Int = Int.MAX_VALUE
    var zOrder: Int = 0
    var geometryType: MvtGeometryType? = null
    private var paint: MvtStyleRule.PaintSpec? = null

    fun paint(block: MvtPaintBuilder.() -> Unit) {
        paint = MvtPaintBuilder().apply(block).build()
    }

    internal fun build(): MvtStyleRule = MvtStyleRule(
        sourceLayer = sourceLayer,
        filter = filter,
        minZoom = minZoom,
        maxZoom = maxZoom,
        zOrder = zOrder,
        geometryType = geometryType,
        paint = paint ?: error(
            "Rule for sourceLayer='$sourceLayer' has no paint{}; supply at least fill() or line()."
        ),
    )
}

@MvtStyleDsl
class MvtPaintBuilder {
    // Shape paint
    var fillColor: MvtExpression<Color>? = null
    var fillOpacity: MvtExpression<Float>? = null
    var lineColor: MvtExpression<Color>? = null
    var lineWidth: MvtExpression<Float>? = null
    var lineOpacity: MvtExpression<Float>? = null
    var shadowMode: ShadowMode = ShadowMode.DISABLED
    // Text paint (label rendering)
    private var textField: String? = null
    private var textColor: MvtExpression<Color>? = null
    private var textSize: MvtExpression<Float>? = null
    private var textHaloColor: MvtExpression<Color>? = null
    private var textHaloWidth: MvtExpression<Float>? = null
    private var fontFamily: String? = null
    private var fontWeight: FontWeight = FontWeight.NORMAL

    /** Constant fill color shorthand. */
    fun fill(color: Color) { fillColor = MvtZoomInterp.constant(color) }
    /** Constant line color + width shorthand. */
    fun line(color: Color, width: Float = 1f) {
        lineColor = MvtZoomInterp.constant(color)
        lineWidth = MvtZoomInterp.constant(width)
    }

    // Casing — declared next to line(), populated via `casing(...)`. Threaded through build().
    private var lineCasingColor: MvtExpression<Color>? = null
    private var lineCasingWidth: MvtExpression<Float>? = null
    private var lineDashArray: FloatArray? = null
    private var lineGradient: MvtExpression<Color>? = null

    /**
     * Mapbox `line-gradient` — set a color expression that's evaluated along the line's
     * arc-length parameter. Typical use: `["interpolate", ["linear"], ["line-progress"],
     * 0, "green", 0.5, "yellow", 1, "red"]` for a route-elevation tint. The layer
     * subdivides each polyline into [MvtStyleRule.PaintSpec.GRADIENT_SUBDIVISIONS]
     * piecewise-constant segments for rendering.
     */
    fun gradient(expression: MvtExpression<Color>) { lineGradient = expression }
    private var fillExtrusionHeight: MvtExpression<Float>? = null
    private var fillExtrusionBase: MvtExpression<Float>? = null

    /**
     * Constant 3D-extrusion shorthand. Polygon features matching this rule extrude to
     * [heightMeters] above terrain (optionally starting [baseMeters] above ground). Renders
     * via OsmBuildingsTile — same path the OSM Buildings layer uses, so styling reads as
     * `fill-extrusion-height` in Mapbox terms.
     */
    fun extrusion(heightMeters: Float, baseMeters: Float = 0f) {
        fillExtrusionHeight = MvtZoomInterp.constant(heightMeters)
        fillExtrusionBase = if (baseMeters > 0f) MvtZoomInterp.constant(baseMeters) else null
    }

    /**
     * Mapbox `line-dasharray` shorthand. Alternating dash + gap lengths (e.g.
     * `dasharray(2f, 2f)` = even dashes/gaps; `dasharray(3f, 1f, 1f, 1f)` = long-short-short).
     * Dashed features bypass [MvtBatchedLineTile] and render as per-feature
     * [earth.worldwind.shape.Path]s.
     */
    fun dasharray(vararg dashes: Float) {
        lineDashArray = if (dashes.isEmpty()) null else dashes
    }

    /**
     * Constant line-casing shorthand. The casing draws as a wider stroke under [line] /
     * [lineWidth], giving the Mapbox-style road-shoulder effect. [width] is the total casing
     * stroke width; visible thickness on each side of the fill = `(width - lineWidth) / 2`.
     */
    fun casing(color: Color, width: Float) {
        lineCasingColor = MvtZoomInterp.constant(color)
        lineCasingWidth = MvtZoomInterp.constant(width)
    }

    /**
     * Declare a text label for matching features. [field] names the feature property to read
     * for the label string (typically `"name"`). Configure color / size / halo / placement
     * via the [MvtTextBuilder] receiver. Default placement is POINT (matches POINT
     * features); set `placement = MvtStyleRule.LabelPlacement.LINE` to label LINESTRING
     * features at their midpoint along the local tangent.
     *
     * ```
     * paint {
     *     text("name") {
     *         placement = MvtStyleRule.LabelPlacement.LINE
     *         color(Color(1f, 1f, 1f))
     *         size = MvtZoomInterp.floats(13 to 12f, 16 to 16f)
     *         halo(Color(0f, 0f, 0f), 2f)
     *     }
     * }
     * ```
     */
    fun text(field: String, block: MvtTextBuilder.() -> Unit = {}) {
        val b = MvtTextBuilder().apply(block)
        textField = field
        textColor = b.color
        textSize = b.size
        textHaloColor = b.haloColor
        textHaloWidth = b.haloWidth
        fontFamily = b.fontFamily
        fontWeight = b.fontWeight
        textPlacement = b.placement
        textMaxWidth = b.maxWidth
    }

    /**
     * Declare a sprite-atlas icon for matching POINT features. [image] is the atlas entry
     * name (Mapbox-style `{property}` placeholders are expanded against the feature's
     * properties at resolve time).
     *
     * ```
     * paint {
     *     icon("mountain-peak") { size = 1.2f }
     *     // or with a per-feature template:
     *     icon("poi-{kind}")
     * }
     * ```
     */
    fun icon(image: String, block: MvtIconBuilder.() -> Unit = {}) {
        val b = MvtIconBuilder().apply(block)
        iconImage = MvtExpression.Literal(image)
        iconSize = b.size
        iconOffset = b.offset
        iconAnchor = b.anchor
    }

    // Threaded through to PaintSpec via build(); declared here so build() can read.
    private var textPlacement: MvtStyleRule.LabelPlacement = MvtStyleRule.LabelPlacement.POINT
    private var textMaxWidth: MvtExpression<Float>? = null
    // Icon paint
    private var iconImage: MvtExpression<String>? = null
    private var iconSize: MvtExpression<Float>? = null
    private var iconOffset: MvtExpression<Float>? = null
    private var iconAnchor: MvtExpression<String>? = null

    internal fun build(): MvtStyleRule.PaintSpec = MvtStyleRule.PaintSpec(
        fillColor = fillColor,
        fillOpacity = fillOpacity,
        fillExtrusionHeight = fillExtrusionHeight,
        fillExtrusionBase = fillExtrusionBase,
        lineColor = lineColor,
        lineWidth = lineWidth,
        lineOpacity = lineOpacity,
        lineCasingColor = lineCasingColor,
        lineCasingWidth = lineCasingWidth,
        lineDashArray = lineDashArray,
        lineGradient = lineGradient,
        shadowMode = shadowMode,
        textField = textField,
        textColor = textColor,
        textSize = textSize,
        textHaloColor = textHaloColor,
        textHaloWidth = textHaloWidth,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        textPlacement = textPlacement,
        textMaxWidth = textMaxWidth,
        iconImage = iconImage,
        iconSize = iconSize,
        iconOffset = iconOffset,
        iconAnchor = iconAnchor,
    )
}

@MvtStyleDsl
class MvtIconBuilder {
    /** Multiplier on the icon's native pixel size. */
    var size: MvtExpression<Float>? = null
    /** Horizontal offset in icon-pixel units (applied to anchor). */
    var offset: MvtExpression<Float>? = null
    /** Anchor: `"center" | "top" | "bottom" | "left" | "right"`. */
    var anchor: MvtExpression<String>? = null
}

@MvtStyleDsl
class MvtTextBuilder {
    /** Constant or zoom-interpolated text color. Use [MvtZoomInterp.constant] for a fixed value. */
    var color: MvtExpression<Color>? = null
    /** Constant or zoom-interpolated text size in pixels. */
    var size: MvtExpression<Float>? = null
    /** Halo (outline) behind text. Use [halo] shorthand for the common case. */
    var haloColor: MvtExpression<Color>? = null
    var haloWidth: MvtExpression<Float>? = null
    var fontFamily: String? = null
    var fontWeight: FontWeight = FontWeight.NORMAL
    /**
     * Label placement strategy — POINT (default, anchored at feature geo) or LINE (single
     * label at the midpoint of a LINESTRING, rotated along the local tangent).
     */
    var placement: MvtStyleRule.LabelPlacement = MvtStyleRule.LabelPlacement.POINT
    /** Mapbox `text-max-width` (em units). Unset = no word-wrap. */
    var maxWidth: MvtExpression<Float>? = null

    /** Constant-color shorthand for [color]. */
    fun color(c: Color) { color = MvtZoomInterp.constant(c) }
    /** Constant-size shorthand for [size]. */
    fun size(px: Float) { size = MvtZoomInterp.constant(px) }
    /** Constant halo shorthand — colour + width together. */
    fun halo(color: Color, width: Float) {
        haloColor = MvtZoomInterp.constant(color)
        haloWidth = MvtZoomInterp.constant(width)
    }
}
