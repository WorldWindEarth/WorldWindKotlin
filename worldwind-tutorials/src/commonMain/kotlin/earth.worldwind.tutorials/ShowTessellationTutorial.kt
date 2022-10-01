package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.layer.ShowTessellationLayer

class ShowTessellationTutorial(private val engine: WorldWind) : AbstractTutorial() {
    // Create a layer that displays the globe's tessellation geometry.
    private val layer = ShowTessellationLayer()

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }
}