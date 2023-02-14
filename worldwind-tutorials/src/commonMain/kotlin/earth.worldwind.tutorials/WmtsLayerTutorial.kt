package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.ogc.WmtsLayerFactory
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WmtsLayerTutorial(private val engine: WorldWind, private val scope: CoroutineScope) : AbstractTutorial() {

    private var wmtsLayer: TiledImageLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                // Create an OGC Web Map Tile Service (WMTS) layer to display Global Hillshade based on GMTED2010
                WmtsLayerFactory.createLayer("https://tiles.geoservice.dlr.de/service/wmts", "hillshade").also {
                    if (isActive) {
                        wmtsLayer = it
                        engine.layers.addLayer(it)
                        WorldWind.requestRedraw()
                    }
                }
                Logger.log(Logger.INFO, "WMTS layer creation succeeded")
            } catch (e: Exception) {
                Logger.log(Logger.ERROR, "WMTS layer creation failed", e)
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
        wmtsLayer?.let { engine.layers.removeLayer(it) }.also { wmtsLayer = null }
    }

}