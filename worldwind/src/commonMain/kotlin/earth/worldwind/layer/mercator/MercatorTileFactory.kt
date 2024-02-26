package earth.worldwind.layer.mercator

import earth.worldwind.geom.Sector
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.Level
import earth.worldwind.util.TileFactory

interface MercatorTileFactory : TileFactory {
    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) =
        MercatorImageTile(sector as MercatorSector, level, row, column).apply {
            imageSource = getImageSource(column, (1 shl level.levelNumber) - 1 - row, level.levelNumber)?.also {
                it.postprocessor = this
            }
        }

    fun getImageSource(x: Int, y: Int, z: Int): ImageSource?
}