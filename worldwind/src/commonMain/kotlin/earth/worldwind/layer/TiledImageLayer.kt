package earth.worldwind.layer

import earth.worldwind.render.Renderable
import earth.worldwind.shape.TiledSurfaceImage

expect abstract class TiledImageLayer(name: String) {
    protected open val firstLevelOffset: Int
    protected open var tiledSurfaceImage: TiledSurfaceImage?

    fun getRenderable(index: Int): Renderable
    fun addRenderable(renderable: Renderable)
    fun removeRenderable(renderable: Renderable): Boolean
}