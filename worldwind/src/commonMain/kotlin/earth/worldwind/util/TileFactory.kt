package earth.worldwind.util

import earth.worldwind.geom.Sector

/**
 * Factory for delegating construction of [Tile] instances.
 */
interface TileFactory {
    /**
     * Unique tile factory content type name
     */
    val contentType: String

    /**
     * Returns a tile for a specified sector, level within a [LevelSet], and row and column within that level.
     *
     * @param sector the sector spanned by the tile
     * @param level  the level at which the tile lies within a LevelSet
     * @param row    the row within the specified level
     * @param column the column within the specified level
     *
     * @return a tile constructed with the specified arguments
     */
    fun createTile(sector: Sector, level: Level, row: Int, column: Int): Tile
}