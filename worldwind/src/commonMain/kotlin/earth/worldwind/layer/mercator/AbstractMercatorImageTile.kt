package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle.Companion.average
import earth.worldwind.layer.mercator.MercatorSector.Companion.fromSector
import earth.worldwind.layer.mercator.MercatorSector.Companion.gudermannianInverse
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.Level
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory
import kotlin.jvm.JvmStatic

/**
 * Constructs a tile with a specified sector, level, row and column.
 *
 * @param sector the sector spanned by the tile
 * @param level  the tile's level in a LevelSet
 * @param row    the tile's row within the specified level
 * @param column the tile's column within the specified level
 */
abstract class AbstractMercatorImageTile(
    sector: MercatorSector, level: Level, row: Int, column: Int
): ImageTile(sector, level, row, column) {
    companion object {
        /**
         * Creates all Mercator tiles for a specified level within a LevelSet.
         *
         * @param level       the level to create the tiles for
         * @param tileFactory the tile factory to use for creating tiles.
         * @param result      a pre-allocated Collection in which to store the results
         */
        @JvmStatic
        fun assembleMercatorTilesForLevel(level: Level, tileFactory: TileFactory, result: MutableList<Tile>): List<Tile> {
            val sector = fromSector(level.parent.sector)
            val tileOrigin = level.parent.tileOrigin
            val dLat = level.tileDelta.latitude
            val dLon = level.tileDelta.longitude
            val firstRow = computeRow(dLat, sector.minLatitude, tileOrigin.latitude)
            val lastRow = computeLastRow(dLat, sector.maxLatitude, tileOrigin.latitude)
            val firstCol = computeColumn(dLon, sector.minLongitude, tileOrigin.longitude)
            val lastCol = computeLastColumn(dLon, sector.maxLongitude, tileOrigin.longitude)
            val dLatPercent = dLat.inDegrees / sector.deltaLatitude.inDegrees * (sector.maxLatPercent - sector.minLatPercent)
            val firstRowPercent = gudermannianInverse(tileOrigin.latitude) + firstRow * dLatPercent
            val firstColLon = tileOrigin.longitude.plusDegrees(firstCol * dLon.inDegrees)
            var d1 = firstRowPercent
            for (row in firstRow..lastRow) {
                val d2 = d1 + dLatPercent
                var t1 = firstColLon
                for (col in firstCol..lastCol) {
                    val t2 = t1 + dLon
                    result.add(tileFactory.createTile(MercatorSector(d1, d2, t1, t2), level, row, col))
                    t1 = t2
                }
                d1 = d2
            }
            return result
        }
    }

    /**
     * Returns the four children formed by subdividing this tile. This tile's sector is subdivided into four quadrants
     * as follows: Southwest; Southeast; Northwest; Northeast. A new tile is then constructed for each quadrant and
     * configured with the next level within this tile's LevelSet and its corresponding row and column within that
     * level. This returns null if this tile's level is the last level within its LevelSet.
     *
     * @param tileFactory the tile factory to use to create the children
     *
     * @return an array containing the four child tiles, or null if this tile's level is the last level
     */
    override fun subdivide(tileFactory: TileFactory): Array<Tile> {
        val childLevel = level.nextLevel ?: return emptyArray()
        val sector = sector as MercatorSector
        val d0 = sector.minLatPercent
        val d2 = sector.maxLatPercent
        val d1 = d0 + (d2 - d0) / 2.0
        val t0 = sector.minLongitude
        val t2 = sector.maxLongitude
        val t1 = average(t0, t2)
        val northRow = 2 * row
        val southRow = northRow + 1
        val westCol = 2 * column
        val eastCol = westCol + 1
        val child0 = tileFactory.createTile(MercatorSector(d0, d1, t0, t1), childLevel, northRow, westCol)
        val child1 = tileFactory.createTile(MercatorSector(d0, d1, t1, t2), childLevel, northRow, eastCol)
        val child2 = tileFactory.createTile(MercatorSector(d1, d2, t0, t1), childLevel, southRow, westCol)
        val child3 = tileFactory.createTile(MercatorSector(d1, d2, t1, t2), childLevel, southRow, eastCol)
        return arrayOf(child0, child1, child2, child3)
    }
}