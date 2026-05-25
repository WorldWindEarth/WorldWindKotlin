package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.ogc.WmsLayerFactory
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * @param layerLoader full layer-creation override. JVM/Android pass a loader that reads
 *   any previously-persisted capabilities XML (`contentManager.findEntry(key)?.service?.metadata`),
 *   constructs the layer via [WmsLayerFactory.createLayer], and calls
 *   [earth.worldwind.layer.cache.attachCache] so subsequent launches stay offline-replayable.
 *   The default loader skips the cache and runs network-only.
 */
class WmsLayerTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val layerLoader: suspend () -> TiledImageLayer = ::defaultNetworkOnlyLoader,
) : AbstractTutorial(engine) {

    private var wmsLayer: TiledImageLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val layer = layerLoader()
                if (isActive) {
                    wmsLayer = layer
                    engine.layers.addLayer(layer)
                    WorldWind.requestRedraw()
                }
                Logger.log(Logger.INFO, "WMS layer creation succeeded")
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "WMS layer creation failed", e)
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
        wmsLayer?.let { engine.layers.removeLayer(it) }.also { wmsLayer = null }
    }

    companion object {
        /** NASA Near Earth Observations WMS — surface-temperature climatology (monthly). */
        const val SERVICE_ADDRESS = "https://neo.gsfc.nasa.gov/wms/wms"
        val LAYER_NAMES = listOf("MOD_LSTD_CLIM_M")

        /** Default loader: network-only WMS request via [WmsLayerFactory]. */
        suspend fun defaultNetworkOnlyLoader(): TiledImageLayer =
            WmsLayerFactory.createLayer(SERVICE_ADDRESS, LAYER_NAMES)
    }
}
