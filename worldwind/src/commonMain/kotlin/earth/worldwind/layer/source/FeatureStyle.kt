package earth.worldwind.layer.source

import earth.worldwind.geom.OffsetMode
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.Label
import earth.worldwind.shape.Placemark

/**
 * Style attributes applied to feature renderables built from a properties map. Recognises
 * the GeoJSON [simplestyle-spec](https://github.com/mapbox/simplestyle-spec) keys (`stroke`,
 * `fill`, `stroke-width`, `stroke-opacity`, `fill-opacity`, `icon`, `icon-scale`,
 * `icon-offset`, `label-scale`) plus a handful of feature-name aliases used by common
 * sources (WFS, GeoJSON files). Other property keys are ignored — caller-side lambdas can
 * read them directly from the same properties map for custom styling.
 *
 * Apply with the per-shape extensions:
 *   * [AbstractShape.applyFeatureStyle] for `Path` / `Polygon`
 *   * [Placemark.applyFeatureStyle] for point Placemarks
 *   * [Label.applyFeatureStyle] for point Labels
 *
 * Used by [earth.worldwind.layer.BulkFeatureLayer]'s auto-style mode (on by default) and
 * exposed for `customLogicToApplyProperties` lambdas that want the same defaults plus their
 * own per-feature overrides.
 */

const val DEFAULT_DENSITY: Float = 1.0f
const val DEFAULT_IMAGE_SCALE: Double = 1.0
const val DEFAULT_PLACEMARK_ICON_SIZE: Double = 24.0
const val DEFAULT_LABEL_VISIBILITY_THRESHOLD: Double = 0.0

val DEFAULT_FEATURE_LINE_COLOR: Color = Color(1f, 1f, 1f, 1f)            // white
val DEFAULT_FEATURE_FILL_COLOR: Color = Color(0.72f, 0.72f, 0.72f, 1f)   // light gray

/** Feature-name property aliases. Common sources (WFS MapServer / GeoServer, GeoJSON
 *  fixtures) emit varying casing; the layer pipeline checks these aliases before falling
 *  back to a label-less Placemark. */
val NAME_ALIASES: List<String> = listOf("name", "NAME", "Name", "NAMEASCII", "name_en", "NAME_EN")

private const val DEFAULT_ICON_OFFSET = 16.0

/** Typed view of a feature's `properties` map. The view is a thin wrapper around the
 *  shared `LinkedHashMap` — mutating the map mutates the view. Property reads tolerate
 *  number-vs-string-vs-int variance per source (e.g. GeoJSON numbers can be Int or Double). */
class FeatureProperties(val properties: LinkedHashMap<String, Any?>) {
    val name: String? get() = NAME_ALIASES.firstNotNullOfOrNull { properties[it] as? String }

    val stroke: String? get() = properties["stroke"] as? String
    val strokeWidth: Double? get() = numberOrNull("stroke-width")
    val strokeOpacity: Double? get() = numberOrNull("stroke-opacity")

    val fill: String? get() = properties["fill"] as? String
    val fillOpacity: Double? get() = numberOrNull("fill-opacity")

    val icon: String? get() = properties["icon"] as? String
    val iconScale: Double? get() = numberOrNull("icon-scale")
    val iconOffset: Pair<Double, Double>?
        get() = (properties["icon-offset"] as? List<*>)?.let { list ->
            val x = (list.getOrNull(0) as? Number)?.toDouble() ?: DEFAULT_ICON_OFFSET
            val y = (list.getOrNull(1) as? Number)?.toDouble() ?: DEFAULT_ICON_OFFSET
            x to y
        }

    val labelScale: Double? get() = numberOrNull("label-scale")

    val strokeColor: Color? get() = colorFromHex(stroke, strokeOpacity)
    val fillColor: Color? get() = colorFromHex(fill, fillOpacity)

    private fun numberOrNull(key: String): Double? =
        properties[key].let { it as? Double ?: (it as? Number)?.toDouble() }
}

/**
 * Apply simplestyle `stroke` / `fill` / `stroke-width` to a Path or Polygon's attributes.
 * Writes outline / interior color only when there's an explicit value to apply — either
 * the feature's `stroke` / `fill` property OR a non-null [defaultLineColor] /
 * [defaultFillColor]. With both null (the default), an absent property leaves the
 * renderable's existing color alone — important when the layer's `shapeAttributes`
 * supplied an intentional template colour at construction time (e.g. Shapefile's
 * translucent blue) that would otherwise be clobbered by auto-style.
 */
fun AbstractShape.applyFeatureStyle(
    properties: LinkedHashMap<String, Any?>,
    defaultLineColor: Color? = null,
    defaultFillColor: Color? = null,
) {
    val p = FeatureProperties(properties)
    attributes.apply {
        (p.strokeColor ?: defaultLineColor)?.let { outlineColor = it }
        (p.fillColor ?: defaultFillColor)?.let { interiorColor = it }
        p.strokeWidth?.let { outlineWidth = it.toFloat() }
        maximumNumEdgeIntervals = 0
        isPickInterior = false
        isDrawVerticals = true
    }
}

/** Apply icon URL + icon-offset + icon-scale (× [density]) to a Placemark, plus
 *  label-scale to its label-attributes. Invalid / non-HTTPS icon URLs are skipped. */
fun Placemark.applyFeatureStyle(
    properties: LinkedHashMap<String, Any?>,
    density: Float = DEFAULT_DENSITY,
) {
    val p = FeatureProperties(properties)
    attributes.apply {
        runCatching {
            p.icon
                ?.let(::forceHttps)
                ?.takeIf(::isValidHttpsUrl)
                ?.let { url ->
                    imageSource = ImageSource.fromUrlString(url)
                    p.iconOffset?.let { (x, y) ->
                        imageOffset.set(OffsetMode.PIXELS, x, OffsetMode.INSET_PIXELS, y)
                        labelAttributes.textOffset.set(
                            OffsetMode.PIXELS, -x / 2.0,
                            OffsetMode.INSET_PIXELS, y / 2.0,
                        )
                    }
                }
        }
        imageScale = if (imageSource == null) {
            p.iconScale ?: DEFAULT_PLACEMARK_ICON_SIZE
        } else {
            DEFAULT_IMAGE_SCALE
        } * density
        p.labelScale?.let { labelAttributes.scale = it }
    }
}

/** Apply label-scale to a Label's text attributes. */
fun Label.applyFeatureStyle(properties: LinkedHashMap<String, Any?>) {
    val p = FeatureProperties(properties)
    p.labelScale?.let { attributes.scale = it }
}

private fun colorFromHex(hex: String?, opacity: Double?): Color? = hex?.let {
    Color.fromHexString(hexString = it, argb = false).apply {
        val alpha = when {
            opacity == null -> 1.0f
            opacity < 0.0 -> 0.0f
            opacity > 1.0 -> 1.0f
            else -> opacity.toFloat()
        }
        set(red = red, green = green, blue = blue, alpha = alpha)
    }
}

private fun forceHttps(url: String): String = url.replace("http://", "https://")

private fun isValidHttpsUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val rx = Regex("^https://[\\w.-]+(?:\\.[\\w.-]+)+(?:/\\S*)?$", RegexOption.IGNORE_CASE)
    return rx.matches(url)
}
