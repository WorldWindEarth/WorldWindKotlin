package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.layer.mercator.MercatorSector.Companion.gudermannianInverse
import earth.worldwind.render.image.ImageData
import earth.worldwind.util.Level

actual open class MercatorImageTile actual constructor(
    sector: MercatorSector, level: Level, row: Int, column: Int
) : AbstractMercatorImageTile(sector, level, row, column) {
    override suspend fun <Resource> process(resource: Resource) = super.process(resource).let { data ->
        if (data is ImageData) {
            // Re-project mercator tile to equirectangular projection by remapping rows of RGBA
            // bytes per [gudermannianInverse]; same algorithm as the JVM/Android ports modulo the
            // pixel-buffer storage (RGBA8888 instead of BufferedImage / Bitmap).
            val w = data.width
            val h = data.height
            val rowBytes = w * 4
            val src = data.rgba
            val dst = ByteArray(rowBytes * h)
            val sector = sector as MercatorSector
            val miny = sector.minLatPercent
            val maxy = sector.maxLatPercent
            for (y in 0 until h) {
                val sy = 1.0 - y / (h - 1.0)
                val lat = sy * sector.deltaLatitude.inDegrees + sector.minLatitude.inDegrees
                val dy = (1.0 - (gudermannianInverse(lat.degrees) - miny) / (maxy - miny)).coerceIn(0.0, 1.0)
                val iy = (dy * (h - 1)).toInt()
                src.copyInto(dst, destinationOffset = y * rowBytes, startIndex = iy * rowBytes, endIndex = (iy + 1) * rowBytes)
            }
            @Suppress("UNCHECKED_CAST")
            ImageData(w, h, dst) as Resource
        } else data
    }
}
