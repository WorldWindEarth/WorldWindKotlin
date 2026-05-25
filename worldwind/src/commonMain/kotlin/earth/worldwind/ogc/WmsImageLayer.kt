package earth.worldwind.ogc

import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.shape.TiledSurfaceImage

class WmsImageLayer(
    override val serviceAddress: String,
    override val serviceMetadata: String,
    override val layerName: String,
    override val imageFormat: String = "image/png",
    override val isTransparent: Boolean = true,
    name: String? = null,
    tiledSurfaceImage: TiledSurfaceImage? = null,
) : TiledImageLayer(name, tiledSurfaceImage), WebImageLayer {
    override val serviceType = SERVICE_TYPE

    override fun clone() = WmsImageLayer(
        serviceAddress, serviceMetadata, layerName, imageFormat, isTransparent,
        displayName, tiledSurfaceImage?.clone(),
    )

    companion object {
        const val SERVICE_TYPE = "WMS"
    }
}
