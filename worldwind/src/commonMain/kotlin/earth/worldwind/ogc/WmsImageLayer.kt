package earth.worldwind.ogc

import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.shape.TiledSurfaceImage

class WmsImageLayer(
    override val serviceAddress: String,
    override val serviceMetadata: String,
    override val layerName: String,
    name: String? = null,
    tiledSurfaceImage: TiledSurfaceImage? = null
) : TiledImageLayer(name, tiledSurfaceImage), WebImageLayer {
    override val serviceType = SERVICE_TYPE
    override val imageFormat get() = (tiledSurfaceImage?.tileFactory as? WmsTileFactory)?.imageFormat ?: "image/png"
    override val isTransparent get() = (tiledSurfaceImage?.tileFactory as? WmsTileFactory)?.isTransparent ?: true

    override fun clone() = WmsImageLayer(
        serviceAddress, serviceMetadata, layerName, displayName, tiledSurfaceImage?.clone()
    )

    companion object {
        const val SERVICE_TYPE = "WMS"
    }
}