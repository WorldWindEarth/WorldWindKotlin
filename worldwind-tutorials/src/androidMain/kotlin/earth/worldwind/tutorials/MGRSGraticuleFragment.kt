package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.layer.graticule.MGRSGraticuleLayer

open class MGRSGraticuleFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a MGRS Graticule layer.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Create a layer that displays the globe's tessellation geometry.
        wwd.engine.layers.addLayer(MGRSGraticuleLayer())
        return wwd
    }
}