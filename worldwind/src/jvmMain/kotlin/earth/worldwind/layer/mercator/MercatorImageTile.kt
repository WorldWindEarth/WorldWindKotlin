package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.layer.mercator.MercatorSector.Companion.gudermannianInverse
import earth.worldwind.util.Level
import java.awt.image.BufferedImage

/**
 * Constructs a tile with a specified sector, level, row and column.
 *
 * @param sector the sector spanned by the tile
 * @param level  the tile's level in a LevelSet
 * @param row    the tile's row within the specified level
 * @param column the tile's column within the specified level
 */
actual open class MercatorImageTile actual constructor(
    sector: MercatorSector, level: Level, row: Int, column: Int
): AbstractMercatorImageTile(sector, level, row, column) {
    override suspend fun process(resource: BufferedImage): BufferedImage {
        // Re-project mercator tile to equirectangular projection
        val type = if (resource.type != BufferedImage.TYPE_INT_RGB) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val result = BufferedImage(resource.width, resource.height, type)
        val sector = sector as MercatorSector
        val miny = sector.minLatPercent
        val maxy = sector.maxLatPercent
        for (y in 0 until resource.height) {
            val sy = 1.0 - y / (resource.height - 1.0)
            val lat = sy * sector.deltaLatitude.inDegrees + sector.minLatitude.inDegrees
            val dy = (1.0 - (gudermannianInverse(lat.degrees) - miny) / (maxy - miny)).coerceIn(0.0, 1.0)
            val iy = (dy * (resource.height - 1)).toInt()
            for (x in 0 until resource.width) result.setRGB(x, y, resource.getRGB(x, iy))
        }
        return super.process(result)
    }
}