package earth.worldwind.layer.cache
import earth.worldwind.layer.source.BulkFeatureSource
import earth.worldwind.layer.source.TiledFeatureSource
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.envelopeCenter

import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Cache-first decorator over a [BulkFeatureSource]. On [fetchAll]:
 *   1. If [store] has any bulk rows, return them as-is (no network).
 *   2. Otherwise stream from [inner], emit rows to the caller as they arrive, accumulate
 *      them, and atomically [FeatureStore.replaceAll] when the inner flow completes.
 *
 * When [inner] is `null`, this acts as a pure cache view — useful for replaying a GPKG
 * file offline. A network failure with an empty cache yields an empty flow.
 *
 * Streaming preserves incremental-render behaviour for paginated WFS responses; the cache
 * write happens once, atomically, after the full fetch succeeds.
 */
class CachedBulkFeatureSource(
    private val inner: BulkFeatureSource?,
    private val store: FeatureStore,
) : BulkFeatureSource, OfflineToggleable, CachedSourceInfoProvider {

    /** The wrapped upstream feature source. Exposed for rebind flows — `attachCache`
     *  extractors peel this off when the layer is already cache-attached. */
    val networkSource: BulkFeatureSource? get() = inner

    /** When true, [fetchAll] never calls [inner] — cache content is returned as-is and a
     *  cold cache yields an empty flow. Mirrors [CachedTileSource.isCacheOnly]. */
    override var isCacheOnly: Boolean = inner == null

    override val cacheInfo: CachedSourceInfo?
        get() = (store as? CachedSourceInfoProvider)?.cacheInfo

    /** Serialises the one-time store population so concurrent viewport reads don't double-download. */
    private val populateMutex = Mutex()

    /** Populate the [store] once from [inner] (a full download), then serve only the rows
     *  intersecting [sector] from its spatial index — offline and viewport-bounded thereafter. An
     *  already-populated store (e.g. a prior session) skips the download. */
    override suspend fun readBySector(sector: Sector): Flow<CachedFeatureRow> {
        if (!isCacheOnly && store.sizeBytes() == 0L) populateMutex.withLock {
            val source = inner
            if (source != null && store.sizeBytes() == 0L) store.replaceAll(source.fetchAll())
        }
        return store.readBySector(sector)
    }

    override suspend fun fetchAll(): Flow<CachedFeatureRow> = flow {
        if (store.sizeBytes() > 0) {
            var cachedRows = 0
            store.readAll().collect { cachedRows++; emit(it) }
            if (cachedRows > 0 || isCacheOnly) return@flow
            // Cache present but yielded nothing (e.g. schema-drift decode failure) — refetch.
            logMessage(WARN, "CachedBulkFeatureSource", "fetchAll",
                "cache present but yielded 0 rows; refetching from source")
        }
        if (isCacheOnly) return@flow
        val source = inner ?: return@flow
        val accumulator = mutableListOf<CachedFeatureRow>()
        try {
            source.fetchAll().collect { row ->
                accumulator += row
                emit(row)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logMessage(WARN, "CachedBulkFeatureSource", "fetchAll",
                "Inner source failed mid-stream: ${e::class.simpleName}: ${e.message}")
            // Partial accumulator is intentionally NOT written to cache — a partial cache
            // would be served on the next launch as if complete. Let the caller retry.
            throw e
        }
        try {
            store.replaceAll(accumulator.asFlow())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logMessage(WARN, "CachedBulkFeatureSource", "fetchAll",
                "Cache write failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** Propagate close to the wrapped network source so it can release its HTTP client. */
    override fun close() = inner?.close() ?: Unit
}

/**
 * Cache-first decorator over a [TiledFeatureSource]. On [fetchTile]:
 *   1. Read [store] for `(z, x, y)`. Hit → return cached flow (an empty flow means the
 *      tile is cached with zero features — the negative-cache sentinel); if the tile is older
 *      than the store's eviction [CachePolicy.staleAfter], also kick a background refresh
 *      (stale-while-revalidate — see [maybeRevalidate]).
 *   2. Miss → fetch from [inner], write-through, return.
 */
@OptIn(DelicateCoroutinesApi::class)
class CachedTiledFeatureSource(
    private val inner: TiledFeatureSource?,
    private val store: FeatureStore,
    /** Background scope for stale-while-revalidate refreshes; defaults to [GlobalScope] since
     *  they're fire-and-forget and must outlive the render call that triggered them. */
    private val revalidationScope: CoroutineScope = GlobalScope,
) : TiledFeatureSource, OfflineToggleable, CachedSourceInfoProvider, RevalidatingSource {

    /** The wrapped upstream tiled-feature source. Exposed for rebind flows — `attachCache`
     *  extractors peel this off when the layer is already cache-attached. */
    val networkSource: TiledFeatureSource? get() = inner

    /** The backing store. Exposed for bulk-download flows that persist store-level metadata
     *  (e.g. the downloaded-region bounding sector). */
    val featureStore: FeatureStore get() = store

    /** Invoked (off the render thread) after a stale tile is re-downloaded and replaced, so the
     *  render layer can drop its in-memory copy and reload the fresh rows. */
    override var onTileRevalidated: ((z: Int, x: Int, y: Int) -> Unit)? = null

    private val revalidating = mutableSetOf<Long>()
    private val revalidateMutex = Mutex()

    /** When true, [fetchTile] never calls [inner] — cache hits served, cache misses
     *  return `null`. Mirrors [CachedTileSource.isCacheOnly]. */
    override var isCacheOnly: Boolean = inner == null

    override val cacheInfo: CachedSourceInfo?
        get() = (store as? CachedSourceInfoProvider)?.cacheInfo

    override suspend fun tryReadCachedTile(z: Int, x: Int, y: Int, sector: Sector): Flow<CachedFeatureRow>? =
        store.readTile(z, x, y, sector)?.also { maybeRevalidate(z, x, y, sector) }?.filter { it.ownedBy(sector) }

    /**
     * Bulk-download counterpart to [fetchTile] — used by "save a region for offline use" flows.
     * Ignores [isCacheOnly] (bulk always allows network) and writes the FULL fetch through to
     * [store]. The write-through happens when the returned flow is collected, so the caller must
     * drain it. Returns `null` when there's nothing to fetch (no rows / no network source).
     *
     * Unlike [fetchTile], transport errors are NOT swallowed to `null`: they surface during
     * collection so the bulk-retrieval loop can retry with backoff instead of recording a
     * permanent miss.
     *
     * @param overrideCache when `true`, re-fetch from the network even if the tile is cached.
     */
    suspend fun bulkFetchTile(
        z: Int, x: Int, y: Int, sector: Sector, overrideCache: Boolean = false,
    ): Flow<CachedFeatureRow>? {
        if (!overrideCache) store.readTile(z, x, y, sector)?.let { return it }
        val fetched = (inner ?: return null).fetchTile(z, x, y, sector) ?: return null
        return flow {
            val accumulator = mutableListOf<CachedFeatureRow>()
            fetched.collect { row ->
                accumulator += row
                emit(row)
            }
            // NonCancellable: persist atomically even if the caller aborts mid-write, so a cancelled
            // write can't leave a "covered but empty" record that later reads as a negative-cache hit.
            withContext(NonCancellable) {
                try {
                    store.writeTile(z, x, y, accumulator.asFlow(), sector)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logMessage(WARN, "CachedTiledFeatureSource", "bulkFetchTile",
                        "Cache write failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    override suspend fun fetchTile(z: Int, x: Int, y: Int, sector: Sector): Flow<CachedFeatureRow>? {
        store.readTile(z, x, y, sector)?.let { maybeRevalidate(z, x, y, sector); return it.filter { row -> row.ownedBy(sector) } }
        if (isCacheOnly) return null
        val fetched = try {
            inner?.fetchTile(z, x, y, sector)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logMessage(WARN, "CachedTiledFeatureSource", "fetchTile",
                "Inner source failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
            null
        } ?: return null
        return flow {
            // Emit only features this tile owns (envelope-center inside the tile) so a boundary-
            // straddling feature renders once; but write through the FULL fetch so the neighbouring
            // tiles can serve the parts they own.
            val accumulator = mutableListOf<CachedFeatureRow>()
            fetched.collect { row ->
                accumulator += row
                if (row.ownedBy(sector)) emit(row)
            }
            // NonCancellable: once the whole tile is fetched, persist it atomically even if the layer
            // aborts this tile mid-write (abort-on-view-change). A cancelled write can interrupt
            // writeFeatureTile between clearing the region and inserting rows, leaving a "covered but
            // empty" coverage record that the next read serves as a (wrong) negative-cache hit.
            withContext(NonCancellable) {
                try {
                    store.writeTile(z, x, y, accumulator.asFlow(), sector)
                } catch (e: Throwable) {
                    logMessage(WARN, "CachedTiledFeatureSource", "fetchTile",
                        "Cache write failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** A feature is owned by the tile whose [sector] contains its geometry's envelope-center; the
     *  upper/right edges are exclusive so a center exactly on a shared boundary picks one tile. The
     *  null-geometry sentinel (never emitted to renderables) is treated as owned. */
    private fun CachedFeatureRow.ownedBy(sector: Sector): Boolean {
        val (lon, lat) = (geometry ?: return true).envelopeCenter()
        return lon >= sector.minLongitude.inDegrees && lon < sector.maxLongitude.inDegrees &&
            lat >= sector.minLatitude.inDegrees && lat < sector.maxLatitude.inDegrees
    }

    /**
     * Stale-while-revalidate: after serving a cached tile, if it's older than the store's
     * eviction [CachePolicy.staleAfter], re-download it in the background and **replace it
     * only on a non-empty success** — an empty or failed refresh leaves the existing tile
     * untouched, so a transient Overpass hiccup can never blank good data. The successful write
     * bumps `last_modified`, so the tile stops being stale. No-op when offline, when there's no
     * network source, when freshness isn't tracked (`staleAfter == INFINITE` or the store doesn't
     * surface a tile timestamp), or when a refresh for this tile is already in flight.
     */
    private suspend fun maybeRevalidate(z: Int, x: Int, y: Int, sector: Sector) {
        if (isCacheOnly) return
        val network = inner ?: return
        val staleAfter = store.cachePolicy.staleAfter
        if (staleAfter == Duration.INFINITE) return
        val cachedAt = store.readTileLastModified(z, x, y) ?: return
        if (Clock.System.now().toEpochMilliseconds() - cachedAt <= staleAfter.inWholeMilliseconds) return
        val key = tileKey(z, x, y)
        if (!revalidateMutex.withLock { revalidating.add(key) }) return  // already refreshing
        revalidationScope.launch {
            try {
                val fresh = network.fetchTile(z, x, y, sector)?.toList() ?: return@launch
                // Never blank: an empty refresh keeps the existing rows — replacement only.
                if (fresh.isEmpty()) return@launch
                store.writeTile(z, x, y, fresh.asFlow(), sector)
                onTileRevalidated?.invoke(z, x, y)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logMessage(WARN, "CachedTiledFeatureSource", "maybeRevalidate",
                    "Background refresh failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
            } finally {
                revalidateMutex.withLock { revalidating.remove(key) }
            }
        }
    }
}
