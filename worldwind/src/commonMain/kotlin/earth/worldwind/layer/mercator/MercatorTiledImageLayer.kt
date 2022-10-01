package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle.Companion.NEG180
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.DownloadPostprocessor
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
import earth.worldwind.util.TileFactory

abstract class MercatorTiledImageLayer(
    name: String, numLevels: Int, tileSize: Int, overlay: Boolean
): TiledImageLayer(name) {
    override val firstLevelOffset = 3 // Skip several topmost levels with bad resolution from processing
    private val tileFactory = object : TileFactory {
        override fun createTile(sector: Sector, level: Level, row: Int, column: Int) =
            MercatorImageTile(sector as MercatorSector, level, row, column).apply {
                imageSource = ImageSource.fromUrlString(
                    getImageSourceUrl(
                        column, (1 shl level.levelNumber + firstLevelOffset) - 1 - row, level.levelNumber + firstLevelOffset
                    ), this as DownloadPostprocessor<*>
                )
            }
    }
    override var tiledSurfaceImage: TiledSurfaceImage? = MercatorTiledSurfaceImage(tileFactory).apply {
        val sector = MercatorSector(-1.0, 1.0, NEG180, POS180)
        val divisor = (1 shl firstLevelOffset).toDouble()
        levelSet = LevelSet(
            sector, Location(sector.minLatitude, sector.minLongitude),
            Location(sector.deltaLatitude / divisor, sector.deltaLongitude / divisor),
            numLevels - firstLevelOffset, tileSize, tileSize
        )
        if (!overlay) imageOptions = ImageOptions(ImageConfig.RGB_565) // reduce memory usage by using a 16-bit configuration with no alpha
    }.also { addRenderable(it) }

    protected abstract fun getImageSourceUrl(x: Int, y: Int, z: Int): String
}