package earth.worldwind.layer.mercator

import earth.worldwind.geom.Location
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.util.LevelSet

open class MercatorTiledImageLayer(
    name: String? = null, tiledSurfaceImage: MercatorTiledSurfaceImage? = null
): TiledImageLayer(name, tiledSurfaceImage) {
    override fun clone() = MercatorTiledImageLayer(displayName, tiledSurfaceImage?.clone() as MercatorTiledSurfaceImage)

    companion object {
        fun buildTiledSurfaceImage(
            tileFactory: MercatorTileFactory, numLevels: Int = 22, tileSize: Int = 256, transparent: Boolean = false, levelOffset: Int = 1
        ): MercatorTiledSurfaceImage {
            val sector = MercatorSector()
            val tileOrigin = MercatorSector()
            val firstLevelDelta = Location(tileOrigin.deltaLatitude, tileOrigin.deltaLongitude)
            // Skip 1 topmost level with bad resolution from processing
            val levelSet = LevelSet(sector, tileOrigin, firstLevelDelta, numLevels, tileSize, tileSize, levelOffset)
            return MercatorTiledSurfaceImage(tileFactory, levelSet).apply {
                // Reduce memory usage by using a 16-bit configuration with no alpha
                if (!transparent) imageOptions = ImageOptions(ImageConfig.RGB_565)
            }
        }
    }
}