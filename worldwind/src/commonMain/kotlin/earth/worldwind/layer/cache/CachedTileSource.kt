package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource

import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.SynchronizedList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    /** Caps concurrent background refreshes so a pan across a stale region can't fan hundreds of
     *  conditional GETs + write-throughs onto the shared HTTP client and GeoPackage write lane. */
    private val revalidationSemaphore = Semaphore(MAX_CONCURRENT_REVALIDATIONS)

    /** Validator-less stale tiles pending a coalesced freshness stamp — see [scheduleBumpValidatedAt]. */
    private val pendingBumps = mutableSetOf<Long>()
    private val bumpMutex = Mutex()
    private var bumpFlushScheduled = false

    /** Bulk jobs currently downloading through this source — while any is active, SWR is fully
     *  paused so render-side refreshes can't contend with bulk for the network and write lane. */
    private val bulkJobs = SynchronizedList<Job>()

    /** Pause stale-while-revalidate for the lifetime of [job] (a bulk region download using this
     *  source). SWR resumes automatically when the job completes or is cancelled. */
    fun trackBulkJob(job: Job) {
        bulkJobs.add(job)
        job.invokeOnCompletion { bulkJobs.remove(job) }
    }

    /** The wrapped upstream tile source (e.g. `WmsTileSource`, `WmtsTileSource`,
     *  `UrlTemplateImageTileSource`). Exposed for rebind flows — `attachCache` extractors
     *  peel this off when the layer is already cache-attached and being rebound to a
     *  different content manager. `null` for offline-only caches. */
    val networkSource: TileSource? get() = inner

    /** The backing store. Exposed for bulk-download flows that persist store-level metadata
     *  (e.g. the downloaded-region bounding sector). */
    val tileStore: TileStore get() = store

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
        // A running bulk download owns the network + write lane; refreshing stale tiles can wait.
        if (bulkJobs.isNotEmpty()) return
        val network = inner ?: return
        val staleAfter = store.cachePolicy.staleAfter
        if (staleAfter == Duration.INFINITE) return
        val cachedAt = cached.cachedAt ?: return
        if (Clock.System.now().toEpochMilliseconds() - cachedAt <= staleAfter.inWholeMilliseconds) return
        // No ETag or Last-Modified means no validator, so the server can't answer 304 and every revalidation re-downloads + re-tessellates forever (GC storm); treat such tiles as fresh — restart the window, skip the GET.
        if (cached.etag == null && cached.lastModified == null) { scheduleBumpValidatedAt(z, x, y); return }
        val key = tileKey(z, x, y)
        if (!revalidateMutex.withLock { revalidating.add(key) }) return  // already refreshing
        revalidationScope.launch {
            try {
                revalidationSemaphore.withPermit {
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

    /**
     * Coalesced freshness restamp for validator-less stale tiles. The previous per-read
     * [TileStore.bumpValidatedAt] issued one write-dispatcher hop + commit per rendered tile,
     * so panning a stale cached region flooded the single GeoPackage write lane exactly when a
     * bulk download needed it. Pending tiles accumulate for [BUMP_FLUSH_DELAY], then flush in one
     * [TileStore.bumpValidatedAtBatch] call. A failed flush just leaves the tiles stale — they
     * re-enqueue on the next read.
     */
    private suspend fun scheduleBumpValidatedAt(z: Int, x: Int, y: Int) {
        val flush = bumpMutex.withLock {
            pendingBumps.add(tileKey(z, x, y))
            if (bumpFlushScheduled) false else { bumpFlushScheduled = true; true }
        }
        if (flush) revalidationScope.launch {
            delay(BUMP_FLUSH_DELAY)
            val keys = bumpMutex.withLock {
                bumpFlushScheduled = false
                pendingBumps.toList().also { pendingBumps.clear() }
            }
            try {
                store.bumpValidatedAtBatch(keys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logMessage(WARN, "CachedTileSource", "scheduleBumpValidatedAt",
                    "Freshness restamp failed for ${keys.size} tiles: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private companion object {
        /** See [revalidationSemaphore]. */
        const val MAX_CONCURRENT_REVALIDATIONS = 2

        /** Coalescing window for validator-less freshness restamps — long enough to gather a whole
         *  pan gesture's worth of tiles into one write transaction. */
        private val BUMP_FLUSH_DELAY = 2.seconds
    }
}
