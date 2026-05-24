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
    var fillColor: MvtZoomInterp<Color>? = null
    var fillOpacity: MvtZoomInterp<Float>? = null
    var lineColor: MvtZoomInterp<Color>? = null
    var lineWidth: MvtZoomInterp<Float>? = null
    var lineOpacity: MvtZoomInterp<Float>? = null
    var shadowMode: ShadowMode = ShadowMode.DISABLED
    // Text paint (label rendering)
    private var textField: String? = null
    private var textColor: MvtZoomInterp<Color>? = null
    private var textSize: MvtZoomInterp<Float>? = null
    private var textHaloColor: MvtZoomInterp<Color>? = null
    private var textHaloWidth: MvtZoomInterp<Float>? = null
    private var fontFamily: String? = null
    private var fontWeight: FontWeight = FontWeight.NORMAL

    /** Constant fill color shorthand. */
    fun fill(color: Color) { fillColor = MvtZoomInterp.constant(color) }
    /** Constant line color + width shorthand. */
    fun line(color: Color, width: Float = 1f) {
        lineColor = MvtZoomInterp.constant(color)
        lineWidth = MvtZoomInterp.constant(width)
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
    }
    // Threaded through to PaintSpec via build(); declared here so build() can read.
    private var textPlacement: MvtStyleRule.LabelPlacement = MvtStyleRule.LabelPlacement.POINT

    internal fun build(): MvtStyleRule.PaintSpec = MvtStyleRule.PaintSpec(
        fillColor = fillColor,
        fillOpacity = fillOpacity,
        lineColor = lineColor,
        lineWidth = lineWidth,
        lineOpacity = lineOpacity,
        shadowMode = shadowMode,
        textField = textField,
        textColor = textColor,
        textSize = textSize,
        textHaloColor = textHaloColor,
        textHaloWidth = textHaloWidth,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        textPlacement = textPlacement,
    )
}

@MvtStyleDsl
class MvtTextBuilder {
    /** Constant or zoom-interpolated text color. Use [MvtZoomInterp.constant] for a fixed value. */
    var color: MvtZoomInterp<Color>? = null
    /** Constant or zoom-interpolated text size in pixels. */
    var size: MvtZoomInterp<Float>? = null
    /** Halo (outline) behind text. Use [halo] shorthand for the common case. */
    var haloColor: MvtZoomInterp<Color>? = null
    var haloWidth: MvtZoomInterp<Float>? = null
    var fontFamily: String? = null
    var fontWeight: FontWeight = FontWeight.NORMAL
    /**
     * Label placement strategy — POINT (default, anchored at feature geo) or LINE (single
     * label at the midpoint of a LINESTRING, rotated along the local tangent).
     */
    var placement: MvtStyleRule.LabelPlacement = MvtStyleRule.LabelPlacement.POINT

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
