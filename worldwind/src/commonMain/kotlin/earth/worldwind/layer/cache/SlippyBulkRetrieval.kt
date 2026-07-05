package earth.worldwind.layer.cache

import earth.worldwind.geom.Sector
import earth.worldwind.layer.mercator.SlippyTiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/** Number of slippy tiles intersecting [sector] across `[minZoom, maxZoom]` — the progress denominator. */
internal fun slippyTileCount(sector: Sector, minZoom: Int, maxZoom: Int): Long {
    var total = 0L
    for (z in minZoom..maxZoom) {
        val firstX = SlippyTiles.lonToTileX(sector.minLongitude.inDegrees, z)
        val lastX = SlippyTiles.lonToTileX(sector.maxLongitude.inDegrees, z)
        // Slippy y grows southward, so the north edge yields the smallest row.
        val firstY = SlippyTiles.latToTileY(sector.maxLatitude.inDegrees, z)
        val lastY = SlippyTiles.latToTileY(sector.minLatitude.inDegrees, z)
        total += (lastX - firstX + 1).toLong() * (lastY - firstY + 1).toLong()
    }
    return total
}

/**
 * Generic slippy-tile bulk-download loop. Enumerates every tile intersecting [sector] across
 * `[minZoom, maxZoom]` and hands each to [fetchOne], applying the same retry/backoff, cooperative
 * cancellation and `(downloaded, skipped, total)` progress semantics as [BulkTileRetrieval].
 *
 * Unlike the geographic [BulkTileRetrieval] loop — whose rows are linear in latitude — slippy y is
 * linear in Mercator-y, so tile enumeration goes through [SlippyTiles]. [fetchOne] receives the tile
 * coordinate plus its geographic [Sector] and returns a [TileOutcome]; a thrown transport error
 * triggers retry with alternating [retryTimeoutShort] / [retryTimeoutLong] backoff up to [maxRetries]
 * attempts, after which the tile is counted as skipped.
 *
 * @param scope coroutine scope owning the returned [Job]; typically `GlobalScope` for a long job.
 */
internal fun launchSlippyBulkRetrieval(
    sector: Sector,
    minZoom: Int,
    maxZoom: Int,
    scope: CoroutineScope,
    maxRetries: Int,
    retryTimeoutShort: Duration,
    retryTimeoutLong: Duration,
    onProgress: ((downloaded: Long, skipped: Long, total: Long) -> Unit)?,
    fetchOne: suspend (z: Int, x: Int, y: Int, tileSector: Sector) -> TileOutcome,
): Job = scope.launch(Dispatchers.Default) {
    val progress = BulkProgress(slippyTileCount(sector, minZoom, maxZoom), onProgress)
    for (z in minZoom..maxZoom) {
        val firstX = SlippyTiles.lonToTileX(sector.minLongitude.inDegrees, z)
        val lastX = SlippyTiles.lonToTileX(sector.maxLongitude.inDegrees, z)
        val firstY = SlippyTiles.latToTileY(sector.maxLatitude.inDegrees, z)
        val lastY = SlippyTiles.latToTileY(sector.minLatitude.inDegrees, z)
        for (y in firstY..lastY) for (x in firstX..lastX) {
            ensureActive()
            val tileSector = SlippyTiles.tileToSector(z, x, y)
            progress.record(retryTile(maxRetries, retryTimeoutShort, retryTimeoutLong) {
                fetchOne(z, x, y, tileSector)
            })
        }
    }
}
