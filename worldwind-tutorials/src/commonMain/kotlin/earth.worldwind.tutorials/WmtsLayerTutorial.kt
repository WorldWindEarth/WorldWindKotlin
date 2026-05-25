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

/** @param layerLoader see [WmsLayerTutorial]. */
class WmtsLayerTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val layerLoader: suspend () -> TiledImageLayer = ::defaultNetworkOnlyLoader,
) : AbstractTutorial(engine) {

    private var wmtsLayer: TiledImageLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val layer = layerLoader()
                if (isActive) {
                    wmtsLayer = layer
                    engine.layers.addLayer(layer)
                    WorldWind.requestRedraw()
                }
                Logger.log(Logger.INFO, "WMTS layer creation succeeded")
            } catch (e: Throwable) {
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

    companion object {
        /** DLR Geoservice WMTS — global hillshade based on GMTED2010. */
        const val SERVICE_ADDRESS = "https://tiles.geoservice.dlr.de/service/wmts"
        const val LAYER_NAME = "hillshade"

        /** Default loader: network-only WMTS request via [WmtsLayerFactory]. */
        suspend fun defaultNetworkOnlyLoader(): TiledImageLayer =
            WmtsLayerFactory.createLayer(SERVICE_ADDRESS, LAYER_NAME)
    }
}
