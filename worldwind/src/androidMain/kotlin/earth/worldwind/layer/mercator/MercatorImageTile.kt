package earth.worldwind.layer.mercator

import android.graphics.Bitmap
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.layer.mercator.MercatorSector.Companion.gudermannianInverse
import earth.worldwind.util.Level

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
    override suspend fun <Resource> process(resource: Resource) = super.process(resource).let { bitmap ->
        if (bitmap is Bitmap) {
            // Re-project mercator tile to equirectangular projection
            val pixels = IntArray(bitmap.width * bitmap.height)
            val result = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            sector as MercatorSector
            val miny = sector.minLatPercent
            val maxy = sector.maxLatPercent
            for (y in 0 until bitmap.height) {
                val sy = 1.0 - y / (bitmap.height - 1.0)
                val lat = sy * sector.deltaLatitude.inDegrees + sector.minLatitude.inDegrees
                val dy = (1.0 - (gudermannianInverse(lat.degrees) - miny) / (maxy - miny)).coerceIn(0.0, 1.0)
                val iy = (dy * (bitmap.height - 1)).toInt()
                for (x in 0 until bitmap.width) result[x + y * bitmap.width] = pixels[x + iy * bitmap.width]
            }
            @Suppress("UNCHECKED_CAST")
            (Bitmap.createBitmap(result, bitmap.width, bitmap.height, bitmap.config) as Resource).also { bitmap.recycle() }
        } else bitmap
    }
}