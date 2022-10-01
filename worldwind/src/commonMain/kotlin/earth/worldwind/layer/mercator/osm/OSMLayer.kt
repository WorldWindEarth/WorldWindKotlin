package earth.worldwind.layer.mercator.osm

import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import kotlin.random.Random

class OSMLayer: MercatorTiledImageLayer(NAME, 20, 256, false) {
    companion object {
        const val NAME = "OpenStreetMap"
    }

    override fun getImageSourceUrl(x: Int, y: Int, z: Int): String {
        val abc = "abc"[Random.nextInt(2)]
        return "https://$abc.tile.openstreetmap.org/$z/$x/$y.png"
    }

}