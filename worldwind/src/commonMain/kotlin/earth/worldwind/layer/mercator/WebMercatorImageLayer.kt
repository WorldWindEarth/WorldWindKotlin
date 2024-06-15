package earth.worldwind.layer.mercator

import earth.worldwind.layer.WebImageLayer

class WebMercatorImageLayer(
    override val serviceAddress: String,
    override val imageFormat: String = "image/png",
    override val isTransparent: Boolean = false,
    name: String? = null,
    tiledSurfaceImage: MercatorTiledSurfaceImage? = null
) : MercatorTiledImageLayer(name, tiledSurfaceImage), WebImageLayer {
    override val serviceType = SERVICE_TYPE

    override fun clone() = WebMercatorImageLayer(
        serviceAddress, imageFormat, isTransparent, displayName, tiledSurfaceImage?.clone() as? MercatorTiledSurfaceImage
    )

    companion object {
        const val SERVICE_TYPE = "XYZ"
    }
}