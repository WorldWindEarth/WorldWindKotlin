package earth.worldwind.geom

import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.util.Tile
import kotlin.math.ceil

open class TileMatrix internal constructor(
    val sector: Sector, val ordinal: Int, val matrixWidth: Int, val matrixHeight: Int, val tileWidth: Int, val tileHeight: Int
) {
    val resolution get() = sector.deltaLatitude.div(matrixHeight * tileHeight)

    fun tileKey(row: Int, column: Int): Long {
        val lOrd = (ordinal and 0xFF).toLong() // 8 bits
        val lRow = (row and 0xFFFFFFF).toLong() // 28 bits
        val lCol = (column and 0xFFFFFFF).toLong() // 28 bits
        return lOrd.shl(56) or lRow.shl(28) or lCol
    }

    fun tileSector(row: Int, column: Int): Sector {
        val deltaLat = sector.deltaLatitude.inDegrees / matrixHeight
        val deltaLon = sector.deltaLongitude.inDegrees / matrixWidth
        val minLat = sector.maxLatitude.inDegrees - deltaLat * (row + 1)
        val minLon = sector.minLongitude.inDegrees + deltaLon * column
        return fromDegrees(minLat, minLon, deltaLat, deltaLon)
    }

    /**
     * Calculates number of tiles, which fit specified sector
     *
     * @param sector the desired sector to check tile count
     * @return Number of tiles which fit specified sector at this level
     */
    fun tilesInSector(sector: Sector): Long {
        val deltaLat = this.sector.deltaLatitude / matrixHeight
        val deltaLon = this.sector.deltaLongitude / matrixWidth
        val firstRow = Tile.computeRow(deltaLat, sector.minLatitude, this.sector.minLatitude)
        val lastRow = Tile.computeLastRow(deltaLat, sector.maxLatitude, this.sector.minLatitude)
        val firstCol = Tile.computeColumn(deltaLon, sector.minLongitude, this.sector.minLongitude)
        val lastCol = Tile.computeLastColumn(deltaLon, sector.maxLongitude, this.sector.minLongitude)
        return (lastRow - firstRow + 1).toLong() * (lastCol - firstCol + 1).toLong()
    }
}