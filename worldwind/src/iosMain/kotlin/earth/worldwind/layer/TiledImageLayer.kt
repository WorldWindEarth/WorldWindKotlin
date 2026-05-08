package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

actual open class TiledImageLayer actual constructor(
    name: String?, tiledSurfaceImage: TiledSurfaceImage?
) : AbstractTiledImageLayer(name, tiledSurfaceImage) {
    actual open fun clone() = TiledImageLayer(displayName, tiledSurfaceImage?.clone())
}
