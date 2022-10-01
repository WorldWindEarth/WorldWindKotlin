package earth.worldwind.geom

import earth.worldwind.geom.Sector.Companion.fromDegrees

open class TileMatrix internal constructor(
    val sector: Sector, val ordinal: Int, val matrixWidth: Int, val matrixHeight: Int, val tileWidth: Int, val tileHeight: Int
) {
    val degreesPerPixel get() = sector.deltaLatitude.degrees / (matrixHeight * tileHeight)

    fun tileKey(row: Int, column: Int): Long {
        val lOrd = (ordinal and 0xFF).toLong() // 8 bits
        val lRow = (row and 0xFFFFFFF).toLong() // 28 bits
        val lCol = (column and 0xFFFFFFF).toLong() // 28 bits
        return lOrd.shl(56) or lRow.shl(28) or lCol
    }

    fun tileSector(row: Int, column: Int): Sector {
        val deltaLat = sector.deltaLatitude.degrees / matrixHeight
        val deltaLon = sector.deltaLongitude.degrees / matrixWidth
        val minLat = sector.maxLatitude.degrees - deltaLat * (row + 1)
        val minLon = sector.minLongitude.degrees + deltaLon * column
        return fromDegrees(minLat, minLon, deltaLat, deltaLon)
    }
}