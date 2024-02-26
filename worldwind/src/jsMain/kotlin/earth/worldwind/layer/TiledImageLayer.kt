package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

actual open class TiledImageLayer actual constructor(
    name: String?, tiledSurfaceImage: TiledSurfaceImage?
): AbstractTiledImageLayer(name, tiledSurfaceImage) {
    /**
     * Makes a copy of this image layer
     */
    actual open fun clone() = TiledImageLayer(displayName, tiledSurfaceImage?.clone())
}