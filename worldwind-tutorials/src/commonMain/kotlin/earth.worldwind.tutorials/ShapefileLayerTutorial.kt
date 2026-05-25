package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.shapefile.ShapefileLayerFactory
import earth.worldwind.formats.shapefile.ShapefileShapeConfiguration
import earth.worldwind.geom.Angle
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Loads world country boundaries from a Natural Earth shapefile (110m admin0 countries:
 * ~250 KB total across `.shp` + `.dbf` + `.prj`). Demonstrates `ShapefileLayerFactory`
 * fetching all three files over HTTP and producing surface-clamped polygons with a
 * per-record `shapeConfiguration` callback.
 *
 * Source files are hosted on the `nvkelso/natural-earth-vector` GitHub raw URLs, which
 * is the canonical Natural Earth vector mirror.
 *
 * [layerLoader] lets JVM/Android inject a cache-wrapped source (CachedBulkFeatureSource +
 * GpkgFeatureStore feeding BulkFeatureLayer); the default is network-only.
 */
class ShapefileLayerTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val layerLoader: (suspend () -> RenderableLayer)? = null,
) : AbstractTutorial(engine) {

    private var shapefileLayer: RenderableLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val layer = layerLoader?.invoke() ?: defaultLayerLoader()
                if (!isActive) return@launch
                shapefileLayer = layer
                engine.layers.addLayer(layer)
                // The cache-wrapped path returns a BulkFeatureLayer that's empty until
                // `load()` pulls rows from its source (network → ShapefileBulkFeatureSource,
                // or cache hit on second launch). The network-only `defaultLayerLoader`
                // returns a populated RenderableLayer from ShapefileLayerFactory and skips
                // this call. Same pattern as WfsLayerTutorial.
                if (layer is BulkFeatureLayer) layer.load()
                WorldWind.requestRedraw()
                Logger.log(Logger.INFO, "Shapefile layer creation succeeded")
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "Shapefile layer creation failed", e)
            }
        }
        engine.camera.apply {
            position.altitude = engine.distanceToViewGlobeExtents * 1.1
            heading = Angle.ZERO
            tilt = Angle.ZERO
            roll = Angle.ZERO
        }
    }

    private suspend fun defaultLayerLoader(): RenderableLayer {
        val polygonStyle = ShapeAttributes().apply {
            interiorColor = Color(0.4f, 0.6f, 0.9f, 0.35f)
            outlineColor = Color(1f, 1f, 1f, 0.9f)
            outlineWidth = 1f
        }
        return ShapefileLayerFactory.createLayer(
            shpUrl = SHP_URL,
            displayName = DISPLAY_NAME,
        ) { ShapefileShapeConfiguration(attributes = polygonStyle) }
    }

    override fun stop() {
        super.stop()
        job?.cancel()
        shapefileLayer?.let { engine.layers.removeLayer(it) }.also { shapefileLayer = null }
    }

    companion object {
        // Natural Earth 110m countries (~250 KB). Sidecars `.dbf`/`.prj` auto-discovered.
        const val SHP_URL =
            "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/110m_cultural/ne_110m_admin_0_countries.shp"

        const val DISPLAY_NAME = "Country Boundaries (Shapefile)"

        /** Polygon style shared between the network-only and cached paths. */
        fun defaultPolygonStyle() = ShapeAttributes().apply {
            interiorColor = Color(0.4f, 0.6f, 0.9f, 0.35f)
            outlineColor = Color(1f, 1f, 1f, 0.9f)
            outlineWidth = 1f
        }
    }
}
