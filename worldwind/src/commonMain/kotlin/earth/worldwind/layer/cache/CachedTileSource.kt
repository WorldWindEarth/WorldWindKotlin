package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource

import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Cache-first decorator over a [TileSource]. The lookup order is:
 *   1. Read [store]. Hit → return cached blob (no network); if the blob is older than the
 *      store's eviction [CachePolicy.staleAfter], also kick a background refresh
 *      (stale-while-revalidate — see [maybeRevalidate]).
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
@OptIn(DelicateCoroutinesApi::class)
class CachedTileSource(
    private val inner: TileSource?,
    private val store: TileStore,
    /** Background scope for stale-while-revalidate refreshes; defaults to [GlobalScope] since
     *  they're fire-and-forget and must outlive the render call that triggered them. */
    private val revalidationScope: CoroutineScope = GlobalScope,
) : TileSource, OfflineToggleable, CachedSourceInfoProvider, RevalidatingSource {

    /** Invoked (off the render thread) after a stale tile is re-downloaded and written through,
     *  so the render layer can drop the tile's cached texture and redraw. `null` = no swap;
     *  the fresh tile then appears on the next texture (re)load. */
    override var onTileRevalidated: ((z: Int, x: Int, y: Int) -> Unit)? = null

    private val revalidating = mutableSetOf<Long>()
    private val revalidateMutex = Mutex()

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

    // Mirror fetchTile's hit path: serving a cached tile here (the MVT render fast-path) must also
    // kick stale-while-revalidate, or vector tiles served via tryReadCachedTile never refresh.
    override suspend fun tryReadCachedTile(z: Int, x: Int, y: Int): TileBlob? =
        store.readTile(z, x, y)?.also { maybeRevalidate(z, x, y, it) }

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
        store.readTile(z, x, y)?.let { cached ->
            maybeRevalidate(z, x, y, cached)
            return cached
        }
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

    /**
     * Stale-while-revalidate: after serving a cached tile, if it's older than the store's eviction
     * [CachePolicy.staleAfter], revalidate it in the background via a conditional GET. A `200`
     * writes the fresh bytes through and fires [onTileRevalidated]; a `304` only bumps the freshness
     * stamp ([TileStore.bumpValidatedAt]) and leaves the tile in place. Either outcome restarts the
     * staleAfter window so the tile isn't re-requested every frame. No-op when offline, when there's no
     * network source, when freshness isn't tracked (`staleAfter == INFINITE` or the store didn't surface
     * [TileBlob.cachedAt]), or when a refresh for this tile is already in flight. Errors are
     * swallowed — a failed refresh leaves the stale tile (and its old stamp) in place to retry.
     */
    private suspend fun maybeRevalidate(z: Int, x: Int, y: Int, cached: TileBlob) {
        if (isCacheOnly) return
        val network = inner ?: return
        val staleAfter = store.cachePolicy.staleAfter
        if (staleAfter == Duration.INFINITE) return
        val cachedAt = cached.cachedAt ?: return
        if (Clock.System.now().toEpochMilliseconds() - cachedAt <= staleAfter.inWholeMilliseconds) return
        // No ETag or Last-Modified means no validator, so the server can't answer 304 and every revalidation re-downloads + re-tessellates forever (GC storm); treat such tiles as fresh — restart the window, skip the GET.
        if (cached.etag == null && cached.lastModified == null) { store.bumpValidatedAt(z, x, y); return }
        val key = tileKey(z, x, y)
        if (!revalidateMutex.withLock { revalidating.add(key) }) return  // already refreshing
        revalidationScope.launch {
            try {
                // Conditional GET: pass the cached validators so the server can answer 304 when the
                // tile is unchanged (cheap header-only round-trip, no body re-download).
                val fresh = network.fetchTile(z, x, y, cached.etag, cached.lastModified)
                if (fresh == null) {
                    // 304 Not Modified — bytes still current. Bump the freshness stamp so we don't
                    // re-request until the next staleAfter window, and leave the tile (and its texture)
                    // untouched: no onTileRevalidated, no redraw.
                    store.bumpValidatedAt(z, x, y)
                } else if (cached.bytes.contentEquals(fresh.bytes)) {
                    // 200 but byte-identical — server ignored our conditional headers; treat as not-modified (bump freshness, keep the tessellation) to avoid re-tessellating every tile forever.
                    store.bumpValidatedAt(z, x, y)
                } else {
                    store.writeTile(z, x, y, fresh)
                    onTileRevalidated?.invoke(z, x, y)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logMessage(WARN, "CachedTileSource", "maybeRevalidate",
                    "Background refresh failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
            } finally {
                revalidateMutex.withLock { revalidating.remove(key) }
            }
        }
    }
}
