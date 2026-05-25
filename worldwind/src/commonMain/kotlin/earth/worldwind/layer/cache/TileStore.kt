package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob

/**
 * Persistent backing for [CachedTileSource] — read/write/evict tile blobs keyed by
 * `(z, x, y)`. The store is the cache; [CachedTileSource] is the cache-aware view that
 * combines a store with an underlying network [TileSource].
 *
 * Implementations:
 *   * GeoPackage on JVM/Android (tiles table + `im_vector_tiles_mapbox` extension for MVT).
 *   * IndexedDB / Cache API on JS.
 *
 * [evictionPolicy] is enforced lazily — implementations may evict during [writeTile] when
 * the policy is bounded, or on demand via [evict].
 */
interface TileStore {
    val evictionPolicy: CacheEvictionPolicy

    /** Returns the cached blob, or `null` if the tile isn't present. Treat [TileBlob.EMPTY]
     *  as "the server confirmed no tile here" — distinct from `null` cache miss. */
    suspend fun readTile(z: Int, x: Int, y: Int): TileBlob?

    /** Persist a tile. [TileBlob.EMPTY] is allowed (and recommended) for "no tile here". */
    suspend fun writeTile(z: Int, x: Int, y: Int, blob: TileBlob)

    /** Drop a tile from the store (after a server-side delete, manual eviction, etc.). */
    suspend fun deleteTile(z: Int, x: Int, y: Int)

    /** Apply [evictionPolicy] now. Idempotent / unbounded policy → no-op. */
    suspend fun evict() {}

    /** Approximate byte size of all tiles in the store. */
    suspend fun sizeBytes(): Long
}
