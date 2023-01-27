package earth.worldwind.layer.mercator.osm

import earth.worldwind.layer.mercator.MercatorTiledImageLayer
import earth.worldwind.render.image.ImageSource
import kotlin.random.Random

class OTMLayer: MercatorTiledImageLayer(NAME, 18, 256, false) {
    companion object {
        const val NAME = "OpenTopoMap"
    }

    override fun getImageSource(x: Int, y: Int, z: Int): ImageSource {
        val abc = "abc"[Random.nextInt(2)]
        return ImageSource.fromUrlString("https://$abc.tile.opentopomap.org/$z/$x/$y.png")
    }
}