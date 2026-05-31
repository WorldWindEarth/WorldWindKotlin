package earth.worldwind.formats.geojson

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.source.DEFAULT_DENSITY
import earth.worldwind.layer.source.DEFAULT_FEATURE_FILL_COLOR
import earth.worldwind.layer.source.DEFAULT_FEATURE_LINE_COLOR
import earth.worldwind.layer.source.DEFAULT_LABEL_VISIBILITY_THRESHOLD
import earth.worldwind.layer.source.GeoJsonBulkFeatureSource
import earth.worldwind.render.Renderable

/**
 * Convenience builder for a [BulkFeatureLayer] over a static GeoJSON document. Wraps the
 * text in a [GeoJsonBulkFeatureSource], constructs a [BulkFeatureLayer] with the GeoJSON-
 * appropriate defaults (always-clamp-to-ground, simplestyle keys consumed automatically),
 * loads it, and returns the layer ready to add to the engine. The layer's pick-enabled flag
 * is left at the [BulkFeatureLayer] default; callers that want a pick-disabled or pick-
 * filtered layer set [isPickEnabled][earth.worldwind.layer.Layer.isPickEnabled] (or per-
 * renderable picks) themselves.
 *
 * For cache integration use the underlying [GeoJsonBulkFeatureSource] directly:
 *     `CachedBulkFeatureSource(GeoJsonBulkFeatureSource(text), store)`
 * and wire into [BulkFeatureLayer] yourself; the cache decorator works the same way as it
 * does for WFS / Shapefile sources.
 */
object GeoJsonLayerFactory {
    private const val GEO_JSON_LAYER_NAME = "Geo Json Layer"

    suspend fun createLayer(
        text: String,
        displayName: String? = GEO_JSON_LAYER_NAME,
        density: Float = DEFAULT_DENSITY,
        labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
        customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
    ): BulkFeatureLayer {
        // GeoJSON callers historically expect white outline / light-gray fill for features
        // without explicit simplestyle keys — pass these as defaults so the auto-style
        // writes them. Sources without colour intent (Shapefile, WFS) leave these null and
        // their `shapeAttributes` template colour survives.
        val layer = BulkFeatureLayer(
            source = GeoJsonBulkFeatureSource(text),
            displayName = displayName,
            density = density,
            labelVisibilityThreshold = labelVisibilityThreshold,
            defaultAltitudeMode = AltitudeMode.CLAMP_TO_GROUND,
            defaultLineColor = DEFAULT_FEATURE_LINE_COLOR,
            defaultFillColor = DEFAULT_FEATURE_FILL_COLOR,
            customLogicToApplyProperties = customLogicToApplyProperties,
        ).apply { isPickEnabled = false }
        layer.load()
        return layer
    }
}
