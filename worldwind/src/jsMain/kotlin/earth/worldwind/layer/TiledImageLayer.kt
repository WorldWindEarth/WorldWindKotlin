package earth.worldwind.layer

import earth.worldwind.shape.TiledSurfaceImage

actual open class TiledImageLayer actual constructor(
    name: String?, tiledSurfaceImage: TiledSurfaceImage?
): AbstractTiledImageLayer(name, tiledSurfaceImage)