package earth.worldwind.layer.mercator.osm

import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.render.image.ImageSource
import kotlin.random.Random

class OSMLayer: MercatorTiledImageLayer(NAME, 20, 256, false) {
    companion object {
        const val NAME = "OpenStreetMap"
    }

    override fun getImageSource(x: Int, y: Int, z: Int): ImageSource {
        val abc = "abc"[Random.nextInt(2)]
        return ImageSource.fromUrlString("https://$abc.tile.openstreetmap.org/$z/$x/$y.png")
    }

}