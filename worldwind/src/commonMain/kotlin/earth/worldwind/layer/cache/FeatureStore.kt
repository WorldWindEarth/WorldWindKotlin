package earth.worldwind.layer.cache
import earth.worldwind.layer.source.CachedFeatureRow

import earth.worldwind.geom.Sector
import kotlinx.coroutines.flow.Flow

/**
 * Persistent backing for cached feature data. Supports both shapes that the source layer
 * may produce:
 *   * Bulk — every row in one stream (WFS, Shapefile).
 *   * Tile-addressed — rows keyed by `(z, x, y)` (OsmBuildings).
 *
 * One GPKG `features` table backs both. Tile-addressed rows carry `(z, x, y)` columns
 * populated; bulk rows leave them NULL. The store knows nothing about the schema beyond
 * "rows keyed by tile or by insertion order" — [CachedFeatureRow] is the wire format both
 * paths use.
 */
interface FeatureStore {
    val cachePolicy: CachePolicy

    /** Read every bulk row (`(z, x, y)` IS NULL) in insertion order. */
    suspend fun readAll(): Flow<CachedFeatureRow>

    /** Read only the bulk rows whose geometry intersects [sector]. The default ignores [sector]
     *  and returns [readAll] — correct but unbounded; a spatially-indexed store (GeoPackage RTree)
     *  overrides it to serve viewport reads without loading the whole table. */
    suspend fun readBySector(sector: Sector): Flow<CachedFeatureRow> = readAll()

    /** Atomically replace every bulk row with the supplied flow. Existing tile-addressed
     *  rows are left untouched. */
    suspend fun replaceAll(rows: Flow<CachedFeatureRow>)

    /** Read rows for one tile, or `null` if the tile is absent (cache miss).
     *  An empty flow distinguishes "tile cached with zero rows" from "tile not cached".
     *  [sector] is the tile's true geographic bounds for the spatial query; null → derive
     *  Web-Mercator slippy bounds from `(z, x, y)` (back-compat for slippy-keyed tiles). */
    suspend fun readTile(z: Int, x: Int, y: Int, sector: Sector? = null): Flow<CachedFeatureRow>?

    /** Epoch-millis the tile at `(z, x, y)` was last written, for stale-while-revalidate.
     *  Default `null` = freshness isn't tracked (the store opts out of SWR — its tiles are
     *  served but never background-refreshed). */
    suspend fun readTileLastModified(z: Int, x: Int, y: Int): Long? = null

    /** Atomically replace rows for one tile. Pass an empty flow to record "tile has no
     *  features" — that's the negative cache for ocean / empty Overpass responses.
     *  [sector] is the tile's true geographic bounds (null → slippy bounds from `(z, x, y)`). */
    suspend fun writeTile(z: Int, x: Int, y: Int, rows: Flow<CachedFeatureRow>, sector: Sector? = null)

    /** Drop a tile entry from the store. */
    suspend fun deleteTile(z: Int, x: Int, y: Int)

    /** Apply [cachePolicy] now. Idempotent / unbounded → no-op. */
    suspend fun evict() {}

    /** Persist [sector] as the store's content bounding box — the data-availability extent a
     *  bulk download records (e.g. `gpkg_contents`). Default no-op for stores without one. */
    suspend fun setBoundingSector(sector: Sector) {}

    /** Approximate byte size of all rows in the store. */
    suspend fun sizeBytes(): Long
}
