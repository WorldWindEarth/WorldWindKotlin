package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

actual abstract class TiledImageLayer actual constructor(name: String): RenderableLayer(name) {
    protected actual open val firstLevelOffset = 0
    protected actual open var tiledSurfaceImage: TiledSurfaceImage? = null
    override var isPickEnabled = false
}