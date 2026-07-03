package earth.worldwind.layer.cache

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Download every elevation tile that intersects [sector] across every tile matrix whose
 * pixel-resolution falls in [resolution], writing each tile through to the coverage's
 * cache.
 *
 * Returns the launched [Job] so the caller can cancel mid-download; returns `null` when
 * the coverage's `elevationSourceFactory` doesn't implement
 * [BulkRetrievableElevationSourceFactory] — i.e. the coverage isn't cache-aware and
 * there's no cache to populate.
 *
 * Mirrors the `TiledImageLayer`-receiver [launchBulkRetrieval] for image tiles.
 *
 * @param sector world region to download — clipped to each matrix's coverage.
 * @param resolution range of angular pixel-resolutions to fetch. The finest matrix whose
 *   `pixelHeight` is ≥ `resolution.start` and the coarsest matrix whose `pixelHeight` is
 *   ≤ `resolution.endInclusive` bound the iteration; every matrix in between is included.
 * @param scope coroutine scope; defaults to [GlobalScope] for a long-running background job.
 * @param overrideCache when `true`, every tile is re-downloaded from the network even if
 *   it's already in the cache. Mirrors the image-tile flag of the same name.
 * @param onProgress optional `(downloaded, skipped, total)` callback fired after every tile.
 */
@OptIn(DelicateCoroutinesApi::class)
fun TiledElevationCoverage.launchBulkRetrieval(
    sector: Sector,
    resolution: ClosedRange<Angle>,
    scope: CoroutineScope = GlobalScope,
    maxRetries: Int = 3,
    retryTimeoutShort: Duration = 5.seconds,
    retryTimeoutLong: Duration = 15.seconds,
    overrideCache: Boolean = false,
    onProgress: ((downloaded: Long, skipped: Long, total: Long) -> Unit)? = null,
): Job? {
    val factory = elevationSourceFactory as? BulkRetrievableElevationSourceFactory ?: return null
    val matrices = tileMatrixSet.entries.filter { matrix ->
        val px = matrix.pixelHeight()
        px in resolution
    }
    if (matrices.isEmpty()) return null

    val total = matrices.sumOf { m -> m.tileCount(sector).toLong() }

    return scope.launch(Dispatchers.Default) {
        val progress = BulkProgress(total, onProgress)
        for (matrix in matrices) {
            val tileSector = matrix.sector
            if (!sector.intersect(tileSector)) continue
            // Convert the requested sector into row/column ranges for this matrix.
            val first = matrix.rowColumnFor(sector.minLatitude, sector.minLongitude)
            val last = matrix.rowColumnFor(sector.maxLatitude, sector.maxLongitude)
            val firstRow = minOf(first.row, last.row).coerceIn(0, matrix.matrixHeight - 1)
            val lastRow = maxOf(first.row, last.row).coerceIn(0, matrix.matrixHeight - 1)
            val firstCol = minOf(first.column, last.column).coerceIn(0, matrix.matrixWidth - 1)
            val lastCol = maxOf(first.column, last.column).coerceIn(0, matrix.matrixWidth - 1)
            for (row in firstRow..lastRow) for (col in firstCol..lastCol) {
                ensureActive()
                progress.record(retryTile(maxRetries, retryTimeoutShort, retryTimeoutLong) {
                    // true = cached (DOWNLOADED); false = permanent miss (SKIPPED, no retry); thrown = retry.
                    if (factory.fetchAndCacheTile(matrix.ordinal, col, row, overrideCache)) TileOutcome.DOWNLOADED
                    else TileOutcome.SKIPPED
                })
            }
        }
    }
}

/** Angular pixel height of one tile of this [TileMatrix] — used to map a target
 *  resolution to the matching matrix(es) inside the bulk-retrieval iteration. */
private fun TileMatrix.pixelHeight(): Angle =
    Angle.fromRadians(sector.deltaLatitude.inRadians / (matrixHeight * tileHeight))

/** Tile count intersecting [sector] in this matrix — bounds the progress denominator. */
private fun TileMatrix.tileCount(sector: Sector): Int {
    val tileDeltaLat = this.sector.deltaLatitude.inRadians / matrixHeight
    val tileDeltaLon = this.sector.deltaLongitude.inRadians / matrixWidth
    val firstRow = ((sector.minLatitude.inRadians - this.sector.minLatitude.inRadians) / tileDeltaLat).toInt()
        .coerceIn(0, matrixHeight - 1)
    val lastRow = ((sector.maxLatitude.inRadians - this.sector.minLatitude.inRadians) / tileDeltaLat).toInt()
        .coerceIn(0, matrixHeight - 1)
    val firstCol = ((sector.minLongitude.inRadians - this.sector.minLongitude.inRadians) / tileDeltaLon).toInt()
        .coerceIn(0, matrixWidth - 1)
    val lastCol = ((sector.maxLongitude.inRadians - this.sector.minLongitude.inRadians) / tileDeltaLon).toInt()
        .coerceIn(0, matrixWidth - 1)
    return (lastRow - firstRow + 1) * (lastCol - firstCol + 1)
}

private data class RowCol(val row: Int, val column: Int)

private fun TileMatrix.rowColumnFor(lat: Angle, lon: Angle): RowCol {
    val tileDeltaLat = sector.deltaLatitude.inRadians / matrixHeight
    val tileDeltaLon = sector.deltaLongitude.inRadians / matrixWidth
    // Rows are top-down: row 0 is the northernmost tile, matching TileMatrix.tileSector
    // (minLat = maxLatitude - delta*(row+1)) and the renderer's row derivation in
    // AbstractTiledElevationCoverage. Measuring up from minLatitude mirrors the rows and
    // caches tiles at keys the renderer never reads.
    val row = ((sector.maxLatitude.inRadians - lat.inRadians) / tileDeltaLat).toInt()
    val col = ((lon.inRadians - sector.minLongitude.inRadians) / tileDeltaLon).toInt()
    return RowCol(row, col)
}
