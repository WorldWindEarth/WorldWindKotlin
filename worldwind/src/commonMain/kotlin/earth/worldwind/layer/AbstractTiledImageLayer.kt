package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

abstract class AbstractTiledImageLayer(name: String?, tiledSurfaceImage: TiledSurfaceImage?): RenderableLayer(name) {
    override var isPickEnabled = false // Image layer is not pick able

    var tiledSurfaceImage = tiledSurfaceImage?.also { addRenderable(it) }
        protected set(value) {
            field?.let { removeRenderable(it) }
            value?.let { addRenderable(it) }
            field = value
        }

    /**
     * Unique identifier of the layer type, defined by tiled surface image tile factory content type
     */
    val type get() = tiledSurfaceImage?.tileFactory?.contentType
}