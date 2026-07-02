package earth.worldwind.layer.cache

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.layer.mercator.MercatorSector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.time.Duration

/** Outcome of a single bulk tile fetch — drives the `(downloaded, skipped, total)` progress tally. */
internal enum class TileOutcome {
    /** Tile was fetched (or already cached) and written through. */
    DOWNLOADED,
    /** Nothing to fetch — 404 / empty payload / no network source. Never retried. */
    SKIPPED,
}

/**
 * Web-Mercator (slippy-map) tile math shared by the MVT and OSM-Buildings bulk drivers.
 *
 * Unlike the geographic [BulkTileRetrieval] loop — whose rows are linear in latitude — slippy y
 * is linear in Mercator-y, so these layers need their own enumeration. Mirrors the conversions
 * already living privately in `MvtVectorLayer.tileToSector` and `OsmBuildingsLayer.lonLatToTile`.
 */
internal object WebMercatorTiles {
    private const val TILE_SIZE = 256

    /**
     * Angular resolution (degrees/pixel) of slippy zoom 0 — the full Mercator latitude span
     * (`2·MAX_LATITUDE_DEG`) over one 256-px tile. Halves with each zoom, so it plays the role
     * of `LevelSet.firstLevelDelta.latitude / tileHeight` for the Web-Mercator pyramid; see
     * [zoomForResolution].
     */
    private val firstLevelDegreesPerPixel = 2.0 * MercatorSector.MAX_LATITUDE_DEG / TILE_SIZE

    /**
     * Slippy zoom whose resolution best matches [resolution], mirroring
     * [earth.worldwind.util.LevelSet.levelForResolution]'s nearest-neighbor rounding so a given
     * resolution range selects the same levels here as it does for raster/elevation layers. Never
     * negative; callers clamp the upper bound to the layer's deepest available zoom.
     */
    fun zoomForResolution(resolution: Angle): Int {
        val degreesPerPixel = resolution.inDegrees
        if (degreesPerPixel <= 0.0) return Int.MAX_VALUE // finest possible; caller clamps to maxZoom
        return log2(firstLevelDegreesPerPixel / degreesPerPixel).roundToInt().coerceAtLeast(0)
    }

    /** Slippy column for [lonDegrees] at [zoom], clamped to the pyramid width. */
    fun lonToTileX(lonDegrees: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return ((lonDegrees + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Slippy row (0 = north) for [latDegrees] at [zoom], clamped to Mercator bounds and pyramid height. */
    fun latToTileY(latDegrees: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = latDegrees.coerceIn(-MercatorSector.MAX_LATITUDE_DEG, MercatorSector.MAX_LATITUDE_DEG) * PI / 180.0
        return ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Geographic bounds of slippy tile `(zoom, x, y)` (y slippy, 0 = north). */
    fun tileToSector(zoom: Int, x: Int, y: Int): Sector {
        val n = 1 shl zoom
        val west = x.toDouble() / n * 360.0 - 180.0
        val east = (x + 1).toDouble() / n * 360.0 - 180.0
        val north = atan(sinh(PI * (1 - 2 * y.toDouble() / n))) * 180.0 / PI
        val south = atan(sinh(PI * (1 - 2 * (y + 1).toDouble() / n))) * 180.0 / PI
        return Sector.fromDegrees(south, west, north - south, east - west)
    }

    /** Number of slippy tiles intersecting [sector] across `[minZoom, maxZoom]` — the progress denominator. */
    fun tileCount(sector: Sector, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val firstX = lonToTileX(sector.minLongitude.inDegrees, z)
            val lastX = lonToTileX(sector.maxLongitude.inDegrees, z)
            // Slippy y grows southward, so the north edge yields the smallest row.
            val firstY = latToTileY(sector.maxLatitude.inDegrees, z)
            val lastY = latToTileY(sector.minLatitude.inDegrees, z)
            total += (lastX - firstX + 1).toLong() * (lastY - firstY + 1).toLong()
        }
        return total
    }
}

/**
 * Generic slippy-tile bulk-download loop. Enumerates every tile intersecting [sector] across
 * `[minZoom, maxZoom]` and hands each to [fetchOne], applying the same retry/backoff, cooperative
 * cancellation and `(downloaded, skipped, total)` progress semantics as [BulkTileRetrieval].
 *
 * [fetchOne] receives the tile coordinate plus its geographic [Sector] and returns a [TileOutcome];
 * a thrown transport error triggers retry with alternating [retryTimeoutShort] / [retryTimeoutLong]
 * backoff up to [maxRetries] attempts, after which the tile is counted as skipped.
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
    val total = WebMercatorTiles.tileCount(sector, minZoom, maxZoom)
    var downloaded = 0L
    var skipped = 0L
    for (z in minZoom..maxZoom) {
        val firstX = WebMercatorTiles.lonToTileX(sector.minLongitude.inDegrees, z)
        val lastX = WebMercatorTiles.lonToTileX(sector.maxLongitude.inDegrees, z)
        val firstY = WebMercatorTiles.latToTileY(sector.maxLatitude.inDegrees, z)
        val lastY = WebMercatorTiles.latToTileY(sector.minLatitude.inDegrees, z)
        for (y in firstY..lastY) for (x in firstX..lastX) {
            ensureActive()
            val tileSector = WebMercatorTiles.tileToSector(z, x, y)
            var outcome: TileOutcome? = null
            var attempt = 0
            while (attempt < maxRetries && outcome == null) {
                ensureActive()
                attempt++
                try {
                    outcome = fetchOne(z, x, y, tileSector)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    val backoff = if (attempt % 2 == 0) retryTimeoutLong else retryTimeoutShort
                    delay(backoff)
                }
            }
            // Exhausted retries (outcome still null) is treated as a skip, matching BulkTileRetrieval.
            if (outcome == TileOutcome.DOWNLOADED) onProgress?.invoke(++downloaded, skipped, total)
            else onProgress?.invoke(downloaded, ++skipped, total)
        }
    }
}
