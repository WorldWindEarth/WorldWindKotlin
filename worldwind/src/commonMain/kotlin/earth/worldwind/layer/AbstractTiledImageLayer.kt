package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

expect abstract class AbstractTiledImageLayer(name: String): RenderableLayer {
    protected open val firstLevelOffset: Int
    protected var tiledSurfaceImage: TiledSurfaceImage?
}