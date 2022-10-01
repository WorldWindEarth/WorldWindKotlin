package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.ogc.WmtsLayerFactory
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope

class WmtsLayerTutorial(private val engine: WorldWind, mainScope: CoroutineScope) : AbstractTutorial() {

    private val wmtsLayer by lazy {
        // Create a WMTS layer factory.
        val wmtsLayerFactory = WmtsLayerFactory(mainScope)

        // Create an OGC Web Map Tile Service (WMTS) layer to display Global Hillshade based on GMTED2010
        wmtsLayerFactory.createLayer(
            "https://tiles.geoservice.dlr.de/service/wmts", "hillshade",
            { Logger.log(Logger.ERROR, "WMTS layer creation failed", it) },
            { Logger.log(Logger.INFO, "WMTS layer creation succeeded") }
        )
    }

    override fun start() {
        super.start()
        // Add WMTS layer to the WorldWindow.
        engine.layers.addLayer(wmtsLayer)
        engine.camera.apply {
            position.altitude = engine.distanceToViewGlobeExtents * 1.1
            heading = Angle.ZERO
            tilt = Angle.ZERO
            roll = Angle.ZERO
        }
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(wmtsLayer)
    }

}