package earth.worldwind.examples

import android.os.Bundle
import earth.worldwind.layer.graticule.MGRSGraticuleLayer

open class MGRSGraticuleActivity: GeneralGlobeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wwd.engine.layers.addLayer(MGRSGraticuleLayer())
    }
}