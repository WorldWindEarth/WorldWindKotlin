package earth.worldwind.ogc

import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.shape.TiledSurfaceImage

class WmtsImageLayer(
    override val serviceAddress: String,
    override val serviceMetadata: String,
    override val layerName: String,
    name: String? = null,
    tiledSurfaceImage: TiledSurfaceImage? = null
) : TiledImageLayer(name, tiledSurfaceImage), WebImageLayer {
    override val serviceType = SERVICE_TYPE
    override val imageFormat get() = (tiledSurfaceImage?.tileFactory as? WmtsTileFactory)?.imageFormat ?: "image/png"
    override val isTransparent = true // WMTS has no transparency data available

    override fun clone() = WmtsImageLayer(
        serviceAddress, serviceMetadata, layerName, displayName, tiledSurfaceImage?.clone()
    )

    companion object {
        const val SERVICE_TYPE = "WMTS"
    }
}