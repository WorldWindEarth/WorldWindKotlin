package earth.worldwind.layer.cache
import earth.worldwind.layer.source.CachedFeatureRow

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
    val evictionPolicy: CacheEvictionPolicy

    /** Read every bulk row (`(z, x, y)` IS NULL) in insertion order. */
    suspend fun readAll(): Flow<CachedFeatureRow>

    /** Atomically replace every bulk row with the supplied flow. Existing tile-addressed
     *  rows are left untouched. */
    suspend fun replaceAll(rows: Flow<CachedFeatureRow>)

    /** Read rows for one tile, or `null` if the tile is absent (cache miss).
     *  An empty flow distinguishes "tile cached with zero rows" from "tile not cached". */
    suspend fun readTile(z: Int, x: Int, y: Int): Flow<CachedFeatureRow>?

    /** Atomically replace rows for one tile. Pass an empty flow to record "tile has no
     *  features" — that's the negative cache for ocean / empty Overpass responses. */
    suspend fun writeTile(z: Int, x: Int, y: Int, rows: Flow<CachedFeatureRow>)

    /** Drop a tile entry from the store. */
    suspend fun deleteTile(z: Int, x: Int, y: Int)

    /** Apply [evictionPolicy] now. Idempotent / unbounded → no-op. */
    suspend fun evict() {}

    /** Approximate byte size of all rows in the store. */
    suspend fun sizeBytes(): Long
}
