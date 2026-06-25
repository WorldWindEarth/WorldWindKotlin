package earth.worldwind.layer.mercator

import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.layer.TiledVectorLayer
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Tile
import earth.worldwind.util.TileFactory

/**
 * [TiledVectorLayer] over a **Web-Mercator slippy** tile pyramid (the `z/x/y` scheme used by MVT
 * vector tiles and OSM building tiles), as opposed to the geographic Plate-Carrée pyramid of
 * [earth.worldwind.layer.TiledFeatureLayer].
 *
 * It feeds the base a [LevelSet] + [MercatorTileFactory] built exactly like
 * [MercatorTiledImageLayer], so each quadtree tile has `level.levelNumber == slippy z`,
 * `column == x`, `row == y` (the factory inverts Y), and a [MercatorSector] extent — `subdivide`
 * and `mustSubdivide` come from the engine's Mercator tiles for free. The factory's
 * [MercatorTileFactory.getImageSource] returns null: these tiles are pure quadtree nodes (the layer
 * draws vector content via [renderTileContent], never imagery).
 */
abstract class MercatorTiledVectorLayer<C : Any>(
    /** Quadtree levels coarser than this aren't fetched (the slippy min-zoom floor). */
    levelOffset: Int = 0,
    /** Total pyramid levels (0 .. numLevels-1); the deepest fetchable slippy zoom is numLevels-1. */
    numLevels: Int = 22,
    maxLoadedTiles: Int = 256,
    maxConcurrentFetches: Int = 4,
    displayName: String? = null,
) : TiledVectorLayer<C>(
    buildMercatorLevelSet(numLevels, levelOffset),
    VectorMercatorTileFactory, maxLoadedTiles, maxConcurrentFetches, displayName,
) {
    private object VectorMercatorTileFactory : TileFactory {
        override val contentType = "MercatorTiledVectorLayer"
        // Top-level tiles arrive as plain Sectors (Tile.assembleTilesForLevel); subdivided children
        // are already MercatorSectors. Convert as needed. No imageSource — these are pure quadtree
        // nodes; the layer draws vector content via renderTileContent, never imagery.
        override fun createTile(sector: Sector, level: Level, row: Int, column: Int): Tile =
            MercatorImageTile(sector as? MercatorSector ?: MercatorSector.fromSector(sector), level, row, column)
    }

    companion object {
        private const val TILE_SIZE = 256

        private fun buildMercatorLevelSet(numLevels: Int, levelOffset: Int): LevelSet {
            // Level 0 is one tile spanning the whole Web-Mercator world; each level halves both deltas.
            val origin = MercatorSector()
            return LevelSet(
                MercatorSector(), origin, Location(origin.deltaLatitude, origin.deltaLongitude),
                numLevels, TILE_SIZE, TILE_SIZE, levelOffset,
            )
        }
    }
}
