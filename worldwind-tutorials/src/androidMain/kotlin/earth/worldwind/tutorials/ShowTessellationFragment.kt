package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.layer.ShowTessellationLayer

open class ShowTessellationFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a tessellation layer.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Create a layer that displays the globe's tessellation geometry.
        wwd.engine.layers.addLayer(ShowTessellationLayer())
        return wwd
    }
}