package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.WfsLayerFactory
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WfsLayerTutorial(engine: WorldWind, private val scope: CoroutineScope) : AbstractTutorial(engine) {

    private var wfsLayer: RenderableLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                // Create an OGC Web Feature Service (WFS) layer rendering world country
                // boundaries from Natural Earth (~255 polygons) fetched as GeoJSON.
                WfsLayerFactory.createLayer(
                    serviceAddress = "https://ahocevar.com/geoserver/wfs",
                    typeName = "ne:ne_10m_admin_0_countries",
                    displayName = "Country Boundaries (WFS)",
                ).also {
                    if (isActive) {
                        wfsLayer = it
                        engine.layers.addLayer(it)
                        WorldWind.requestRedraw()
                    }
                }
                Logger.log(Logger.INFO, "WFS layer creation succeeded")
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "WFS layer creation failed", e)
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
        wfsLayer?.let { engine.layers.removeLayer(it) }.also { wfsLayer = null }
    }

}
