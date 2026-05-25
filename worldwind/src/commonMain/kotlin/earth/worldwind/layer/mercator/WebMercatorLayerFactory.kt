package earth.worldwind.layer.mercator

import earth.worldwind.layer.mercator.MercatorTiledImageLayer.Companion.buildTiledSurfaceImage
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.UrlTemplate

object WebMercatorLayerFactory {
    fun createLayer(
        urlTemplate: String, name: String? = null, imageFormat: String = "image/png", transparent: Boolean = false,
        maxZoom: Int = 21, minZoom: Int = 1, tileSize: Int = 256
    ): WebMercatorImageLayer {
        val template = UrlTemplate(urlTemplate)
        val tileFactory = object : MercatorTileFactory {
            override val contentType = "Web Mercator"

            override fun getImageSource(x: Int, y: Int, z: Int): ImageSource =
                ImageSource.fromUrlString(template.buildUrl(z, x, y))
        }
        val tiledSurfaceImage = buildTiledSurfaceImage(tileFactory, transparent, maxZoom, minZoom, tileSize)
        return WebMercatorImageLayer(urlTemplate, imageFormat, transparent, name, tiledSurfaceImage)
    }
}
