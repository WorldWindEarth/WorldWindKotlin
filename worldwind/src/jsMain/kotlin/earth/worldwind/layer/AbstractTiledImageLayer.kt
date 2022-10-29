package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

actual abstract class AbstractTiledImageLayer actual constructor(name: String): RenderableLayer(name) {
    protected actual open val firstLevelOffset = 0
    protected actual var tiledSurfaceImage: TiledSurfaceImage? = null
        set(value) {
            field?.let { removeRenderable(it) }
            value?.let { addRenderable(it) }
            field = value
        }
    override var isPickEnabled = false
}