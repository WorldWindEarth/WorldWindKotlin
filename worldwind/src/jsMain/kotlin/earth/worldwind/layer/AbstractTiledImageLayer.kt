package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

actual abstract class AbstractTiledImageLayer actual constructor(name: String): RenderableLayer(name) {
    actual var tiledSurfaceImage: TiledSurfaceImage? = null
        protected set(value) {
            field?.let { removeRenderable(it) }
            value?.let { addRenderable(it) }
            field = value
        }
    override var isPickEnabled = false
    /**
     * Determines how many levels to skip from retrieving texture during tile pyramid subdivision.
     */
    var levelOffset: Int
        get() = tiledSurfaceImage?.levelOffset ?: 0
        set(value) { tiledSurfaceImage?.levelOffset = value }
}