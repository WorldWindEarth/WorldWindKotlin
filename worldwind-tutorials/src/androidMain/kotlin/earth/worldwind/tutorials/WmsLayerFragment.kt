package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.ogc.WmsLayerFactory
import earth.worldwind.util.Logger

open class WmsLayerFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WMS Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Create a WMS layer factory.
        val wmsLayerFactory = WmsLayerFactory(wwd.mainScope)

        // Create an OGC Web Map Service (WMS) layer to display the
        // surface temperature layer from NASA's Near Earth Observations WMS.
        val layer = wmsLayerFactory.createLayer(
            "https://neo.gsfc.nasa.gov/wms/wms", listOf("MOD_LSTD_CLIM_M"),
            { Logger.log(Logger.ERROR, "WMS layer creation failed", it) },
            { Logger.log(Logger.INFO, "WMS layer creation succeeded") }
        )

        // Add the WMS layer to the WorldWindow.
        wwd.engine.layers.addLayer(layer)
        return wwd
    }
}