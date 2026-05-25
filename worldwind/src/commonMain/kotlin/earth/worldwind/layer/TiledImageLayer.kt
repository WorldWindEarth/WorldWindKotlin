package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

open class TiledImageLayer(name: String? = null, tiledSurfaceImage: TiledSurfaceImage? = null) : RenderableLayer(name) {
    override var isPickEnabled = false

    var tiledSurfaceImage = tiledSurfaceImage?.also { addRenderable(it) }
        protected set(value) {
            field?.let { removeRenderable(it) }
            value?.let { addRenderable(it) }
            field = value
        }

    val type get() = tiledSurfaceImage?.tileFactory?.contentType

    open fun clone() = TiledImageLayer(displayName, this.tiledSurfaceImage?.clone())
}
