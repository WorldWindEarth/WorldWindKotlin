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

class WmsLayerTutorial(private val engine: WorldWind, private val scope: CoroutineScope) : AbstractTutorial() {

    private var wmsLayer: TiledImageLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                // Create an OGC Web Map Service (WMS) layer to display the
                // surface temperature layer from NASA's Near Earth Observations WMS.
                WmsLayerFactory.createLayer("https://neo.gsfc.nasa.gov/wms/wms", listOf("MOD_LSTD_CLIM_M")).also {
                    if (isActive) {
                        wmsLayer = it
                        engine.layers.addLayer(it)
                        WorldWind.requestRedraw()
                    }
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

}