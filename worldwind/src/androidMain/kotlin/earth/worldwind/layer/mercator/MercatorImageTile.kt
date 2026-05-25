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
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            val result = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            sector as MercatorSector
            val miny = sector.minLatPercent
            val maxy = sector.maxLatPercent
            for (y in 0 until h) {
                val sy = 1.0 - y / (h - 1.0)
                val lat = sy * sector.deltaLatitude.inDegrees + sector.minLatitude.inDegrees
                val dy = (1.0 - (gudermannianInverse(lat.degrees) - miny) / (maxy - miny)).coerceIn(0.0, 1.0)
                val iy = (dy * (h - 1)).toInt()
                System.arraycopy(pixels, iy * w, result, y * w, w)
            }
            @Suppress("UNCHECKED_CAST")
            (Bitmap.createBitmap(result, w, h, bitmap.config) as Resource).also { bitmap.recycle() }
        } else bitmap
    }
}