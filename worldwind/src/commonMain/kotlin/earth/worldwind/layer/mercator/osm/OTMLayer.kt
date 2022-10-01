package earth.worldwind.layer.mercator.osm

import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import kotlin.random.Random

class OTMLayer: MercatorTiledImageLayer(NAME, 18, 256, false) {
    companion object {
        const val NAME = "OpenTopoMap"
    }

    override fun getImageSourceUrl(x: Int, y: Int, z: Int): String {
        val abc = "abc"[Random.nextInt(2)]
        return "https://$abc.tile.opentopomap.org/$z/$x/$y.png"
    }
}