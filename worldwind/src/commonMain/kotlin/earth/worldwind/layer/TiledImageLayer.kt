package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

expect open class TiledImageLayer(name: String? = null, tiledSurfaceImage: TiledSurfaceImage? = null): AbstractTiledImageLayer {
    open fun clone(): TiledImageLayer
}