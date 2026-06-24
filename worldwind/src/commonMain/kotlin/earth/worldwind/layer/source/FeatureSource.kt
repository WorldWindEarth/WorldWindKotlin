package earth.worldwind.layer.source

import earth.worldwind.geom.Sector
import kotlinx.coroutines.flow.Flow

/**
 * A source of [CachedFeatureRow]s fetched in bulk — no tile axis. Used by WFS (one
 * GetFeature pulls every visible feature) and Shapefile (one HTTP fetch pulls the entire
 * .shp/.dbf/.prj triple).
 *
 * Implementations may stream pages internally; [fetchAll] returns a single flow that
 * yields rows as they arrive so the caller can render incrementally.
 */
interface BulkFeatureSource {
    suspend fun fetchAll(): Flow<CachedFeatureRow>

    /** Read only the rows intersecting [sector]. The default ignores [sector] and returns
     *  [fetchAll] — a plain network source has no spatial index. A cache-backed source overrides
     *  it to serve viewport reads from a spatially-indexed store. */
    suspend fun readBySector(sector: Sector): Flow<CachedFeatureRow> = fetchAll()

    /** Release any resources held by the source (HTTP client, etc.). Idempotent default no-op. */
    fun close() {}
}

/**
 * A source of [CachedFeatureRow]s addressed by slippy-map `(z, x, y)`. Used by
 * OsmBuildings (one Overpass call per tile, features clipped to that tile's [Sector]).
 *
 * Returns `null` from [fetchTile] to mean "the source confirms there are no features in
 * this tile" — typically because the network call returned an empty result; the cache
 * should remember the empty answer so the layer doesn't re-query every load.
 */
interface TiledFeatureSource {
    suspend fun fetchTile(z: Int, x: Int, y: Int, sector: Sector): Flow<CachedFeatureRow>?

    /**
     * Cache-only fast-path read for `(z, x, y)`. Default returns `null` — a plain network
     * source has no cache to consult. Cache-backed sources override to read their store
     * without going through the network-fetch concurrency budget. Empty flow is the
     * negative-cache sentinel ("fetched, no features"); `null` is a true miss.
     *
     * [sector] is the tile's geographic bounds — supplied so a cache-backed source can issue
     * a stale-while-revalidate background refetch (which needs the bbox) without a second
     * coordinate→sector conversion on the caller's side. Read-only hits ignore it.
     */
    suspend fun tryReadCachedTile(z: Int, x: Int, y: Int, sector: Sector): Flow<CachedFeatureRow>? = null

    /** Release any resources held by the source (HTTP client, etc.). Idempotent default no-op. */
    fun close() {}
}
