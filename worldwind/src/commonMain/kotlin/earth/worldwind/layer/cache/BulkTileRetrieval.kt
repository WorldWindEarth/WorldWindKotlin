package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileSource

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.util.Level
import earth.worldwind.util.LevelSet
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
 * Download every tile that intersects [sector] across every level whose resolution falls
 * in [resolution] — typically used by "save a region for offline use" flows. Returns the
 * launched [Job] so the caller can cancel mid-download.
 *
 * Cache wiring is transparent: if [this] is a [CachedTileSource] wrapping a network source
 * + [TileStore], every successful fetch writes through to the store. A plain network
 * source (no cache decorator) still works — the user just won't get persistence.
 *
 * On 404 / empty payloads the tile is counted as `skipped`. Transport errors retry with
 * exponential-ish backoff ([retryTimeoutShort] / [retryTimeoutLong]) until [maxRetries]
 * attempts; persistent failure also counts as `skipped`.
 *
 * @param levelSet describes the tile pyramid (zoom range, tile size, origin).
 * @param sector world region to download — clipped to [LevelSet.sector].
 * @param resolution angular resolution range in radians-per-pixel (open interval); maps
 *   to the level range via [LevelSet.levelForResolution].
 * @param scope coroutine scope; defaults to [GlobalScope] for a long-running background job.
 * @param onProgress optional `(downloaded, skipped, total)` callback fired after every tile.
 */
@OptIn(DelicateCoroutinesApi::class)
fun TileSource.launchBulkRetrieval(
    levelSet: LevelSet,
    sector: Sector,
    resolution: ClosedRange<Angle>,
    scope: CoroutineScope = GlobalScope,
    maxRetries: Int = 3,
    retryTimeoutShort: Duration = 5.seconds,
    retryTimeoutLong: Duration = 15.seconds,
    onProgress: ((downloaded: Long, skipped: Long, total: Long) -> Unit)? = null,
): Job {
    require(sector.intersect(levelSet.sector)) { "Sector does not intersect level set sector" }
    val source = this
    return scope.launch(Dispatchers.Default) {
        val minLevel = levelSet.levelForResolution(resolution.endInclusive)
        val maxLevel = levelSet.levelForResolution(resolution.start)
        val progress = BulkProgress(levelSet.tileCount(sector, minLevel, maxLevel), onProgress)
        var cursor: Level? = minLevel
        while (cursor != null && cursor.levelNumber <= maxLevel.levelNumber) {
            val level = cursor
            val rowsAtLevel = level.levelHeight / level.tileHeight
            val tileDelta = level.tileDelta
            with(levelSet.tileOrigin) {
                val firstRow = computeRow(tileDelta.latitude, sector.minLatitude)
                val lastRow = computeLastRow(tileDelta.latitude, sector.maxLatitude)
                val firstCol = computeColumn(tileDelta.longitude, sector.minLongitude)
                val lastCol = computeLastColumn(tileDelta.longitude, sector.maxLongitude)
                for (row in firstRow..lastRow) for (col in firstCol..lastCol) {
                    ensureActive()
                    // Convert renderer-side row (bottom-up) to slippy-map y (top-down).
                    val slippyY = rowsAtLevel - row - 1
                    progress.record(retryTile(maxRetries, retryTimeoutShort, retryTimeoutLong) {
                        // No conditional GET here, so no 304: null (no source/tile) or isEmpty (404) is a permanent skip.
                        val blob = source.fetchTile(level.levelNumber, col, slippyY)
                        if (blob == null || blob.isEmpty) TileOutcome.SKIPPED else TileOutcome.DOWNLOADED
                    })
                }
            }
            cursor = level.nextLevel
        }
    }
}
