package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle.Companion.NEG180
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.DownloadPostprocessor
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
import earth.worldwind.util.TileFactory

abstract class MercatorTiledImageLayer(
    name: String, numLevels: Int, tileSize: Int, overlay: Boolean
): TiledImageLayer(name) {
    private val tileFactory = object : TileFactory {
        override fun createTile(sector: Sector, level: Level, row: Int, column: Int) =
            MercatorImageTile(sector as MercatorSector, level, row, column).apply {
                imageSource = ImageSource.fromUrlString(
                    getImageSourceUrl(
                        column, (1 shl level.levelNumber) - 1 - row, level.levelNumber
                    ), this as DownloadPostprocessor<*>
                )
            }
    }

    init {
        tiledSurfaceImage = MercatorTiledSurfaceImage(tileFactory).apply {
            val sector = MercatorSector(-1.0, 1.0, NEG180, POS180)
            levelSet = LevelSet(
                sector, Location(sector.minLatitude, sector.minLongitude),
                Location(sector.deltaLatitude, sector.deltaLongitude), numLevels, tileSize, tileSize
            )
            if (!overlay) imageOptions = ImageOptions(ImageConfig.RGB_565) // reduce memory usage by using a 16-bit configuration with no alpha
            levelOffset = 1 // Skip topmost level with bad resolution from processing
        }
    }

    protected abstract fun getImageSourceUrl(x: Int, y: Int, z: Int): String
}