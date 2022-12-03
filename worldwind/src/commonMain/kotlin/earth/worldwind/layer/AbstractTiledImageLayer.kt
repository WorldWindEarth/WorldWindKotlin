package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

expect abstract class AbstractTiledImageLayer(name: String): RenderableLayer {
    protected var tiledSurfaceImage: TiledSurfaceImage?
}