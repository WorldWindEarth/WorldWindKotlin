package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob

import earth.worldwind.geom.Sector

/**
 * Persistent backing for [CachedTileSource] — read/write/evict tile blobs keyed by
 * `(z, x, y)`. The store is the cache; [CachedTileSource] is the cache-aware view that
 * combines a store with an underlying network `TileSource`.
 *
 * Implementations:
 *   * GeoPackage on JVM/Android (tiles table + `im_vector_tiles_mapbox` extension for MVT).
 *   * IndexedDB / Cache API on JS.
 *
 * [cachePolicy] is enforced lazily — implementations may evict during [writeTile] when
 * the policy is bounded, or on demand via [evict].
 */
interface TileStore {
    val cachePolicy: CachePolicy

    /** Returns the cached blob, or `null` if the tile isn't present. Treat [TileBlob.EMPTY]
     *  as "the server confirmed no tile here" — distinct from `null` cache miss. */
    suspend fun readTile(z: Int, x: Int, y: Int): TileBlob?

    /** Persist a tile. [TileBlob.EMPTY] is allowed (and recommended) for "no tile here". */
    suspend fun writeTile(z: Int, x: Int, y: Int, blob: TileBlob)

    /** Drop a tile from the store (after a server-side delete, manual eviction, etc.). */
    suspend fun deleteTile(z: Int, x: Int, y: Int)

    /** Refresh a tile's freshness timestamp WITHOUT rewriting its bytes — the conditional-GET
     *  `304 Not Modified` path. Restarts the stale-while-revalidate window so a still-current tile
     *  isn't re-requested every frame. Default no-op for stores that don't track freshness. */
    suspend fun bumpValidatedAt(z: Int, x: Int, y: Int) {}

    /** Apply [cachePolicy] now. Idempotent / unbounded policy → no-op. */
    suspend fun evict() {}

    /** Persist [sector] as the store's content bounding box — the data-availability extent a
     *  bulk download records (e.g. `gpkg_contents`). Default no-op for stores without one. */
    suspend fun setBoundingSector(sector: Sector) {}

    /** Approximate byte size of all tiles in the store. */
    suspend fun sizeBytes(): Long
}
