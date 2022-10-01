package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.ogc.WmsLayerFactory
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope

class WmsLayerTutorial(private val engine: WorldWind, mainScope: CoroutineScope) : AbstractTutorial() {

    private val wmsLayer by lazy {
        // Create a WMS layer factory.
        val wmsLayerFactory = WmsLayerFactory(mainScope)

        // Create an OGC Web Map Service (WMS) layer to display the
        // surface temperature layer from NASA's Near Earth Observations WMS.
        wmsLayerFactory.createLayer(
            "https://neo.gsfc.nasa.gov/wms/wms", listOf("MOD_LSTD_CLIM_M"),
            { Logger.log(Logger.ERROR, "WMS layer creation failed", it) },
            { Logger.log(Logger.INFO, "WMS layer creation succeeded") }
        )
    }

    override fun start() {
        super.start()
        // Add WMS layer to the WorldWindow.
        engine.layers.addLayer(wmsLayer)
        engine.camera.apply {
            position.altitude = engine.distanceToViewGlobeExtents * 1.1
            heading = Angle.ZERO
            tilt = Angle.ZERO
            roll = Angle.ZERO
        }
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(wmsLayer)
    }

}