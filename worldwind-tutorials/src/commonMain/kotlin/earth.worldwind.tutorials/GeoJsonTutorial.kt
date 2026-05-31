package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.geojson.GeoJsonLayerFactory
import earth.worldwind.geom.Angle
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.source.GeoJsonBulkFeatureSource
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Label
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Demonstrates the v3 GeoJSON pipeline end-to-end:
 *   * [GeoJsonBulkFeatureSource] parses the document text supplied via [geoJsonTextProvider].
 *     Each platform reads `MR.assets.geojson_sample_json` with its own moko-resources hook
 *     (classpath on JVM, AssetManager on Android, NSBundle on iOS, `fetch` on JS).
 *   * [BulkFeatureLayer] renders each feature with simplestyle keys
 *     (`stroke` / `fill` / `stroke-width`) applied automatically before user logic.
 *   * `customLogicToApplyProperties` adds a population-tier overlay so cities are tinted
 *     by their `population` property over the simplestyle defaults.
 *   * The "United Kingdom" entry is a MultiPolygon — one feature, two Polygon
 *     renderables sharing the same properties map. The customLogic lambda runs once
 *     per renderable, so both inner polygons get the same overlay.
 */
class GeoJsonTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val geoJsonTextProvider: suspend () -> String,
) : AbstractTutorial(engine) {

    private var layer: BulkFeatureLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val text = geoJsonTextProvider()
                val built = GeoJsonLayerFactory.createLayer(
                    text = text,
                    displayName = "European demo",
                    labelVisibilityThreshold = 4_000_000.0,
                    customLogicToApplyProperties = populationStyling,
                )
                if (!isActive) return@launch
                layer = built
                engine.layers.addLayer(built)
                WorldWind.requestRedraw()
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "GeoJSON tutorial: ${e.message}", e)
            }
        }
        engine.camera.apply {
            position.setDegrees(47.0, 1.0, 6_000_000.0)
            heading = Angle.ZERO
            tilt = Angle.ZERO
            roll = Angle.ZERO
        }
    }

    override fun stop() {
        super.stop()
        job?.cancel()
        layer?.let { engine.layers.removeLayer(it) }.also { layer = null }
    }

    companion object {
        private val populationTiers = listOf(
            5_000_000.0 to Color.fromHexString("#E63946"), // megacity (5M+)  — red
            1_000_000.0 to Color.fromHexString("#F4A261"), // major (1–5M)    — orange
            0.0         to Color.fromHexString("#2A9D8F"), // small (<1M)     — teal
        )

        /** Tint each Placemark / Label / Polygon by its `population` property. Polygons
         *  reuse the same lookup for visual coherence between cities and their countries. */
        val populationStyling: Renderable.(LinkedHashMap<String, Any?>) -> Unit = { properties ->
            val population = (properties["population"] as? Number)?.toDouble()
            if (population != null && population > 0.0) {
                val color = populationTiers.first { (threshold, _) -> population >= threshold }.second
                when (this) {
                    is Placemark -> attributes.imageColor = color
                    is Label -> attributes.textColor = color
                    is Polygon -> attributes.interiorColor = color
                }
            }
        }
    }
}
