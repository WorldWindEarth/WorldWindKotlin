package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.starfield.StarFieldLayer
import kotlinx.datetime.Clock

class BasicTutorial(private val engine: WorldWind): AbstractTutorial() {

    var starFieldLayer: StarFieldLayer? = null
    var atmosphereLayer: AtmosphereLayer? = null

    override fun start() {
        super.start()
        engine.layers.run {
            val time = Clock.System.now()
            starFieldLayer = (getLayer(indexOfLayerNamed("StarField")) as StarFieldLayer).apply { this.time = time }
            atmosphereLayer = (getLayer(indexOfLayerNamed("Atmosphere")) as AtmosphereLayer).apply { this.time = time }
        }
    }

    override fun stop() {
        super.stop()
        starFieldLayer?.time = null
        atmosphereLayer?.time = null
    }

}