package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.layer.graticule.gk.GKGraticuleLayer

class GKGraticuleTutorial(engine: WorldWind) : AbstractTutorial(engine) {
    private val layer = GKGraticuleLayer()

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }
}