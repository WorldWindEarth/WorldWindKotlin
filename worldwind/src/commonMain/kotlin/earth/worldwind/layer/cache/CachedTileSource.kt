package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource

import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cache-first decorator over a [TileSource]. The lookup order is:
 *   1. Read [store]. Hit → return cached blob (no network).
 *   2. Miss → fetch from [inner] (network), write-through to [store], return.
 *
 * When [inner] is `null`, this acts as a pure cache view — useful for replaying a GPKG
 * file offline (e.g. opened in QGIS-style "browse cached layers" mode). A network failure
 * with no cached blob propagates as `null` from [fetchTile].
 *
 * Cache hits bypass `previousEtag` / `previousLastModified` entirely — the cached blob is
 * served verbatim. Pass those revalidation headers in only when you want the inner source
 * to issue a conditional GET, which the decorator only does on a miss.
 */
class CachedTileSource(
    private val inner: TileSource?,
    private val store: TileStore,
) : TileSource, OfflineToggleable, CachedSourceInfoProvider {

    /** The wrapped upstream tile source (e.g. `WmsTileSource`, `WmtsTileSource`,
     *  `UrlTemplateImageTileSource`). Exposed for rebind flows — `attachCache` extractors
     *  peel this off when the layer is already cache-attached and being rebound to a
     *  different content manager. `null` for offline-only caches. */
    val networkSource: TileSource? get() = inner

    /** Delegates to the underlying [store] when it reports a [CachedSourceInfo]; `null`
     *  when the store isn't cache-introspectable (custom in-memory store, etc.). */
    override val cacheInfo: CachedSourceInfo?
        get() = (store as? CachedSourceInfoProvider)?.cacheInfo

    /** When true, [fetchTile] never calls [inner] — cache hits are served as usual, cache
     *  misses return `null`. Equivalent to constructing with `inner = null`, but toggleable
     *  at runtime so a single layer can flip between online and offline modes without
     *  re-wiring its tile factory. */
    override var isCacheOnly: Boolean = inner == null

    override suspend fun tryReadCachedTile(z: Int, x: Int, y: Int): TileBlob? = store.readTile(z, x, y)

    /**
     * Bulk-download semantics: by default skip already-cached tiles, otherwise force a
     * network fetch and write through to [store]. Unlike [fetchTile], this **bypasses
     * [isCacheOnly]** — bulk is a user-initiated "fetch these bytes now" operation
     * orthogonal to the renderer's offline toggle. Returns the blob (cached or freshly
     * fetched), or `null` when there's no cached tile *and* no network source / network
     * fetch failed.
     *
     * @param overrideCache when `true`, skip the cache read and force a fresh network
     *   fetch for every tile (still write-through to [store]). Used by bulk download's
     *   "re-download everything" mode — mirrors pre-refactor `makeLocal(overrideCache)`.
     */
    suspend fun bulkFetchTile(z: Int, x: Int, y: Int, overrideCache: Boolean = false): TileBlob? {
        if (!overrideCache) store.readTile(z, x, y)?.let { return it }
        val network = inner ?: return null
        // Let transport errors propagate so the bulk-retrieval loop can retry with backoff.
        // Swallowing them to `null` here (as the renderer-facing fetchTile does) would make
        // the loop treat a transient failure as a permanent miss with no retry.
        val fetched = network.fetchTile(z, x, y) ?: return null
        try {
            store.writeTile(z, x, y, fetched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logMessage(WARN, "CachedTileSource", "bulkFetchTile",
                "Cache write failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
        }
        return fetched
    }


    override suspend fun fetchTile(
        z: Int, x: Int, y: Int,
        previousEtag: String?,
        previousLastModified: String?,
    ): TileBlob? {
        store.readTile(z, x, y)?.let { return it }
        if (isCacheOnly) return null
        val fetched = try {
            inner?.fetchTile(z, x, y, previousEtag, previousLastModified)
        } catch (e: CancellationException) {
            // Cooperative cancellation: the renderer or caller wants us to stop. Re-throw
            // so the coroutine actually winds down — swallowing it would deadlock the
            // structured-concurrency parent waiting on this job.
            throw e
        } catch (e: Throwable) {
            logMessage(WARN, "CachedTileSource", "fetchTile",
                "Inner source failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
            null
        } ?: return null
        try {
            store.writeTile(z, x, y, fetched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Persist failure must not break rendering — the bytes are already in memory.
            logMessage(WARN, "CachedTileSource", "fetchTile",
                "Cache write failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
        }
        return fetched
    }
}
