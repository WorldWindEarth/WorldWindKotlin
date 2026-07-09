package earth.worldwind.layer.cache

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.layer.mercator.SlippyTiles
import earth.worldwind.layer.mvt.MvtVectorLayer
import earth.worldwind.layer.source.TileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Download every MVT tile intersecting [sector] across the [resolution] range into the layer's
 * vector-tile store — the "save a region for offline use" flow. Takes the same
 * `resolution: ClosedRange<Angle>` as [TiledImageLayer.launchBulkRetrieval] (raster) and
 * [TiledElevationCoverage.launchBulkRetrieval] (elevation) for a uniform bulk API across layer
 * types; the range is mapped to a slippy zoom range via [SlippyTiles.zoomForResolution]
 * (nearest-neighbor, matching `LevelSet.levelForResolution`) and clamped to the layer's
 * [MvtVectorLayer.minZoom]..[MvtVectorLayer.maxZoom].
 *
 * Cache wiring is transparent: when the layer's [MvtVectorLayer.source] is a [CachedTileSource]
 * (the usual case after attaching a GeoPackage vector-tile store), each fetch routes through
 * [CachedTileSource.bulkFetchTile] — bypassing the renderer-side [OfflineToggleable.isCacheOnly]
 * gate and writing through to the store. A plain network source still downloads (validating the
 * region is reachable) but persists nothing.
 *
 * Returns the launched [Job] so the caller can cancel mid-download.
 *
 * @param sector world region to download.
 * @param resolution angular resolution range in degrees/radians-per-pixel (open-ended range);
 *   `resolution.start` (finest) maps to the deepest zoom, `resolution.endInclusive` (coarsest)
 *   to the shallowest — both clamped to the layer's zoom range.
 * @param scope coroutine scope; defaults to [GlobalScope] for a long-running background job.
 * @param overrideCache when `true`, every tile is re-downloaded from the network even if already
 *   cached (still write-through). Ignored when the source isn't a [CachedTileSource].
 * @param onProgress optional `(downloaded, skipped, total)` callback fired after every tile.
 */
@OptIn(DelicateCoroutinesApi::class)
fun MvtVectorLayer.launchBulkRetrieval(
    sector: Sector,
    resolution: ClosedRange<Angle>,
    scope: CoroutineScope = GlobalScope,
    maxRetries: Int = 3,
    retryTimeoutShort: Duration = 5.seconds,
    retryTimeoutLong: Duration = 15.seconds,
    overrideCache: Boolean = false,
    onProgress: ((downloaded: Long, skipped: Long, total: Long) -> Unit)? = null,
): Job {
    val (shallowestZoom, deepestZoom) = zoomRangeForResolution(resolution)
    val layer = this
    val source: TileSource = this.source
    val cached = source as? CachedTileSource
    return launchSlippyBulkRetrieval(
        sector = sector,
        minZoom = shallowestZoom,
        maxZoom = deepestZoom,
        scope = scope,
        maxRetries = maxRetries,
        retryTimeoutShort = retryTimeoutShort,
        retryTimeoutLong = retryTimeoutLong,
        onProgress = onProgress,
        onSuccess = {
            // Record the downloaded region as the layer's data extent and persist it for reopen.
            if (layer.sector.isFullSphere) layer.sector.copy(sector) else layer.sector.union(sector)
            cached?.tileStore?.setBoundingSector(layer.sector)
        },
    ) { z, x, y, _ ->
        // The source consumes slippy coordinates directly (y = 0 is north), which is exactly what
        // the enumerator yields — no bottom-up/top-down flip like the renderer's loadTileContent.
        val blob = cached?.bulkFetchTile(z, x, y, overrideCache) ?: source.fetchTile(z, x, y)
        if (blob == null || blob.isEmpty) TileOutcome.SKIPPED else TileOutcome.DOWNLOADED
    }
}

/**
 * Total number of MVT tiles [launchBulkRetrieval] would fetch for [sector] across the [resolution]
 * range — the slippy analog of [LevelSet.tileCount], for sizing a download before committing to it.
 * Uses the same zoom mapping and clamp as [launchBulkRetrieval], so the result matches the `total`
 * later reported to that call's `onProgress`.
 */
fun MvtVectorLayer.tileCount(sector: Sector, resolution: ClosedRange<Angle>): Long {
    val (shallowestZoom, deepestZoom) = zoomRangeForResolution(resolution)
    return slippyTileCount(sector, shallowestZoom, deepestZoom)
}

/** Maps an angular [resolution] range to a `(shallowest, deepest)` slippy zoom pair clamped to the layer's zoom range. */
private fun MvtVectorLayer.zoomRangeForResolution(resolution: ClosedRange<Angle>): Pair<Int, Int> {
    // resolution.start is the finest (smallest angle) → deepest zoom; endInclusive is the coarsest.
    val deepestZoom = SlippyTiles.zoomForResolution(resolution.start).coerceIn(minZoom, maxZoom)
    val shallowestZoom = SlippyTiles.zoomForResolution(resolution.endInclusive).coerceIn(minZoom, maxZoom)
    return shallowestZoom to deepestZoom
}
