package earth.worldwind.layer.cache
import earth.worldwind.layer.source.BulkFeatureSource
import earth.worldwind.layer.source.TiledFeatureSource
import earth.worldwind.layer.source.CachedFeatureRow

import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException

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

    override suspend fun fetchAll(): Flow<CachedFeatureRow> = flow {
        if (store.sizeBytes() > 0) {
            store.readAll().collect { emit(it) }
            return@flow
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
}

/**
 * Cache-first decorator over a [TiledFeatureSource]. On [fetchTile]:
 *   1. Read [store] for `(z, x, y)`. Hit → return cached flow (an empty flow means the
 *      tile is cached with zero features — the negative-cache sentinel).
 *   2. Miss → fetch from [inner], write-through, return.
 */
class CachedTiledFeatureSource(
    private val inner: TiledFeatureSource?,
    private val store: FeatureStore,
) : TiledFeatureSource, OfflineToggleable, CachedSourceInfoProvider {

    /** The wrapped upstream tiled-feature source. Exposed for rebind flows — `attachCache`
     *  extractors peel this off when the layer is already cache-attached. */
    val networkSource: TiledFeatureSource? get() = inner

    /** When true, [fetchTile] never calls [inner] — cache hits served, cache misses
     *  return `null`. Mirrors [CachedTileSource.isCacheOnly]. */
    override var isCacheOnly: Boolean = inner == null

    override val cacheInfo: CachedSourceInfo?
        get() = (store as? CachedSourceInfoProvider)?.cacheInfo

    override suspend fun tryReadCachedTile(z: Int, x: Int, y: Int): Flow<CachedFeatureRow>? =
        store.readTile(z, x, y)

    override suspend fun fetchTile(z: Int, x: Int, y: Int, sector: Sector): Flow<CachedFeatureRow>? {
        store.readTile(z, x, y)?.let { return it }
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
            val accumulator = mutableListOf<CachedFeatureRow>()
            fetched.collect { row ->
                accumulator += row
                emit(row)
            }
            try {
                store.writeTile(z, x, y, accumulator.asFlow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logMessage(WARN, "CachedTiledFeatureSource", "fetchTile",
                    "Cache write failed for ($z,$x,$y): ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}
