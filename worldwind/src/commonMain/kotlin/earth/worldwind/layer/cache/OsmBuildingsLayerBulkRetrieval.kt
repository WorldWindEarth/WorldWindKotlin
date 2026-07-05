package earth.worldwind.layer.cache

import earth.worldwind.geom.Sector
import earth.worldwind.layer.buildings.OsmBuildingsLayer
import earth.worldwind.layer.source.TiledFeatureSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Download every OSM-Buildings feature tile intersecting [sector] into the layer's feature store —
 * the "save a region for offline use" flow.
 *
 * Unlike the pyramid-walking raster/elevation/MVT bulk drivers, OSM Buildings render at a single
 * slippy zoom ([OsmBuildingsLayer.tileZoom], default 15) — there is no pyramid to select from, so
 * this takes no resolution/zoom argument and always caches at `tileZoom`, the only zoom the
 * renderer ever reads.
 *
 * Cache wiring is transparent: when [OsmBuildingsLayer.source] is a [CachedTiledFeatureSource]
 * (after attaching a GeoPackage feature store), each tile routes through
 * [CachedTiledFeatureSource.bulkFetchTile] — bypassing the renderer-side [OfflineToggleable.isCacheOnly]
 * gate and writing the full fetch through to the store. A plain network source still downloads but
 * persists nothing.
 *
 * Returns the launched [Job] so the caller can cancel mid-download.
 *
 * @param sector world region to download.
 * @param scope coroutine scope; defaults to [GlobalScope] for a long-running background job.
 * @param overrideCache when `true`, re-download every tile even if already cached (still
 *   write-through). Ignored when the source isn't a [CachedTiledFeatureSource].
 * @param onProgress optional `(downloaded, skipped, total)` callback fired after every tile.
 */
@OptIn(DelicateCoroutinesApi::class)
fun OsmBuildingsLayer.launchBulkRetrieval(
    sector: Sector,
    scope: CoroutineScope = GlobalScope,
    maxRetries: Int = 3,
    retryTimeoutShort: Duration = 5.seconds,
    retryTimeoutLong: Duration = 15.seconds,
    overrideCache: Boolean = false,
    onProgress: ((downloaded: Long, skipped: Long, total: Long) -> Unit)? = null,
): Job {
    val source: TiledFeatureSource = this.source
    val cached = source as? CachedTiledFeatureSource
    return launchSlippyBulkRetrieval(
        sector = sector,
        minZoom = tileZoom,
        maxZoom = tileZoom,
        scope = scope,
        maxRetries = maxRetries,
        retryTimeoutShort = retryTimeoutShort,
        retryTimeoutLong = retryTimeoutLong,
        onProgress = onProgress,
    ) { z, x, y, tileSector ->
        // Feature sources are flow-based and write through on collection, so the returned flow must
        // be drained to actually persist the tile. A null flow means the source confirms no features.
        val flow = cached?.bulkFetchTile(z, x, y, tileSector, overrideCache)
            ?: source.fetchTile(z, x, y, tileSector)
        if (flow == null) TileOutcome.SKIPPED else { flow.collect {}; TileOutcome.DOWNLOADED }
    }
}

/**
 * Total number of OSM-Buildings feature tiles [launchBulkRetrieval] would fetch for [sector] — the
 * slippy analog of [LevelSet.tileCount], for sizing a download before committing to it. Counts a
 * single slippy zoom ([OsmBuildingsLayer.tileZoom]), the only zoom the renderer reads.
 */
fun OsmBuildingsLayer.tileCount(sector: Sector): Long = slippyTileCount(sector, tileZoom, tileZoom)
