package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.shapefile.ShapefileLayerFactory
import earth.worldwind.formats.shapefile.ShapefileShapeConfiguration
import earth.worldwind.geom.Angle
import earth.worldwind.layer.RenderableLayer
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
 */
class ShapefileLayerTutorial(engine: WorldWind, private val scope: CoroutineScope) : AbstractTutorial(engine) {

    private var shapefileLayer: RenderableLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val polygonStyle = ShapeAttributes().apply {
                    interiorColor = Color(0.4f, 0.6f, 0.9f, 0.35f)
                    outlineColor = Color(1f, 1f, 1f, 0.9f)
                    outlineWidth = 1f
                }
                val layer = ShapefileLayerFactory.createLayer(
                    shpUrl = SHP_URL,
                    displayName = "Country Boundaries (Shapefile)",
                ) { record ->
                    // Each polygon record gets the same shared attributes plus its DBF
                    // `NAME` column as a label (auto-derived by the factory when name is null).
                    ShapefileShapeConfiguration(attributes = polygonStyle)
                }
                if (isActive) {
                    shapefileLayer = layer
                    engine.layers.addLayer(layer)
                    WorldWind.requestRedraw()
                }
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

    override fun stop() {
        super.stop()
        job?.cancel()
        shapefileLayer?.let { engine.layers.removeLayer(it) }.also { shapefileLayer = null }
    }

    companion object {
        // Natural Earth 110m countries (~250 KB total). Sidecars `.dbf` and `.prj` are
        // discovered automatically by ShapefileLayerFactory.
        private const val SHP_URL =
            "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/110m_cultural/ne_110m_admin_0_countries.shp"
    }
}
