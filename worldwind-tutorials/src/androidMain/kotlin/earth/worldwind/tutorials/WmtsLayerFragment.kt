package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.ogc.WmtsLayerFactory
import earth.worldwind.util.Logger

open class WmtsLayerFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WMTS Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Create a WMTS layer factory.
        val wmtsLayerFactory = WmtsLayerFactory(wwd.mainScope)

        // Create an OGC Web Map Tile Service (WMTS) layer to display Global Hillshade based on GMTED2010
        val layer = wmtsLayerFactory.createLayer(
            "https://tiles.geoservice.dlr.de/service/wmts", "hillshade",
            { Logger.log(Logger.ERROR, "WMTS layer creation failed", it) },
            { Logger.log(Logger.INFO, "WMTS layer creation succeeded") }
        )

        // Add the finished WMTS layer to the WorldWindow.
        wwd.engine.layers.addLayer(layer)
        return wwd
    }
}