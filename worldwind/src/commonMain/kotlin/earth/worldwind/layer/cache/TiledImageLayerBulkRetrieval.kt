package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.layer.TiledImageLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Download every tile within [sector] across the [resolution] range into the layer's
 * underlying [TileStore] (when the layer was constructed with a [CachedTileSource]).
 * Returns the launched [Job] for cancellation; returns `null` when the layer's tile
 * factory isn't backed by a [TileSourceFactoryAdapter] (i.e. no source-based wiring,
 * so bulk-via-source is impossible).
 *
 * Bulk download is user-initiated and operates on the underlying network + store
 * directly, peeling apart any [CachedTileSource] decorator so the renderer-side
 * [OfflineToggleable.isCacheOnly] flag never gates the fetch. A renderer drawing the
 * same source concurrently still respects its cache-only setting — the two paths share
 * the store but use distinct entry points into the network.
 *
 * Replaces the previous `makeLocal` JVM/Android methods that hung off [TiledImageLayer]
 * directly. Same `(downloaded, skipped, total)` progress semantics, but cache wiring is
 * now external (composed into the [TileSourceFactoryAdapter]) so this is one helper that
 * works uniformly across platforms.
 *
 * @param overrideCache when `true`, every tile is re-downloaded from the network even if
 *   it's already in the cache (still write-through). Mirrors pre-refactor `makeLocal`'s
 *   flag of the same name. Ignored when the source isn't a [CachedTileSource].
 */
@OptIn(DelicateCoroutinesApi::class)
fun TiledImageLayer.launchBulkRetrieval(
    sector: Sector,
    resolution: ClosedRange<Angle>,
    scope: CoroutineScope = GlobalScope,
    maxRetries: Int = 3,
    retryTimeoutShort: Duration = 5.seconds,
    retryTimeoutLong: Duration = 15.seconds,
    overrideCache: Boolean = false,
    onProgress: ((downloaded: Long, skipped: Long, total: Long) -> Unit)? = null,
): Job? {
    val surface = tiledSurfaceImage ?: return null
    val adapter = surface.tileFactory as? TileSourceFactoryAdapter ?: return null
    // When the layer's source is a CachedTileSource, route through a thin adapter that
    // calls bulkFetchTile (cache-then-network, always allows network). For a non-cached
    // source the generic source.launchBulkRetrieval path is unchanged.
    val bulkSource: TileSource = (adapter.source as? CachedTileSource)
        ?.let { CachedBulkSourceAdapter(it, overrideCache) }
        ?: adapter.source
    return bulkSource.launchBulkRetrieval(
        levelSet = surface.levelSet,
        sector = sector,
        resolution = resolution,
        scope = scope,
        maxRetries = maxRetries,
        retryTimeoutShort = retryTimeoutShort,
        retryTimeoutLong = retryTimeoutLong,
        onProgress = onProgress,
    )
}

/** Thin [TileSource] over a [CachedTileSource] whose `fetchTile` delegates to
 *  [CachedTileSource.bulkFetchTile], bypassing the decorator's `isCacheOnly` gate. */
private class CachedBulkSourceAdapter(
    private val cached: CachedTileSource,
    private val overrideCache: Boolean,
) : TileSource {
    override suspend fun fetchTile(
        z: Int, x: Int, y: Int, previousEtag: String?, previousLastModified: String?,
    ): TileBlob? = cached.bulkFetchTile(z, x, y, overrideCache)
}
